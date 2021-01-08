/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.ui;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;

import com.android.car.R;

/**
 * Helper for notification-related tasks
 */
final class NotificationHelper {

    static final int FACTORY_RESET_NOTIFICATION_ID = 42;
    static final String IMPORTANCE_HIGH_ID = "importance_high";

    /**
     * Creates a notification (and its notification channel) for the given importance type, setting
     * its name to be {@code Android System}.
     *
     * @param context context for showing the notification
     * @param importance notification importance. Currently only
     * {@link NotificationManager.IMPORTANCE_HIGH} is supported.
     */
    @NonNull
    static Notification.Builder newNotificationBuilder(Context context,
            @NotificationManager.Importance int importance) {
        String importanceId, importanceName;
        switch (importance) {
            case NotificationManager.IMPORTANCE_HIGH:
                importanceId = IMPORTANCE_HIGH_ID;
                importanceName = context.getString(R.string.importance_high);
                break;
            default:
                throw new IllegalArgumentException("Unsupported importance: " + importance);
        }
        NotificationManager notificationMgr = context.getSystemService(NotificationManager.class);
        notificationMgr.createNotificationChannel(
                new NotificationChannel(importanceId, importanceName, importance));

        Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME,
                context.getString(com.android.internal.R.string.android_system_label));

        return new Notification.Builder(context, importanceId).addExtras(extras);
    }

    private NotificationHelper() {
        throw new UnsupportedOperationException("Contains only static methods");
    }
}
