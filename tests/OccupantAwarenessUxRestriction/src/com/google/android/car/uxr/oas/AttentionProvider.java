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

package com.google.android.car.uxr.oas;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.car.Car;
import android.car.occupantawareness.OccupantAwarenessDetection;
import android.car.occupantawareness.OccupantAwarenessManager;
import android.car.occupantawareness.SystemStatusEvent;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public final class AttentionProvider extends Service {
    private static final String TAG = AttentionProvider.class.getSimpleName();
    private static final String NOTIFICATION_CHANNEL_ID = "adam_notification_channel_0";
    private static final CharSequence NOTIFICATION_CHANNEL_NAME = "adam_channel_name";
    private static final String NOTIFICATION_TITLE = "Android Automotive OS";
    private static final String NOTIFICATION_TEXT = "ADAM Service";
    private static final int NOTIFICATION_IMPORTANCE = NotificationManager.IMPORTANCE_NONE;
    private static final int NOTIFICATION_ID = 101;
    private static boolean sIsServiceRunning = false;

    private OccupantAwarenessManager mOasManager;
    private Car mCar;

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate()");

        Notification notification;
        NotificationChannel channel =
                new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        NOTIFICATION_CHANNEL_NAME, NOTIFICATION_IMPORTANCE);

        NotificationManager notificationManager =
                (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(channel);

        notification =
            new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.adam_icon)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentTitle(NOTIFICATION_TITLE)
                .setContentText(NOTIFICATION_TEXT)
                .build();

        startForeground(NOTIFICATION_ID, notification);


        mCar = Car.createCar(this);
        mOasManager = (OccupantAwarenessManager) mCar.getCarManager(Car.OCCUPANT_AWARENESS_SERVICE);

        if (mOasManager == null) {
            Log.e(TAG, "Occupant Awareness Manager is NULL. Check that occupant awareness "
                       + "feature is enabled");
        } else {
            Log.i(TAG, "Connected to Occupant Awareness Manager");

            int caps =
                    mOasManager.getCapabilityForRole(
                            OccupantAwarenessDetection.VEHICLE_OCCUPANT_DRIVER);
            Log.i(TAG, "Driver capabilities: " + caps);

            Log.i(TAG, "Registering callback");
            mOasManager.registerChangeCallback(new CallbackHandler());

            Log.i(TAG, "Setup complete!");
        }

        UxRestriction.getInstance().enableUxRestriction(this);
        sIsServiceRunning = true;
    }

    public static synchronized Boolean getIsServiceRunning() {
        return sIsServiceRunning;
    }

    String systemToText(int statusCode) {
        switch (statusCode) {
            case SystemStatusEvent.SYSTEM_STATUS_READY:
                return "ready";
            case SystemStatusEvent.SYSTEM_STATUS_NOT_SUPPORTED:
                return "not supported";
            case SystemStatusEvent.SYSTEM_STATUS_NOT_READY:
                return "not ready";
            case SystemStatusEvent.SYSTEM_STATUS_SYSTEM_FAILURE:
                return "failure";
            default:
                return "unknown code";
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't bind to anything.
        return null;
    }

    @Override
    public void onDestroy() {
        sIsServiceRunning = false;
        super.onDestroy();
    }

    private final class CallbackHandler extends OccupantAwarenessManager.ChangeCallback {
        @Override
        public void onDetectionEvent(OccupantAwarenessDetection event) {
            Log.i(TAG, "Got occupant awareness detection: " + event);
            if (event.driverMonitoringDetection != null) {
                UxRestriction.getInstance().setAttentionBufferValue(
                        (int) (event.driverMonitoringDetection.gazeDurationMillis / 60));
            }
        }

        @Override
        public void onSystemStateChanged(SystemStatusEvent systemStatus) {
            Log.i(TAG, "Got occupant awareness system status: " + systemStatus + " ("
                        + systemToText(systemStatus.systemStatus) + ").");
        }
    }
}
