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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

public final class AlertSnoozeReceiver extends BroadcastReceiver {
    private static final String TAG = AlertSnoozeReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!AlertNotificationHelper.ACTION_NOTIFICATION.equals(action)) {
            Log.e(TAG, "Undefined action " + action);
            return;
        }
        int notificationId = intent.getIntExtra(AlertNotificationHelper.EXTRA_KEY_NOTIFICATION_ID,
                AlertNotificationHelper.INVALID_NOTIFICATION_ID);
        if (notificationId == AlertNotificationHelper.INVALID_NOTIFICATION_ID) {
            Log.e(TAG,  "Invalid notification id");
            return;
        }
        Log.i(TAG, action + " for alert notification " + notificationId);

        NotificationManagerCompat notificationManagerCompat =
                NotificationManagerCompat.from(context);
        String title = intent.getStringExtra(AlertNotificationHelper.EXTRA_KEY_TITLE);
        String text = intent.getStringExtra(AlertNotificationHelper.EXTRA_KEY_TEXT);
        notificationManagerCompat.cancel(notificationId);
        AlertNotificationHelper.createRadioAlertNotification(context, title, text, notificationId);
    }
}
