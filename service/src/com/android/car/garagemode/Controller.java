/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.garagemode;

import android.app.job.JobScheduler;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.CompletableFuture;

/**
 * Main controller for GarageMode. It controls all the flows of GarageMode and defines the logic.
 */
public class Controller implements CarPowerStateListener {
    private static final Logger LOG = new Logger("Controller");

    @VisibleForTesting final WakeupPolicy mWakeupPolicy;
    private final GarageMode mGarageMode;
    private final Handler mHandler;
    private final Context mContext;
    private final Car mCar;

    private CarPowerManager mCarPowerManager;

    public Controller(Context context, Looper looper) {
        this(context, looper, null, null, null, null);
    }

    public Controller(
            Context context,
            Looper looper,
            WakeupPolicy wakeupPolicy,
            Handler handler,
            GarageMode garageMode,
            Car car) {
        mContext = context;
        mHandler = (handler == null ? new Handler(looper) : handler);
        mWakeupPolicy =
                (wakeupPolicy == null ? WakeupPolicy.initFromResources(context) : wakeupPolicy);
        mGarageMode = (garageMode == null ? new GarageMode(this) : garageMode);
        if (car == null) {
            mCar = Car.createCar(context, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    try {
                        mCarPowerManager = (CarPowerManager) mCar.getCarManager(Car.POWER_SERVICE);
                        mCarPowerManager.setListener(Controller.this);
                    } catch (CarNotConnectedException e) {
                        LOG.e("Failed to get CarPowerManager instance", e);
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    if (mCarPowerManager != null) {
                        mCarPowerManager.clearListener();
                    }
                }
            });
        } else {
            mCar = car;
        }
    }

    @Override
    public void onStateChanged(int state, CompletableFuture<Void> future) {
        switch (state) {
            case CarPowerStateListener.SHUTDOWN_CANCELLED:
                LOG.d("CPM state changed to SHUTDOWN_CANCELLED");
                handleShutdownCancelled();
                break;
            case CarPowerStateListener.SHUTDOWN_ENTER:
                LOG.d("CPM state changed to SHUTDOWN_ENTER");
                handleShutdownEnter(future);
                break;
            case CarPowerStateListener.SUSPEND_ENTER:
                LOG.d("CPM state changed to SUSPEND_ENTER");
                handleSuspendEnter(future);
                break;
            case CarPowerStateListener.SUSPEND_EXIT:
                LOG.d("CPM state changed to SUSPEND_EXIT");
                handleSuspendExit();
                break;
            default:
        }
    }

    /**
     * @return boolean whether any jobs are currently in running that GarageMode cares about
     */
    boolean isGarageModeActive() {
        return mGarageMode.isGarageModeActive();
    }

    /**
     * Wrapper method to send a broadcast
     *
     * @param i intent that contains broadcast data
     */
    void sendBroadcast(Intent i) {
        LOG.d("Sending broadcast with action: " + i.getAction());
        mContext.sendBroadcast(i);
    }

    /**
     * @return JobSchedulerService instance
     */
    JobScheduler getJobSchedulerService() {
        return (JobScheduler) mContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    /**
     * @return Handler instance used by controller
     */
    Handler getHandler() {
        return mHandler;
    }

    /**
     * Initiates GarageMode flow which will set the system idleness to true and will start
     * monitoring jobs which has idleness constraint enabled.
     */
    void initiateGarageMode(CompletableFuture<Void> future) {
        mWakeupPolicy.incrementCounter();
        mGarageMode.enterGarageMode(future);
    }

    /**
     * Resets GarageMode.
     */
    void resetGarageMode() {
        mGarageMode.cancel();
        mWakeupPolicy.resetCounter();
    }

    @VisibleForTesting
    void finishGarageMode() {
        mGarageMode.finish();
    }

    @VisibleForTesting
    void setCarPowerManager(CarPowerManager cpm) {
        mCarPowerManager = cpm;
    }

    void scheduleNextWakeup() {
        if (mWakeupPolicy.getNextWakeUpInterval() <= 0) {
            // Either there is no policy or nothing left to schedule
            return;
        }
        int seconds = mWakeupPolicy.getNextWakeUpInterval();
        try {
            mCarPowerManager.scheduleNextWakeupTime(seconds);
        } catch (CarNotConnectedException e) {
            LOG.e("Car is not connected.", e);
        }
    }

    private void handleSuspendExit() {
        resetGarageMode();
    }

    private void handleSuspendEnter(CompletableFuture<Void> future) {
        initiateGarageMode(future);
    }

    private void handleShutdownEnter(CompletableFuture<Void> future) {
        initiateGarageMode(future);
    }

    private void handleShutdownCancelled() {
        resetGarageMode();
    }
}