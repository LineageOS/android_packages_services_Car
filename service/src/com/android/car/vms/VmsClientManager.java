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
import android.car.userlib.CarUserManagerHelper;
import android.car.vms.IVmsPublisherClient;
import android.car.vms.IVmsSubscriberClient;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.car.CarServiceBase;
import com.android.car.R;
import com.android.car.VmsPublisherService;
import com.android.car.hal.VmsHalService;
import com.android.car.user.CarUserService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
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
    private final Handler mHandler;
    private final UserManager mUserManager;
    private final CarUserService mUserService;
    private final CarUserManagerHelper mUserManagerHelper;
    private final int mMillisBeforeRebind;
    private final IntSupplier mGetCallingUid;

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
    private final Map<IBinder, SubscriberConnection> mSubscribers = new HashMap<>();

    @GuardedBy("mRebindCounts")
    private final Map<String, AtomicLong> mRebindCounts = new ArrayMap<>();

    @VisibleForTesting
    final Runnable mSystemUserUnlockedListener = () -> {
        synchronized (mLock) {
            mSystemUserUnlocked = true;
        }
        bindToSystemClients();
    };

    @VisibleForTesting
    final BroadcastReceiver mUserSwitchReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DBG) Log.d(TAG, "Received " + intent);
            synchronized (mLock) {
                int currentUserId = mUserManagerHelper.getCurrentForegroundUserId();
                if (mCurrentUser != currentUserId) {
                    terminate(mCurrentUserClients);
                    terminate(mSubscribers.values().stream()
                            .filter(subscriber -> subscriber.mUserId != currentUserId)
                            .filter(subscriber -> subscriber.mUserId != UserHandle.USER_SYSTEM));
                }
                mCurrentUser = currentUserId;

                if (mUserManager.isUserUnlocked(mCurrentUser)) {
                    bindToSystemClients();
                    bindToUserClients();
                }
            }
        }
    };

    /**
     * Constructor for client manager.
     *
     * @param context           Context to use for registering receivers and binding services.
     * @param brokerService     Service managing the VMS publisher/subscriber state.
     * @param userService       User service for registering system unlock listener.
     * @param userManagerHelper User manager for querying current user state.
     * @param halService        Service providing the HAL client interface
     */
    public VmsClientManager(Context context, VmsBrokerService brokerService,
            CarUserService userService, CarUserManagerHelper userManagerHelper,
            VmsHalService halService) {
        this(context, brokerService, userService, userManagerHelper, halService,
                new Handler(Looper.getMainLooper()), Binder::getCallingUid);
    }

    @VisibleForTesting
    VmsClientManager(Context context, VmsBrokerService brokerService,
            CarUserService userService, CarUserManagerHelper userManagerHelper,
            VmsHalService halService, Handler handler, IntSupplier getCallingUid) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mHandler = handler;
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mUserService = userService;
        mUserManagerHelper = userManagerHelper;
        mCurrentUser = mUserManagerHelper.getCurrentForegroundUserId();
        mBrokerService = brokerService;
        mMillisBeforeRebind = mContext.getResources().getInteger(
                com.android.car.R.integer.millisecondsBeforeRebindToVmsPublisher);
        mGetCallingUid = getCallingUid;
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

        IntentFilter userSwitchFilter = new IntentFilter();
        userSwitchFilter.addAction(Intent.ACTION_USER_SWITCHED);
        userSwitchFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(mUserSwitchReceiver, UserHandle.ALL, userSwitchFilter, null,
                null);
    }

    @Override
    public void release() {
        mContext.unregisterReceiver(mUserSwitchReceiver);
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
        dumpMetrics(writer);
    }

    @Override
    public void dumpMetrics(PrintWriter writer) {
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
        synchronized (mRebindCounts) {
            writer.println("mRebindCounts:");
            for (Map.Entry<String, AtomicLong> entry : mRebindCounts.entrySet()) {
                writer.printf("\t%s: %s\n", entry.getKey(), entry.getValue());
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
            Log.e(TAG, "Trying to add a null subscriber: " + getCallingPackage());
            throw new IllegalArgumentException("subscriber cannot be null.");
        }

        synchronized (mLock) {
            IBinder subscriberBinder = subscriberClient.asBinder();
            if (mSubscribers.containsKey(subscriberBinder)) {
                // Already registered
                return;
            }

            int subscriberUserId = UserHandle.getUserId(mGetCallingUid.getAsInt());
            if (subscriberUserId != mCurrentUser && subscriberUserId != UserHandle.USER_SYSTEM) {
                throw new SecurityException("Caller must be foreground user or system");
            }

            SubscriberConnection subscriber = new SubscriberConnection(
                    subscriberClient, getCallingPackage(), subscriberUserId);
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
     *
     * @param publisherClient
     * @param subscriberClient
     */
    public void onHalConnected(IVmsPublisherClient publisherClient,
            IVmsSubscriberClient subscriberClient) {
        synchronized (mLock) {
            mHalClient = publisherClient;
            mPublisherService.onClientConnected(HAL_CLIENT_NAME, mHalClient);
            mSubscribers.put(subscriberClient.asBinder(),
                    new SubscriberConnection(subscriberClient, HAL_CLIENT_NAME,
                            UserHandle.USER_SYSTEM));
        }
    }

    /**
     *
     */
    public void onHalDisconnected() {
        synchronized (mLock) {
            if (mHalClient != null) {
                mPublisherService.onClientDisconnected(HAL_CLIENT_NAME);
            }
            mHalClient = null;
            terminate(mSubscribers.values().stream()
                    .filter(subscriber -> HAL_CLIENT_NAME.equals(subscriber.mPackageName)));
        }
        synchronized (mRebindCounts) {
            mRebindCounts.computeIfAbsent(HAL_CLIENT_NAME, k -> new AtomicLong()).incrementAndGet();
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
        synchronized (mLock) {
            // To avoid the risk of double-binding, clients running as the system user must only
            // ever be bound in bindToSystemClients().
            // In a headless multi-user system, the system user will never be in the foreground.
            if (mCurrentUser == UserHandle.USER_SYSTEM) {
                Log.e(TAG, "System user in foreground. Userspace clients will not be bound.");
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

        if (!Car.PERMISSION_BIND_VMS_CLIENT.equals(serviceInfo.permission)) {
            Log.e(TAG, "Client service: " + clientName
                    + " does not require " + Car.PERMISSION_BIND_VMS_CLIENT + " permission");
            return;
        }

        PublisherConnection connection = new PublisherConnection(name, userHandle);
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
        private boolean mIsBound = false;
        private boolean mIsTerminated = false;
        private boolean mRebindScheduled = false;
        private IVmsPublisherClient mClientService;

        PublisherConnection(ComponentName name, UserHandle user) {
            mName = name;
            mUser = user;
            mFullName = mName.flattenToString() + " U=" + mUser.getIdentifier();
        }

        synchronized boolean bind() {
            if (mIsBound) {
                return true;
            }
            if (mIsTerminated) {
                return false;
            }

            if (DBG) Log.d(TAG, "binding: " + mFullName);
            Intent intent = new Intent();
            intent.setComponent(mName);
            try {
                mIsBound = mContext.bindServiceAsUser(intent, this, Context.BIND_AUTO_CREATE,
                        mHandler, mUser);
            } catch (SecurityException e) {
                Log.e(TAG, "While binding " + mFullName, e);
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
            synchronized (mRebindCounts) {
                mRebindCounts.computeIfAbsent(mName.getPackageName(), k -> new AtomicLong())
                        .incrementAndGet();
            }
        }

        synchronized void terminate() {
            if (DBG) Log.d(TAG, "terminating: " + mFullName);
            mIsTerminated = true;
            notifyOnDisconnect();
            unbind();
        }

        synchronized void notifyOnDisconnect() {
            if (mClientService != null) {
                mPublisherService.onClientDisconnected(mFullName);
                mClientService = null;
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DBG) Log.d(TAG, "onServiceConnected: " + mFullName);
            mClientService = IVmsPublisherClient.Stub.asInterface(service);
            mPublisherService.onClientConnected(mFullName, mClientService);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DBG) Log.d(TAG, "onServiceDisconnected: " + mFullName);
            notifyOnDisconnect();
            scheduleRebind();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (DBG) Log.d(TAG, "onBindingDied: " + mFullName);
            notifyOnDisconnect();
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
    private String getCallingPackage() {
        String packageName = mPackageManager.getNameForUid(mGetCallingUid.getAsInt());
        if (packageName == null) {
            return UNKNOWN_PACKAGE;
        } else {
            return packageName;
        }
    }

    private class SubscriberConnection implements IBinder.DeathRecipient {
        private final IVmsSubscriberClient mClient;
        private final String mPackageName;
        private final int mUserId;

        SubscriberConnection(IVmsSubscriberClient subscriberClient, String packageName,
                int userId) {
            mClient = subscriberClient;
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
