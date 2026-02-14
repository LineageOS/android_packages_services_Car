/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.google.android.car.samplerearviewservice;

import android.app.Service;
import android.car.Car;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.feature.Flags;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.car.user.UserLifecycleEventFilter;
import android.content.Intent;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public class SampleRearViewService extends Service {

    private static final String TAG = "SampleRearViewService";
    private Car mCar;
    private CarPropertyManager mCarPropertyManager;
    private CarUserManager mCarUserManager;
    private final AtomicBoolean mInReverse = new AtomicBoolean(false);

    private final CarPropertyEventCallback mGearCallback =
            new CarPropertyEventCallback() {
                @Override
                public void onChangeEvent(CarPropertyValue value) {
                    if (value.getPropertyId() == VehiclePropertyIds.GEAR_SELECTION) {
                        Log.d(TAG, "onChangeEvent gear selection");
                        int currentGear = (Integer) value.getValue();
                        mInReverse.set(currentGear == VehicleGear.GEAR_REVERSE);
                        if (mInReverse.get()) {
                            launchRearviewActivity();
                        } else {
                            stopRearviewActivity();
                        }
                    }
                }

                @Override
                public void onErrorEvent(int propId, int zone) {
                    Log.e(
                            TAG,
                            "GEAR_SELECTION property error: propId=" + propId + ", zone=" + zone);
                }
            };

    private final UserLifecycleListener mUserLifecycleListener =
            event -> {
                if (mInReverse.get()) {
                    Log.i(TAG, "Re-launching rearview activity after user event UNLOCKED");
                    launchRearviewActivity();
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        if (!Flags.camera2SampleRearViewService()) {
            Log.w(TAG, "Feature camera2_sample_rear_view_service is disabled, stopping service.");
            stopSelf();
            return;
        }
        mCar = Car.createCar(this);
        mCarPropertyManager = (CarPropertyManager) mCar.getCarManager(Car.PROPERTY_SERVICE);
        if (mCarPropertyManager != null) {
            try {
                int currentGear = mCarPropertyManager.getIntProperty(
                        VehiclePropertyIds.GEAR_SELECTION, 0);
                mInReverse.set(currentGear == VehicleGear.GEAR_REVERSE);
                if (mInReverse.get()) {
                    launchRearviewActivity();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to get initial gear selection", e);
            }
            mCarPropertyManager.registerCallback(
                    mGearCallback, VehiclePropertyIds.GEAR_SELECTION, /* updateRateHz= */ 0);
        }
        mCarUserManager = mCar.getCarManager(CarUserManager.class);
        if (mCarUserManager != null) {
            UserLifecycleEventFilter filter = new UserLifecycleEventFilter.Builder()
                    .addEventType(CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED)
                    .build();
            mCarUserManager.addListener(getMainExecutor(), filter, mUserLifecycleListener);
        }
    }

    private void launchRearviewActivity() {
        Intent intent = new Intent(this, SampleRearViewActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // disable animations to speed up the time the camera shows up in
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        // To ensure reliable display, the activity should be launched as User 0
        startActivityAsUser(intent, UserHandle.SYSTEM);
    }

    private void stopRearviewActivity() {
        Intent intent = new Intent(SampleRearViewActivity.ACTION_STOP_REARVIEW);
        intent.setPackage(getPackageName());
        sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mCarUserManager != null) {
            mCarUserManager.removeListener(mUserLifecycleListener);
        }
        if (mCarPropertyManager != null) {
            mCarPropertyManager.unregisterCallback(mGearCallback);
        }
        if (mCar != null) {
            mCar.disconnect();
        }
        super.onDestroy();
    }
}
