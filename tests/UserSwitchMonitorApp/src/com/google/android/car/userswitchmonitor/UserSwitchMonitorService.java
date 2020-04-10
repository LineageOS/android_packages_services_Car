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
package com.google.android.car.userswitchmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.car.Car;
import android.car.user.CarUserManager;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Service that users {@link CarUserManager.UserLifecycleEvent UserLifecycleEvents} to monitor
 * user switches.
 *
 */
public final class UserSwitchMonitorService extends Service {

    private static final String TAG = UserSwitchMonitorService.class.getSimpleName();


    private final CarUserManager.UserLifecycleListener mListener = (e) ->
            Log.d(TAG, "onEvent(): " + e);

    private CarUserManager mCarUserManager;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand(): " + intent);

        Context context = getApplicationContext();
        Car car = Car.createCar(context);
        mCarUserManager = (CarUserManager) car.getCarManager(Car.CAR_USER_SERVICE);
        mCarUserManager.addListener((r)-> r.run(), mListener);

        NotificationManager notificationMgr = context.getSystemService(NotificationManager.class);

        String channelId = "4815162342";
        String name = "UserSwitchMonitor";
        NotificationChannel channel = new NotificationChannel(channelId, name,
                NotificationManager.IMPORTANCE_MIN);
        notificationMgr.createNotificationChannel(channel);

        startForeground(startId,
                new Notification.Builder(context, channelId)
                        .setContentText(name)
                        .setContentTitle(name)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .build());

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");

        if (mCarUserManager != null) {
            mCarUserManager.removeListener(mListener);
        } else {
            Log.w(TAG, "Cannot remove listener because manager is null");
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind(): " + intent);
        return null;
    }

}
