/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.car.bugreport;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;


/**
 * Provides a bug storage interface to save and upload bugreports filed from all users.
 * In Android Automotive user 0 runs as the system and all the time, while other users won't once
 * their session ends. This content provider enables bug reports to be uploaded even after
 * user session ends.
 */
public class BugStorageProvider extends ContentProvider {
    private static final String TAG = BugStorageProvider.class.getSimpleName();

    private static final String AUTHORITY = "com.android.car.bugreport";
    private static final String BUG_REPORTS_TABLE = "bugreports";
    private static final String URL_SEGMENT_DELETE_ZIP_FILE = "deleteZipFile";
    private static final String URL_SEGMENT_COMPLETE_DELETE = "completeDelete";

    static final Uri BUGREPORT_CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/" + BUG_REPORTS_TABLE);

    /** See {@link MetaBugReport} for column descriptions. */
    static final String COLUMN_ID = "_ID";
    static final String COLUMN_USERNAME = "username";
    static final String COLUMN_TITLE = "title";
    static final String COLUMN_TIMESTAMP = "timestamp";
    static final String COLUMN_DESCRIPTION = "description";
    static final String COLUMN_FILEPATH = "filepath";
    static final String COLUMN_STATUS = "status";
    static final String COLUMN_STATUS_MESSAGE = "message";
    static final String COLUMN_TYPE = "type";

    // URL Matcher IDs.
    private static final int URL_MATCHED_BUG_REPORTS_URI = 1;
    private static final int URL_MATCHED_BUG_REPORT_ID_URI = 2;
    private static final int URL_MATCHED_DELETE_ZIP_FILE = 3;
    private static final int URL_MATCHED_COMPLETE_DELETE = 4;

    private Handler mHandler;

    private DatabaseHelper mDatabaseHelper;
    private final UriMatcher mUriMatcher;
    private Config mConfig;

    /**
     * A helper class to work with sqlite database.
     */
    private static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String TAG = DatabaseHelper.class.getSimpleName();

        private static final String DATABASE_NAME = "bugreport.db";

        /**
         * All changes in database versions should be recorded here.
         * 1: Initial version.
         * 2: Add integer column details_needed.
         */
        private static final int INITIAL_VERSION = 1;
        private static final int TYPE_VERSION = 2;
        private static final int DATABASE_VERSION = TYPE_VERSION;

        private static final String CREATE_TABLE = "CREATE TABLE " + BUG_REPORTS_TABLE + " ("
                + COLUMN_ID + " INTEGER PRIMARY KEY,"
                + COLUMN_USERNAME + " TEXT,"
                + COLUMN_TITLE + " TEXT,"
                + COLUMN_TIMESTAMP + " TEXT NOT NULL,"
                + COLUMN_DESCRIPTION + " TEXT NULL,"
                + COLUMN_FILEPATH + " TEXT DEFAULT NULL,"
                + COLUMN_STATUS + " INTEGER DEFAULT " + Status.STATUS_WRITE_PENDING.getValue() + ","
                + COLUMN_STATUS_MESSAGE + " TEXT NULL,"
                + COLUMN_TYPE + " INTEGER DEFAULT " + MetaBugReport.TYPE_INTERACTIVE
                + ");";

        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(CREATE_TABLE);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.w(TAG, "Upgrading from " + oldVersion + " to " + newVersion);
            if (oldVersion == INITIAL_VERSION && newVersion == TYPE_VERSION) {
                db.execSQL("ALTER TABLE " + BUG_REPORTS_TABLE + " ADD COLUMN "
                        + COLUMN_TYPE + " INTEGER DEFAULT " + MetaBugReport.TYPE_INTERACTIVE);
            }
        }
    }

    /** Builds {@link Uri} that points to a bugreport entry with provided bugreport id. */
    static Uri buildUriWithBugId(int bugReportId) {
        return Uri.parse("content://" + AUTHORITY + "/" + BUG_REPORTS_TABLE + "/" + bugReportId);
    }

    /** Builds {@link Uri} that completely deletes the bugreport from DB and files. */
    static Uri buildUriCompleteDelete(int bugReportId) {
        return Uri.parse("content://" + AUTHORITY + "/" + BUG_REPORTS_TABLE + "/"
                + URL_SEGMENT_COMPLETE_DELETE + "/" + bugReportId);
    }

    /** Builds {@link Uri} that deletes a zip file for given bugreport id. */
    static Uri buildUriDeleteZipFile(int bugReportId) {
        return Uri.parse("content://" + AUTHORITY + "/" + BUG_REPORTS_TABLE + "/"
                + URL_SEGMENT_DELETE_ZIP_FILE + "/" + bugReportId);
    }

    public BugStorageProvider() {
        mUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        mUriMatcher.addURI(AUTHORITY, BUG_REPORTS_TABLE, URL_MATCHED_BUG_REPORTS_URI);
        mUriMatcher.addURI(AUTHORITY, BUG_REPORTS_TABLE + "/#", URL_MATCHED_BUG_REPORT_ID_URI);
        mUriMatcher.addURI(
                AUTHORITY, BUG_REPORTS_TABLE + "/" + URL_SEGMENT_DELETE_ZIP_FILE + "/#",
                URL_MATCHED_DELETE_ZIP_FILE);
        mUriMatcher.addURI(
                AUTHORITY, BUG_REPORTS_TABLE + "/" + URL_SEGMENT_COMPLETE_DELETE + "/#",
                URL_MATCHED_COMPLETE_DELETE);
    }

    @Override
    public boolean onCreate() {
        mDatabaseHelper = new DatabaseHelper(getContext());
        mHandler = new Handler();
        mConfig = new Config();
        mConfig.start();
        return true;
    }

    @Override
    public Cursor query(
            @NonNull Uri uri,
            @Nullable String[] projection,
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String sortOrder) {
        return query(uri, projection, selection, selectionArgs, sortOrder, null);
    }

    @Nullable
    @Override
    public Cursor query(
            @NonNull Uri uri,
            @Nullable String[] projection,
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String sortOrder,
            @Nullable CancellationSignal cancellationSignal) {
        String table;
        switch (mUriMatcher.match(uri)) {
            // returns the list of bugreports that match the selection criteria.
            case URL_MATCHED_BUG_REPORTS_URI:
                table = BUG_REPORTS_TABLE;
                break;
            //  returns the bugreport that match the id.
            case URL_MATCHED_BUG_REPORT_ID_URI:
                table = BUG_REPORTS_TABLE;
                if (selection != null || selectionArgs != null) {
                    throw new IllegalArgumentException("selection is not allowed for "
                            + URL_MATCHED_BUG_REPORT_ID_URI);
                }
                selection = COLUMN_ID + "=?";
                selectionArgs = new String[]{ uri.getLastPathSegment() };
                break;
            default:
                throw new IllegalArgumentException("Unknown URL " + uri);
        }
        SQLiteDatabase db = mDatabaseHelper.getReadableDatabase();
        Cursor cursor = db.query(false, table, null, selection, selectionArgs, null, null,
                sortOrder, null, cancellationSignal);
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        String table;
        if (values == null) {
            throw new IllegalArgumentException("values cannot be null");
        }
        switch (mUriMatcher.match(uri)) {
            case URL_MATCHED_BUG_REPORTS_URI:
                table = BUG_REPORTS_TABLE;
                String filepath = FileUtils.getZipFile(getContext(),
                        (String) values.get(COLUMN_TIMESTAMP),
                        (String) values.get(COLUMN_USERNAME)).getPath();
                values.put(COLUMN_FILEPATH, filepath);
                break;
            default:
                throw new IllegalArgumentException("unknown uri" + uri);
        }
        SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();
        long rowId = db.insert(table, null, values);
        if (rowId > 0) {
            Uri resultUri = Uri.parse("content://" + AUTHORITY + "/" + table + "/" + rowId);
            // notify registered content observers
            getContext().getContentResolver().notifyChange(resultUri, null);
            return resultUri;
        }
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        if (mUriMatcher.match(uri) != URL_MATCHED_BUG_REPORT_ID_URI) {
            throw new IllegalArgumentException("unknown uri:" + uri);
        }
        // We only store zip files in this provider.
        return "application/zip";
    }

    @Override
    public int delete(
            @NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        SQLiteDatabase db = mDatabaseHelper.getReadableDatabase();
        switch (mUriMatcher.match(uri)) {
            case URL_MATCHED_DELETE_ZIP_FILE:
                if (selection != null || selectionArgs != null) {
                    throw new IllegalArgumentException("selection is not allowed for "
                            + URL_MATCHED_DELETE_ZIP_FILE);
                }
                if (deleteZipFile(Integer.parseInt(uri.getLastPathSegment()))) {
                    getContext().getContentResolver().notifyChange(uri, null);
                    return 1;
                }
                return 0;
            case URL_MATCHED_COMPLETE_DELETE:
                if (selection != null || selectionArgs != null) {
                    throw new IllegalArgumentException("selection is not allowed for "
                            + URL_MATCHED_COMPLETE_DELETE);
                }
                selection = COLUMN_ID + " = ?";
                selectionArgs = new String[]{uri.getLastPathSegment()};
                // Ignore the results of zip file deletion, possibly it wasn't even created.
                deleteZipFile(Integer.parseInt(uri.getLastPathSegment()));
                getContext().getContentResolver().notifyChange(uri, null);
                return db.delete(BUG_REPORTS_TABLE, selection, selectionArgs);
            default:
                throw new IllegalArgumentException("Unknown URL " + uri);
        }
    }

    @Override
    public int update(
            @NonNull Uri uri,
            @Nullable ContentValues values,
            @Nullable String selection,
            @Nullable String[] selectionArgs) {
        if (values == null) {
            throw new IllegalArgumentException("values cannot be null");
        }
        String table;
        switch (mUriMatcher.match(uri)) {
            case URL_MATCHED_BUG_REPORTS_URI:
                table = BUG_REPORTS_TABLE;
                break;
            default:
                throw new IllegalArgumentException("Unknown URL " + uri);
        }
        SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();
        int rowCount = db.update(table, values, selection, selectionArgs);
        if (rowCount > 0) {
            // notify registered content observers
            getContext().getContentResolver().notifyChange(uri, null);
        }
        Integer status = values.getAsInteger(COLUMN_STATUS);
        // When the status is set to STATUS_UPLOAD_PENDING, we schedule an UploadJob under the
        // current user, which is the primary user.
        if (status != null && status.equals(Status.STATUS_UPLOAD_PENDING.getValue())) {
            JobSchedulingUtils.scheduleUploadJob(BugStorageProvider.this.getContext());
        }
        return rowCount;
    }

    /**
     * This is called when the OutputStream is requested by
     * {@link BugStorageUtils#openBugReportFile}.
     *
     * It expects the file to be a zip file and schedules an upload under the primary user.
     */
    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode)
            throws FileNotFoundException {
        if (mUriMatcher.match(uri) != URL_MATCHED_BUG_REPORT_ID_URI) {
            throw new IllegalArgumentException("unknown uri:" + uri);
        }

        // Because we expect URI to be URL_MATCHED_BUG_REPORT_ID_URI.
        int bugreportId = Integer.parseInt(uri.getLastPathSegment());
        MetaBugReport bugReport =
                BugStorageUtils.findBugReport(getContext(), bugreportId)
                        .orElseThrow(() -> new FileNotFoundException("No record found for " + uri));
        String path = bugReport.getFilePath();

        int modeBits = ParcelFileDescriptor.parseMode(mode);
        try {
            return ParcelFileDescriptor.open(new File(path), modeBits, mHandler, e -> {
                if (mode.equals("r")) {
                    Log.i(TAG, "File " + path + " opened in read-only mode.");
                    return;
                } else if (!mode.equals("w")) {
                    Log.e(TAG, "Only read-only or write-only mode supported; mode=" + mode);
                    return;
                }
                Log.i(TAG, "File " + path + " opened in write-only mode, scheduling for upload.");
                Status status;
                if (e == null) {
                    // success writing the file. Update the field to indicate bugreport
                    // is ready for upload.
                    status = mConfig.autoUploadBugReport(bugReport)
                            ? Status.STATUS_UPLOAD_PENDING
                            : Status.STATUS_PENDING_USER_ACTION;
                } else {
                    // We log it and ignore it
                    Log.e(TAG, "Bug report file write failed ", e);
                    status = Status.STATUS_WRITE_FAILED;
                }
                SQLiteDatabase db = mDatabaseHelper.getWritableDatabase();
                ContentValues values = new ContentValues();
                values.put(COLUMN_STATUS, status.getValue());
                db.update(BUG_REPORTS_TABLE, values, COLUMN_ID + "=?",
                        new String[]{ uri.getLastPathSegment() });
                if (status == Status.STATUS_UPLOAD_PENDING) {
                    JobSchedulingUtils.scheduleUploadJob(BugStorageProvider.this.getContext());
                }
                Log.i(TAG, "Finished adding bugreport " + path + " " + uri);
                getContext().getContentResolver().notifyChange(uri, null);
            });
        } catch (IOException e) {
            // An IOException (for example not being able to open the file, will crash us.
            // That is ok.
            throw new RuntimeException(e);
        }
    }

    /**
     * Print the Provider's state into the given stream. This gets invoked if
     * you run "dumpsys activity provider com.android.car.bugreport/.BugStorageProvider".
     *
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param writer The PrintWriter to which you should dump your state.  This will be
     * closed for you after you return.
     * @param args additional arguments to the dump request.
     */
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("BugStorageProvider:");
        mConfig.dump(/* prefix= */ "  ", writer);
    }

    private boolean deleteZipFile(int bugreportId) {
        Optional<MetaBugReport> bugReport =
                BugStorageUtils.findBugReport(getContext(), bugreportId);
        if (!bugReport.isPresent()) {
            Log.i(TAG,
                    "Failed to delete zip file for bug " + bugreportId + ": bugreport not found.");
            return false;
        }
        Path zipPath = new File(bugReport.get().getFilePath()).toPath();
        // This statement is to prevent the app to print confusing full FileNotFound error stack
        // when the user is navigating from BugReportActivity (audio message recording dialog) to
        // BugReportInfoActivity by clicking "Show Bug Reports" button.
        if (!Files.exists(zipPath)) {
            Log.w(TAG, "Failed to delete " + zipPath + ". File not found.");
            return false;
        }
        try {
            Files.delete(zipPath);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to delete zip file " + zipPath, e);
            return false;
        }
    }
}
