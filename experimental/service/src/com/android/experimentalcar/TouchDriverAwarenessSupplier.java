/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.experimentalcar;

import android.car.experimental.DriverAwarenessEvent;
import android.car.experimental.DriverAwarenessSupplierConfig;
import android.car.experimental.DriverAwarenessSupplierService;
import android.car.experimental.IDriverAwarenessSupplier;
import android.car.experimental.IDriverAwarenessSupplierCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


/**
 * A driver awareness supplier that estimates the driver's current awareness level based on touches
 * on the headunit.
 */
// TODO(b/136663803) update with actual implementation and configuration
public class TouchDriverAwarenessSupplier extends IDriverAwarenessSupplier.Stub {

    private static final String TAG = "Car.TouchDriverAwarenessSupplier";
    private static final float INITIAL_DRIVER_AWARENESS_VALUE = 1.0f;
    private static final long MAX_STALENESS = DriverAwarenessSupplierService.NO_STALENESS;

    // demo classes - emit a random value every 1s as a proof of concept
    private final Random mRandom = new Random();
    private final ScheduledExecutorService mDemoScheduler =
            Executors.newScheduledThreadPool(1);

    private ScheduledFuture<?> mDemoScheduleHandle;
    private IDriverAwarenessSupplierCallback mDriverAwarenessSupplierCallback;

    private final Runnable mEmitDemoAwarenessRunnable = () -> {
        long timestamp = SystemClock.elapsedRealtime();
        float demoAwareness = mRandom.nextFloat();
        try {
            mDriverAwarenessSupplierCallback.onDriverAwarenessUpdated(
                    new DriverAwarenessEvent(timestamp, demoAwareness));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to emit awareness event", e);
        }
    };

    @Override
    public void onReady() {
        try {
            mDriverAwarenessSupplierCallback.onConfigLoaded(
                    new DriverAwarenessSupplierConfig(MAX_STALENESS));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send config - abandoning ready process", e);
            return;
        }
        startSupplyingDemoDriverAwareness();
    }

    @Override
    public void setCallback(IDriverAwarenessSupplierCallback callback) {
        mDriverAwarenessSupplierCallback = callback;
    }

    private void startSupplyingDemoDriverAwareness() {
        // send an initial event, as required by the IDriverAwarenessSupplierCallback spec
        try {
            mDriverAwarenessSupplierCallback.onDriverAwarenessUpdated(
                    new DriverAwarenessEvent(SystemClock.elapsedRealtime(),
                            INITIAL_DRIVER_AWARENESS_VALUE));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to emit initial awareness event", e);
        }

        // TODO(b/136663803) update with actual implementation and configuration
        mDemoScheduleHandle = mDemoScheduler.scheduleAtFixedRate(
                mEmitDemoAwarenessRunnable,
                /* initialDelay= */ 0,
                /* period= */ 1,
                TimeUnit.SECONDS);
    }
}
