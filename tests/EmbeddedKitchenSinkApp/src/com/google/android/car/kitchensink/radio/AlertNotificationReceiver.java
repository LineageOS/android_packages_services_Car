/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.google.android.car.kitchensink.radio;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import java.util.concurrent.TimeUnit;

public final class AlertNotificationReceiver extends BroadcastReceiver {

    private static final String TAG = AlertNotificationReceiver.class.getSimpleName();
    private static final long SNOOZE_TIME = TimeUnit.MINUTES.toMillis(1);

    @Override
    public void onReceive(Context context, Intent intent) {
        int notificationId = intent.getIntExtra(AlertNotificationHelper.EXTRA_KEY_NOTIFICATION_ID,
                AlertNotificationHelper.INVALID_NOTIFICATION_ID);
        if (notificationId == AlertNotificationHelper.INVALID_NOTIFICATION_ID) {
            Log.e(TAG, "Invalid notification id");
            return;
        }
        String action = intent.getAction();
        Log.i(TAG, action + " for alert notification " + notificationId);

        NotificationManagerCompat notificationManagerCompat =
                NotificationManagerCompat.from(context);
        if (AlertNotificationHelper.ACTION_SNOOZE.equals(action)) {
            String title = intent.getStringExtra(AlertNotificationHelper.EXTRA_KEY_TITLE);
            String text = intent.getStringExtra(AlertNotificationHelper.EXTRA_KEY_TEXT);
            notificationManagerCompat.cancel(notificationId);
            scheduleSnooze(context, title, text, notificationId);
        } else {
            Log.e(TAG, "Undefined action " + action);
        }
    }

    private void scheduleSnooze(Context context, String title, String text, int notificationId) {
        PendingIntent snoozePendingIntent = createSnoozePendingIntent(context, title, text,
                notificationId);
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager != null && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, SNOOZE_TIME,
                    snoozePendingIntent);
        } else {
            Log.e(TAG, "Cannot schedule snooze");
        }
    }

    private static PendingIntent createSnoozePendingIntent(Context activityContext, String title,
            String text, int notificationId) {
        Intent intent = new Intent(activityContext, AlertSnoozeReceiver.class);
        intent.setAction(AlertNotificationHelper.ACTION_NOTIFICATION);
        intent.putExtra(AlertNotificationHelper.EXTRA_KEY_NOTIFICATION_ID, notificationId);
        intent.putExtra(AlertNotificationHelper.EXTRA_KEY_TITLE, title);
        intent.putExtra(AlertNotificationHelper.EXTRA_KEY_TEXT, text);
        return PendingIntent.getBroadcast(activityContext, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }
}
