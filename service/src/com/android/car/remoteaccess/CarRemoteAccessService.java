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
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.ICarPowerStateListener;
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
import android.hardware.automotive.vehicle.VehicleProperty;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
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
import com.android.car.remoteaccess.RemoteAccessStorage.ClientIdEntry;
import com.android.car.remoteaccess.hal.RemoteAccessHalCallback;
import com.android.car.remoteaccess.hal.RemoteAccessHalWrapper;
import com.android.car.systeminterface.SystemInterface;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final Duration PACKAGE_SEARCH_DELAY = Duration.ofSeconds(1);
    // Remote task client can use up to 30 seconds to initialize and upload necessary info to the
    // server.
    private static final long ALLOWED_TIME_FOR_REMOTE_TASK_CLIENT_INIT_MS = 30_000;
    private static final long SHUTDOWN_WARNING_MARGIN_IN_MS = 5000;
    private static final long INVALID_ALLOWED_SYSTEM_UPTIME = -1;

    private final Object mLock = new Object();
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final HandlerThread mHandlerThread =
            CarServiceUtils.getHandlerThread(getClass().getSimpleName());
    private final RemoteTaskClientServiceHandler mHandler =
            new RemoteTaskClientServiceHandler(mHandlerThread.getLooper(), this);
    private final AtomicLong mTaskCount = new AtomicLong(/* initialValule= */ 0);
    private final AtomicLong mClientCount = new AtomicLong(/* initialValule= */ 0);
    @GuardedBy("mLock")
    private final ArrayMap<String, String> mPackageByClientId = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, ArrayList<RemoteTask>> mTasksToBeNotifiedByClientId =
            new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, ClientToken> mClientTokenByPackage = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, ArraySet<String>> mActiveTasksByPackage = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, RemoteTaskClientServiceInfo> mClientServiceInfoByPackage =
            new ArrayMap<>();
    private final RemoteAccessStorage mRemoteAccessStorage;

    private final ICarPowerStateListener mCarPowerStateListener =
            new ICarPowerStateListener.Stub() {
        @Override
        public void onStateChanged(int state, long expirationTimeMs) {
            // isReadyForRemoteTask and isWakeupRequired are only valid when apStateChangeRequired
            // is true.
            boolean apStateChangeRequired = false;
            boolean isReadyForRemoteTask = false;
            boolean isWakeupRequired = false;
            boolean needsComplete = false;

            switch (state) {
                case CarPowerManager.STATE_SHUTDOWN_PREPARE:
                    apStateChangeRequired = true;
                    isReadyForRemoteTask = false;
                    // TODO(b/268810241): Restore isWakeupRequired to false.
                    isWakeupRequired = true;

                    needsComplete = true;
                    break;
                case CarPowerManager.STATE_WAIT_FOR_VHAL:
                case CarPowerManager.STATE_SUSPEND_EXIT:
                case CarPowerManager.STATE_HIBERNATION_EXIT:
                    apStateChangeRequired = true;
                    isReadyForRemoteTask = true;
                    isWakeupRequired = false;
                    break;
                case CarPowerManager.STATE_POST_SHUTDOWN_ENTER:
                case CarPowerManager.STATE_POST_SUSPEND_ENTER:
                case CarPowerManager.STATE_POST_HIBERNATION_ENTER:
                    apStateChangeRequired = true;
                    isReadyForRemoteTask = false;
                    isWakeupRequired = true;

                    needsComplete = true;
                    break;
            }
            if (apStateChangeRequired && !mRemoteAccessHal.notifyApStateChange(
                    isReadyForRemoteTask, isWakeupRequired)) {
                Slogf.e(TAG, "Cannot notify AP state change according to power state(%d)",
                        state);
            }
            if (needsComplete) {
                mPowerService.finished(state, this);
            }
        }
    };

    private final RemoteAccessHalCallback mHalCallback = new RemoteAccessHalCallback() {
        @Override
        public void onRemoteTaskRequested(String clientId, byte[] data) {
            String taskId;
            ICarRemoteAccessCallback callback;
            List<RemoteTask> remoteTasksToNotify;

            if (DEBUG) {
                Slogf.d(TAG, "Remote task is requested through the HAL to client(%s)", clientId);
            }
            int taskMaxDurationInSec;
            synchronized (mLock) {
                taskMaxDurationInSec = calcTaskMaxDurationLocked();
                if (taskMaxDurationInSec <= 0) {
                    Slogf.w(TAG, "onRemoteTaskRequested: system shutdown was supposed to start, "
                            + "but still on: expected shutdown time=%d, current time=%d",
                            mShutdownTimeInMs, SystemClock.uptimeMillis());
                    return;
                }
                taskId = generateNewTaskId();
                pushTaskToPendingQueueLocked(clientId, new RemoteTask(taskId, data));
                // TODO(b/271954440): Set a timeout for a task in the pending queue.
                String packageName = mPackageByClientId.get(clientId);
                if (packageName == null) {
                    Slogf.w(TAG, "Cannot notify task: client(%s) is not registered.", clientId);
                    return;
                }
                ClientToken token = mClientTokenByPackage.get(packageName);
                if (token != null && !token.isClientIdValid()) {
                    // TODO(b/266371728): Handle client ID expiration.
                    Slogf.w(TAG, "Cannot notify task: clientID has expired: token = %s", token);
                    return;
                }
                taskId = generateNewTaskId();
                pushTaskToPendingQueueLocked(clientId, new RemoteTask(taskId, data));
                if (DEBUG) {
                    Slogf.d(TAG, "New task(%s) is added to the package(%s)", taskId, packageName);
                }
                RemoteTaskClientServiceInfo serviceInfo =
                        mClientServiceInfoByPackage.get(packageName);
                if (serviceInfo == null || serviceInfo.getServiceConnection() == null) {
                    Slogf.w(TAG, "Notifying task is delayed: client(%s) is not started yet.",
                            clientId);
                    if (serviceInfo != null) {
                        startRemoteTaskClientService(serviceInfo, /* scheduleUnbind= */ false);
                    }
                    return;
                }
                if (token == null || token.getCallback() == null) {
                    Slogf.w(TAG, "Notifying task is delayed: client(%s) is not registered",
                            clientId);
                    return;
                }
                remoteTasksToNotify = popTasksFromPendingQueueLocked(clientId);
                callback = token.getCallback();
                addTasksToActiveTasksLocked(packageName, remoteTasksToNotify);
            }
            invokeTaskRequestCallbacks(callback, clientId, remoteTasksToNotify,
                    taskMaxDurationInSec);
        }
    };
    private final RemoteAccessHalWrapper mRemoteAccessHal;
    private final long mShutdownTimeInMs;
    private final long mAllowedSystemUptimeMs;

    private String mWakeupServiceName = "";
    private String mDeviceId = "";
    @GuardedBy("mLock")
    private int mNextPowerState;
    @GuardedBy("mLock")
    private boolean mRunGarageMode;
    private CarPowerManagementService mPowerService;
    private AtomicBoolean mInitialized;

    public CarRemoteAccessService(Context context, SystemInterface systemInterface) {
        this(context, systemInterface, /* remoteAccessHal= */ null,
                new RemoteAccessStorage(context, systemInterface),
                INVALID_ALLOWED_SYSTEM_UPTIME);
    }

    @VisibleForTesting
    public CarRemoteAccessService(Context context, SystemInterface systemInterface,
            @Nullable RemoteAccessHalWrapper remoteAccessHal,
            @Nullable RemoteAccessStorage remoteAccessStorage, long allowedSystemUptimeMs) {
        mContext = context;
        mPackageManager = mContext.getPackageManager();
        mPowerService = CarLocalServices.getService(CarPowerManagementService.class);
        mRemoteAccessHal = remoteAccessHal != null ? remoteAccessHal
                : new RemoteAccessHalWrapper(mHalCallback);
        mAllowedSystemUptimeMs = allowedSystemUptimeMs == INVALID_ALLOWED_SYSTEM_UPTIME
                ? getAllowedSystemUptimeForRemoteTaskInMs() : allowedSystemUptimeMs;
        mShutdownTimeInMs = SystemClock.uptimeMillis() + mAllowedSystemUptimeMs;
        mRemoteAccessStorage = remoteAccessStorage;
        // TODO(b/263807920): CarService restart should be handled.
        systemInterface.scheduleActionForBootCompleted(() -> searchForRemoteTaskClientPackages(),
                PACKAGE_SEARCH_DELAY);
    }

    @Override
    public void init() {
        populatePackageClientIdMapping();
        mRemoteAccessHal.init();
        try {
            mWakeupServiceName = mRemoteAccessHal.getWakeupServiceName();
            mDeviceId = mRemoteAccessHal.getDeviceId();
        } catch (IllegalStateException e) {
            Slogf.e(TAG, e, "Cannot get device/service information from remote access HAL");
        }
        synchronized (mLock) {
            mNextPowerState = getLastShutdownState();
        }

        mPowerService.registerListenerWithCompletion(mCarPowerStateListener);

        long delayForShutdowWarningMs = mAllowedSystemUptimeMs - SHUTDOWN_WARNING_MARGIN_IN_MS;
        if (delayForShutdowWarningMs > 0) {
            mHandler.postNotifyShutdownStarting(delayForShutdowWarningMs);
        }
        mHandler.postWrapUpRemoteAccessService(mAllowedSystemUptimeMs);

        if (!mRemoteAccessHal.notifyApStateChange(/* isReadyForRemoteTask= */ true,
                /* isWakeupRequired= */ false)) {
            Slogf.e(TAG, "Cannot notify AP state change at init()");
        }
    }

    @Override
    public void release() {
        mHandler.cancelAll();
        mRemoteAccessHal.release();
        mRemoteAccessStorage.release();
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        synchronized (mLock) {
            writer.println("*Car Remote Access Service*");
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
            writer.println("mClientServiceInfoByPackage:");
            writer.increaseIndent();
            for (int i = 0; i < mClientServiceInfoByPackage.size(); i++) {
                writer.printf("%d: %s ==> %s\n", i, mClientServiceInfoByPackage.keyAt(i),
                        mClientServiceInfoByPackage.valueAt(i));
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
        saveClientIdInDb(token, packageName);
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
            removeTaskFromActiveTasksLocked(packageName, taskId);
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

    private void populatePackageClientIdMapping() {
        List<ClientIdEntry> clientIdEntries = mRemoteAccessStorage.getClientIdEntries();
        if (clientIdEntries == null) return;

        synchronized (mLock) {
            for (int i = 0; i < clientIdEntries.size(); i++) {
                ClientIdEntry entry = clientIdEntries.get(i);
                mPackageByClientId.put(entry.clientId, entry.packageName);
                mClientTokenByPackage.put(entry.packageName,
                        new ClientToken(entry.clientId, entry.idCreationTime));
            }
        }
    }

    private void saveClientIdInDb(ClientToken token, String packageName) {
        ClientIdEntry entry = new ClientIdEntry(token.getClientId(), token.getIdCreationTime(),
                packageName);
        if (!mRemoteAccessStorage.updateClientId(entry)) {
            Slogf.e(TAG, "Failed to save %s for %s in the database", token, packageName);
        }
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

            // Just after a registration callback is invoked, let's call onRemoteTaskRequested
            // callback if there are pending tasks.
            int taskMaxDurationInSec;
            List<RemoteTask> tasks;
            synchronized (mLock) {
                taskMaxDurationInSec = calcTaskMaxDurationLocked();
                if (taskMaxDurationInSec <= 0) {
                    Slogf.w(TAG, "postRegistration: system shutdown was supposed to start, but "
                            + "still on: expected shutdown time=%d, current time=%d",
                            mShutdownTimeInMs, SystemClock.uptimeMillis());
                    return;
                }
                tasks = popTasksFromPendingQueueLocked(clientId);
                if (tasks == null) {
                    Slogf.w(TAG, "No pending tasks to deliver to client(%s)", clientId);
                    return;
                }
                // packageName cannot be null because clientId is verified.
                String packageName = mPackageByClientId.get(clientId);
                addTasksToActiveTasksLocked(packageName, tasks);
            }
            invokeTaskRequestCallbacks(callback, clientId, tasks, taskMaxDurationInSec);
        });
    }

    private void shutdownIfNeeded(boolean force) {
        int nextPowerState;
        boolean runGarageMode;
        synchronized (mLock) {
            if (mNextPowerState == CarRemoteAccessManager.NEXT_POWER_STATE_ON) {
                Slogf.i(TAG, "Will not shutdown. The next power state is ON.");
                return;
            }
            if (isVehicleInUse()) {
                Slogf.i(TAG, "Will not shutdown. The vehicle is in use.");
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
            unbindAllServicesLocked();
        }
        // Send SHUTDOWN_REQUEST to VHAL.
        if (DEBUG) {
            Slogf.d(TAG, "Requesting shutdown of AP: nextPowerState = %d, runGarageMode = %b",
                    nextPowerState, runGarageMode);
        }
        try {
            mPowerService.requestShutdownAp(nextPowerState, runGarageMode);
        } catch (Exception e) {
            Slogf.e(TAG, e, "Cannot shutdown to %s", nextPowerStateToString(nextPowerState));
        }
    }

    @GuardedBy("mLock")
    private void unbindAllServicesLocked() {
        for (int i = 0; i < mClientServiceInfoByPackage.size(); i++) {
            RemoteTaskClientServiceInfo serviceInfo = mClientServiceInfoByPackage.valueAt(i);
            unbindRemoteTaskClientService(serviceInfo);
        }
    }

    @GuardedBy("mLock")
    private int calcTaskMaxDurationLocked() {
        long currentTimeInMs = SystemClock.uptimeMillis();
        int taskMaxDurationInSec = (int) (mShutdownTimeInMs - currentTimeInMs) / MILLI_TO_SECOND;
        if (mNextPowerState == CarRemoteAccessManager.NEXT_POWER_STATE_ON) {
            taskMaxDurationInSec = (int) (mAllowedSystemUptimeMs / MILLI_TO_SECOND);
        }
        return taskMaxDurationInSec;
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
        return mPowerService.getLastShutdownState();
    }

    private long getAllowedSystemUptimeForRemoteTaskInMs() {
        long timeout = mContext.getResources()
                .getInteger(R.integer.config_allowedSystemUptimeForRemoteAccess);
        if (timeout < MIN_SYSTEM_UPTIME_FOR_REMOTE_ACCESS_IN_SEC) {
            timeout = MIN_SYSTEM_UPTIME_FOR_REMOTE_ACCESS_IN_SEC;
            Slogf.w(TAG, "config_allowedSystemUptimeForRemoteAccess(%d) should be no less than %d",
                    timeout, MIN_SYSTEM_UPTIME_FOR_REMOTE_ACCESS_IN_SEC);
        }
        return timeout * MILLI_TO_SECOND;
    }

    private void searchForRemoteTaskClientPackages() {
        List<RemoteTaskClientServiceInfo> servicesToStart = new ArrayList<>();
        List<ResolveInfo> services = mPackageManager.queryIntentServices(
                new Intent(Car.CAR_REMOTEACCESS_REMOTE_TASK_CLIENT_SERVICE),
                PackageManager.ResolveInfoFlags.of(/* value= */ 0));
        synchronized (mLock) {
            for (int i = 0; i < services.size(); i++) {
                ServiceInfo info = services.get(i).serviceInfo;
                ComponentName componentName = new ComponentName(info.packageName, info.name);
                if (mPackageManager.checkPermission(Car.PERMISSION_USE_REMOTE_ACCESS,
                        info.packageName) != PackageManager.PERMISSION_GRANTED) {
                    Slogf.w(TAG, "Component(%s) has %s intent but doesn't have %s permission",
                            componentName.flattenToString(),
                            Car.CAR_REMOTEACCESS_REMOTE_TASK_CLIENT_SERVICE,
                            Car.PERMISSION_USE_REMOTE_ACCESS);
                    continue;
                }
                // TODO(b/263798644): mClientServiceInfoByPackage should be updated when packages
                // are added, removed, updated.
                RemoteTaskClientServiceInfo serviceInfo =
                        new RemoteTaskClientServiceInfo(componentName);
                mClientServiceInfoByPackage.put(info.packageName, serviceInfo);
                servicesToStart.add(serviceInfo);
            }
        }
        startRemoteTaskClientServices(servicesToStart);
    }

    private void startRemoteTaskClientServices(List<RemoteTaskClientServiceInfo> services) {
        for (int i = 0; i < services.size(); i++) {
            startRemoteTaskClientService(services.get(i), /* scheduleUnbind= */ true);
        }
    }

    private void startRemoteTaskClientService(RemoteTaskClientServiceInfo serviceInfo,
            boolean scheduleUnbind) {
        ComponentName serviceName = serviceInfo.getServiceComponentName();
        RemoteTaskClientServiceConnection serviceConnection =
                new RemoteTaskClientServiceConnection(mContext, serviceName);
        serviceConnection.bindService();
        serviceInfo.setServiceConnection(serviceConnection);
        Slogf.i(TAG, "Service(%s) is bound to give a time to register as a remote task client",
                serviceName.flattenToString());
        if (scheduleUnbind) {
            mHandler.postUnbindServiceAfterRegistration(serviceInfo,
                    ALLOWED_TIME_FOR_REMOTE_TASK_CLIENT_INIT_MS);
        }
    }

    private void unbindRemoteTaskClientService(RemoteTaskClientServiceInfo serviceInfo) {
        RemoteTaskClientServiceConnection connection = serviceInfo.getServiceConnection();
        if (connection == null) {
            Slogf.w(TAG, "Cannot unbind remote task client service: no service connection");
            return;
        }
        connection.unbindService();
        ComponentName serviceName = serviceInfo.getServiceComponentName();
        Slogf.i(TAG, "Service(%s) is unbound from CarRemoteAccessService", serviceName);
    }

    private void notifyShutdownStarting() {
        List<ICarRemoteAccessCallback> callbacks = new ArrayList<>();
        synchronized (mLock) {
            if (mNextPowerState == CarRemoteAccessManager.NEXT_POWER_STATE_ON) {
                if (DEBUG) {
                    Slogf.d(TAG, "Skipping notifyShutdownStarting because the next power state is "
                            + "ON");
                }
                return;
            }
            for (int i = 0; i < mClientTokenByPackage.size(); i++) {
                ClientToken token = mClientTokenByPackage.valueAt(i);
                if (token == null || token.getCallback() == null || !token.isClientIdValid()) {
                    Slogf.w(TAG, "Notifying client(%s) of shutdownStarting is skipped: invalid "
                            + "client token", token);
                    continue;
                }
                callbacks.add(token.getCallback());
            }
        }
        for (int i = 0; i < callbacks.size(); i++) {
            ICarRemoteAccessCallback callback = callbacks.get(i);
            try {
                callback.onShutdownStarting();
            } catch (RemoteException e) {
                Slogf.e(TAG, "Calling onShutdownStarting() failed: package", e);
            }
        }
    }

    private void wrapUpRemoteAccessServiceIfNeeded() {
        Slogf.i(TAG, "Remote task execution time has expired: wrapping up the service");
        shutdownIfNeeded(/* force= */ true);
    }

    @Nullable
    RemoteTaskClientServiceInfo getRemoteTaskClientServiceInfo(String clientId) {
        synchronized (mLock) {
            String packageName = mPackageByClientId.get(clientId);
            if (packageName == null) {
                Slogf.w(TAG, "Cannot get package name for client ID(%s)", clientId);
                return null;
            }
            return mClientServiceInfoByPackage.get(packageName);
        }
    }

    private void invokeTaskRequestCallbacks(ICarRemoteAccessCallback callback, String clientId,
            List<RemoteTask> tasks, int taskMaxDurationInSec) {
        // Cancel the service unbinding request. Remote task client service will be unbound when all
        // tasks are completed or timeout for task execution goes off.
        RemoteTaskClientServiceInfo serviceInfo = getRemoteTaskClientServiceInfo(clientId);
        if (serviceInfo != null) {
            mHandler.cancelUnbindServiceAfterRegistration(serviceInfo);
        }

        for (int i = 0; i < tasks.size(); i++) {
            RemoteTask task = tasks.get(i);
            try {
                callback.onRemoteTaskRequested(clientId, task.id, task.data, taskMaxDurationInSec);
            } catch (RemoteException e) {
                Slogf.e(TAG, e, "Calling onRemoteTaskRequested() failed: clientId = %s, "
                        + "taskId = %s, data size = %d, taskMaxDurationInSec = %d", clientId,
                        task.id, task.data != null ? task.data.length : 0, taskMaxDurationInSec);
                synchronized (mLock) {
                    removeTaskFromActiveTasksOfClientLocked(clientId, task.id);
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void removeTaskFromActiveTasksLocked(String packageName, String taskId) {
        ArraySet<String> tasks = mActiveTasksByPackage.get(packageName);
        if (tasks == null || !tasks.contains(taskId)) {
            throw new IllegalArgumentException("Task ID(" + taskId + ") is not valid");
        }
        tasks.remove(taskId);
        if (tasks.isEmpty()) {
            mActiveTasksByPackage.remove(packageName);
        }
    }

    @GuardedBy("mLock")
    private void removeTaskFromActiveTasksOfClientLocked(String clientId, String taskId) {
        String packageName = mPackageByClientId.get(clientId);
        if (packageName != null) {
            removeTaskFromActiveTasksLocked(packageName, taskId);
        } else {
            Slogf.w(TAG, "Cannot remove task(%s) from the active tasks: package for "
                    + "clientId(%s) is not available", taskId, clientId);
        }
    }

    @GuardedBy("mLock")
    private void addTasksToActiveTasksLocked(String packageName, List<RemoteTask> tasks) {
        ArraySet<String> activeTasks = mActiveTasksByPackage.get(packageName);
        if (activeTasks == null) {
            activeTasks = new ArraySet<>();
            mActiveTasksByPackage.put(packageName, activeTasks);
        }
        for (int i = 0; i < tasks.size(); i++) {
            RemoteTask task = tasks.get(i);
            activeTasks.add(task.id);
        }
    }

    @GuardedBy("mLock")
    private void pushTaskToPendingQueueLocked(String clientId, RemoteTask task) {
        ArrayList remoteTasks = mTasksToBeNotifiedByClientId.get(clientId);
        if (remoteTasks == null) {
            remoteTasks = new ArrayList<RemoteTask>();
            mTasksToBeNotifiedByClientId.put(clientId, remoteTasks);
        }
        remoteTasks.add(task);
    }

    @GuardedBy("mLock")
    @Nullable
    private List<RemoteTask> popTasksFromPendingQueueLocked(String clientId) {
        return mTasksToBeNotifiedByClientId.remove(clientId);
    }

    private String generateNewTaskId() {
        return TASK_PREFIX + "_" + mTaskCount.incrementAndGet() + "_"
                + CarServiceUtils.generateRandomAlphaNumericString(RANDOM_STRING_LENGTH);
    }

    private String generateNewClientId() {
        return CLIENT_PREFIX + "_" + mClientCount.incrementAndGet() + "_"
                + CarServiceUtils.generateRandomAlphaNumericString(RANDOM_STRING_LENGTH);
    }

    private static boolean isVehicleInUse() {
        CarPropertyService propertyService = CarLocalServices.getService(CarPropertyService.class);
        CarPropertyValue propValue = propertyService.getPropertySafe(
                VehicleProperty.VEHICLE_IN_USE, /* areaId= */ 0);
        if (propValue == null) {
            Slogf.w(TAG, "Cannot get property of VEHICLE_IN_USE");
            return false;
        }
        return propValue.getStatus() == CarPropertyValue.STATUS_AVAILABLE
                && (boolean) propValue.getValue();
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

    private static final class RemoteTaskClientServiceInfo {
        private final ComponentName mServiceComponentName;
        private final AtomicReference<RemoteTaskClientServiceConnection> mConnection =
                new AtomicReference<>(null);

        private RemoteTaskClientServiceInfo(ComponentName componentName) {
            mServiceComponentName = componentName;
        }

        public ComponentName getServiceComponentName() {
            return mServiceComponentName;
        }

        public RemoteTaskClientServiceConnection getServiceConnection() {
            return mConnection.get();
        }

        public void setServiceConnection(@Nullable RemoteTaskClientServiceConnection connection) {
            mConnection.set(connection);
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("RemoteTaskClientServiceInfo[")
                    .append("Component name=")
                    .append(mServiceComponentName)
                    .append(", hasConnection=")
                    .append(mConnection.get() != null)
                    .append("]")
                    .toString();
        }
    }

    private static final class RemoteTask {
        public String id;
        public byte[] data;

        private RemoteTask(String id, byte[] data) {
            this.id = id;
            this.data = data;
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
            synchronized (mTokenLock) {
                return new StringBuilder()
                        .append("ClientToken[")
                        .append("mClientId=").append(mClientId)
                        .append(", mIdCreationTimeInMs=").append(mIdCreationTimeInMs)
                        .append(", hasCallback=").append(mCallback != null)
                        .append(']')
                        .toString();
            }
        }
    }

    private static final class RemoteTaskClientServiceConnection implements ServiceConnection {

        private static final String TAG = RemoteTaskClientServiceConnection.class.getSimpleName();

        private final Object mServiceLock = new Object();
        private final Context mContext;
        private final Intent mIntent;

        @GuardedBy("mServiceLock")
        private boolean mBound;

        private RemoteTaskClientServiceConnection(Context context, ComponentName serviceName) {
            mContext = context;
            mIntent = new Intent();
            mIntent.setComponent(serviceName);
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

    private static final class RemoteTaskClientServiceHandler extends Handler {

        private static final String TAG = RemoteTaskClientServiceHandler.class.getSimpleName();
        private static final int MSG_UNBIND_SERVICE_AFTER_REGISTRATION = 1;
        private static final int MSG_WRAP_UP_REMOTE_ACCESS_SERVICE = 2;
        private static final int MSG_NOTIFY_SHUTDOWN_STARTING = 3;

        private final WeakReference<CarRemoteAccessService> mService;

        private RemoteTaskClientServiceHandler(Looper looper, CarRemoteAccessService service) {
            super(looper);
            mService = new WeakReference<>(service);
        }

        private void postUnbindServiceAfterRegistration(
                RemoteTaskClientServiceInfo serviceInfo, long delayMs) {
            Message msg = obtainMessage(MSG_UNBIND_SERVICE_AFTER_REGISTRATION, serviceInfo);
            sendMessageDelayed(msg, delayMs);
        }

        private void postNotifyShutdownStarting(long delayMs) {
            Message msg = obtainMessage(MSG_NOTIFY_SHUTDOWN_STARTING);
            sendMessageDelayed(msg, delayMs);
        }

        private void postWrapUpRemoteAccessService(long delayMs) {
            Message msg = obtainMessage(MSG_WRAP_UP_REMOTE_ACCESS_SERVICE);
            sendMessageDelayed(msg, delayMs);
        }

        private void cancelUnbindServiceAfterRegistration(
                RemoteTaskClientServiceInfo serviceInfo) {
            removeMessages(MSG_UNBIND_SERVICE_AFTER_REGISTRATION, serviceInfo);
        }

        private void cancelAllUnbindServiceAfterRegistration() {
            removeMessages(MSG_UNBIND_SERVICE_AFTER_REGISTRATION);
        }

        private void cancelNotifyShutdownStarting() {
            removeMessages(MSG_NOTIFY_SHUTDOWN_STARTING);
        }

        private void cancelWrapUpRemoteAccessService() {
            removeMessages(MSG_WRAP_UP_REMOTE_ACCESS_SERVICE);
        }

        private void cancelAll() {
            cancelAllUnbindServiceAfterRegistration();
            cancelWrapUpRemoteAccessService();
            cancelNotifyShutdownStarting();
        }

        @Override
        public void handleMessage(Message msg) {
            CarRemoteAccessService service = mService.get();
            if (service == null) {
                Slogf.w(TAG, "CarRemoteAccessService is not available");
                return;
            }
            switch (msg.what) {
                case MSG_UNBIND_SERVICE_AFTER_REGISTRATION:
                    service.unbindRemoteTaskClientService((RemoteTaskClientServiceInfo) msg.obj);
                    break;
                case MSG_WRAP_UP_REMOTE_ACCESS_SERVICE:
                    service.wrapUpRemoteAccessServiceIfNeeded();
                    break;
                case MSG_NOTIFY_SHUTDOWN_STARTING:
                    service.notifyShutdownStarting();
                    break;
                default:
                    Slogf.w(TAG, "Unknown(%d) message", msg.what);
            }
        }
    }
}
