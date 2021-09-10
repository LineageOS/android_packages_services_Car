/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.watchdog;

import static com.android.car.watchdog.CarWatchdogService.SYSTEM_INSTANCE;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.automotive.watchdog.PerStateBytes;
import android.car.builtin.util.Slog;
import android.car.builtin.util.Slogf;
import android.car.watchdog.IoOveruseStats;
import android.car.watchdog.PackageKillableState.KillableState;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Process;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;

import com.android.car.CarLog;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Defines the database to store/retrieve system resource stats history from local storage.
 */
public final class WatchdogStorage {
    private static final String TAG = CarLog.tagFor(WatchdogStorage.class);
    private static final int RETENTION_PERIOD_IN_DAYS = 30;
    /* Stats are stored on a daily basis. */
    public static final TemporalUnit STATS_TEMPORAL_UNIT = ChronoUnit.DAYS;
    /* Number of days to retain the stats in local storage. */
    public static final Period RETENTION_PERIOD =
            Period.ofDays(RETENTION_PERIOD_IN_DAYS).normalized();
    /* Zone offset for all date based table entries. */
    public static final ZoneOffset ZONE_OFFSET = ZoneOffset.UTC;

    private final WatchdogDbHelper mDbHelper;
    private final ArrayMap<String, UserPackage> mUserPackagesByKey = new ArrayMap<>();
    private final ArrayMap<String, UserPackage> mUserPackagesById = new ArrayMap<>();
    private TimeSourceInterface mTimeSource = SYSTEM_INSTANCE;

    public WatchdogStorage(Context context) {
        this(context, /* useDataSystemCarDir= */ true);
    }

    @VisibleForTesting
    WatchdogStorage(Context context, boolean useDataSystemCarDir) {
        mDbHelper = new WatchdogDbHelper(context, useDataSystemCarDir);
    }

    /** Releases resources. */
    public void release() {
        mDbHelper.close();
    }

    /** Handles database shrink. */
    public void shrinkDatabase() {
        try (SQLiteDatabase db = mDbHelper.getWritableDatabase()) {
            mDbHelper.onShrink(db);
        }
    }

    /** Saves the given user package settings entries and returns whether the change succeeded. */
    public boolean saveUserPackageSettings(List<UserPackageSettingsEntry> entries) {
        List<ContentValues> rows = new ArrayList<>(entries.size());
        /* Capture only unique user ids. */
        ArraySet<Integer> usersWithMissingIds = new ArraySet<>();
        for (int i = 0; i < entries.size(); ++i) {
            UserPackageSettingsEntry entry = entries.get(i);
            if (mUserPackagesByKey.get(UserPackage.getKey(entry.userId, entry.packageName))
                    == null) {
                usersWithMissingIds.add(entry.userId);
            }
            rows.add(UserPackageSettingsTable.getContentValues(entry));
        }
        try (SQLiteDatabase db = mDbHelper.getWritableDatabase()) {
            if (!atomicReplaceEntries(db, UserPackageSettingsTable.TABLE_NAME, rows)) {
                return false;
            }
            populateUserPackages(db, usersWithMissingIds);
        }
        return true;
    }

    /** Returns the user package setting entries. */
    public List<UserPackageSettingsEntry> getUserPackageSettings() {
        ArrayMap<String, UserPackageSettingsEntry> entriesById;
        try (SQLiteDatabase db = mDbHelper.getReadableDatabase()) {
            entriesById = UserPackageSettingsTable.querySettings(db);
        }
        List<UserPackageSettingsEntry> entries = new ArrayList<>(entriesById.size());
        for (int i = 0; i < entriesById.size(); ++i) {
            String rowId = entriesById.keyAt(i);
            UserPackageSettingsEntry entry = entriesById.valueAt(i);
            UserPackage userPackage = new UserPackage(rowId, entry.userId, entry.packageName);
            mUserPackagesByKey.put(userPackage.getKey(), userPackage);
            mUserPackagesById.put(userPackage.getUniqueId(), userPackage);
            entries.add(entry);
        }
        return entries;
    }

    /** Saves the given I/O usage stats. Returns true only on success. */
    public boolean saveIoUsageStats(List<IoUsageStatsEntry> entries) {
        return saveIoUsageStats(entries, /* shouldCheckRetention= */ true);
    }

    /** Returns the saved I/O usage stats for the current day. */
    public List<IoUsageStatsEntry> getTodayIoUsageStats() {
        ZonedDateTime statsDate =
                mTimeSource.now().atZone(ZONE_OFFSET).truncatedTo(STATS_TEMPORAL_UNIT);
        long startEpochSeconds = statsDate.toEpochSecond();
        long endEpochSeconds = mTimeSource.now().atZone(ZONE_OFFSET).toEpochSecond();
        ArrayMap<String, WatchdogPerfHandler.PackageIoUsage> ioUsagesById;
        try (SQLiteDatabase db = mDbHelper.getReadableDatabase()) {
            ioUsagesById = IoUsageStatsTable.queryStats(db, startEpochSeconds, endEpochSeconds);
        }
        List<IoUsageStatsEntry> entries = new ArrayList<>();
        for (int i = 0; i < ioUsagesById.size(); ++i) {
            String id = ioUsagesById.keyAt(i);
            UserPackage userPackage = mUserPackagesById.get(id);
            if (userPackage == null) {
                Slogf.i(TAG, "Failed to find user id and package name for unique database id: '%s'",
                        id);
                continue;
            }
            entries.add(new IoUsageStatsEntry(userPackage.getUserId(), userPackage.getPackageName(),
                    ioUsagesById.valueAt(i)));
        }
        return entries;
    }

    /** Deletes user package settings and resource overuse stats. */
    public void deleteUserPackage(@UserIdInt int userId, String packageName) {
        UserPackage userPackage = mUserPackagesByKey.get(UserPackage.getKey(userId, packageName));
        if (userPackage == null) {
            Slogf.w(TAG, "Failed to find unique database id for user id '%d' and package '%s",
                    userId, packageName);
            return;
        }
        mUserPackagesByKey.remove(userPackage.getKey());
        mUserPackagesById.remove(userPackage.getUniqueId());
        try (SQLiteDatabase db = mDbHelper.getWritableDatabase()) {
            UserPackageSettingsTable.deleteUserPackage(db, userId, packageName);
        }
    }

    /**
     * Returns the aggregated historical I/O overuse stats for the given user package or
     * {@code null} when stats are not available.
     */
    @Nullable
    public IoOveruseStats getHistoricalIoOveruseStats(
            @UserIdInt int userId, String packageName, int numDaysAgo) {
        ZonedDateTime currentDate =
                mTimeSource.now().atZone(ZONE_OFFSET).truncatedTo(STATS_TEMPORAL_UNIT);
        long startEpochSeconds = currentDate.minusDays(numDaysAgo).toEpochSecond();
        long endEpochSeconds = currentDate.toEpochSecond();
        try (SQLiteDatabase db = mDbHelper.getReadableDatabase()) {
            UserPackage userPackage = mUserPackagesByKey.get(
                    UserPackage.getKey(userId, packageName));
            if (userPackage == null) {
                /* Packages without historical stats don't have userPackage entry. */
                return null;
            }
            return IoUsageStatsTable.queryHistoricalStats(
                    db, userPackage.getUniqueId(), startEpochSeconds, endEpochSeconds);
        }
    }

    /**
     * Deletes all user package settings and resource stats for all non-alive users.
     *
     * @param aliveUserIds Array of alive user ids.
     */
    public void syncUsers(int[] aliveUserIds) {
        IntArray aliveUsers = IntArray.wrap(aliveUserIds);
        for (int i = mUserPackagesByKey.size() - 1; i >= 0; --i) {
            UserPackage userPackage = mUserPackagesByKey.valueAt(i);
            if (aliveUsers.indexOf(userPackage.getUserId()) == -1) {
                mUserPackagesByKey.removeAt(i);
                mUserPackagesById.remove(userPackage.getUniqueId());
            }
        }
        try (SQLiteDatabase db = mDbHelper.getWritableDatabase()) {
            UserPackageSettingsTable.syncUserPackagesWithAliveUsers(db, aliveUsers);
        }
    }

    @VisibleForTesting
    boolean saveIoUsageStats(List<IoUsageStatsEntry> entries, boolean shouldCheckRetention) {
        ZonedDateTime currentDate =
                mTimeSource.now().atZone(ZONE_OFFSET).truncatedTo(STATS_TEMPORAL_UNIT);
        List<ContentValues> rows = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); ++i) {
            IoUsageStatsEntry entry = entries.get(i);
            UserPackage userPackage = mUserPackagesByKey.get(
                    UserPackage.getKey(entry.userId, entry.packageName));
            if (userPackage == null) {
                Slogf.i(TAG, "Failed to find unique database id for user id '%d' and package '%s",
                        entry.userId, entry.packageName);
                continue;
            }
            android.automotive.watchdog.IoOveruseStats ioOveruseStats =
                    entry.ioUsage.getInternalIoOveruseStats();
            ZonedDateTime statsDate = Instant.ofEpochSecond(ioOveruseStats.startTime)
                    .atZone(ZONE_OFFSET).truncatedTo(STATS_TEMPORAL_UNIT);
            if (shouldCheckRetention && STATS_TEMPORAL_UNIT.between(statsDate, currentDate)
                    >= RETENTION_PERIOD.get(STATS_TEMPORAL_UNIT)) {
                continue;
            }
            long statsDateEpochSeconds = statsDate.toEpochSecond();
            rows.add(IoUsageStatsTable.getContentValues(
                    userPackage.getUniqueId(), entry, statsDateEpochSeconds));
        }
        try (SQLiteDatabase db = mDbHelper.getWritableDatabase()) {
            return atomicReplaceEntries(db, IoUsageStatsTable.TABLE_NAME, rows);
        }
    }

    @VisibleForTesting
    void setTimeSource(TimeSourceInterface timeSource) {
        mTimeSource = timeSource;
        mDbHelper.setTimeSource(timeSource);
    }

    private void populateUserPackages(SQLiteDatabase db, ArraySet<Integer> users) {
        List<UserPackage> userPackages = UserPackageSettingsTable.queryUserPackages(db, users);
        for (int i = 0; i < userPackages.size(); ++i) {
            UserPackage userPackage = userPackages.get(i);
            mUserPackagesByKey.put(userPackage.getKey(), userPackage);
            mUserPackagesById.put(userPackage.getUniqueId(), userPackage);
        }
    }

    private static boolean atomicReplaceEntries(
            SQLiteDatabase db, String tableName, List<ContentValues> rows) {
        if (rows.isEmpty()) {
            return true;
        }
        try {
            db.beginTransaction();
            for (int i = 0; i < rows.size(); ++i) {
                try {
                    if (db.replaceOrThrow(tableName, null, rows.get(i)) == -1) {
                        Slogf.e(TAG, "Failed to insert %s entry [%s]", tableName, rows.get(i));
                        return false;
                    }
                } catch (SQLException e) {
                    Slog.e(TAG, "Failed to insert " + tableName + " entry [" + rows.get(i) + "]",
                            e);
                    return false;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return true;
    }

    /** Defines the user package settings entry stored in the UserPackageSettingsTable. */
    static final class UserPackageSettingsEntry {
        public final String packageName;
        public final @UserIdInt int userId;
        public final @KillableState int killableState;

        UserPackageSettingsEntry(
                @UserIdInt int userId, String packageName, @KillableState int killableState) {
            this.userId = userId;
            this.packageName = packageName;
            this.killableState = killableState;
        }
    }

    /**
     * Defines the contents and queries for the user package settings table.
     */
    static final class UserPackageSettingsTable {
        public static final String TABLE_NAME = "user_package_settings";
        public static final String COLUMN_ROWID = "rowid";
        public static final String COLUMN_PACKAGE_NAME = "package_name";
        public static final String COLUMN_USER_ID = "user_id";
        public static final String COLUMN_KILLABLE_STATE = "killable_state";

        public static void createTable(SQLiteDatabase db) {
            StringBuilder createCommand = new StringBuilder();
            createCommand.append("CREATE TABLE ").append(TABLE_NAME).append(" (")
                    .append(COLUMN_PACKAGE_NAME).append(" TEXT NOT NULL, ")
                    .append(COLUMN_USER_ID).append(" INTEGER NOT NULL, ")
                    .append(COLUMN_KILLABLE_STATE).append(" INTEGER NOT NULL, ")
                    .append("PRIMARY KEY (").append(COLUMN_PACKAGE_NAME)
                    .append(", ").append(COLUMN_USER_ID).append("))");
            db.execSQL(createCommand.toString());
            Slogf.i(TAG, "Successfully created the %s table in the %s database version %d",
                    TABLE_NAME, WatchdogDbHelper.DATABASE_NAME, WatchdogDbHelper.DATABASE_VERSION);
        }

        public static ContentValues getContentValues(UserPackageSettingsEntry entry) {
            ContentValues values = new ContentValues();
            values.put(COLUMN_USER_ID, entry.userId);
            values.put(COLUMN_PACKAGE_NAME, entry.packageName);
            values.put(COLUMN_KILLABLE_STATE, entry.killableState);
            return values;
        }

        public static ArrayMap<String, UserPackageSettingsEntry> querySettings(SQLiteDatabase db) {
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("SELECT ")
                    .append(COLUMN_ROWID).append(", ")
                    .append(COLUMN_USER_ID).append(", ")
                    .append(COLUMN_PACKAGE_NAME).append(", ")
                    .append(COLUMN_KILLABLE_STATE)
                    .append(" FROM ").append(TABLE_NAME);

            try (Cursor cursor = db.rawQuery(queryBuilder.toString(), new String[]{})) {
                ArrayMap<String, UserPackageSettingsEntry> entriesById = new ArrayMap<>(
                        cursor.getCount());
                while (cursor.moveToNext()) {
                    entriesById.put(cursor.getString(0), new UserPackageSettingsEntry(
                            cursor.getInt(1), cursor.getString(2), cursor.getInt(3)));
                }
                return entriesById;
            }
        }

        public static List<UserPackage> queryUserPackages(
                SQLiteDatabase db, ArraySet<Integer> users) {
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("SELECT ")
                    .append(COLUMN_ROWID).append(", ")
                    .append(COLUMN_USER_ID).append(", ")
                    .append(COLUMN_PACKAGE_NAME)
                    .append(" FROM ").append(TABLE_NAME);
            for (int i = 0; i < users.size(); ++i) {
                if (i == 0) {
                    queryBuilder.append(" WHERE ").append(COLUMN_USER_ID).append(" IN (");
                } else {
                    queryBuilder.append(", ");
                }
                queryBuilder.append(users.valueAt(i));
                if (i == users.size() - 1) {
                    queryBuilder.append(")");
                }
            }

            try (Cursor cursor = db.rawQuery(queryBuilder.toString(), new String[]{})) {
                List<UserPackage> userPackages = new ArrayList<>(cursor.getCount());
                while (cursor.moveToNext()) {
                    userPackages.add(new UserPackage(
                            cursor.getString(0), cursor.getInt(1), cursor.getString(2)));
                }
                return userPackages;
            }
        }

        public static void deleteUserPackage(SQLiteDatabase db, @UserIdInt int userId,
                String packageName) {
            String whereClause = COLUMN_USER_ID + "= ? and " + COLUMN_PACKAGE_NAME + "= ?";
            String[] whereArgs = new String[]{String.valueOf(userId), packageName};
            int deletedRows = db.delete(TABLE_NAME, whereClause, whereArgs);
            Slogf.i(TAG, "Deleted %d user package settings db rows for user %d and package %s",
                    deletedRows, userId, packageName);
        }

        public static void syncUserPackagesWithAliveUsers(SQLiteDatabase db, IntArray aliveUsers) {
            StringBuilder queryBuilder = new StringBuilder();
            for (int i = 0; i < aliveUsers.size(); ++i) {
                if (i == 0) {
                    queryBuilder.append(COLUMN_USER_ID).append(" NOT IN (");
                } else {
                    queryBuilder.append(", ");
                }
                queryBuilder.append(aliveUsers.get(i));
                if (i == aliveUsers.size() - 1) {
                    queryBuilder.append(")");
                }
            }
            int deletedRows = db.delete(TABLE_NAME, queryBuilder.toString(), new String[]{});
            Slogf.i(TAG, "Deleted %d user package settings db rows while syncing with alive users",
                    deletedRows);
        }
    }

    /** Defines the I/O usage entry stored in the IoUsageStatsTable. */
    static final class IoUsageStatsEntry {
        public final @UserIdInt int userId;
        public final String packageName;
        public final WatchdogPerfHandler.PackageIoUsage ioUsage;

        IoUsageStatsEntry(@UserIdInt int userId,
                String packageName, WatchdogPerfHandler.PackageIoUsage ioUsage) {
            this.userId = userId;
            this.packageName = packageName;
            this.ioUsage = ioUsage;
        }
    }

    /**
     * Defines the contents and queries for the I/O usage stats table.
     */
    static final class IoUsageStatsTable {
        public static final String TABLE_NAME = "io_usage_stats";
        public static final String COLUMN_USER_PACKAGE_ID = "user_package_id";
        public static final String COLUMN_DATE_EPOCH = "date_epoch";
        public static final String COLUMN_NUM_OVERUSES = "num_overuses";
        public static final String COLUMN_NUM_FORGIVEN_OVERUSES =  "num_forgiven_overuses";
        public static final String COLUMN_NUM_TIMES_KILLED = "num_times_killed";
        public static final String COLUMN_WRITTEN_FOREGROUND_BYTES = "written_foreground_bytes";
        public static final String COLUMN_WRITTEN_BACKGROUND_BYTES = "written_background_bytes";
        public static final String COLUMN_WRITTEN_GARAGE_MODE_BYTES = "written_garage_mode_bytes";
        /* Below columns will be null for historical stats i.e., when the date != current date. */
        public static final String COLUMN_REMAINING_FOREGROUND_WRITE_BYTES =
                "remaining_foreground_write_bytes";
        public static final String COLUMN_REMAINING_BACKGROUND_WRITE_BYTES =
                "remaining_background_write_bytes";
        public static final String COLUMN_REMAINING_GARAGE_MODE_WRITE_BYTES =
                "remaining_garage_mode_write_bytes";
        public static final String COLUMN_FORGIVEN_FOREGROUND_WRITE_BYTES =
                "forgiven_foreground_write_bytes";
        public static final String COLUMN_FORGIVEN_BACKGROUND_WRITE_BYTES =
                "forgiven_background_write_bytes";
        public static final String COLUMN_FORGIVEN_GARAGE_MODE_WRITE_BYTES =
                "forgiven_garage_mode_write_bytes";

        public static void createTable(SQLiteDatabase db) {
            StringBuilder createCommand = new StringBuilder();
            createCommand.append("CREATE TABLE ").append(TABLE_NAME).append(" (")
                    .append(COLUMN_USER_PACKAGE_ID).append(" INTEGER NOT NULL, ")
                    .append(COLUMN_DATE_EPOCH).append(" INTEGER NOT NULL, ")
                    .append(COLUMN_NUM_OVERUSES).append(" INTEGER NOT NULL, ")
                    .append(COLUMN_NUM_FORGIVEN_OVERUSES).append(" INTEGER NOT NULL, ")
                    .append(COLUMN_NUM_TIMES_KILLED).append(" INTEGER NOT NULL, ")
                    .append(COLUMN_WRITTEN_FOREGROUND_BYTES).append(" INTEGER, ")
                    .append(COLUMN_WRITTEN_BACKGROUND_BYTES).append(" INTEGER, ")
                    .append(COLUMN_WRITTEN_GARAGE_MODE_BYTES).append(" INTEGER, ")
                    .append(COLUMN_REMAINING_FOREGROUND_WRITE_BYTES).append(" INTEGER, ")
                    .append(COLUMN_REMAINING_BACKGROUND_WRITE_BYTES).append(" INTEGER, ")
                    .append(COLUMN_REMAINING_GARAGE_MODE_WRITE_BYTES).append(" INTEGER, ")
                    .append(COLUMN_FORGIVEN_FOREGROUND_WRITE_BYTES).append(" INTEGER, ")
                    .append(COLUMN_FORGIVEN_BACKGROUND_WRITE_BYTES).append(" INTEGER, ")
                    .append(COLUMN_FORGIVEN_GARAGE_MODE_WRITE_BYTES).append(" INTEGER, ")
                    .append("PRIMARY KEY (").append(COLUMN_USER_PACKAGE_ID).append(", ")
                    .append(COLUMN_DATE_EPOCH).append("), FOREIGN KEY (")
                    .append(COLUMN_USER_PACKAGE_ID).append(") REFERENCES ")
                    .append(UserPackageSettingsTable.TABLE_NAME).append(" (")
                    .append(UserPackageSettingsTable.COLUMN_ROWID).append(") ON DELETE CASCADE )");
            db.execSQL(createCommand.toString());
            Slogf.i(TAG, "Successfully created the %s table in the %s database version %d",
                    TABLE_NAME, WatchdogDbHelper.DATABASE_NAME, WatchdogDbHelper.DATABASE_VERSION);
        }

        public static ContentValues getContentValues(
                String userPackageId, IoUsageStatsEntry entry, long statsDateEpochSeconds) {
            android.automotive.watchdog.IoOveruseStats ioOveruseStats =
                    entry.ioUsage.getInternalIoOveruseStats();
            ContentValues values = new ContentValues();
            values.put(COLUMN_USER_PACKAGE_ID, userPackageId);
            values.put(COLUMN_DATE_EPOCH, statsDateEpochSeconds);
            values.put(COLUMN_NUM_OVERUSES, ioOveruseStats.totalOveruses);
            /* TODO(b/195425666): Put total forgiven overuses for the day. */
            values.put(COLUMN_NUM_FORGIVEN_OVERUSES, 0);
            values.put(COLUMN_NUM_TIMES_KILLED, entry.ioUsage.getTotalTimesKilled());
            values.put(
                    COLUMN_WRITTEN_FOREGROUND_BYTES, ioOveruseStats.writtenBytes.foregroundBytes);
            values.put(
                    COLUMN_WRITTEN_BACKGROUND_BYTES, ioOveruseStats.writtenBytes.backgroundBytes);
            values.put(
                    COLUMN_WRITTEN_GARAGE_MODE_BYTES, ioOveruseStats.writtenBytes.garageModeBytes);
            values.put(COLUMN_REMAINING_FOREGROUND_WRITE_BYTES,
                    ioOveruseStats.remainingWriteBytes.foregroundBytes);
            values.put(COLUMN_REMAINING_BACKGROUND_WRITE_BYTES,
                    ioOveruseStats.remainingWriteBytes.backgroundBytes);
            values.put(COLUMN_REMAINING_GARAGE_MODE_WRITE_BYTES,
                    ioOveruseStats.remainingWriteBytes.garageModeBytes);
            android.automotive.watchdog.PerStateBytes forgivenWriteBytes =
                    entry.ioUsage.getForgivenWriteBytes();
            values.put(COLUMN_FORGIVEN_FOREGROUND_WRITE_BYTES, forgivenWriteBytes.foregroundBytes);
            values.put(COLUMN_FORGIVEN_BACKGROUND_WRITE_BYTES, forgivenWriteBytes.backgroundBytes);
            values.put(COLUMN_FORGIVEN_GARAGE_MODE_WRITE_BYTES, forgivenWriteBytes.garageModeBytes);
            return values;
        }

        public static ArrayMap<String, WatchdogPerfHandler.PackageIoUsage> queryStats(
                SQLiteDatabase db, long startEpochSeconds, long endEpochSeconds) {
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("SELECT ")
                    .append(COLUMN_USER_PACKAGE_ID).append(", ")
                    .append("MIN(").append(COLUMN_DATE_EPOCH).append("), ")
                    .append("SUM(").append(COLUMN_NUM_OVERUSES).append("), ")
                    .append("SUM(").append(COLUMN_NUM_TIMES_KILLED).append("), ")
                    .append("SUM(").append(COLUMN_WRITTEN_FOREGROUND_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_WRITTEN_BACKGROUND_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_WRITTEN_GARAGE_MODE_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_REMAINING_FOREGROUND_WRITE_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_REMAINING_BACKGROUND_WRITE_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_REMAINING_GARAGE_MODE_WRITE_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_FORGIVEN_FOREGROUND_WRITE_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_FORGIVEN_BACKGROUND_WRITE_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_FORGIVEN_GARAGE_MODE_WRITE_BYTES).append(") ")
                    .append("FROM ").append(TABLE_NAME).append(" WHERE ")
                    .append(COLUMN_DATE_EPOCH).append(">= ? and ")
                    .append(COLUMN_DATE_EPOCH).append("< ? GROUP BY ")
                    .append(COLUMN_USER_PACKAGE_ID);
            String[] selectionArgs = new String[]{
                    String.valueOf(startEpochSeconds), String.valueOf(endEpochSeconds)};

            ArrayMap<String, WatchdogPerfHandler.PackageIoUsage> ioUsageById = new ArrayMap<>();
            try (Cursor cursor = db.rawQuery(queryBuilder.toString(), selectionArgs)) {
                while (cursor.moveToNext()) {
                    android.automotive.watchdog.IoOveruseStats ioOveruseStats =
                            new android.automotive.watchdog.IoOveruseStats();
                    ioOveruseStats.startTime = cursor.getLong(1);
                    ioOveruseStats.durationInSeconds = endEpochSeconds - startEpochSeconds;
                    ioOveruseStats.totalOveruses = cursor.getInt(2);
                    ioOveruseStats.writtenBytes = new PerStateBytes();
                    ioOveruseStats.writtenBytes.foregroundBytes = cursor.getLong(4);
                    ioOveruseStats.writtenBytes.backgroundBytes = cursor.getLong(5);
                    ioOveruseStats.writtenBytes.garageModeBytes = cursor.getLong(6);
                    ioOveruseStats.remainingWriteBytes = new PerStateBytes();
                    ioOveruseStats.remainingWriteBytes.foregroundBytes = cursor.getLong(7);
                    ioOveruseStats.remainingWriteBytes.backgroundBytes = cursor.getLong(8);
                    ioOveruseStats.remainingWriteBytes.garageModeBytes = cursor.getLong(9);
                    PerStateBytes forgivenWriteBytes = new PerStateBytes();
                    forgivenWriteBytes.foregroundBytes = cursor.getLong(10);
                    forgivenWriteBytes.backgroundBytes = cursor.getLong(11);
                    forgivenWriteBytes.garageModeBytes = cursor.getLong(12);

                    ioUsageById.put(cursor.getString(0), new WatchdogPerfHandler.PackageIoUsage(
                            ioOveruseStats, forgivenWriteBytes, cursor.getInt(3)));
                }
            }
            return ioUsageById;
        }

        public static IoOveruseStats queryHistoricalStats(SQLiteDatabase db, String uniqueId,
                long startEpochSeconds, long endEpochSeconds) {
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("SELECT SUM(").append(COLUMN_NUM_OVERUSES).append("), ")
                    .append("SUM(").append(COLUMN_NUM_TIMES_KILLED).append("), ")
                    .append("SUM(").append(COLUMN_WRITTEN_FOREGROUND_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_WRITTEN_BACKGROUND_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_WRITTEN_GARAGE_MODE_BYTES).append("), ")
                    .append("MIN(").append(COLUMN_DATE_EPOCH).append(") ")
                    .append("FROM ").append(TABLE_NAME).append(" WHERE ")
                    .append(COLUMN_USER_PACKAGE_ID).append("=? and ")
                    .append(COLUMN_DATE_EPOCH).append(" >= ? and ")
                    .append(COLUMN_DATE_EPOCH).append("< ?");
            String[] selectionArgs = new String[]{uniqueId,
                    String.valueOf(startEpochSeconds), String.valueOf(endEpochSeconds)};
            long totalOveruses = 0;
            long totalTimesKilled = 0;
            long totalBytesWritten = 0;
            long earliestEpochSecond = endEpochSeconds;
            try (Cursor cursor = db.rawQuery(queryBuilder.toString(), selectionArgs)) {
                if (cursor.getCount() == 0) {
                    return null;
                }
                while (cursor.moveToNext()) {
                    totalOveruses += cursor.getLong(0);
                    totalTimesKilled += cursor.getLong(1);
                    totalBytesWritten += cursor.getLong(2) + cursor.getLong(3) + cursor.getLong(4);
                    earliestEpochSecond = Math.min(cursor.getLong(5), earliestEpochSecond);
                }
            }
            if (totalBytesWritten == 0) {
                return null;
            }
            long durationInSeconds = endEpochSeconds - earliestEpochSecond;
            IoOveruseStats.Builder statsBuilder = new IoOveruseStats.Builder(
                    earliestEpochSecond, durationInSeconds);
            statsBuilder.setTotalOveruses(totalOveruses);
            statsBuilder.setTotalTimesKilled(totalTimesKilled);
            statsBuilder.setTotalBytesWritten(totalBytesWritten);
            return statsBuilder.build();
        }

        public static void truncateToDate(SQLiteDatabase db, ZonedDateTime latestTruncateDate) {
            String selection = COLUMN_DATE_EPOCH + " <= ?";
            String[] selectionArgs = { String.valueOf(latestTruncateDate.toEpochSecond()) };

            int rows = db.delete(TABLE_NAME, selection, selectionArgs);
            Slogf.i(TAG, "Truncated %d I/O usage stats entries on pid %d", rows, Process.myPid());
        }

        public static void trimHistoricalStats(SQLiteDatabase db, ZonedDateTime currentDate) {
            ContentValues values = new ContentValues();
            values.putNull(COLUMN_REMAINING_FOREGROUND_WRITE_BYTES);
            values.putNull(COLUMN_REMAINING_BACKGROUND_WRITE_BYTES);
            values.putNull(COLUMN_REMAINING_GARAGE_MODE_WRITE_BYTES);
            values.putNull(COLUMN_FORGIVEN_FOREGROUND_WRITE_BYTES);
            values.putNull(COLUMN_FORGIVEN_BACKGROUND_WRITE_BYTES);
            values.putNull(COLUMN_FORGIVEN_GARAGE_MODE_WRITE_BYTES);

            String selection = COLUMN_DATE_EPOCH + " < ?";
            String[] selectionArgs = { String.valueOf(currentDate.toEpochSecond()) };

            int rows = db.update(TABLE_NAME, values, selection, selectionArgs);
            Slogf.i(TAG, "Trimmed %d I/O usage stats entries on pid %d", rows, Process.myPid());
        }
    }

    /**
     * Defines the Watchdog database and database level operations.
     */
    static final class WatchdogDbHelper extends SQLiteOpenHelper {
        public static final String DATABASE_DIR = "/data/system/car/watchdog/";
        public static final String DATABASE_NAME = "car_watchdog.db";

        private static final int DATABASE_VERSION = 1;

        private ZonedDateTime mLatestShrinkDate;
        private TimeSourceInterface mTimeSource = SYSTEM_INSTANCE;

        WatchdogDbHelper(Context context, boolean useDataSystemCarDir) {
            /* Use device protected storage because CarService may need to access the database
             * before the user has authenticated.
             */
            super(context.createDeviceProtectedStorageContext(),
                    useDataSystemCarDir ? DATABASE_DIR + DATABASE_NAME : DATABASE_NAME,
                    /* name= */ null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            UserPackageSettingsTable.createTable(db);
            IoUsageStatsTable.createTable(db);
        }

        public synchronized void close() {
            super.close();

            mLatestShrinkDate = null;
        }

        public void onShrink(SQLiteDatabase db) {
            ZonedDateTime currentDate =
                    mTimeSource.now().atZone(ZONE_OFFSET).truncatedTo(ChronoUnit.DAYS);
            if (currentDate.equals(mLatestShrinkDate)) {
                return;
            }
            IoUsageStatsTable.truncateToDate(db, currentDate.minus(RETENTION_PERIOD));
            IoUsageStatsTable.trimHistoricalStats(db, currentDate);
            mLatestShrinkDate = currentDate;
            Slogf.i(TAG, "Shrunk watchdog database for the date '%s'", mLatestShrinkDate);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
            /* Still on the 1st version so no upgrade required. */
        }

        void setTimeSource(TimeSourceInterface timeSource) {
            mTimeSource = timeSource;
        }
    }

    private static final class UserPackage {
        private final String mUniqueId;
        private final int mUserId;
        private final String mPackageName;

        UserPackage(String uniqueId, int userId, String packageName) {
            mUniqueId = uniqueId;
            mUserId = userId;
            mPackageName = packageName;
        }

        String getKey() {
            return getKey(mUserId, mPackageName);
        }

        static String getKey(int userId, String packageName) {
            return String.format(Locale.ENGLISH, "%d:%s", userId, packageName);
        }

        public String getUniqueId() {
            return mUniqueId;
        }

        public int getUserId() {
            return mUserId;
        }

        public String getPackageName() {
            return mPackageName;
        }

    }
}
