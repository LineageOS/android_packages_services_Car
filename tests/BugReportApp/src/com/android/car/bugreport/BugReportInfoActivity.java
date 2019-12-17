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

import static com.android.car.bugreport.PackageUtils.getPackageVersion;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides an activity that provides information on the bugreports that are filed.
 */
public class BugReportInfoActivity extends Activity {
    public static final String TAG = BugReportInfoActivity.class.getSimpleName();

    /** Used for moving bug reports to a new location (e.g. USB drive). */
    private static final int SELECT_DIRECTORY_REQUEST_CODE = 1;

    private RecyclerView mRecyclerView;
    private BugInfoAdapter mBugInfoAdapter;
    private RecyclerView.LayoutManager mLayoutManager;
    private NotificationManager mNotificationManager;
    private MetaBugReport mLastSelectedBugReport;
    private BugInfoAdapter.BugInfoViewHolder mLastSelectedBugInfoViewHolder;
    private BugStorageObserver mBugStorageObserver;
    private Config mConfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bug_report_info_activity);

        mNotificationManager = getSystemService(NotificationManager.class);

        mRecyclerView = findViewById(R.id.rv_bug_report_info);
        mRecyclerView.setHasFixedSize(true);
        // use a linear layout manager
        mLayoutManager = new LinearLayoutManager(this);
        mRecyclerView.setLayoutManager(mLayoutManager);
        mRecyclerView.addItemDecoration(new DividerItemDecoration(mRecyclerView.getContext(),
                DividerItemDecoration.VERTICAL));

        mConfig = new Config();
        mConfig.start();

        mBugInfoAdapter = new BugInfoAdapter(this::onBugReportItemClicked, mConfig);
        mRecyclerView.setAdapter(mBugInfoAdapter);

        mBugStorageObserver = new BugStorageObserver(this, new Handler());

        findViewById(R.id.quit_button).setOnClickListener(this::onQuitButtonClick);
        findViewById(R.id.start_bug_report_button).setOnClickListener(
                this::onStartBugReportButtonClick);
        ((TextView) findViewById(R.id.version_text_view)).setText(
                String.format("v%s", getPackageVersion(this)));

        cancelBugReportFinishedNotification();
    }

    @Override
    protected void onStart() {
        super.onStart();
        new BugReportsLoaderAsyncTask(this).execute();
        // As BugStorageProvider is running under user0, we register using USER_ALL.
        getContentResolver().registerContentObserver(BugStorageProvider.BUGREPORT_CONTENT_URI, true,
                mBugStorageObserver, UserHandle.USER_ALL);
    }

    @Override
    protected void onStop() {
        super.onStop();
        getContentResolver().unregisterContentObserver(mBugStorageObserver);
    }

    /**
     * Dismisses {@link BugReportService#BUGREPORT_FINISHED_NOTIF_ID}, otherwise the notification
     * will stay there forever if this activity opened through the App Launcher.
     */
    private void cancelBugReportFinishedNotification() {
        mNotificationManager.cancel(BugReportService.BUGREPORT_FINISHED_NOTIF_ID);
    }

    private void onBugReportItemClicked(
            int buttonType, MetaBugReport bugReport, BugInfoAdapter.BugInfoViewHolder holder) {
        if (buttonType == BugInfoAdapter.BUTTON_TYPE_UPLOAD) {
            Log.i(TAG, "Uploading " + bugReport.getFilePath());
            BugStorageUtils.setBugReportStatus(this, bugReport, Status.STATUS_UPLOAD_PENDING, "");
            // Refresh the UI to reflect the new status.
            new BugReportsLoaderAsyncTask(this).execute();
        } else if (buttonType == BugInfoAdapter.BUTTON_TYPE_MOVE) {
            Log.i(TAG, "Moving " + bugReport.getFilePath());
            mLastSelectedBugReport = bugReport;
            mLastSelectedBugInfoViewHolder = holder;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                    SELECT_DIRECTORY_REQUEST_CODE);
        } else {
            throw new IllegalStateException("unreachable");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SELECT_DIRECTORY_REQUEST_CODE && resultCode == RESULT_OK) {
            int takeFlags =
                    data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Uri destDirUri = data.getData();
            getContentResolver().takePersistableUriPermission(destDirUri, takeFlags);
            if (mLastSelectedBugReport == null || mLastSelectedBugInfoViewHolder == null) {
                Log.w(TAG, "No bug report is selected.");
                return;
            }
            MetaBugReport updatedBugReport = BugStorageUtils.setBugReportStatus(this,
                    mLastSelectedBugReport, Status.STATUS_MOVE_IN_PROGRESS, "");
            mBugInfoAdapter.updateBugReportInDataSet(
                    updatedBugReport, mLastSelectedBugInfoViewHolder.getAdapterPosition());
            new AsyncMoveFilesTask(
                this,
                    mBugInfoAdapter,
                    updatedBugReport,
                    mLastSelectedBugInfoViewHolder,
                    destDirUri).execute();
        }
    }

    private void onQuitButtonClick(View view) {
        finish();
    }

    private void onStartBugReportButtonClick(View view) {
        Intent intent = new Intent(this, BugReportActivity.class);
        // Clear top is needed, otherwise multiple BugReportActivity-ies get opened and
        // MediaRecorder crashes.
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    /**
     * Print the Provider's state into the given stream. This gets invoked if
     * you run "adb shell dumpsys activity BugReportInfoActivity".
     *
     * @param prefix Desired prefix to prepend at each line of output.
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param writer The PrintWriter to which you should dump your state.  This will be
     * closed for you after you return.
     * @param args additional arguments to the dump request.
     */
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        mConfig.dump(prefix, writer);
    }

    /** Observer for {@link BugStorageProvider}. */
    private static class BugStorageObserver extends ContentObserver {
        private final BugReportInfoActivity mInfoActivity;

        /**
         * Creates a content observer.
         *
         * @param activity A {@link BugReportInfoActivity} instance.
         * @param handler The handler to run {@link #onChange} on, or null if none.
         */
        BugStorageObserver(BugReportInfoActivity activity, Handler handler) {
            super(handler);
            mInfoActivity = activity;
        }

        @Override
        public void onChange(boolean selfChange) {
            new BugReportsLoaderAsyncTask(mInfoActivity).execute();
        }
    }

    /** Moves bugreport zip to a new location and updates RecyclerView. */
    private static final class AsyncMoveFilesTask extends AsyncTask<Void, Void, MetaBugReport> {
        private static final int COPY_BUFFER_SIZE = 4096; // bytes

        private final BugReportInfoActivity mActivity;
        private final MetaBugReport mBugReport;
        private final Uri mDestinationDirUri;
        /** RecyclerView.Adapter that contains all the bug reports. */
        private final BugInfoAdapter mBugInfoAdapter;
        /** ViewHolder for {@link #mBugReport}. */
        private final BugInfoAdapter.BugInfoViewHolder mBugViewHolder;

        AsyncMoveFilesTask(BugReportInfoActivity activity, BugInfoAdapter bugInfoAdapter,
                MetaBugReport bugReport, BugInfoAdapter.BugInfoViewHolder holder,
                Uri destinationDir) {
            mActivity = activity;
            mBugInfoAdapter = bugInfoAdapter;
            mBugReport = bugReport;
            mBugViewHolder = holder;
            mDestinationDirUri = destinationDir;
        }

        /** Moves the bugreport to the USB drive and returns the updated {@link MetaBugReport}. */
        @Override
        protected MetaBugReport doInBackground(Void... params) {
            Uri sourceUri = BugStorageProvider.buildUriWithBugId(mBugReport.getId());
            ContentResolver resolver = mActivity.getContentResolver();
            String documentId = DocumentsContract.getTreeDocumentId(mDestinationDirUri);
            Uri parentDocumentUri =
                    DocumentsContract.buildDocumentUriUsingTree(mDestinationDirUri, documentId);
            String mimeType = resolver.getType(sourceUri);
            try {
                Uri newFileUri = DocumentsContract.createDocument(resolver, parentDocumentUri,
                        mimeType,
                        new File(mBugReport.getFilePath()).toPath().getFileName().toString());
                if (newFileUri == null) {
                    Log.e(TAG, "Unable to create a new file.");
                    return BugStorageUtils.setBugReportStatus(
                            mActivity,
                            mBugReport,
                            com.android.car.bugreport.Status.STATUS_MOVE_FAILED,
                            "Unable to create a new file.");
                }
                try (InputStream input = resolver.openInputStream(sourceUri);
                     AssetFileDescriptor fd = resolver.openAssetFileDescriptor(newFileUri, "w")) {
                    OutputStream output = fd.createOutputStream();
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int len;
                    while ((len = input.read(buffer)) > 0) {
                        output.write(buffer, 0, len);
                    }
                    // Force sync the written data from memory to the disk.
                    fd.getFileDescriptor().sync();
                }
                Log.d(TAG, "Finished writing to " + newFileUri.getPath());
                if (BugStorageUtils.deleteBugReportZipfile(mActivity, mBugReport.getId())) {
                    Log.i(TAG, "Local bugreport zip is deleted.");
                } else {
                    Log.w(TAG, "Failed to delete the local bugreport " + mBugReport.getFilePath());
                }
                return BugStorageUtils.setBugReportStatus(mActivity, mBugReport,
                            com.android.car.bugreport.Status.STATUS_MOVE_SUCCESSFUL,
                            "Moved to: " + mDestinationDirUri.getPath());
            } catch (IOException e) {
                Log.e(TAG, "Failed to create the bug report in the location.", e);
                return BugStorageUtils.setBugReportStatus(
                        mActivity, mBugReport,
                        com.android.car.bugreport.Status.STATUS_MOVE_FAILED, e);
            }
        }

        @Override
        protected void onPostExecute(MetaBugReport updatedBugReport) {
            // Refresh the UI to reflect the new status.
            mBugInfoAdapter.updateBugReportInDataSet(
                    updatedBugReport, mBugViewHolder.getAdapterPosition());
        }
    }

    /** Asynchronously loads bugreports from {@link BugStorageProvider}. */
    private static final class BugReportsLoaderAsyncTask extends
            AsyncTask<Void, Void, List<MetaBugReport>> {
        private final WeakReference<BugReportInfoActivity> mBugReportInfoActivityWeakReference;

        BugReportsLoaderAsyncTask(BugReportInfoActivity activity) {
            mBugReportInfoActivityWeakReference = new WeakReference<>(activity);
        }

        @Override
        protected List<MetaBugReport> doInBackground(Void... voids) {
            BugReportInfoActivity activity = mBugReportInfoActivityWeakReference.get();
            if (activity == null) {
                Log.w(TAG, "Activity is gone, cancelling BugReportsLoaderAsyncTask.");
                return new ArrayList<>();
            }
            return BugStorageUtils.getAllBugReportsDescending(activity);
        }

        @Override
        protected void onPostExecute(List<MetaBugReport> result) {
            BugReportInfoActivity activity = mBugReportInfoActivityWeakReference.get();
            if (activity == null) {
                Log.w(TAG, "Activity is gone, cancelling onPostExecute.");
                return;
            }
            activity.mBugInfoAdapter.setDataset(result);
        }
    }
}
