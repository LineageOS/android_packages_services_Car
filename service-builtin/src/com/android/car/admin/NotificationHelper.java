/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.admin;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.car.ICarResultReceiver;
import android.car.builtin.util.Slog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;

import com.android.car.R;
import com.android.car.admin.ui.ManagedDeviceTextView;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

// TODO(b/196947649): move this class to CarSettings or at least to some common package (not
// car-admin-ui-lib)
/**
 * Helper for notification-related tasks
 */
public final class NotificationHelper {
    // TODO: Move these constants to a common place. Right now a copy of these is present in
    // CarSettings' FactoryResetActivity.
    public static final String EXTRA_FACTORY_RESET_CALLBACK = "factory_reset_callback";
    public static final int FACTORY_RESET_NOTIFICATION_ID = 42;
    public static final int NEW_USER_DISCLAIMER_NOTIFICATION_ID = 108;

    @VisibleForTesting
    public static final String CHANNEL_ID_DEFAULT = "channel_id_default";
    @VisibleForTesting
    public static final String CHANNEL_ID_HIGH = "channel_id_high";
    private static final boolean DEBUG = false;
    @VisibleForTesting
    static final String TAG = NotificationHelper.class.getSimpleName();

    /**
     * Creates a notification (and its notification channel) for the given importance type, setting
     * its name to be {@code Android System}.
     *
     * @param context context for showing the notification
     * @param importance notification importance. Currently only
     * {@link NotificationManager.IMPORTANCE_HIGH} is supported.
     */
    @NonNull
    public static Notification.Builder newNotificationBuilder(Context context,
            @NotificationManager.Importance int importance) {
        Objects.requireNonNull(context, "context cannot be null");

        String channelId, importanceName;
        switch (importance) {
            case NotificationManager.IMPORTANCE_DEFAULT:
                channelId = CHANNEL_ID_DEFAULT;
                importanceName = context.getString(R.string.importance_default);
                break;
            case NotificationManager.IMPORTANCE_HIGH:
                channelId = CHANNEL_ID_HIGH;
                importanceName = context.getString(R.string.importance_high);
                break;
            default:
                throw new IllegalArgumentException("Unsupported importance: " + importance);
        }
        NotificationManager notificationMgr = context.getSystemService(NotificationManager.class);
        notificationMgr.createNotificationChannel(
                new NotificationChannel(channelId, importanceName, importance));

        Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME,
                context.getString(com.android.internal.R.string.android_system_label));

        return new Notification.Builder(context, channelId).addExtras(extras);
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE,
            details = "private constructor")
    private NotificationHelper() {
        throw new UnsupportedOperationException("Contains only static methods");
    }

    /**
     * Shows the user disclaimer notification.
     */
    public static void showUserDisclaimerNotification(int userId, Context context) {
        // TODO(b/175057848) persist status so it's shown again if car service crashes?
        PendingIntent pendingIntent = getPendingUserDisclaimerIntent(context, /* extraFlags= */ 0,
                userId);

        Notification notification = NotificationHelper
                .newNotificationBuilder(context, NotificationManager.IMPORTANCE_DEFAULT)
                // TODO(b/177552737): Use a better icon?
                .setSmallIcon(R.drawable.car_ic_mode)
                .setContentTitle(context.getString(R.string.new_user_managed_notification_title))
                .setContentText(ManagedDeviceTextView.getManagedDeviceText(context))
                .setCategory(Notification.CATEGORY_CAR_INFORMATION)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        if (DEBUG) {
            Slog.d(TAG, "Showing new managed notification (id "
                    + NEW_USER_DISCLAIMER_NOTIFICATION_ID + " on user " + context.getUser());
        }
        context.getSystemService(NotificationManager.class)
                .notifyAsUser(TAG, NEW_USER_DISCLAIMER_NOTIFICATION_ID,
                        notification, UserHandle.of(userId));
    }

    /**
     * Cancels the user disclaimer notification.
     */
    public static void cancelUserDisclaimerNotification(int userId, Context context) {
        if (DEBUG) {
            Slog.d(TAG, "Canceling notification " + NEW_USER_DISCLAIMER_NOTIFICATION_ID
                    + " for user " + context.getUser());
        }
        context.getSystemService(NotificationManager.class)
                .cancelAsUser(TAG, NEW_USER_DISCLAIMER_NOTIFICATION_ID, UserHandle.of(userId));
        getPendingUserDisclaimerIntent(context, PendingIntent.FLAG_UPDATE_CURRENT, userId).cancel();
    }

    /**
     * Creates and returns the PendingIntent for User Disclaimer notification.
     */
    @VisibleForTesting
    public static PendingIntent getPendingUserDisclaimerIntent(Context context, int extraFlags,
            int userId) {
        return PendingIntent
                .getActivityAsUser(context, NEW_USER_DISCLAIMER_NOTIFICATION_ID,
                new Intent().setComponent(ComponentName.unflattenFromString(
                        context.getString(R.string.config_newUserDisclaimerActivity)
                )),
                PendingIntent.FLAG_IMMUTABLE | extraFlags, null, UserHandle.of(userId));
    }

    /**
     * Sends the notification warning the user about the factory reset.
     */
    public static void showFactoryResetNotification(Context context, ICarResultReceiver callback) {
        // The factory request is received by CarService - which runs on system user - but the
        // notification will be sent to all users.
        UserHandle currentUser = UserHandle.of(ActivityManager.getCurrentUser());

        ComponentName factoryResetActivity = ComponentName.unflattenFromString(
                context.getString(R.string.config_factoryResetActivity));
        @SuppressWarnings("deprecation")
        Intent intent = new Intent()
                .setComponent(factoryResetActivity)
                .putExtra(EXTRA_FACTORY_RESET_CALLBACK, callback.asBinder());
        PendingIntent pendingIntent = PendingIntent.getActivityAsUser(context,
                FACTORY_RESET_NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE,
                /* options= */ null, currentUser);

        Notification notification = NotificationHelper
                .newNotificationBuilder(context, NotificationManager.IMPORTANCE_HIGH)
                .setSmallIcon(R.drawable.car_ic_warning)
                .setColor(context.getColor(R.color.red_warning))
                .setContentTitle(context.getString(R.string.factory_reset_notification_title))
                .setContentText(context.getString(R.string.factory_reset_notification_text))
                .setCategory(Notification.CATEGORY_CAR_WARNING)
                .setOngoing(true)
                .addAction(/* icon= */ 0,
                        context.getString(R.string.factory_reset_notification_button),
                        pendingIntent)
                .build();

        Slog.i(TAG, "Showing factory reset notification on all users");
        context.getSystemService(NotificationManager.class)
                .notifyAsUser(TAG, FACTORY_RESET_NOTIFICATION_ID, notification, UserHandle.ALL);
    }
}
