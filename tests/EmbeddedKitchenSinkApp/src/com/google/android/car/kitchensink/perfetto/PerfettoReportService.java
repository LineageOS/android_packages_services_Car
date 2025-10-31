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

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.os.UserHandle;
import android.service.tracing.TraceReportService;
import android.util.Log;

import com.google.android.car.kitchensink.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * A service that receives Perfetto traces and saves them to disk.
 *
 * <p>This service is always started in user 0 / system user's context by the TraceReportService
 * API. Thus, the traces are saved under user 0's directory.
 */
public class PerfettoReportService extends TraceReportService {
    private static final String TAG = PerfettoReportService.class.getSimpleName();
    private static final String NOTIFICATION_CHANNEL_ID = "KITCHENSINK_PERFETTO_REPORT_SERVICE";
    private static final int NOTIFICATION_ID = 1;
    public static final String TRACE_FILES_ROOT_DIR = "perfetto_traces";

    private NotificationManager mNotificationManager;
    private NotificationChannel mNotificationChannel;
    private String mNotificationTitle;
    private String mNotificationContent;

    @Override
    public void onCreate() {
        Log.d(TAG, "Executing PerfettoReporter#onCreate");
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            // SECURITY: Trace uploading is protected by the permission
            // android.permission.BIND_TRACE_REPORT_SERVICE which is only defined in T. Pre-T, any
            // side-loaded app can claim this permission and get access to reporting traces.
            throw new AssertionError("Trace reporting service should not be enabled pre-T.");
        }
        super.onCreate();
        mNotificationManager = getSystemService(NotificationManager.class);
        mNotificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                getString(R.string.perfetto_notification_channel_label),
                NotificationManager.IMPORTANCE_HIGH);
        mNotificationTitle = getString(R.string.perfetto_issue_detected_notification_title);
        mNotificationContent = getString(R.string.perfetto_issue_detected_notification_content);
        mNotificationManager.createNotificationChannel(mNotificationChannel);
    }

    @Override
    public void onReportTrace(TraceParams args) {
        Log.d(TAG, "Received trace with UUID '" + args.getUuid().toString() + "'");
        String fileName = "perfetto_" + args.getUuid().toString() + ".trace";

        try {
            File traceDir = new File(getFilesDir(), TRACE_FILES_ROOT_DIR);
            if (!traceDir.exists()) {
                traceDir.mkdirs();
            }
            File f = new File(traceDir, fileName);
            boolean created = f.createNewFile();
            if (!created) {
                throw new IllegalStateException("Failed to create file");
            }
            try (AutoCloseInputStream i = new AutoCloseInputStream(args.getFd())) {
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(i.readAllBytes());
                }
            }
            Log.d(TAG, "Wrote the trace to " + f.getAbsolutePath());
            sendNotification();
        } catch (IOException e) {
            throw new IllegalStateException("IO Exception", e);
        }
    }

    private void sendNotification() {
        PendingIntent listTracesIntent = getPendingIntent(this);
        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(mNotificationTitle)
                .setContentText(mNotificationContent)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setCategory(Notification.CATEGORY_CAR_WARNING)
                .setLocalOnly(true)
                .setContentIntent(listTracesIntent)
                .setAutoCancel(true)
                .build();

        // The notification is triggered from User 0's context. So, send the notification for the
        // current visible foreground user. Don't cache the user ID because the user profiles may
        // switch anytime.
        mNotificationManager.notifyAsUser(TAG, NOTIFICATION_ID, notification,
                UserHandle.of(ActivityManager.getCurrentUser()));
        Log.d(TAG, "Sent user notification with id " + NOTIFICATION_ID);
    }

    /**
     * Returns a {@link PendingIntent} to launch the {@link PerfettoTraceListActivity}.
     *
     * {@link PerfettoTraceListActivity} must be launched in user 0 context to show the traces
     * saved under user 0's directory.
     */
    static PendingIntent getPendingIntent(Context context) {
        Intent intent = new Intent(context, PerfettoTraceListActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, NOTIFICATION_ID, intent,
                PendingIntent.FLAG_IMMUTABLE, /* options= */ null);
    }
}
