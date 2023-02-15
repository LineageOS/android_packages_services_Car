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

import static android.content.Context.BIND_AUTO_CREATE;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyValue;
import android.car.remoteaccess.CarRemoteAccessManager;
import android.car.remoteaccess.ICarRemoteAccessCallback;
import android.car.remoteaccess.ICarRemoteAccessService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.hardware.automotive.vehicle.VehicleApPowerBootupReason;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
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
import com.android.car.systeminterface.SystemInterface;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    private static final Duration PACKAGE_SEARCH_DELAY = Duration.ofSeconds(3);
    // Remote task client can use up to 30 seconds to initialize and upload necessary info to the
    // server.
    private static final long ALLOWED_TIME_FOR_REMOTE_TASK_CLIENT_INIT_MS = 30_000;

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
    @GuardedBy("mLock")
    private final ArrayList<String> mRemoteTaskClientServices = new ArrayList<>();

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

    public CarRemoteAccessService(Context context, SystemInterface systemInterface) {
        this(context, systemInterface, /* remoteAccessHal= */ null);
    }

    @VisibleForTesting
    public CarRemoteAccessService(Context context, SystemInterface systemInterface,
            @Nullable RemoteAccessHalWrapper remoteAccessHal) {
        mContext = context;
        mPackageManager = mContext.getPackageManager();
        mRemoteAccessHal = remoteAccessHal != null ? remoteAccessHal
                : new RemoteAccessHalWrapper(mHalCallback);
        mBootUpForRemoteAccess = isBootUpForRemoteAccess();
        mShutdownTimeInMs = SystemClock.uptimeMillis() + getAllowedSystemUpTimeForRemoteTaskInMs();
        // TODO(b/263807920): CarService restart should be handled.
        systemInterface.scheduleActionForBootCompleted(() -> searchForRemoteTaskClientPackages(),
                PACKAGE_SEARCH_DELAY);
    }

    @Override
    public void init() {
        // TODO(b/255335607): Schedule a shutdown handler in the main thread and read client IDs,
        // creation time, and package mapping from the DB

        mRemoteAccessHal.init();
        mWakeupServiceName = mRemoteAccessHal.getWakeupServiceName();
        mDeviceId = mRemoteAccessHal.getDeviceId();
        synchronized (mLock) {
            mNextPowerState = mBootUpForRemoteAccess ? getLastShutdownState()
                    : CarRemoteAccessManager.NEXT_POWER_STATE_ON;
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
            writer.println("mRemoteTaskClientServices:");
            writer.increaseIndent();
            for (int i = 0; i < mRemoteTaskClientServices.size(); i++) {
                writer.printf("%d: %s\n", i, mRemoteTaskClientServices.get(i));
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
            token.setCallback(null);
        }
        // TODO(b/261337288): Shutdown if there are pending remote tasks and no client is
        // registered.
    }

    /**
     * Reports that the task of {@code taskId} is completed.
     *
     * @param clientId ID of a client that has completed the task.
     * @param taskId ID of a task that has been completed.
     * @throws IllegalArgumentException When {@code clientId} is not valid, or {@code taskId} is not
     *         valid.
     */
    @Override
    public void reportRemoteTaskDone(String clientId, String taskId) {
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_USE_REMOTE_ACCESS);
        Preconditions.checkArgument(clientId != null, "clientId cannot be null");
        Preconditions.checkArgument(taskId != null, "taskId cannot be null");
        int callingUid = Binder.getCallingUid();
        String packageName = mPackageManager.getNameForUid(callingUid);
        synchronized (mLock) {
            ClientToken token = mClientTokenByPackage.get(packageName);
            if (token == null || token.getCallback() == null) {
                throw new IllegalArgumentException("Callback has not been registered");
            }
            // TODO(b/252698817): Update the validity checking logic.
            if (!clientId.equals(token.getClientId())) {
                throw new IllegalArgumentException("Client ID(" + clientId + ") doesn't match the "
                        + "registered one(" + token.getClientId() + ")");
            }
            ArraySet<String> tasks = mActiveTasksByPackage.get(packageName);
            if (tasks == null || !tasks.contains(taskId)) {
                throw new IllegalArgumentException("Task ID(" + taskId + ") is not valid");
            }
            tasks.remove(taskId);
            if (tasks.isEmpty()) {
                mActiveTasksByPackage.remove(packageName);
            }
        }
        shutdownIfNeeded(/* force= */ false);
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

    private void shutdownIfNeeded(boolean force) {
        int nextPowerState;
        boolean runGarageMode;
        synchronized (mLock) {
            if (mNextPowerState == CarRemoteAccessManager.NEXT_POWER_STATE_ON) {
                if (DEBUG) {
                    Slogf.d(TAG, "Will not shutdown. The next power state is ON.");
                }
                return;
            }
            int taskCount = getActiveTaskCountLocked();
            if (!force && taskCount > 0) {
                if (DEBUG) {
                    Slogf.d(TAG, "Will not shutdown. The activen task count is %d.", taskCount);
                }
                return;
            }
            nextPowerState = mNextPowerState;
            runGarageMode = mRunGarageMode;
        }
        // Send SHUTDOWN_REQUEST to VHAL.
        if (DEBUG) {
            Slogf.d(TAG, "Requesting shutdown of AP: nextPowerState = %d, runGarageMode = %b",
                    nextPowerState, runGarageMode);
        }
        CarPowerManagementService cpms = CarLocalServices.getService(
                CarPowerManagementService.class);
        try {
            cpms.requestShutdownAp(nextPowerState, runGarageMode);
        } catch (Exception e) {
            Slogf.e(TAG, e, "Cannot shutdown to %s", nextPowerStateToString(nextPowerState));
        }
    }

    @GuardedBy("mLock")
    private int getActiveTaskCountLocked() {
        int count = 0;
        for (int i = 0; i < mActiveTasksByPackage.size(); i++) {
            count += mActiveTasksByPackage.valueAt(i).size();
        }
        return count;
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

    private void searchForRemoteTaskClientPackages() {
        List<String> servicesToStart = new ArrayList<>();
        List<ResolveInfo> services = mPackageManager.queryIntentServices(
                new Intent(Car.CAR_REMOTEACCESS_REMOTE_TASK_CLIENT_SERVICE),
                PackageManager.ResolveInfoFlags.of(/* value= */ 0));
        synchronized (mLock) {
            for (int i = 0; i < services.size(); i++) {
                ServiceInfo info = services.get(i).serviceInfo;
                String component = String.join("/", info.packageName, info.name);
                if (mPackageManager.checkPermission(Car.PERMISSION_USE_REMOTE_ACCESS,
                        info.packageName) != PackageManager.PERMISSION_GRANTED) {
                    Slogf.w(TAG, "Package(%s) has %s intent but doesn't have %s permission",
                            component, Car.CAR_REMOTEACCESS_REMOTE_TASK_CLIENT_SERVICE,
                            Car.PERMISSION_USE_REMOTE_ACCESS);
                    continue;
                }
                // TODO(b/263798644): mRemoteTaskClientServices should be updated when packages are
                // added, removed, updated.
                mRemoteTaskClientServices.add(component);
                servicesToStart.add(component);
            }
        }
        startRemoteTaskClientServices(servicesToStart);
    }

    private void startRemoteTaskClientServices(List<String> services) {
        for (int i = 0; i < services.size(); i++) {
            String serviceName = services.get(i);
            RemoteTaskClientServiceConnection serviceConnection =
                    new RemoteTaskClientServiceConnection(mContext, serviceName);
            serviceConnection.bindService();
            Slogf.i(TAG, "Service(%s) is bound to give a time to register as a remote task client",
                    serviceName);
            mHandler.postDelayed(() -> {
                serviceConnection.unbindService();
                Slogf.i(TAG, "Service(%s) is unbound from CarRemoteAccessService", serviceName);
            }, ALLOWED_TIME_FOR_REMOTE_TASK_CLIENT_INIT_MS);
        }
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

    private static String nextPowerStateToString(int nextPowerState) {
        switch (nextPowerState) {
            case CarRemoteAccessManager.NEXT_POWER_STATE_ON:
                return "ON";
            case CarRemoteAccessManager.NEXT_POWER_STATE_OFF:
                return "OFF";
            case CarRemoteAccessManager.NEXT_POWER_STATE_SUSPEND_TO_RAM:
                return "Suspend-to-RAM";
            case CarRemoteAccessManager.NEXT_POWER_STATE_SUSPEND_TO_DISK:
                return "Suspend-to-disk";
            default:
                return "Unknown(" + nextPowerState + ")";
        }
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

    private static final class RemoteTaskClientServiceConnection implements ServiceConnection {

        private static final String TAG = RemoteTaskClientServiceConnection.class.getSimpleName();

        private final Object mServiceLock = new Object();
        private final Context mContext;
        private final Intent mIntent;

        @GuardedBy("mServiceLock")
        private boolean mBound;

        private RemoteTaskClientServiceConnection(Context context, String serviceName) {
            mContext = context;
            mIntent = new Intent();
            mIntent.setComponent(ComponentName.unflattenFromString(serviceName));
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mServiceLock) {
                mBound = true;
            }
            Slogf.i(TAG, "Service(%s) is bound", name.flattenToShortString());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mServiceLock) {
                mBound = false;
            }
            Slogf.i(TAG, "Service(%s) is unbound", name.flattenToShortString());
        }

        @Override
        public void onBindingDied(ComponentName name) {
            synchronized (mServiceLock) {
                mBound = false;
            }
            Slogf.w(TAG, "Service(%s) died", name.flattenToShortString());
        }

        public void bindService() {
            synchronized (mServiceLock) {
                if (mBound) {
                    Slogf.w(TAG, "%s is already bound", mIntent);
                    return;
                }
            }
            // TODO(b/266129982): Start a service for the user under which the task needs to be
            // executed.
            UserHandle currentUser = UserHandle.of(ActivityManager.getCurrentUser());
            boolean status = mContext.bindServiceAsUser(mIntent, /* conn= */ this, BIND_AUTO_CREATE,
                    currentUser);
            if (!status) {
                Slogf.w(TAG, "Failed to bind service %s as user %s", mIntent, currentUser);
                mContext.unbindService(/* conn= */ this);
            }
        }

        public void unbindService() {
            synchronized (mServiceLock) {
                if (!mBound) {
                    Slogf.w(TAG, "%s is not bound", mIntent);
                    return;
                }
            }
            mContext.unbindService(/* conn= */ this);
        }
    }
}
