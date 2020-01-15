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

package com.android.car.vms;

import android.car.Car;
import android.car.vms.IVmsPublisherClient;
import android.car.vms.IVmsSubscriberClient;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.car.CarServiceBase;
import com.android.car.R;
import com.android.car.VmsPublisherService;
import com.android.car.hal.VmsHalService;
import com.android.car.stats.CarStatsService;
import com.android.car.stats.VmsClientLogger;
import com.android.car.stats.VmsClientLogger.ConnectionState;
import com.android.car.user.CarUserService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages service connections lifecycle for VMS publisher clients.
 *
 * Binds to system-level clients at boot and creates/destroys bindings for userspace clients
 * according to the Android user lifecycle.
 */
public class VmsClientManager implements CarServiceBase {
    private static final boolean DBG = false;
    private static final String TAG = "VmsClientManager";
    private static final String HAL_CLIENT_NAME = "HalClient";
    private static final String UNKNOWN_PACKAGE = "UnknownPackage";

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final CarUserService mUserService;
    private final CarStatsService mStatsService;
    private final Handler mHandler;
    private final IntSupplier mGetCallingUid;
    private final int mMillisBeforeRebind;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final VmsBrokerService mBrokerService;
    @GuardedBy("mLock")
    private VmsPublisherService mPublisherService;

    @GuardedBy("mLock")
    private final Map<String, PublisherConnection> mSystemClients = new ArrayMap<>();
    @GuardedBy("mLock")
    private IVmsPublisherClient mHalClient;
    @GuardedBy("mLock")
    private boolean mSystemUserUnlocked;

    @GuardedBy("mLock")
    private final Map<String, PublisherConnection> mCurrentUserClients = new ArrayMap<>();
    @GuardedBy("mLock")
    private int mCurrentUser;

    @GuardedBy("mLock")
    private final Map<IBinder, SubscriberConnection> mSubscribers = new ArrayMap<>();

    @VisibleForTesting
    final Runnable mSystemUserUnlockedListener = () -> {
        synchronized (mLock) {
            mSystemUserUnlocked = true;
        }
        bindToSystemClients();
    };

    @VisibleForTesting
    public final CarUserService.UserCallback mUserCallback = new CarUserService.UserCallback() {
        @Override
        public void onSwitchUser(int userId) {
            synchronized (mLock) {
                if (mCurrentUser != userId) {
                    mCurrentUser = userId;
                    terminate(mCurrentUserClients);
                    terminate(mSubscribers.values().stream()
                            .filter(subscriber -> subscriber.mUserId != mCurrentUser)
                            .filter(subscriber -> subscriber.mUserId != UserHandle.USER_SYSTEM));
                }
            }
            bindToUserClients();
        }

        @Override
        public void onUserLockChanged(int userId, boolean unlocked) {
            synchronized (mLock) {
                if (mCurrentUser == userId && unlocked) {
                    bindToUserClients();
                }
            }
        }
    };

    /**
     * Constructor for client manager.
     *
     * @param context           Context to use for registering receivers and binding services.
     * @param statsService      Service for logging client metrics.
     * @param userService       User service for registering system unlock listener.
     * @param brokerService     Service managing the VMS publisher/subscriber state.
     * @param halService        Service providing the HAL client interface
     */
    public VmsClientManager(Context context, CarStatsService statsService,
            CarUserService userService, VmsBrokerService brokerService,
            VmsHalService halService) {
        this(context, statsService, userService, brokerService, halService,
                new Handler(Looper.getMainLooper()), Binder::getCallingUid);
    }

    @VisibleForTesting
    VmsClientManager(Context context, CarStatsService statsService,
            CarUserService userService, VmsBrokerService brokerService,
            VmsHalService halService, Handler handler, IntSupplier getCallingUid) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mStatsService = statsService;
        mUserService = userService;
        mCurrentUser = UserHandle.USER_NULL;
        mBrokerService = brokerService;
        mHandler = handler;
        mGetCallingUid = getCallingUid;
        mMillisBeforeRebind = context.getResources().getInteger(
                com.android.car.R.integer.millisecondsBeforeRebindToVmsPublisher);

        halService.setClientManager(this);
    }

    /**
     * Registers the publisher service for connection callbacks.
     *
     * @param publisherService Publisher service to register.
     */
    public void setPublisherService(VmsPublisherService publisherService) {
        synchronized (mLock) {
            mPublisherService = publisherService;
        }
    }

    @Override
    public void init() {
        mUserService.runOnUser0Unlock(mSystemUserUnlockedListener);
        mUserService.addUserCallback(mUserCallback);
    }

    @Override
    public void release() {
        mUserService.removeUserCallback(mUserCallback);
        synchronized (mLock) {
            if (mHalClient != null) {
                mPublisherService.onClientDisconnected(HAL_CLIENT_NAME);
            }
            terminate(mSystemClients);
            terminate(mCurrentUserClients);
            terminate(mSubscribers.values().stream());
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*" + getClass().getSimpleName() + "*");
        synchronized (mLock) {
            writer.println("mCurrentUser:" + mCurrentUser);
            writer.println("mHalClient: " + (mHalClient != null ? "connected" : "disconnected"));
            writer.println("mSystemClients:");
            dumpConnections(writer, mSystemClients);

            writer.println("mCurrentUserClients:");
            dumpConnections(writer, mCurrentUserClients);

            writer.println("mSubscribers:");
            for (SubscriberConnection subscriber : mSubscribers.values()) {
                writer.printf("\t%s\n", subscriber);
            }
        }
    }


    /**
     * Adds a subscriber for connection tracking.
     *
     * @param subscriberClient Subscriber client to track.
     */
    public void addSubscriber(IVmsSubscriberClient subscriberClient) {
        if (subscriberClient == null) {
            Log.e(TAG, "Trying to add a null subscriber: "
                    + getCallingPackage(mGetCallingUid.getAsInt()));
            throw new IllegalArgumentException("subscriber cannot be null.");
        }

        synchronized (mLock) {
            IBinder subscriberBinder = subscriberClient.asBinder();
            if (mSubscribers.containsKey(subscriberBinder)) {
                // Already registered
                return;
            }

            int callingUid = mGetCallingUid.getAsInt();
            int subscriberUserId = UserHandle.getUserId(callingUid);
            if (subscriberUserId != mCurrentUser && subscriberUserId != UserHandle.USER_SYSTEM) {
                throw new SecurityException("Caller must be foreground user or system");
            }

            SubscriberConnection subscriber = new SubscriberConnection(
                    subscriberClient, callingUid, getCallingPackage(callingUid), subscriberUserId);
            if (DBG) Log.d(TAG, "Registering subscriber: " + subscriber);
            try {
                subscriberBinder.linkToDeath(subscriber, 0);
            } catch (RemoteException e) {
                throw new IllegalStateException("Subscriber already dead: " + subscriber, e);
            }
            mSubscribers.put(subscriberBinder, subscriber);
        }
    }

    /**
     * Removes a subscriber for connection tracking and expires its subscriptions.
     *
     * @param subscriberClient Subscriber client to remove.
     */
    public void removeSubscriber(IVmsSubscriberClient subscriberClient) {
        synchronized (mLock) {
            SubscriberConnection subscriber = mSubscribers.get(subscriberClient.asBinder());
            if (subscriber != null) {
                subscriber.terminate();
            }
        }
    }

    /**
     * Returns all active subscriber clients.
     */
    public Collection<IVmsSubscriberClient> getAllSubscribers() {
        synchronized (mLock) {
            return mSubscribers.values().stream()
                    .map(subscriber -> subscriber.mClient)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Gets the application UID associated with a subscriber client.
     */
    public int getSubscriberUid(IVmsSubscriberClient subscriberClient) {
        synchronized (mLock) {
            SubscriberConnection subscriber = mSubscribers.get(subscriberClient.asBinder());
            return subscriber != null ? subscriber.mUid : Process.INVALID_UID;
        }
    }

    /**
     * Gets the package name for a given subscriber client.
     */
    public String getPackageName(IVmsSubscriberClient subscriberClient) {
        synchronized (mLock) {
            SubscriberConnection subscriber = mSubscribers.get(subscriberClient.asBinder());
            return subscriber != null ? subscriber.mPackageName : UNKNOWN_PACKAGE;
        }
    }

    /**
     * Registers the HAL client connections.
     */
    public void onHalConnected(IVmsPublisherClient publisherClient,
            IVmsSubscriberClient subscriberClient) {
        synchronized (mLock) {
            mHalClient = publisherClient;
            mPublisherService.onClientConnected(HAL_CLIENT_NAME, mHalClient);
            mSubscribers.put(subscriberClient.asBinder(),
                    new SubscriberConnection(subscriberClient, Process.myUid(), HAL_CLIENT_NAME,
                            UserHandle.USER_SYSTEM));
        }
        mStatsService.getVmsClientLogger(Process.myUid())
                .logConnectionState(ConnectionState.CONNECTED);
    }

    /**
     *
     */
    public void onHalDisconnected() {
        synchronized (mLock) {
            if (mHalClient != null) {
                mPublisherService.onClientDisconnected(HAL_CLIENT_NAME);
                mStatsService.getVmsClientLogger(Process.myUid())
                        .logConnectionState(ConnectionState.DISCONNECTED);
            }
            mHalClient = null;
            terminate(mSubscribers.values().stream()
                    .filter(subscriber -> HAL_CLIENT_NAME.equals(subscriber.mPackageName)));
        }
    }

    private void dumpConnections(PrintWriter writer,
            Map<String, PublisherConnection> connectionMap) {
        for (PublisherConnection connection : connectionMap.values()) {
            writer.printf("\t%s: %s\n",
                    connection.mName.getPackageName(),
                    connection.mIsBound ? "connected" : "disconnected");
        }
    }

    private void bindToSystemClients() {
        String[] clientNames = mContext.getResources().getStringArray(
                R.array.vmsPublisherSystemClients);
        synchronized (mLock) {
            if (!mSystemUserUnlocked) {
                return;
            }
            Log.i(TAG, "Attempting to bind " + clientNames.length + " system client(s)");
            for (String clientName : clientNames) {
                bind(mSystemClients, clientName, UserHandle.SYSTEM);
            }
        }
    }

    private void bindToUserClients() {
        bindToSystemClients(); // Bind system clients on user switch, if they are not already bound.
        synchronized (mLock) {
            if (mCurrentUser == UserHandle.USER_NULL) {
                Log.e(TAG, "Unknown user in foreground.");
                return;
            }
            // To avoid the risk of double-binding, clients running as the system user must only
            // ever be bound in bindToSystemClients().
            if (mCurrentUser == UserHandle.USER_SYSTEM) {
                Log.e(TAG, "System user in foreground. Userspace clients will not be bound.");
                return;
            }

            if (!mUserManager.isUserUnlockingOrUnlocked(mCurrentUser)) {
                Log.i(TAG, "Waiting for foreground user " + mCurrentUser + " to be unlocked.");
                return;
            }

            String[] clientNames = mContext.getResources().getStringArray(
                    R.array.vmsPublisherUserClients);
            Log.i(TAG, "Attempting to bind " + clientNames.length + " user client(s)");
            UserHandle currentUserHandle = UserHandle.of(mCurrentUser);
            for (String clientName : clientNames) {
                bind(mCurrentUserClients, clientName, currentUserHandle);
            }
        }
    }

    private void bind(Map<String, PublisherConnection> connectionMap, String clientName,
            UserHandle userHandle) {
        if (connectionMap.containsKey(clientName)) {
            Log.i(TAG, "Already bound: " + clientName);
            return;
        }

        ComponentName name = ComponentName.unflattenFromString(clientName);
        if (name == null) {
            Log.e(TAG, "Invalid client name: " + clientName);
            return;
        }

        ServiceInfo serviceInfo;
        try {
            serviceInfo = mContext.getPackageManager().getServiceInfo(name,
                    PackageManager.MATCH_DIRECT_BOOT_AUTO);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Client not installed: " + clientName);
            return;
        }

        VmsClientLogger statsLog = mStatsService.getVmsClientLogger(
                UserHandle.getUid(userHandle.getIdentifier(), serviceInfo.applicationInfo.uid));

        if (!Car.PERMISSION_BIND_VMS_CLIENT.equals(serviceInfo.permission)) {
            Log.e(TAG, "Client service: " + clientName
                    + " does not require " + Car.PERMISSION_BIND_VMS_CLIENT + " permission");
            statsLog.logConnectionState(ConnectionState.CONNECTION_ERROR);
            return;
        }

        PublisherConnection connection = new PublisherConnection(name, userHandle, statsLog);
        if (connection.bind()) {
            Log.i(TAG, "Client bound: " + connection);
            connectionMap.put(clientName, connection);
        } else {
            Log.e(TAG, "Binding failed: " + connection);
        }
    }

    private void terminate(Map<String, PublisherConnection> connectionMap) {
        connectionMap.values().forEach(PublisherConnection::terminate);
        connectionMap.clear();
    }

    class PublisherConnection implements ServiceConnection {
        private final ComponentName mName;
        private final UserHandle mUser;
        private final String mFullName;
        private final VmsClientLogger mStatsLog;
        private boolean mIsBound = false;
        private boolean mIsTerminated = false;
        private boolean mRebindScheduled = false;
        private IVmsPublisherClient mClientService;

        PublisherConnection(ComponentName name, UserHandle user, VmsClientLogger statsLog) {
            mName = name;
            mUser = user;
            mFullName = mName.flattenToString() + " U=" + mUser.getIdentifier();
            mStatsLog = statsLog;
        }

        synchronized boolean bind() {
            if (mIsBound) {
                return true;
            }
            if (mIsTerminated) {
                return false;
            }
            mStatsLog.logConnectionState(ConnectionState.CONNECTING);

            if (DBG) Log.d(TAG, "binding: " + mFullName);
            Intent intent = new Intent();
            intent.setComponent(mName);
            try {
                mIsBound = mContext.bindServiceAsUser(intent, this, Context.BIND_AUTO_CREATE,
                        mHandler, mUser);
            } catch (SecurityException e) {
                Log.e(TAG, "While binding " + mFullName, e);
            }

            if (!mIsBound) {
                mStatsLog.logConnectionState(ConnectionState.CONNECTION_ERROR);
            }

            return mIsBound;
        }

        synchronized void unbind() {
            if (!mIsBound) {
                return;
            }

            if (DBG) Log.d(TAG, "unbinding: " + mFullName);
            try {
                mContext.unbindService(this);
            } catch (Throwable t) {
                Log.e(TAG, "While unbinding " + mFullName, t);
            }
            mIsBound = false;
        }

        synchronized void scheduleRebind() {
            if (mRebindScheduled) {
                return;
            }

            if (DBG) {
                Log.d(TAG,
                        String.format("rebinding %s after %dms", mFullName, mMillisBeforeRebind));
            }
            mHandler.postDelayed(this::doRebind, mMillisBeforeRebind);
            mRebindScheduled = true;
        }

        synchronized void doRebind() {
            mRebindScheduled = false;
            // Do not rebind if the connection has been terminated, or the client service has
            // reconnected on its own.
            if (mIsTerminated || mClientService != null) {
                return;
            }

            Log.i(TAG, "Rebinding: " + mFullName);
            // Ensure that the client is not bound before attempting to rebind.
            // If the client is not currently bound, unbind() will have no effect.
            unbind();
            bind();
        }

        synchronized void terminate() {
            if (DBG) Log.d(TAG, "terminating: " + mFullName);
            mIsTerminated = true;
            notifyOnDisconnect(ConnectionState.TERMINATED);
            unbind();
        }

        synchronized void notifyOnDisconnect(int connectionState) {
            if (mClientService != null) {
                mPublisherService.onClientDisconnected(mFullName);
                mClientService = null;
                mStatsLog.logConnectionState(connectionState);
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DBG) Log.d(TAG, "onServiceConnected: " + mFullName);
            mClientService = IVmsPublisherClient.Stub.asInterface(service);
            mPublisherService.onClientConnected(mFullName, mClientService);
            mStatsLog.logConnectionState(ConnectionState.CONNECTED);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DBG) Log.d(TAG, "onServiceDisconnected: " + mFullName);
            notifyOnDisconnect(ConnectionState.DISCONNECTED);
            scheduleRebind();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (DBG) Log.d(TAG, "onBindingDied: " + mFullName);
            notifyOnDisconnect(ConnectionState.DISCONNECTED);
            scheduleRebind();
        }

        @Override
        public String toString() {
            return mFullName;
        }
    }

    private void terminate(Stream<SubscriberConnection> subscribers) {
        // Make a copy of the stream, so that terminate() doesn't cause a concurrent modification
        subscribers.collect(Collectors.toList()).forEach(SubscriberConnection::terminate);
    }

    // If we're in a binder call, returns back the package name of the caller of the binder call.
    private String getCallingPackage(int uid) {
        String packageName = mPackageManager.getNameForUid(uid);
        if (packageName == null) {
            return UNKNOWN_PACKAGE;
        } else {
            return packageName;
        }
    }

    private class SubscriberConnection implements IBinder.DeathRecipient {
        private final IVmsSubscriberClient mClient;
        private final int mUid;
        private final String mPackageName;
        private final int mUserId;

        SubscriberConnection(IVmsSubscriberClient subscriberClient, int uid, String packageName,
                int userId) {
            mClient = subscriberClient;
            mUid = uid;
            mPackageName = packageName;
            mUserId = userId;
        }

        @Override
        public void binderDied() {
            if (DBG) Log.d(TAG, "Subscriber died: " + this);
            terminate();
        }

        @Override
        public String toString() {
            return mPackageName + " U=" + mUserId;
        }

        void terminate() {
            if (DBG) Log.d(TAG, "Terminating subscriber: " + this);
            synchronized (mLock) {
                mBrokerService.removeDeadSubscriber(mClient);
                IBinder subscriberBinder = mClient.asBinder();
                try {
                    subscriberBinder.unlinkToDeath(this, 0);
                } catch (NoSuchElementException e) {
                    if (DBG) Log.d(TAG, "While unlinking subscriber binder for " + this, e);
                }
                mSubscribers.remove(subscriberBinder);
            }
        }
    }
}
