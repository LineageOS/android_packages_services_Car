/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.remoteaccess;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyValue;
import android.car.remoteaccess.ICarRemoteAccessCallback;
import android.car.remoteaccess.ICarRemoteAccessService;
import android.content.Context;
import android.os.SystemClock;

import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.CarPropertyService;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.R;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.remoteaccess.hal.RemoteAccessHalCallback;
import com.android.car.remoteaccess.hal.RemoteAccessHalWrapper;
import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Service to implement CarRemoteAccessManager API.
 */
public final class CarRemoteAccessService extends ICarRemoteAccessService.Stub
        implements CarServiceBase {

    private static final String TAG = CarLog.tagFor(CarRemoteAccessService.class);
    private static final int MILLI_TO_SECOND = 1000;
    private static final String TASK_PREFIX = "task";
    private static final String CLIENT_PREFIX = "client";
    private static final int RANDOM_STRING_LENGTH = 12;
    private static final int MIN_SYSTEM_UPTIME_FOR_REMOTE_ACCESS_IN_SEC = 30;

    private final Object mLock = new Object();
    private final Context mContext;
    private final AtomicLong mTaskCount = new AtomicLong(/* initialValule= */ 0);
    private final AtomicLong mClientCount = new AtomicLong(/* initialValule= */ 0);

    private final RemoteAccessHalCallback mHalCallback = new RemoteAccessHalCallback() {
        @Override
        public void onRemoteTaskRequested(String clientId, byte[] data) {
            // TODO(b/134519794): Implement the logic.
        }
    };

    private final RemoteAccessHalWrapper mRemoteAccessHal =
            new RemoteAccessHalWrapper(mHalCallback);
    private final boolean mBootUpForRemoteAccess;
    private final long mShutdownTimeInMs;

    @GuardedBy("mLock")
    private int mNextPowerState;
    @GuardedBy("mLock")
    private boolean mRunGarageMode;

    public CarRemoteAccessService(Context context) {
        mContext = context;
        mBootUpForRemoteAccess = isBootUpForRemoteAccess();
        mShutdownTimeInMs = SystemClock.uptimeMillis() + getAllowedSystemUpTimeForRemoteTaskInMs();
    }

    @Override
    public void init() {
        // TODO(b/255335607): Implement the following logics:
        // - Schedule a shutdown handler in the main thread.
        // - Read the previous power state and set mNextPowerState;

        mRemoteAccessHal.init();
    }

    @Override
    public void release() {
        mRemoteAccessHal.release();
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        synchronized (mLock) {
            writer.println("*Car Remote Access Service*");
            writer.printf("mBootUpForRemoteAccess: %b\n", mBootUpForRemoteAccess);
            writer.printf("mShutdownTimeInMs: %d\n", mShutdownTimeInMs);
            writer.printf("mNextPowerState: %d\n", mNextPowerState);
            writer.printf("mRunGarageMode: %b\n", mRunGarageMode);
            // TODO(b/134519794): Dump all other member variables.
        }
    }

    /**
     * Registers {@code ICarRemoteAccessCallback}.
     *
     * @param callback {@code ICarRemoteAccessCallback} that listens to remote access events.
     * @throws IllegalStateException When {@code callback} is already registered.
     */
    @Override
    public void addCarRemoteTaskClient(ICarRemoteAccessCallback callback) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_USE_REMOTE_ACCESS);
        // TODO(b/256535375): Add the callback to the data strcuture and issue a client ID.
    }

    @Override
    public void removeCarRemoteTaskClient(ICarRemoteAccessCallback callback) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_USE_REMOTE_ACCESS);
        // TODO(b/134519794): Implement the logic.
    }

    @Override
    public void reportRemoteTaskDone(String clientId, String taskId) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_USE_REMOTE_ACCESS);
        // TODO(b/134519794): Implement the logic.
    }

    @Override
    public void setPowerStatePostTaskExecution(int nextPowerState, boolean runGarageMode) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_CONTROL_REMOTE_ACCESS);

        synchronized (mLock) {
            mNextPowerState = nextPowerState;
            mRunGarageMode = runGarageMode;
        }
    }

    @Override
    public void confirmReadyForShutdown(String clientId) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_USE_REMOTE_ACCESS);
        // TODO(b/134519794): Implement the logic.
    }

    private long getAllowedSystemUpTimeForRemoteTaskInMs() {
        long timeout = mContext.getResources().getInteger(
                R.integer.config_allowedSystemUpTimeForRemoteAccess);
        if (timeout < MIN_SYSTEM_UPTIME_FOR_REMOTE_ACCESS_IN_SEC) {
            timeout = MIN_SYSTEM_UPTIME_FOR_REMOTE_ACCESS_IN_SEC;
            Slogf.w(TAG, "config_allowedSystemUpTimeForRemoteAccess(%d) should be no less than %d",
                    timeout, MIN_SYSTEM_UPTIME_FOR_REMOTE_ACCESS_IN_SEC);
        }
        return timeout * MILLI_TO_SECOND;
    }

    private String generateNewTaskId() {
        return TASK_PREFIX + "_" + mTaskCount.incrementAndGet() + "_"
                + CarServiceUtils.generateRandomAlphaNumericString(RANDOM_STRING_LENGTH);
    }

    private String generateNewClientId() {
        return CLIENT_PREFIX + "_" + mClientCount.incrementAndGet() + "_"
                + CarServiceUtils.generateRandomAlphaNumericString(RANDOM_STRING_LENGTH);
    }

    private static boolean isBootUpForRemoteAccess() {
        CarPropertyService carPropertyService = CarLocalServices.getService(
                CarPropertyService.class);
        if (carPropertyService == null) {
            // TODO(b/256282662): Replace this with VehicleApPowerBootupReason.USER_POWER_ON once
            // ag/17077685 is merged.
            return false;
        }

        CarPropertyValue propValue = carPropertyService.getPropertySafe(
                VehiclePropertyIds.AP_POWER_BOOTUP_REASON, /* areaId= */ 0);
        if (propValue == null) {
            // TODO(b/256282662): Replace this with VehicleApPowerBootupReason.USER_POWER_ON once
            // ag/17077685 is merged.
            return false;
        }
        // TODO(b/256282662): Replace this with VehicleApPowerBootupReason.SYSTEM_REMOTE_ACCESS once
        // ag/17077685 is merged.
        return (int) propValue.getValue() == 2;
    }
}
