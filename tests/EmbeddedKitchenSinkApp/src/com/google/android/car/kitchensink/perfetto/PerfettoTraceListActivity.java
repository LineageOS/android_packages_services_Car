/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.google.android.car.kitchensink.perfetto;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * An activity that shows a list of saved Perfetto traces.
 *
 * <p>This activity is always launched as the system user to be able to read the trace files
 * saved in the system user's context.
 */
public class PerfettoTraceListActivity extends Activity {
    private static final String TAG = "PerfettoTraceList";
    // Threshold to warn users when traces expiry with this period.
    private static final long WARNING_EXPIRY_THRESHOLD_DAYS = 7;
    // Threshold to warn users when disk usage space exceeds this percentage.
    private static final double WARNING_LOW_DISK_SPACE_THRESHOLD_PERCENTAGE = 0.95;

    private Button mBackButton;
    private ListView mTraceListView;
    private TextView mDiskUsageView;
    private TextView mRetentionPeriodView;
    private TextView mWarningBanner;
    private long mMaxDiskUsageBytes;
    private int mRetentionDays;
    private String mNoFilesString;
    private String mDiskUsageDesc;
    private String mMaxRetentionDesc;
    private String mWarnLowDiskSpace;
    private String mWarnExpirySoon;
    private SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int myUserId = UserHandle.myUserId();
        Log.i(TAG, "onCreate userid " + myUserId);
        if (myUserId != UserHandle.USER_SYSTEM) {
            Log.i(TAG, "onCreate re-starting self as user 0");
            Intent selfIntent = new Intent(this, PerfettoTraceListActivity.class);
            startActivityAsUser(selfIntent, UserHandle.SYSTEM);
            finish();
            return;
        }

        PerfettoController.deleteOldTraces(this);

        setContentView(R.layout.perfetto_trace_list_activity);
        mBackButton = findViewById(R.id.back_button);
        mTraceListView = findViewById(R.id.trace_list);
        mDiskUsageView = findViewById(R.id.disk_usage_text);
        mRetentionPeriodView = findViewById(R.id.retention_period_text);
        mWarningBanner = findViewById(R.id.warning_banner);
        mMaxDiskUsageBytes = getResources().getInteger(R.integer.perfetto_trace_max_disk_usage_mib)
                * 1024L * 1024L;
        mRetentionDays = getResources().getInteger(R.integer.perfetto_trace_retention_days);
        mNoFilesString = getString(R.string.perfetto_trace_list_activity_no_files);
        mDiskUsageDesc = getString(R.string.perfetto_trace_list_activity_disk_usage_desc);
        mMaxRetentionDesc = getString(R.string.perfetto_trace_list_activity_max_retention_desc);
        mWarnLowDiskSpace = getString(R.string.perfetto_trace_list_activity_warning_low_disk_space);
        mWarnExpirySoon = getString(R.string.perfetto_trace_list_activity_warning_expiry_soon);

        mBackButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, KitchenSinkActivity.class);
            intent.putExtra("select", "perfetto");
            startActivityAsUser(intent, UserHandle.of(ActivityManager.getCurrentUser()));
            finish();
        });

        updateActivity();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateActivity();
    }

    private void updateActivity() {
        File[] traceFiles = PerfettoController.listTraceFiles(this);
        if (traceFiles == null || traceFiles.length == 0) {
            showNoFiles();
            return;
        }
        showTraceList(traceFiles);
        displayUsageInfo(traceFiles);
        updateWarningBanner(traceFiles);
    }

    private void showNoFiles() {
        String[] noFiles = {mNoFilesString};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                noFiles);
        mTraceListView.setAdapter(adapter);
        return;
    }

    /**
     * Updates the trace list view with the trace infos.
     */
    private void showTraceList(File[] traceFiles) {
        List<TraceInfo> traceInfos = new ArrayList<>();
        long retentionMillis = TimeUnit.DAYS.toMillis(mRetentionDays);
        for (File traceFile : traceFiles) {
            TraceInfo info = new TraceInfo();
            info.mFileName = traceFile.getName();
            info.mCollectedDate = mDateFormat.format(traceFile.lastModified());
            info.mSizeMib = traceFile.length() / (1024.0 * 1024.0);
            long remainingMillis = (traceFile.lastModified() + retentionMillis)
                    - System.currentTimeMillis();
            info.mRemainingDays = TimeUnit.MILLISECONDS.toDays(remainingMillis);
            traceInfos.add(info);
        }

        TraceInfoAdapter adapter = new TraceInfoAdapter(this, traceInfos);
        mTraceListView.setAdapter(adapter);
    }

    /**
     * Displays the disk usage and retention period info.
     */
    private void displayUsageInfo(File[] traceFiles) {
        long currentSizeBytes = 0;
        for (File file : traceFiles) {
            currentSizeBytes += file.length();
        }

        mDiskUsageView.setText(String.format(mDiskUsageDesc, currentSizeBytes / (1024.0 * 1024.0),
                mMaxDiskUsageBytes / (1024.0 * 1024.0)));

        mRetentionPeriodView.setText(String.format(mMaxRetentionDesc, mRetentionDays));
    }

    /**
     * Displays a warning banner.
     *
     * <p>The banner is displayed when exceed 95% of max disk usage limit or some traces are
     * expiring soon.
     */
    private void updateWarningBanner(File[] traceFiles) {
        long currentSize = 0;
        boolean expiringSoon = false;
        long retentionMillis = TimeUnit.DAYS.toMillis(mRetentionDays);
        for (File file : traceFiles) {
            currentSize += file.length();
            long remainingMillis = (file.lastModified() + retentionMillis)
                    - System.currentTimeMillis();
            if (TimeUnit.MILLISECONDS.toDays(remainingMillis) <= WARNING_EXPIRY_THRESHOLD_DAYS) {
                expiringSoon = true;
                break;
            }
        }

        StringBuilder warningMessage = new StringBuilder();
        if (currentSize > mMaxDiskUsageBytes * WARNING_LOW_DISK_SPACE_THRESHOLD_PERCENTAGE) {
            warningMessage.append(mWarnLowDiskSpace);
        }
        if (expiringSoon) {
            if (warningMessage.length() > 0) {
                warningMessage.append(" ");
            }
            warningMessage.append(mWarnExpirySoon);
        }

        if (warningMessage.length() > 0) {
            mWarningBanner.setText(warningMessage.toString());
            mWarningBanner.setVisibility(View.VISIBLE);
        } else {
            mWarningBanner.setVisibility(View.GONE);
        }
    }

    private static class TraceInfo {
        private String mFileName;
        private String mCollectedDate;
        private double mSizeMib;
        private long mRemainingDays;
    }

    private static class TraceInfoAdapter extends ArrayAdapter<TraceInfo> {
        TraceInfoAdapter(Context context, List<TraceInfo> traces) {
            super(context, 0, traces);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TraceInfo trace = getItem(position);
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(
                        R.layout.trace_list_item, parent, false);
            }
            TextView traceName = convertView.findViewById(R.id.trace_name);
            TextView traceCollectedDate = convertView.findViewById(R.id.trace_collected_date);
            TextView traceSize = convertView.findViewById(R.id.trace_size);
            TextView traceExpiry = convertView.findViewById(R.id.trace_expiry);

            traceName.setText(trace.mFileName);
            traceCollectedDate.setText(trace.mCollectedDate);
            traceSize.setText(String.format("%.2f MiB", trace.mSizeMib));
            traceExpiry.setText(String.format("%d days", trace.mRemainingDays));

            return convertView;
        }
    }
}
