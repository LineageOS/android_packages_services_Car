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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.Action;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.car.kitchensink.R;

final class AlertNotificationHelper {

    private AlertNotificationHelper() {
        throw new UnsupportedOperationException("AlertNotificationHelper class is noninstantiable");
    }

    static final int INVALID_NOTIFICATION_ID = -1;
    static final String TEXT_DISMISS = "Dismiss";
    static final String TEXT_SNOOZE = "Snooze";
    static final String ACTION_DISMISS = "DISMISS";
    static final String ACTION_SNOOZE = "SNOOZE";
    static final String ACTION_NOTIFICATION = "NOTIFICATION";
    static final String EXTRA_KEY_NOTIFICATION_ID = "EXTRA_NOTIFICATION_ID";
    static final String EXTRA_KEY_TITLE = "TITLE_TEXT";
    static final String EXTRA_KEY_TEXT = "EXTRA_TEXT";
    static final String EXTRA_KEY_ALERT_TIME_MS = "EXTRA_ALERT_TIME_MS";

    static final String IMPORTANCE_ALERT_ID = "importance_high";

    static void createRadioAlertNotification(Context activityContext, String title, String text,
            long alertTimeMs, int notificationId) {
        NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(activityContext);

        PendingIntent dismissPendingIntent = createPendingIntent(activityContext, ACTION_DISMISS,
                notificationId, title, text, alertTimeMs);
        PendingIntent snoozePendingIntent = createPendingIntent(activityContext, ACTION_SNOOZE,
                notificationId, title, text, alertTimeMs);

        NotificationCompat.Builder builder = new NotificationCompat
                .Builder(activityContext, IMPORTANCE_ALERT_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setShowWhen(true)
                .setWhen(alertTimeMs)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setSmallIcon(R.drawable.ic_warning)
                .setColor(activityContext.getColor(android.R.color.holo_green_light))
                .addAction(new Action.Builder(/* icon= */ null, TEXT_SNOOZE, snoozePendingIntent)
                        .setShowsUserInterface(false).build())
                .addAction(new Action.Builder(/* icon= */ null, TEXT_DISMISS, dismissPendingIntent)
                        .setShowsUserInterface(false).build());

        notificationManager.notify(notificationId, builder.build());
    }

    private static PendingIntent createPendingIntent(Context activityContext, String action,
            int notificationId, String title, String text, long alertTimeMs) {
        Intent intent = new Intent(activityContext, AlertNotificationReceiver.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_KEY_NOTIFICATION_ID, notificationId);
        intent.putExtra(EXTRA_KEY_TITLE, title);
        intent.putExtra(EXTRA_KEY_TEXT, text);
        intent.putExtra(EXTRA_KEY_ALERT_TIME_MS, alertTimeMs);
        return PendingIntent.getBroadcast(activityContext, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }
}
