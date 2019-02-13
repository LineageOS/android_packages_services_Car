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

import android.car.userlib.CarUserManagerHelper;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;

import com.android.car.CarServiceBase;
import com.android.car.R;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Map;

/**
 * Manages service connections lifecycle for VMS publisher clients.
 *
 * Binds to system-level clients at boot and creates/destroys bindings for userspace clients
 * according to the Android user lifecycle.
 */
public class VmsClientManager implements CarServiceBase {
    private static final boolean DBG = false;
    private static final String TAG = "VmsClientManager";

    /**
     * Interface for receiving updates about client connections.
     */
    public interface ConnectionListener {
        /**
         * Called when a client connection is established or re-established.
         *
         * @param clientName String that uniquely identifies the service and user.
         * @param binder Binder for communicating with the client.
         */
        void onClientConnected(String clientName, IBinder binder);

        /**
         * Called when a client connection is terminated.
         *
         * @param clientName String that uniquely identifies the service and user.
         */
        void onClientDisconnected(String clientName);
    }

    private final Context mContext;
    private final Handler mHandler;
    private final CarUserManagerHelper mUserManagerHelper;
    private final int mMillisBeforeRebind;

    @GuardedBy("mListeners")
    private final ArrayList<ConnectionListener> mListeners = new ArrayList<>();
    @GuardedBy("mSystemClients")
    private final Map<String, ClientConnection> mSystemClients = new ArrayMap<>();
    @GuardedBy("mCurrentUserClients")
    private final Map<String, ClientConnection> mCurrentUserClients = new ArrayMap<>();
    @GuardedBy("mCurrentUserClients")
    private int mCurrentUser;

    final BroadcastReceiver mBootCompletedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_LOCKED_BOOT_COMPLETED:
                    bindToSystemClients();
                    break;
                default:
                    Log.e(TAG, "Unexpected intent received: " + intent);
            }
        }
    };

    final BroadcastReceiver mUserSwitchReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DBG) Log.d(TAG, "Received " + intent);
            switch (intent.getAction()) {
                case Intent.ACTION_USER_SWITCHED:
                case Intent.ACTION_USER_UNLOCKED:
                    bindToCurrentUserClients();
                    break;
                default:
                    Log.e(TAG, "Unexpected intent received: " + intent);
            }
        }
    };

    /**
     * Constructor for client managers.
     *
     * @param context Context to use for registering receivers and binding services.
     * @param userManagerHelper User manager for querying current user state.
     */
    public VmsClientManager(Context context, CarUserManagerHelper userManagerHelper) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mUserManagerHelper = userManagerHelper;
        mMillisBeforeRebind = mContext.getResources().getInteger(
                com.android.car.R.integer.millisecondsBeforeRebindToVmsPublisher);
    }

    @Override
    public void init() {
        mContext.registerReceiver(mBootCompletedReceiver,
                new IntentFilter(Intent.ACTION_LOCKED_BOOT_COMPLETED));

        IntentFilter userSwitchFilter = new IntentFilter();
        userSwitchFilter.addAction(Intent.ACTION_USER_SWITCHED);
        userSwitchFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(mUserSwitchReceiver, UserHandle.ALL, userSwitchFilter, null,
                null);
    }

    @Override
    public void release() {
        mContext.unregisterReceiver(mBootCompletedReceiver);
        mContext.unregisterReceiver(mUserSwitchReceiver);
        synchronized (mSystemClients) {
            unbind(mSystemClients);
        }
        synchronized (mCurrentUserClients) {
            unbind(mCurrentUserClients);
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*" + getClass().getSimpleName() + "*");
        writer.println("mListeners:" + mListeners);
        writer.println("mSystemClients:" + mSystemClients.keySet());
        writer.println("mCurrentUser:" + mCurrentUser);
        writer.println("mCurrentUserClients:" + mCurrentUserClients.keySet());
    }

    /**
     * Registers a new client connection state listener.
     *
     * @param listener Listener to register.
     */
    public void registerConnectionListener(ConnectionListener listener) {
        synchronized (mListeners) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener);
            }
        }
    }

    /**
     * Unregisters a client connection state listener.
     *
     * @param listener Listener to remove.
     */
    public void unregisterConnectionListener(ConnectionListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    private void bindToSystemClients() {
        String[] clientNames = mContext.getResources().getStringArray(
                R.array.vmsPublisherSystemClients);
        Log.i(TAG, "Attempting to bind " + clientNames.length + " system client(s)");
        synchronized (mSystemClients) {
            for (String clientName : clientNames) {
                bind(mSystemClients, clientName, UserHandle.SYSTEM);
            }
        }
    }

    private void bindToCurrentUserClients() {
        UserInfo userInfo = mUserManagerHelper.getCurrentForegroundUserInfo();
        synchronized (mCurrentUserClients) {
            if (mCurrentUser != userInfo.id) {
                unbind(mCurrentUserClients);
            }
            mCurrentUser = userInfo.id;

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
            for (String clientName : clientNames) {
                bind(mCurrentUserClients, clientName, userInfo.getUserHandle());
            }
        }
    }

    private void bind(Map<String, ClientConnection> connectionMap, String clientName,
            UserHandle userHandle) {
        if (connectionMap.containsKey(clientName)) {
            return;
        }

        ComponentName name = ComponentName.unflattenFromString(clientName);
        if (name == null) {
            Log.e(TAG, "Invalid client name: " + clientName);
            return;
        }

        if (!mContext.getPackageManager().isPackageAvailable(name.getPackageName())) {
            Log.w(TAG, "Client not installed: " + clientName);
            return;
        }

        ClientConnection connection = new ClientConnection(name, userHandle);
        if (connection.bind()) {
            Log.i(TAG, "Client bound: " + connection);
            connectionMap.put(clientName, connection);
        } else {
            Log.w(TAG, "Binding failed: " + connection);
        }
    }

    private void unbind(Map<String, ClientConnection> connectionMap) {
        for (ClientConnection connection : connectionMap.values()) {
            connection.unbind();
        }
        connectionMap.clear();
    }

    private void notifyListenersOnClientConnected(String clientName, IBinder binder) {
        synchronized (mListeners) {
            for (ConnectionListener listener : mListeners) {
                listener.onClientConnected(clientName, binder);
            }
        }
    }

    private void notifyListenersOnClientDisconnected(String clientName) {
        synchronized (mListeners) {
            for (ConnectionListener listener : mListeners) {
                listener.onClientDisconnected(clientName);
            }
        }
    }

    class ClientConnection implements ServiceConnection {
        private final ComponentName mName;
        private final UserHandle mUser;
        private final String mFullName;
        private boolean mIsBound = false;
        private IBinder mBinder;

        ClientConnection(ComponentName name, UserHandle user) {
            mName = name;
            mUser = user;
            mFullName = mName.flattenToString() + " U=" + mUser.getIdentifier();
        }

        synchronized boolean bind() {
            // Ignore if already bound
            if (mIsBound) {
                return true;
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
            if (DBG) Log.d(TAG, "unbinding: " + mFullName);
            try {
                mContext.unbindService(this);
            } catch (Throwable t) {
                Log.e(TAG, "While unbinding " + mFullName, t);
            }
            mIsBound = false;
            if (mBinder != null) {
                notifyListenersOnClientDisconnected(mFullName);
            }
            mBinder = null;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (DBG) Log.d(TAG, "onServiceConnected: " + mFullName);
            mBinder = binder;
            notifyListenersOnClientConnected(mFullName, mBinder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DBG) Log.d(TAG, "onServiceDisconnected: " + mFullName);
            if (mBinder != null) {
                notifyListenersOnClientDisconnected(mFullName);
            }
            mBinder = null;
            // No need to unbind and rebind, per onServiceDisconnected documentation:
            // "binding to the service will remain active, and you will receive a call
            // to onServiceConnected when the Service is next running"
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (DBG) Log.d(TAG, "onBindingDied: " + mFullName);
            unbind();
            mHandler.postDelayed(this::bind, mMillisBeforeRebind);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            if (DBG) Log.d(TAG, "onNullBinding: " + mFullName);
            unbind();
        }

        @Override
        public String toString() {
            return mFullName;
        }
    }
}
