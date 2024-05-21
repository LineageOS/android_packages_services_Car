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

package com.google.android.car.kitchensink;

import android.car.Car;
import android.car.CarOccupantZoneManager;
import android.car.CarProjectionManager;
import android.car.hardware.CarSensorManager;
import android.car.hardware.hvac.CarHvacManager;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.property.CarPropertyManager;
import android.car.os.CarPerformanceManager;
import android.car.telemetry.CarTelemetryManager;
import android.car.watchdog.CarWatchdogManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.GuardedBy;

import java.util.ArrayList;
import java.util.List;

public class KitchenSinkHelperImpl implements KitchenSinkHelper {
    private static final String TAG = "KitchenSinkHelperImpl";

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private Car mCarApi;
    @GuardedBy("mLock")
    private CarHvacManager mHvacManager;
    @GuardedBy("mLock")
    private CarOccupantZoneManager mOccupantZoneManager;
    @GuardedBy("mLock")
    private CarPowerManager mPowerManager;
    @GuardedBy("mLock")
    private CarPropertyManager mPropertyManager;
    @GuardedBy("mLock")
    private CarSensorManager mSensorManager;
    @GuardedBy("mLock")
    private CarProjectionManager mCarProjectionManager;
    @GuardedBy("mLock")
    private CarTelemetryManager mCarTelemetryManager;
    @GuardedBy("mLock")
    private CarWatchdogManager mCarWatchdogManager;
    @GuardedBy("mLock")
    private CarPerformanceManager mCarPerformanceManager;
    @GuardedBy("mLock")
    private boolean mCarReady = false;

    private record HandlerRunnable(Handler h, Runnable r) {}

    // A list of callbacks that would be posted to handlers every time car service is connected
    // or reconnected.
    @GuardedBy("mLock")
    private final List<HandlerRunnable> mCarReadyCallbacks = new ArrayList<>();

    @Override
    public Car getCar() {
        synchronized (mLock) {
            return mCarApi;
        }
    }

    @Override
    public void requestRefreshManager(final Runnable r, final Handler h) {
        synchronized (mLock) {
            mCarReadyCallbacks.add(new HandlerRunnable(h, r));

            if (mCarReady) {
                // If car service is already ready, we already missed the initial callback so
                // we invoke the runnable now.
                h.post(r);
            }
        }
    }

    @Override
    public CarPropertyManager getPropertyManager() {
        synchronized (mLock) {
            if (mPropertyManager == null) {
                mPropertyManager = (CarPropertyManager) mCarApi.getCarManager(
                        android.car.Car.PROPERTY_SERVICE);
            }
            return mPropertyManager;
        }
    }

    @Override
    public CarHvacManager getHvacManager() {
        synchronized (mLock) {
            if (mHvacManager == null) {
                mHvacManager = (CarHvacManager) mCarApi.getCarManager(android.car.Car.HVAC_SERVICE);
            }
            return mHvacManager;
        }
    }

    @Override
    public CarOccupantZoneManager getOccupantZoneManager() {
        synchronized (mLock) {
            if (mOccupantZoneManager == null) {
                mOccupantZoneManager = (CarOccupantZoneManager) mCarApi.getCarManager(
                        android.car.Car.CAR_OCCUPANT_ZONE_SERVICE);
            }
            return mOccupantZoneManager;
        }
    }

    @Override
    public CarPowerManager getPowerManager() {
        synchronized (mLock) {
            if (mPowerManager == null) {
                mPowerManager = (CarPowerManager) mCarApi.getCarManager(
                        android.car.Car.POWER_SERVICE);
            }
            return mPowerManager;
        }
    }

    @Override
    public CarSensorManager getSensorManager() {
        synchronized (mLock) {
            if (mSensorManager == null) {
                mSensorManager = (CarSensorManager) mCarApi.getCarManager(
                        android.car.Car.SENSOR_SERVICE);
            }
            return mSensorManager;
        }
    }

    @Override
    public CarProjectionManager getProjectionManager() {
        synchronized (mLock) {
            if (mCarProjectionManager == null) {
                mCarProjectionManager =
                        (CarProjectionManager) mCarApi.getCarManager(Car.PROJECTION_SERVICE);
            }
            return mCarProjectionManager;
        }
    }

    @Override
    public CarTelemetryManager getCarTelemetryManager() {
        synchronized (mLock) {
            if (mCarTelemetryManager == null) {
                mCarTelemetryManager =
                        (CarTelemetryManager) mCarApi.getCarManager(Car.CAR_TELEMETRY_SERVICE);
            }
            return mCarTelemetryManager;
        }
    }

    @Override
    public CarWatchdogManager getCarWatchdogManager() {
        synchronized (mLock) {
            if (mCarWatchdogManager == null) {
                mCarWatchdogManager =
                        (CarWatchdogManager) mCarApi.getCarManager(Car.CAR_WATCHDOG_SERVICE);
            }
            return mCarWatchdogManager;
        }
    }

    @Override
    public CarPerformanceManager getPerformanceManager() {
        synchronized (mLock) {
            if (mCarPerformanceManager == null) {
                mCarPerformanceManager =
                        (CarPerformanceManager) mCarApi.getCarManager(Car.CAR_PERFORMANCE_SERVICE);
            }
            return mCarPerformanceManager;
        }
    }

    /**
     * Initialized Car API if in automotive. Must be called during onCreate.
     */
    public void initCarApiIfAutomotive(Context context) {
        // Connection to Car Service does not work for non-automotive yet.
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            initCarApi(context);
        }
    }

    /**
     * Disconnects from car service. Must be called during onDestroy.
     */
    public void disconnect() {
        synchronized (mLock) {
            if (mCarApi != null) {
                mCarApi.disconnect();
                mCarApi = null;
            }
        }
    }

    private void initCarApi(Context context) {
        synchronized (mLock) {
            if (mCarApi != null && mCarApi.isConnected()) {
                mCarApi.disconnect();
                mCarApi = null;
            }
        }
        Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (Car car, boolean ready) -> {
                    synchronized (mLock) {
                        mCarApi = car;
                        mCarReady = ready;
                        if (!mCarReady) {
                            return;
                        }

                        Log.d(TAG, "Car service connected");

                        mHvacManager = null;
                        mOccupantZoneManager = null;
                        mPowerManager = null;
                        mPropertyManager = null;
                        mSensorManager = null;
                        mCarProjectionManager = null;
                        mCarTelemetryManager = null;
                        mCarWatchdogManager = null;
                        mCarPerformanceManager = null;

                        for (int i = 0; i < mCarReadyCallbacks.size(); i++) {
                            var handlerRunnable = mCarReadyCallbacks.get(i);
                            handlerRunnable.h.post(handlerRunnable.r);
                        }
                    }
                });
    }
}
