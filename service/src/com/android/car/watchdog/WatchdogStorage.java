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

import static com.android.car.watchdog.TimeSource.ZONE_OFFSET;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.automotive.watchdog.PerStateBytes;
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
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.internal.util.IntArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.time.Instant;
import java.time.Period;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
    public static final String ZONE_MODIFIER = "utc";
    public static final String DATE_MODIFIER = "unixepoch";

    private final WatchdogDbHelper mDbHelper;
    private final ArrayMap<String, UserPackage> mUserPackagesByKey = new ArrayMap<>();
    private final ArrayMap<String, UserPackage> mUserPackagesById = new ArrayMap<>();
    private TimeSource mTimeSource;
    private final Object mLock = new Object();
    // Cache of today's I/O overuse stats collected during the previous boot. The data contained in
    // the cache won't change until the next boot, so it is safe to cache the data in memory.
    @GuardedBy("mLock")
    private final List<IoUsageStatsEntry> mTodayIoUsageStatsEntries = new ArrayList<>();

    public WatchdogStorage(Context context, TimeSource timeSource) {
        this(context, /* useDataSystemCarDir= */ true, timeSource);
    }

    @VisibleForTesting
    WatchdogStorage(Context context, boolean useDataSystemCarDir, TimeSource timeSource) {
        mTimeSource = timeSource;
        mDbHelper = new WatchdogDbHelper(context, useDataSystemCarDir, mTimeSource);
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
        synchronized (mLock) {
            if (!mTodayIoUsageStatsEntries.isEmpty()) {
                return new ArrayList<>(mTodayIoUsageStatsEntries);
            }
            long includingStartEpochSeconds = mTimeSource.getCurrentDate().toEpochSecond();
            long excludingEndEpochSeconds = mTimeSource.getCurrentDateTime().toEpochSecond();
            ArrayMap<String, WatchdogPerfHandler.PackageIoUsage> ioUsagesById;
            try (SQLiteDatabase db = mDbHelper.getReadableDatabase()) {
                ioUsagesById = IoUsageStatsTable.queryStats(db, includingStartEpochSeconds,
                        excludingEndEpochSeconds);
            }
            for (int i = 0; i < ioUsagesById.size(); ++i) {
                String id = ioUsagesById.keyAt(i);
                UserPackage userPackage = mUserPackagesById.get(id);
                if (userPackage == null) {
                    Slogf.i(TAG,
                            "Failed to find user id and package name for unique database id: '%s'",
                            id);
                    continue;
                }
                mTodayIoUsageStatsEntries.add(new IoUsageStatsEntry(
                        userPackage.getUserId(), userPackage.getPackageName(),
                        ioUsagesById.valueAt(i)));
            }
            return new ArrayList<>(mTodayIoUsageStatsEntries);
        }
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
    public IoOveruseStats getHistoricalIoOveruseStats(@UserIdInt int userId, String packageName,
            int numDaysAgo) {
        ZonedDateTime currentDate = mTimeSource.getCurrentDate();
        long includingStartEpochSeconds = currentDate.minusDays(numDaysAgo).toEpochSecond();
        long excludingEndEpochSeconds = currentDate.toEpochSecond();
        try (SQLiteDatabase db = mDbHelper.getReadableDatabase()) {
            UserPackage userPackage = mUserPackagesByKey.get(
                    UserPackage.getKey(userId, packageName));
            if (userPackage == null) {
                /* Packages without historical stats don't have userPackage entry. */
                return null;
            }
            return IoUsageStatsTable.queryIoOveruseStatsForUniqueId(db, userPackage.getUniqueId(),
                    includingStartEpochSeconds, excludingEndEpochSeconds);
        }
    }

    /**
     * Returns daily system-level I/O usage summaries for the given period or {@code null} when
     * summaries are not available.
     */
    public @Nullable List<AtomsProto.CarWatchdogDailyIoUsageSummary> getDailySystemIoUsageSummaries(
            long includingStartEpochSeconds, long excludingEndEpochSeconds) {
        try (SQLiteDatabase db = mDbHelper.getReadableDatabase()) {
            return IoUsageStatsTable.queryDailySystemIoUsageSummaries(db,
                    includingStartEpochSeconds, excludingEndEpochSeconds);
        }
    }

    /**
     * Returns top N disk I/O users' daily I/O usage summaries for the given period or {@code null}
     * when summaries are not available.
     */
    public @Nullable List<UserPackageDailySummaries> getTopUsersDailyIoUsageSummaries(
            int numTopUsers, long minTotalWrittenBytes, long includingStartEpochSeconds,
            long excludingEndEpochSeconds) {
        ArrayMap<String, List<AtomsProto.CarWatchdogDailyIoUsageSummary>> summariesById;
        try (SQLiteDatabase db = mDbHelper.getReadableDatabase()) {
            summariesById = IoUsageStatsTable.queryTopUsersDailyIoUsageSummaries(db,
                    numTopUsers, minTotalWrittenBytes, includingStartEpochSeconds,
                    excludingEndEpochSeconds);
        }
        if (summariesById == null) {
            return null;
        }
        ArrayList<UserPackageDailySummaries> userPackageDailySummaries = new ArrayList<>();
        for (int i = 0; i < summariesById.size(); ++i) {
            String id = summariesById.keyAt(i);
            UserPackage userPackage = mUserPackagesById.get(id);
            if (userPackage == null) {
                Slogf.i(TAG,
                        "Failed to find user id and package name for unique database id: '%s'",
                        id);
                continue;
            }
            userPackageDailySummaries.add(new UserPackageDailySummaries(userPackage.getUserId(),
                    userPackage.getPackageName(), summariesById.valueAt(i)));
        }
        userPackageDailySummaries
                .sort(Comparator.comparingLong(UserPackageDailySummaries::getTotalWrittenBytes)
                        .reversed());
        return userPackageDailySummaries;
    }

    /**
     * Returns the aggregated historical overuses minus the forgiven overuses for all saved
     * packages. Forgiven overuses are overuses that have been attributed previously to a package's
     * recurring overuse.
     */
    public List<NotForgivenOverusesEntry> getNotForgivenHistoricalIoOveruses(int numDaysAgo) {
        ZonedDateTime currentDate =
                mTimeSource.now().atZone(ZONE_OFFSET).truncatedTo(STATS_TEMPORAL_UNIT);
        long includingStartEpochSeconds = currentDate.minusDays(numDaysAgo).toEpochSecond();
        long excludingEndEpochSeconds = currentDate.toEpochSecond();
        ArrayMap<String, Integer> notForgivenOverusesById;
        try (SQLiteDatabase db = mDbHelper.getReadableDatabase()) {
            notForgivenOverusesById = IoUsageStatsTable.queryNotForgivenHistoricalOveruses(db,
                    includingStartEpochSeconds, excludingEndEpochSeconds);
        }
        List<NotForgivenOverusesEntry> notForgivenOverusesEntries = new ArrayList<>();
        for (int i = 0; i < notForgivenOverusesById.size(); i++) {
            String id = notForgivenOverusesById.keyAt(i);
            UserPackage userPackage = mUserPackagesById.get(id);
            if (userPackage == null) {
                Slogf.w(TAG,
                        "Failed to find user id and package name for unique database id: '%s'",
                        id);
                continue;
            }
            notForgivenOverusesEntries.add(new NotForgivenOverusesEntry(userPackage.getUserId(),
                    userPackage.getPackageName(), notForgivenOverusesById.valueAt(i)));
        }
        return notForgivenOverusesEntries;
    }

    /**
     * Forgives all historical overuses between yesterday and {@code numDaysAgo}
     * for a list of specific {@code userIds} and {@code packageNames}.
     */
    public void forgiveHistoricalOveruses(SparseArray<List<String>> packagesByUserId,
            int numDaysAgo) {
        if (packagesByUserId.size() == 0) {
            Slogf.w(TAG, "No I/O usage stats provided to forgive historical overuses.");
            return;
        }
        ZonedDateTime currentDate =
                mTimeSource.now().atZone(ZONE_OFFSET).truncatedTo(STATS_TEMPORAL_UNIT);
        long includingStartEpochSeconds = currentDate.minusDays(numDaysAgo).toEpochSecond();
        long excludingEndEpochSeconds = currentDate.toEpochSecond();
        List<String> uniqueIds = new ArrayList<>();
        for (int i = 0; i < packagesByUserId.size(); i++) {
            int userId = packagesByUserId.keyAt(i);
            List<String> packages = packagesByUserId.valueAt(i);
            for (int pkgIdx = 0; pkgIdx < packages.size(); pkgIdx++) {
                UserPackage userPackage =
                        mUserPackagesByKey.get(UserPackage.getKey(userId, packages.get(pkgIdx)));
                if (userPackage == null) {
                    // Packages without historical stats don't have userPackage entry.
                    continue;
                }
                uniqueIds.add(userPackage.getUniqueId());
            }
        }
        try (SQLiteDatabase db = mDbHelper.getWritableDatabase()) {
            IoUsageStatsTable.forgiveHistoricalOverusesForPackage(db, uniqueIds,
                    includingStartEpochSeconds, excludingEndEpochSeconds);
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
        ZonedDateTime currentDate = mTimeSource.getCurrentDate();
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
                    Slogf.e(TAG, "Failed to insert " + tableName + " entry [" + rows.get(i) + "]",
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
        public final @UserIdInt int userId;
        public final String packageName;
        public final @KillableState int killableState;

        UserPackageSettingsEntry(@UserIdInt int userId, String packageName,
                @KillableState int killableState) {
            this.userId = userId;
            this.packageName = packageName;
            this.killableState = killableState;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof UserPackageSettingsEntry)) {
                return false;
            }
            UserPackageSettingsEntry other = (UserPackageSettingsEntry) obj;
            return userId == other.userId && packageName.equals(other.packageName)
                    && killableState == other.killableState;
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, packageName, killableState);
        }

        @Override
        public String toString() {
            return new StringBuilder().append("UserPackageSettingsEntry{userId: ").append(userId)
                    .append(", packageName: ").append(packageName)
                    .append(", killableState: ").append(killableState).append('}')
                    .toString();
        }
    }

    /** Defines the daily summaries for user packages. */
    static final class UserPackageDailySummaries {
        public final @UserIdInt int userId;
        public final String packageName;
        public final List<AtomsProto.CarWatchdogDailyIoUsageSummary> dailyIoUsageSummaries;
        private final long mTotalWrittenBytes;

        UserPackageDailySummaries(@UserIdInt int userId, String packageName,
                List<AtomsProto.CarWatchdogDailyIoUsageSummary> dailyIoUsageSummaries) {
            this.userId = userId;
            this.packageName = packageName;
            this.dailyIoUsageSummaries = dailyIoUsageSummaries;
            this.mTotalWrittenBytes = computeTotalWrittenBytes();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof UserPackageDailySummaries)) {
                return false;
            }
            UserPackageDailySummaries other = (UserPackageDailySummaries) obj;
            return userId == other.userId && packageName.equals(other.packageName)
                    && dailyIoUsageSummaries.equals(other.dailyIoUsageSummaries);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, packageName, dailyIoUsageSummaries, mTotalWrittenBytes);
        }

        @Override
        public String toString() {
            return new StringBuilder().append("UserPackageDailySummaries{userId: ").append(userId)
                    .append(", packageName: ").append(packageName)
                    .append(", dailyIoUsageSummaries: ").append(dailyIoUsageSummaries).append('}')
                    .toString();
        }

        long getTotalWrittenBytes() {
            return mTotalWrittenBytes;
        }

        long computeTotalWrittenBytes() {
            long totalBytes = 0;
            for (int i = 0; i < dailyIoUsageSummaries.size(); ++i) {
                AtomsProto.CarWatchdogPerStateBytes writtenBytes =
                        dailyIoUsageSummaries.get(i).getWrittenBytes();
                if (writtenBytes.hasForegroundBytes()) {
                    totalBytes += writtenBytes.getForegroundBytes();
                }
                if (writtenBytes.hasBackgroundBytes()) {
                    totalBytes += writtenBytes.getBackgroundBytes();
                }
                if (writtenBytes.hasGarageModeBytes()) {
                    totalBytes += writtenBytes.getGarageModeBytes();
                }
            }
            return totalBytes;
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

    /** Defines the not forgiven overuses stored in the IoUsageStatsTable. */
    static final class NotForgivenOverusesEntry {
        public final @UserIdInt int userId;
        public final String packageName;
        public final int notForgivenOveruses;

        NotForgivenOverusesEntry(@UserIdInt int userId,
                String packageName, int notForgivenOveruses) {
            this.userId = userId;
            this.packageName = packageName;
            this.notForgivenOveruses = notForgivenOveruses;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof NotForgivenOverusesEntry)) {
                return false;
            }
            NotForgivenOverusesEntry other = (NotForgivenOverusesEntry) obj;
            return userId == other.userId
                    && packageName.equals(other.packageName)
                    && notForgivenOveruses == other.notForgivenOveruses;
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, packageName, notForgivenOveruses);
        }

        @Override
        public String toString() {
            return "NotForgivenOverusesEntry {UserId: " + userId
                    + ", Package name: " + packageName
                    + ", Not forgiven overuses: " + notForgivenOveruses + "}";
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
            values.put(COLUMN_NUM_FORGIVEN_OVERUSES, entry.ioUsage.getForgivenOveruses());
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
                SQLiteDatabase db, long includingStartEpochSeconds, long excludingEndEpochSeconds) {
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("SELECT ")
                    .append(COLUMN_USER_PACKAGE_ID).append(", ")
                    .append("MIN(").append(COLUMN_DATE_EPOCH).append("), ")
                    .append("SUM(").append(COLUMN_NUM_OVERUSES).append("), ")
                    .append("SUM(").append(COLUMN_NUM_FORGIVEN_OVERUSES).append("), ")
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
            String[] selectionArgs = new String[]{String.valueOf(includingStartEpochSeconds),
                    String.valueOf(excludingEndEpochSeconds)};

            ArrayMap<String, WatchdogPerfHandler.PackageIoUsage> ioUsageById = new ArrayMap<>();
            try (Cursor cursor = db.rawQuery(queryBuilder.toString(), selectionArgs)) {
                while (cursor.moveToNext()) {
                    android.automotive.watchdog.IoOveruseStats ioOveruseStats =
                            new android.automotive.watchdog.IoOveruseStats();
                    ioOveruseStats.startTime = cursor.getLong(1);
                    ioOveruseStats.durationInSeconds =
                            excludingEndEpochSeconds - includingStartEpochSeconds;
                    ioOveruseStats.totalOveruses = cursor.getInt(2);
                    ioOveruseStats.writtenBytes = new PerStateBytes();
                    ioOveruseStats.writtenBytes.foregroundBytes = cursor.getLong(5);
                    ioOveruseStats.writtenBytes.backgroundBytes = cursor.getLong(6);
                    ioOveruseStats.writtenBytes.garageModeBytes = cursor.getLong(7);
                    ioOveruseStats.remainingWriteBytes = new PerStateBytes();
                    ioOveruseStats.remainingWriteBytes.foregroundBytes = cursor.getLong(8);
                    ioOveruseStats.remainingWriteBytes.backgroundBytes = cursor.getLong(9);
                    ioOveruseStats.remainingWriteBytes.garageModeBytes = cursor.getLong(10);
                    PerStateBytes forgivenWriteBytes = new PerStateBytes();
                    forgivenWriteBytes.foregroundBytes = cursor.getLong(11);
                    forgivenWriteBytes.backgroundBytes = cursor.getLong(12);
                    forgivenWriteBytes.garageModeBytes = cursor.getLong(13);

                    ioUsageById.put(cursor.getString(0), new WatchdogPerfHandler.PackageIoUsage(
                            ioOveruseStats, forgivenWriteBytes,
                            /* forgivenOveruses= */ cursor.getInt(3),
                            /* totalTimesKilled= */ cursor.getInt(4)));
                }
            }
            return ioUsageById;
        }

        public static @Nullable IoOveruseStats queryIoOveruseStatsForUniqueId(SQLiteDatabase db,
                String uniqueId, long includingStartEpochSeconds, long excludingEndEpochSeconds) {
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
                    String.valueOf(includingStartEpochSeconds),
                    String.valueOf(excludingEndEpochSeconds)};
            long totalOveruses = 0;
            long totalTimesKilled = 0;
            long totalBytesWritten = 0;
            long earliestEpochSecond = excludingEndEpochSeconds;
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
            long durationInSeconds = excludingEndEpochSeconds - earliestEpochSecond;
            IoOveruseStats.Builder statsBuilder = new IoOveruseStats.Builder(
                    earliestEpochSecond, durationInSeconds);
            statsBuilder.setTotalOveruses(totalOveruses);
            statsBuilder.setTotalTimesKilled(totalTimesKilled);
            statsBuilder.setTotalBytesWritten(totalBytesWritten);
            return statsBuilder.build();
        }

        public static ArrayMap<String, Integer> queryNotForgivenHistoricalOveruses(
                SQLiteDatabase db, long includingStartEpochSeconds, long excludingEndEpochSeconds) {
            StringBuilder queryBuilder = new StringBuilder("SELECT ")
                    .append(COLUMN_USER_PACKAGE_ID).append(", ")
                    .append("SUM(").append(COLUMN_NUM_OVERUSES).append("), ")
                    .append("SUM(").append(COLUMN_NUM_FORGIVEN_OVERUSES).append(") ")
                    .append("FROM ").append(TABLE_NAME).append(" WHERE ")
                    .append(COLUMN_DATE_EPOCH).append(" >= ? and ")
                    .append(COLUMN_DATE_EPOCH).append("< ? GROUP BY ")
                    .append(COLUMN_USER_PACKAGE_ID);
            String[] selectionArgs = new String[]{String.valueOf(includingStartEpochSeconds),
                    String.valueOf(excludingEndEpochSeconds)};
            ArrayMap<String, Integer> notForgivenOverusesById = new ArrayMap<>();
            try (Cursor cursor = db.rawQuery(queryBuilder.toString(), selectionArgs)) {
                while (cursor.moveToNext()) {
                    if (cursor.getInt(1) <= cursor.getInt(2)) {
                        continue;
                    }
                    notForgivenOverusesById.put(cursor.getString(0),
                            cursor.getInt(1) - cursor.getInt(2));
                }
            }
            return notForgivenOverusesById;
        }

        public static void forgiveHistoricalOverusesForPackage(SQLiteDatabase db,
                List<String> uniqueIds, long includingStartEpochSeconds,
                long excludingEndEpochSeconds) {
            if (uniqueIds.isEmpty()) {
                Slogf.e(TAG, "No unique ids provided to forgive historical overuses.");
                return;
            }
            StringBuilder updateQueryBuilder = new StringBuilder("UPDATE ").append(TABLE_NAME)
                    .append(" SET ")
                    .append(COLUMN_NUM_FORGIVEN_OVERUSES).append("=").append(COLUMN_NUM_OVERUSES)
                    .append(" WHERE ")
                    .append(COLUMN_DATE_EPOCH).append(">= ").append(includingStartEpochSeconds)
                    .append(" and ")
                    .append(COLUMN_DATE_EPOCH).append("< ").append(excludingEndEpochSeconds);
            for (int i = 0; i < uniqueIds.size(); i++) {
                if (i == 0) {
                    updateQueryBuilder.append(" and ").append(COLUMN_USER_PACKAGE_ID)
                            .append(" IN (");
                } else {
                    updateQueryBuilder.append(", ");
                }
                updateQueryBuilder.append(uniqueIds.get(i));
                if (i == uniqueIds.size() - 1) {
                    updateQueryBuilder.append(")");
                }
            }

            db.execSQL(updateQueryBuilder.toString());
            Slogf.i(TAG, "Attempted to forgive overuses for I/O usage stats entries on pid %d",
                    Process.myPid());
        }

        public static @Nullable List<AtomsProto.CarWatchdogDailyIoUsageSummary>
                queryDailySystemIoUsageSummaries(SQLiteDatabase db, long includingStartEpochSeconds,
                long excludingEndEpochSeconds) {
            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("SELECT SUM(").append(COLUMN_NUM_OVERUSES).append("), ")
                    .append("SUM(").append(COLUMN_WRITTEN_FOREGROUND_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_WRITTEN_BACKGROUND_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_WRITTEN_GARAGE_MODE_BYTES).append("), ")
                    .append("date(").append(COLUMN_DATE_EPOCH).append(", '").append(DATE_MODIFIER)
                    .append("', '").append(ZONE_MODIFIER).append("') as stats_date_epoch ")
                    .append("FROM ").append(TABLE_NAME).append(" WHERE ")
                    .append(COLUMN_DATE_EPOCH).append(" >= ? and ")
                    .append(COLUMN_DATE_EPOCH).append(" < ? ")
                    .append("GROUP BY stats_date_epoch ")
                    .append("HAVING SUM(").append(COLUMN_WRITTEN_FOREGROUND_BYTES).append(" + ")
                    .append(COLUMN_WRITTEN_BACKGROUND_BYTES).append(" + ")
                    .append(COLUMN_WRITTEN_GARAGE_MODE_BYTES).append(") > 0 ")
                    .append("ORDER BY stats_date_epoch ASC");

            Slogf.e(TAG, "Query: %s", queryBuilder.toString());

            String[] selectionArgs = new String[]{String.valueOf(includingStartEpochSeconds),
                    String.valueOf(excludingEndEpochSeconds)};
            List<AtomsProto.CarWatchdogDailyIoUsageSummary> summaries = new ArrayList<>();
            try (Cursor cursor = db.rawQuery(queryBuilder.toString(), selectionArgs)) {
                if (cursor.getCount() == 0) {
                    return null;
                }
                while (cursor.moveToNext()) {
                    summaries.add(AtomsProto.CarWatchdogDailyIoUsageSummary.newBuilder()
                            .setWrittenBytes(WatchdogPerfHandler.constructCarWatchdogPerStateBytes(
                                    /* foregroundBytes= */ cursor.getLong(1),
                                    /* backgroundBytes= */ cursor.getLong(2),
                                    /* garageModeBytes= */ cursor.getLong(3)))
                            .setOveruseCount(cursor.getInt(0))
                            .build());
                }
            }
            return summaries;
        }

        public static @Nullable ArrayMap<String, List<AtomsProto.CarWatchdogDailyIoUsageSummary>>
                queryTopUsersDailyIoUsageSummaries(SQLiteDatabase db, int numTopUsers,
                long minTotalWrittenBytes, long includingStartEpochSeconds,
                long excludingEndEpochSeconds) {
            StringBuilder innerQueryBuilder = new StringBuilder();
            innerQueryBuilder.append("SELECT ").append(COLUMN_USER_PACKAGE_ID)
                    .append(" FROM (SELECT ").append(COLUMN_USER_PACKAGE_ID).append(", ")
                    .append("SUM(").append(COLUMN_WRITTEN_FOREGROUND_BYTES).append(" + ")
                    .append(COLUMN_WRITTEN_BACKGROUND_BYTES).append(" + ")
                    .append(COLUMN_WRITTEN_GARAGE_MODE_BYTES).append(") AS total_written_bytes ")
                    .append("FROM ").append(TABLE_NAME).append(" WHERE ")
                    .append(COLUMN_DATE_EPOCH).append(" >= ? and ")
                    .append(COLUMN_DATE_EPOCH).append(" < ?")
                    .append(" GROUP BY ").append(COLUMN_USER_PACKAGE_ID)
                    .append(" HAVING total_written_bytes >= ").append(minTotalWrittenBytes)
                    .append(" ORDER BY total_written_bytes LIMIT ").append(numTopUsers).append(')');

            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append("SELECT ").append(COLUMN_USER_PACKAGE_ID).append(", ")
                    .append("SUM(").append(COLUMN_NUM_OVERUSES).append("), ")
                    .append("SUM(").append(COLUMN_WRITTEN_FOREGROUND_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_WRITTEN_BACKGROUND_BYTES).append("), ")
                    .append("SUM(").append(COLUMN_WRITTEN_GARAGE_MODE_BYTES).append("), ")
                    .append("date(").append(COLUMN_DATE_EPOCH).append(", '").append(DATE_MODIFIER)
                    .append("', '").append(ZONE_MODIFIER).append("') as stats_date_epoch ")
                    .append("FROM ").append(TABLE_NAME).append(" WHERE ")
                    .append(COLUMN_DATE_EPOCH).append(" >= ? and ")
                    .append(COLUMN_DATE_EPOCH).append(" < ? and (")
                    .append(COLUMN_WRITTEN_FOREGROUND_BYTES).append(" > 0 or ")
                    .append(COLUMN_WRITTEN_BACKGROUND_BYTES).append(" > 0 or ")
                    .append(COLUMN_WRITTEN_GARAGE_MODE_BYTES).append(" > 0) and ")
                    .append(COLUMN_USER_PACKAGE_ID)
                    .append(" in (").append(innerQueryBuilder)
                    .append(") GROUP BY stats_date_epoch, ").append(COLUMN_USER_PACKAGE_ID)
                    .append(" ORDER BY ").append(COLUMN_USER_PACKAGE_ID)
                    .append(", stats_date_epoch ASC");

            String[] selectionArgs = new String[]{
                    // Outer query selection arguments.
                    String.valueOf(includingStartEpochSeconds),
                    String.valueOf(excludingEndEpochSeconds),
                    // Inner query selection arguments.
                    String.valueOf(includingStartEpochSeconds),
                    String.valueOf(excludingEndEpochSeconds)};

            ArrayMap<String, List<AtomsProto.CarWatchdogDailyIoUsageSummary>> summariesById =
                    new ArrayMap<>();
            try (Cursor cursor = db.rawQuery(queryBuilder.toString(), selectionArgs)) {
                if (cursor.getCount() == 0) {
                    return null;
                }
                while (cursor.moveToNext()) {
                    String id = cursor.getString(0);
                    List<AtomsProto.CarWatchdogDailyIoUsageSummary> summaries =
                            summariesById.get(id);
                    if (summaries == null) {
                        summaries = new ArrayList<>();
                    }
                    summaries.add(AtomsProto.CarWatchdogDailyIoUsageSummary.newBuilder()
                            .setWrittenBytes(WatchdogPerfHandler.constructCarWatchdogPerStateBytes(
                                    /* foregroundBytes= */ cursor.getLong(2),
                                    /* backgroundBytes= */ cursor.getLong(3),
                                    /* garageModeBytes= */ cursor.getLong(4)))
                            .setOveruseCount(cursor.getInt(1))
                            .build());
                    summariesById.put(id, summaries);
                }
            }
            return summariesById;
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
        public static final String DATABASE_NAME = "car_watchdog.db";

        private static final int DATABASE_VERSION = 1;

        private ZonedDateTime mLatestShrinkDate;
        private TimeSource mTimeSource;

        WatchdogDbHelper(Context context, boolean useDataSystemCarDir, TimeSource timeSource) {
            /* Use device protected storage because CarService may need to access the database
             * before the user has authenticated.
             */
            super(context.createDeviceProtectedStorageContext(), useDataSystemCarDir
                            ? new File(CarWatchdogService.getWatchdogDirFile(), DATABASE_NAME)
                                    .getAbsolutePath()
                            : DATABASE_NAME,
                    /* name= */ null, DATABASE_VERSION);
            mTimeSource = timeSource;
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
            ZonedDateTime currentDate = mTimeSource.getCurrentDate();
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
