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

import android.annotation.UserIdInt;
import android.car.watchdog.PackageKillableState.KillableState;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.car.CarLog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines the database to store/retrieve system resource stats history from local storage.
 */
public final class WatchdogStorage {
    private static final String TAG = CarLog.tagFor(WatchdogStorage.class);

    private final WatchdogDbHelper mDbHelper;

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

    /** Saves the given user package settings entries and returns whether the change succeeded. */
    public boolean saveUserPackageSettings(List<UserPackageSettingsEntry> entries) {
        List<ContentValues> rows = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); ++i) {
            rows.add(UserPackageSettingsTable.getContentValues(entries.get(i)));
        }
        try (SQLiteDatabase db = mDbHelper.getWritableDatabase()) {
            atomicReplaceEntries(db, UserPackageSettingsTable.TABLE_NAME, rows);
        }
    }

    /** Returns the user package setting entries. */
    public List<UserPackageSettingsEntry> getUserPackageSettings() {
        ArrayMap<String, UserPackageSettingsEntry> entriesById;
        try (SQLiteDatabase db = mDbHelper.getReadableDatabase()) {
            entriesById = UserPackageSettingsTable.querySettings(db);
        }
        List<UserPackageSettingsEntry> entries = new ArrayList<>(entryById.size());
        for (int i = 0; i < entryById.size(); ++i) {
            entries.add(entryById.valueAt(i));
        }
        return entries;
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
    }

    /**
     * Defines the Watchdog database and database level operations.
     */
    static final class WatchdogDbHelper extends SQLiteOpenHelper {
        public static final String DATABASE_DIR = "/data/system/car/watchdog/";
        public static final String DATABASE_NAME = "car_watchdog.db";

        private static final int DATABASE_VERSION = 1;

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
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
            /* Still on the 1st version so no upgrade required. */
        }
    }
}
