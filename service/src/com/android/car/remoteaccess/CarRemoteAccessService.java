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

import android.annotation.Nullable;
import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyValue;
import android.car.remoteaccess.CarRemoteAccessManager;
import android.car.remoteaccess.ICarRemoteAccessCallback;
import android.car.remoteaccess.ICarRemoteAccessService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.automotive.vehicle.VehicleApPowerBootupReason;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.CarPropertyService;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.R;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.power.CarPowerManagementService;
import com.android.car.remoteaccess.hal.RemoteAccessHalCallback;
import com.android.car.remoteaccess.hal.RemoteAccessHalWrapper;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Service to implement CarRemoteAccessManager API.
 */
public final class CarRemoteAccessService extends ICarRemoteAccessService.Stub
        implements CarServiceBase {

    private static final String TAG = CarLog.tagFor(CarRemoteAccessService.class);
    private static final boolean DEBUG = Slogf.isLoggable(TAG, Log.DEBUG);
    private static final int MILLI_TO_SECOND = 1000;
    private static final String TASK_PREFIX = "task";
    private static final String CLIENT_PREFIX = "client";
    private static final int RANDOM_STRING_LENGTH = 12;
    private static final int MIN_SYSTEM_UPTIME_FOR_REMOTE_ACCESS_IN_SEC = 30;
    // Client ID remains valid for 30 days since issued.
    private static final long CLIENT_ID_EXPIRATION_IN_MILLIS = 30L * 24L * 60L * 60L * 1000L;

    private final Object mLock = new Object();
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final Handler mHandler = new Handler(CarServiceUtils.getHandlerThread(TAG).getLooper());
    private final AtomicLong mTaskCount = new AtomicLong(/* initialValule= */ 0);
    private final AtomicLong mClientCount = new AtomicLong(/* initialValule= */ 0);
    @GuardedBy("mLock")
    private final ArrayMap<String, ClientToken> mClientTokenByPackage = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, String> mPackageByClientId = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, ArraySet<String>> mActiveTasksByPackage = new ArrayMap<>();

    private final RemoteAccessHalCallback mHalCallback = new RemoteAccessHalCallback() {
        @Override
        public void onRemoteTaskRequested(String clientId, byte[] data) {
            String taskId;
            ICarRemoteAccessCallback callback;

            if (DEBUG) {
                Slogf.d(TAG, "Remote task is requested through the HAL to client(%s)", clientId);
            }
            long currentTimeInMs = SystemClock.uptimeMillis();
            int taskMaxDurationInSec =
                    (int) (mShutdownTimeInMs - currentTimeInMs) / MILLI_TO_SECOND;
            synchronized (mLock) {
                if (mNextPowerState == CarRemoteAccessManager.NEXT_POWER_STATE_ON) {
                    taskMaxDurationInSec =
                            (int) (getAllowedSystemUpTimeForRemoteTaskInMs() / MILLI_TO_SECOND);
                }
                if (taskMaxDurationInSec <= 0) {
                    Slogf.w(TAG, "System shutdown was supposed to start, but still on: expected"
                            + " shutdown time=%d, current time=%d", mShutdownTimeInMs,
                            currentTimeInMs);
                    return;
                }
                // TODO(b/255337132): Need to delay task notification because the remote task client
                // hasn't been able to register quickly when the system is cold booted.
                String packageName = mPackageByClientId.get(clientId);
                if (packageName == null) {
                    Slogf.w(TAG, "Cannot notify task: client(%s) is not registered.", clientId);
                    return;
                }
                ClientToken token = mClientTokenByPackage.get(packageName);
                if (token == null || token.getCallback() == null) {
                    Slogf.w(TAG, "Cannot notify task: client(%s) is not registered.", clientId);
                    return;
                }
                if (!token.isClientIdValid()) {
                    Slogf.w(TAG, "Cannot notify task: clientID has expired: token = %s", token);
                    return;
                }
                callback = token.getCallback();
                taskId = generateNewTaskId();
                ArraySet<String> tasks = mActiveTasksByPackage.get(packageName);
                if (tasks == null) {
                    tasks = new ArraySet<>();
                    mActiveTasksByPackage.put(packageName, tasks);
                }
                tasks.add(taskId);
                if (DEBUG) {
                    Slogf.d(TAG, "New task(%s) is added to the package(%s)", taskId, packageName);
                }
            }
            try {
                callback.onRemoteTaskRequested(clientId, taskId, data, taskMaxDurationInSec);
            } catch (RemoteException e) {
                Slogf.e(TAG, e, "Calling onRemoteTaskRequested() failed: clientId = %s, "
                        + "taskId = %s, data size = %d, taskMaxDurationInSec = %d", clientId,
                        taskId, data != null ? data.length : 0, taskMaxDurationInSec);
            }
        }
    };
    private final RemoteAccessHalWrapper mRemoteAccessHal;
    private final boolean mBootUpForRemoteAccess;
    private final long mShutdownTimeInMs;

    private String mWakeupServiceName;
    private String mDeviceId;
    @GuardedBy("mLock")
    private int mNextPowerState;
    @GuardedBy("mLock")
    private boolean mRunGarageMode;

    public CarRemoteAccessService(Context context) {
        this(context, /* remoteAccessHal= */ null);
    }

    @VisibleForTesting
    public CarRemoteAccessService(Context context,
            @Nullable RemoteAccessHalWrapper remoteAccessHal) {
        mContext = context;
        mPackageManager = mContext.getPackageManager();
        mRemoteAccessHal = remoteAccessHal != null ? remoteAccessHal
                : new RemoteAccessHalWrapper(mHalCallback);
        mBootUpForRemoteAccess = isBootUpForRemoteAccess();
        mShutdownTimeInMs = SystemClock.uptimeMillis() + getAllowedSystemUpTimeForRemoteTaskInMs();
    }

    @Override
    public void init() {
        // TODO(b/255335607): Schedule a shutdown handler in the main thread and read client IDs,
        // creation time, and package mapping from the DB

        mRemoteAccessHal.init();
        mWakeupServiceName = mRemoteAccessHal.getWakeupServiceName();
        mDeviceId = mRemoteAccessHal.getDeviceId();
        synchronized (mLock) {
            mNextPowerState = getLastShutdownState();
        }
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
            writer.printf("mWakeupServiceName: %s\n", mWakeupServiceName);
            writer.printf("mDeviceId: %s\n", mDeviceId);
            writer.println("mClientTokenByPackage:");
            writer.increaseIndent();
            for (int i = 0; i < mClientTokenByPackage.size(); i++) {
                writer.printf("%s ==> %s\n", mClientTokenByPackage.keyAt(i),
                        mClientTokenByPackage.valueAt(i));
            }
            writer.decreaseIndent();
            writer.println("mActiveTasksByPackage:");
            writer.increaseIndent();
            for (int i = 0; i < mActiveTasksByPackage.size(); i++) {
                writer.printf("%s ==> %s\n", mActiveTasksByPackage.keyAt(i),
                        mActiveTasksByPackage.valueAt(i));
            }
            writer.decreaseIndent();
        }
    }

    /**
     * Registers {@code ICarRemoteAccessCallback}.
     *
     * <p>When a callback is registered for a package, calling {@code addCarRemoteTaskClient} will
     * replace the registered callback with the new one.
     *
     * @param callback {@code ICarRemoteAccessCallback} that listens to remote access events.
     * @throws IllegalArgumentException When {@code callback} is {@code null}.
     */
    @Override
    public void addCarRemoteTaskClient(ICarRemoteAccessCallback callback) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_USE_REMOTE_ACCESS);
        Preconditions.checkArgument(callback != null, "callback cannot be null");
        int callingUid = Binder.getCallingUid();
        String packageName = mPackageManager.getNameForUid(callingUid);
        ClientToken token;
        synchronized (mLock) {
            token = mClientTokenByPackage.get(packageName);
            if (token != null) {
                ICarRemoteAccessCallback oldCallback = token.getCallback();
                if (oldCallback != null) {
                    oldCallback.asBinder().unlinkToDeath(token, /* flags= */ 0);
                }
            } else {
                // Creates a new client ID with a null callback.
                token = new ClientToken(generateNewClientId(), System.currentTimeMillis());
                mClientTokenByPackage.put(packageName, token);
                mPackageByClientId.put(token.getClientId(), packageName);
            }
            token.setCallback(callback);
            try {
                callback.asBinder().linkToDeath(token, /* flags= */ 0);
            } catch (RemoteException e) {
                token.setCallback(null);
                mPackageByClientId.remove(token.getClientId());
                mClientTokenByPackage.remove(packageName);
                throw new IllegalStateException("Failed to linkToDeath callback");
            }
        }
        postRegistrationUpdated(callback, token.getClientId());
    }

    /**
     * Unregisters {@code ICarRemoteAccessCallback}.
     *
     * @param callback {@code ICarRemoteAccessCallback} that listens to remote access events.
     * @throws IllegalArgumentException When {@code callback} is {@code null}.
     */
    @Override
    public void removeCarRemoteTaskClient(ICarRemoteAccessCallback callback) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_USE_REMOTE_ACCESS);
        Preconditions.checkArgument(callback != null, "callback cannot be null");
        int callingUid = Binder.getCallingUid();
        String packageName = mPackageManager.getNameForUid(callingUid);
        synchronized (mLock) {
            ClientToken token = mClientTokenByPackage.get(packageName);
            if (token == null || token.getCallback() != callback) {
                Slogf.w(TAG, "Cannot remove callback. Callback has not been registered for %s",
                        packageName);
                return;
            }
            callback.asBinder().unlinkToDeath(token, /* flags= */ 0);
            mPackageByClientId.remove(token.getClientId());
            mClientTokenByPackage.remove(packageName);
        }
        // TODO(b/261337288): Shutdown if there are pending remote tasks and no client is
        // registered.
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

    @VisibleForTesting
    RemoteAccessHalCallback getRemoteAccessHalCallback() {
        return mHalCallback;
    }

    private void postRegistrationUpdated(ICarRemoteAccessCallback callback, String clientId) {
        mHandler.post(() -> {
            try {
                if (DEBUG) {
                    Slogf.d(TAG, "Calling onClientRegistrationUpdated: serviceName=%s, deviceId=%s,"
                            + " clientId=%s", mWakeupServiceName, mDeviceId, clientId);
                }
                callback.onClientRegistrationUpdated(mWakeupServiceName, mDeviceId, clientId);
            } catch (RemoteException e) {
                Slogf.e(TAG, e, "Calling onClientRegistrationUpdated() failed: clientId = %s",
                        clientId);
            }
        });
    }

    private int getLastShutdownState() {
        CarPowerManagementService cpms = CarLocalServices
                .getService(CarPowerManagementService.class);
        if (cpms == null) {
            Slogf.w(TAG, "CarPowerManagementService is not available. Returning "
                    + "NEXT_POWER_STATE_OFF");
            return CarRemoteAccessManager.NEXT_POWER_STATE_OFF;
        }
        return cpms.getLastShutdownState();
    }

    private long getAllowedSystemUpTimeForRemoteTaskInMs() {
        long timeout = mContext.getResources()
                .getInteger(R.integer.config_allowedSystemUpTimeForRemoteAccess);
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
        CarPropertyService carPropertyService = CarLocalServices
                .getService(CarPropertyService.class);
        if (carPropertyService == null) {
            Slogf.w(TAG, "Checking isBootUpForRemoteAccess: CarPropertyService is not available");
            return false;
        }

        CarPropertyValue propValue = carPropertyService.getPropertySafe(
                VehiclePropertyIds.AP_POWER_BOOTUP_REASON, /* areaId= */ 0);
        if (propValue == null) {
            Slogf.w(TAG, "Cannot get property of AP_POWER_BOOTUP_REASON");
            return false;
        }
        return propValue.getStatus() == CarPropertyValue.STATUS_AVAILABLE
                && (int) propValue.getValue() == VehicleApPowerBootupReason.SYSTEM_REMOTE_ACCESS;
    }

    private static final class ClientToken implements IBinder.DeathRecipient {

        private final Object mTokenLock = new Object();
        private final String mClientId;
        private final long mIdCreationTimeInMs;

        @GuardedBy("mTokenLock")
        private ICarRemoteAccessCallback mCallback;

        private ClientToken(String clientId, long idCreationTimeInMs) {
            mClientId = clientId;
            mIdCreationTimeInMs = idCreationTimeInMs;
        }

        public String getClientId() {
            return mClientId;
        }

        public long getIdCreationTime() {
            return mIdCreationTimeInMs;
        }

        public ICarRemoteAccessCallback getCallback() {
            synchronized (mTokenLock) {
                return mCallback;
            }
        }

        public boolean isClientIdValid() {
            long now = System.currentTimeMillis();
            return mClientId != null
                    && (now - mIdCreationTimeInMs) < CLIENT_ID_EXPIRATION_IN_MILLIS;
        }

        public void setCallback(ICarRemoteAccessCallback callback) {
            synchronized (mTokenLock) {
                mCallback = callback;
            }
        }

        @Override
        public void binderDied() {
            synchronized (mTokenLock) {
                mCallback = null;
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            synchronized (mTokenLock) {
                sb.append("ClientToken[");
                sb.append("mClientId=").append(mClientId);
                sb.append(", mIdCreationTimeInMs=").append(mIdCreationTimeInMs);
                sb.append(", hasCallback=").append(mCallback != null);
                sb.append(']');
            }
            return sb.toString();
        }
    }
}
