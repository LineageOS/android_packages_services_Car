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
package com.google.android.car.bugreport;

import static com.google.android.car.bugreport.PackageUtils.getPackageVersion;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides an activity that provides information on the bugreports that are filed.
 */
public class BugReportInfoActivity extends Activity {
    public static final String TAG = BugReportInfoActivity.class.getSimpleName();

    private RecyclerView mRecyclerView;
    private RecyclerView.Adapter mAdapter;
    private RecyclerView.LayoutManager mLayoutManager;
    private NotificationManager mNotificationManager;

    private static class BugReportInfoTask extends AsyncTask<Void, Void, List<MetaBugReport>> {
        private final WeakReference<BugReportInfoActivity> mBugReportInfoActivityWeakReference;

        BugReportInfoTask(BugReportInfoActivity activity) {
            mBugReportInfoActivityWeakReference = new WeakReference<>(activity);
        }

        @Override
        protected List<MetaBugReport> doInBackground(Void... voids) {
            BugReportInfoActivity activity = mBugReportInfoActivityWeakReference.get();
            if (activity == null) {
                Log.w(TAG, "Activity is gone, cancelling BugReportInfoTask.");
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
            activity.mAdapter = new BugInfoAdapter(result);
            activity.mRecyclerView.setAdapter(activity.mAdapter);
            activity.mRecyclerView.getAdapter().notifyDataSetChanged();
        }
    }

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

        // specify an adapter (see also next example)
        mAdapter = new BugInfoAdapter(new ArrayList<>());
        mRecyclerView.setAdapter(mAdapter);

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
        new BugReportInfoTask(this).execute();
    }

    /**
     * Dismisses {@link BugReportService#BUGREPORT_FINISHED_NOTIF_ID}, otherwise the notification
     * will stay there forever if this activity opened through the App Launcher.
     */
    private void cancelBugReportFinishedNotification() {
        mNotificationManager.cancel(BugReportService.BUGREPORT_FINISHED_NOTIF_ID);
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
}
