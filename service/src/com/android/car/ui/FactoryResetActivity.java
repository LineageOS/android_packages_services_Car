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

import static com.android.car.ui.NotificationHelper.FACTORY_RESET_NOTIFICATION_ID;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Slog;
import android.widget.Button;

import com.android.car.R;
import com.android.internal.os.IResultReceiver;

// TODO(b/171603586): add unit test

/**
 * Activity shown when a factory request is imminent, it gives the user the option to reset now or
 * wait until the device is rebooted / resumed from suspend.
 */
public final class FactoryResetActivity extends Activity {

    private static final String TAG = FactoryResetActivity.class.getSimpleName();

    public static final String EXTRA_CALLBACK = "factory_reset_callback";

    private Button mNowButton;
    private Button mLaterButton;
    private IResultReceiver mCallback;

    /**
     * Sends the notification warning the user about the factory reset.
     */
    public static void sendNotification(Context context, IResultReceiver callback) {
        // The factory request is received by CarService - which runs on system user - but the
        // notification must be sent to the current user.
        UserHandle currentUser = UserHandle.of(ActivityManager.getCurrentUser());

        @SuppressWarnings("deprecation")
        Intent intent = new Intent(context, FactoryResetActivity.class)
                .putExtra(EXTRA_CALLBACK, callback.asBinder());
        PendingIntent pendingIntent = PendingIntent.getActivityAsUser(context,
                FACTORY_RESET_NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE,
                /* options= */ null, currentUser);

        Notification notification = NotificationHelper
                .newNotificationBuilder(context, NotificationManager.IMPORTANCE_HIGH)
                // TODO(b/171603586): proper icon?
                .setSmallIcon(R.drawable.car_ic_mode)
                .setContentTitle(context.getString(R.string.factory_reset_notification_title))
                .setContentText(context.getString(R.string.factory_reset_notification_text))
                .setCategory(Notification.CATEGORY_CAR_WARNING)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        Slog.i(TAG, "Showing factory reset notification on user " + currentUser);
        context.getSystemService(NotificationManager.class)
                .notifyAsUser(TAG, FACTORY_RESET_NOTIFICATION_ID, notification, currentUser);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        Object binder = null;

        try {
            binder = intent.getExtra(EXTRA_CALLBACK);
            mCallback = IResultReceiver.Stub.asInterface((IBinder) binder);
        } catch (Exception e) {
            Slog.w(TAG, "error getting IResultReveiver from " + EXTRA_CALLBACK + " extra ("
                    + binder + ") on " + intent, e);
        }

        if (mCallback == null) {
            Slog.wtf(TAG, "no IResultReceiver / " + EXTRA_CALLBACK + " extra  on " + intent);
            finish();
            return;
        }

        setContentView(R.layout.factory_reset);

        mNowButton = findViewById(R.id.factory_reset_now_button);
        mNowButton.setOnClickListener((v) -> factoryResetNow());

        mLaterButton = findViewById(R.id.factory_reset_later_button);
        mLaterButton.setOnClickListener((v) -> factoryResetLater());
    }

    private void factoryResetNow() {
        Slog.i(TAG, "Factory reset acknowledged; finishing it");

        try {
            mCallback.send(/* resultCode= */ 0, /* resultData= */ null);
            Slog.i(TAG, "Hasta la vista, baby");

            // Cancel pending intent and notification
            getSystemService(NotificationManager.class).cancel(FACTORY_RESET_NOTIFICATION_ID);
            PendingIntent.getActivity(this, FACTORY_RESET_NOTIFICATION_ID, getIntent(),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT).cancel();
        } catch (Exception e) {
            Slog.e(TAG, "error factory resetting or cancelling notification / intent", e);
            return;
        }

        finish();
    }

    private void factoryResetLater() {
        Slog.i(TAG, "Delaying factory reset.");
        finish();
    }
}
