/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car.remoteaccess;

import android.annotation.Nullable;
import android.car.builtin.util.Slogf;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.android.car.systeminterface.SystemInterface;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class RemoteAccessStorage {

    private static final String TAG = RemoteAccessStorage.class.getSimpleName();

    private final RemoteAccessDbHelper mDbHelper;

    RemoteAccessStorage(Context context, SystemInterface systemInterface) {
        mDbHelper = new RemoteAccessDbHelper(context,
                systemInterface.getSystemCarDir().getAbsolutePath());
    }

    void release() {
        mDbHelper.close();
    }

    @Nullable
    ClientIdEntry getClientIdEntry(String clientId) {
        try (SQLiteDatabase db = mDbHelper.getReadableDatabase()) {
            return ClientIdTable.queryClientIdEntry(db, clientId);
        }
    }

    @Nullable
    List<ClientIdEntry> getClientIdEntries() {
        try (SQLiteDatabase db = mDbHelper.getReadableDatabase()) {
            return ClientIdTable.queryClientIdEntries(db);
        }
    }

    boolean updateClientId(ClientIdEntry entry) {
        boolean isSuccessful = false;
        try (SQLiteDatabase db = mDbHelper.getWritableDatabase()) {
            try {
                db.beginTransaction();
                if (!ClientIdTable.replaceEntry(db, entry)) {
                    return false;
                }
                db.setTransactionSuccessful();
                isSuccessful = true;
            } finally {
                db.endTransaction();
            }
        }
        return isSuccessful;
    }

    boolean deleteClientId(String clientId) {
        // TODO(b/260523566): Implement the logic.
        return false;
    }

    // Defines the client token entry stored in the ClientIdTable.
    static final class ClientIdEntry {
        public final String clientId;
        public final long idCreationTime;
        public final String packageName;

        ClientIdEntry(String clientId, long idCreationTime, String packageName) {
            Preconditions.checkArgument(packageName != null, "packageName cannot be null");
            this.clientId = clientId;
            this.idCreationTime = idCreationTime;
            this.packageName = packageName;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof ClientIdEntry)) {
                return false;
            }
            ClientIdEntry other = (ClientIdEntry) obj;
            return clientId.equals(other.clientId) && idCreationTime == other.idCreationTime
                    && packageName.equals(other.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(clientId, idCreationTime, packageName);
        }

        @Override
        public String toString() {
            return new StringBuilder().append("ClientIdEntry{clientId: ").append(clientId)
                    .append(", idCreationTime: ").append(idCreationTime).append(", packageName: ")
                    .append(packageName).append('}').toString();
        }
    }

    /**
     * Defines the contents and queries for the client ID table.
     */
    static final class ClientIdTable {
        // TODO(b/266371728): Add columns to handle client ID expiration.
        public static final String TABLE_NAME = "client_token_table";
        public static final String INDEX_NAME = "index_package_name";
        public static final String COLUMN_CLIENT_ID = "client_id";
        public static final String COLUMN_CLIENT_ID_CREATION_TIME = "id_creation_time";
        public static final String COLUMN_PACKAGE_NAME = "package_name";

        public static void createDb(SQLiteDatabase db) {
            StringBuilder createCommand = new StringBuilder();
            createCommand.append("CREATE TABLE ").append(TABLE_NAME).append(" (")
                    .append(COLUMN_CLIENT_ID).append(" TEXT NOT NULL PRIMARY KEY, ")
                    .append(COLUMN_CLIENT_ID_CREATION_TIME).append(" BIGINT NOT NULL, ")
                    .append(COLUMN_PACKAGE_NAME).append(" TEXT NOT NULL")
                    .append(")");
            db.execSQL(createCommand.toString());
            Slogf.i(TAG, "%s table is successfully created in the %s database (version %d)",
                    TABLE_NAME, RemoteAccessDbHelper.DATABASE_NAME,
                    RemoteAccessDbHelper.DATABASE_VERSION);
        }

        // Returns ClientIdEntry for the given client ID.
        @Nullable
        public static ClientIdEntry queryClientIdEntry(SQLiteDatabase db, String clientId) {
            String queryCommand = String.format("SELECT %s, %s, %s FROM %s WHERE %s = ?",
                    COLUMN_CLIENT_ID, COLUMN_CLIENT_ID_CREATION_TIME, COLUMN_PACKAGE_NAME,
                    TABLE_NAME, COLUMN_CLIENT_ID);
            String[] selectionArgs = new String[]{clientId};

            try (Cursor cursor = db.rawQuery(queryCommand, selectionArgs)) {
                while (cursor.moveToNext()) {
                    // Query by a primary key must return one result.
                    return new ClientIdEntry(cursor.getString(0), cursor.getLong(1),
                            cursor.getString(2));
                }
            }
            return null;
        }

        // Returns all client ID entries.
        @Nullable
        public static List<ClientIdEntry> queryClientIdEntries(SQLiteDatabase db) {
            String queryCommand = String.format("SELECT %s, %s, %s FROM %s", COLUMN_CLIENT_ID,
                    COLUMN_CLIENT_ID_CREATION_TIME, COLUMN_PACKAGE_NAME, TABLE_NAME);

            try (Cursor cursor = db.rawQuery(queryCommand, new String[]{})) {
                int entryCount = cursor.getCount();
                if (entryCount == 0) return null;
                List<ClientIdEntry> entries = new ArrayList<>(entryCount);
                while (cursor.moveToNext()) {
                    entries.add(new ClientIdEntry(cursor.getString(0), cursor.getLong(1),
                            cursor.getString(2)));
                }
                return entries;
            }
        }

        public static boolean replaceEntry(SQLiteDatabase db, ClientIdEntry entry) {
            ContentValues values = new ContentValues();
            values.put(COLUMN_CLIENT_ID, entry.clientId);
            values.put(COLUMN_CLIENT_ID_CREATION_TIME, entry.idCreationTime);
            values.put(COLUMN_PACKAGE_NAME, entry.packageName);

            try {
                if (db.replaceOrThrow(ClientIdTable.TABLE_NAME, /* nullColumnHack= */ null,
                        values) == -1) {
                    Slogf.e(TAG, "Failed to replace %s entry [%s]", TABLE_NAME, values);
                    return false;
                }
            } catch (SQLException e) {
                Slogf.e(TAG, e, "Failed to replace %s entry [%s]", TABLE_NAME, values);
                return false;
            }
            return true;
        }
    }

    static final class RemoteAccessDbHelper extends SQLiteOpenHelper {
        public static final String DATABASE_NAME = "car_remoteaccess.db";

        private static final int DATABASE_VERSION = 1;

        RemoteAccessDbHelper(Context context, String systemCarDirPath) {
            super(context.createDeviceProtectedStorageContext(),
                    new File(systemCarDirPath, DATABASE_NAME).getAbsolutePath(), /* name= */ null,
                    DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            ClientIdTable.createDb(db);
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
            // Do nothing. We have only one version.
        }
    }
}
