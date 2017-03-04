/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.car;

import android.car.annotation.FutureFeature;
import android.car.vms.IOnVmsMessageReceivedListener;

import android.car.vms.IVmsPublisherClient;
import android.car.vms.IVmsPublisherService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.android.car.hal.VmsHalService;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * + Receives HAL updates by implementing VmsHalService.VmsHalListener.
 * + Binds to publishers and configures them to use this service.
 * + Notifies publishers of subscription changes.
 */
@FutureFeature
public class VmsPublisherService extends IVmsPublisherService.Stub
        implements CarServiceBase, VmsHalService.VmsHalPublisherListener {
    private static final boolean DBG = true;
    private static final String TAG = "VmsPublisherService";

    private final Context mContext;
    private final VmsHalService mHal;
    private final VmsPublisherManager mPublisherManager;

    public VmsPublisherService(Context context, VmsHalService hal) {
        mContext = context;
        mHal = hal;
        mPublisherManager = new VmsPublisherManager(this);
    }

    // Implements CarServiceBase interface.
    @Override
    public void init() {
        mHal.addPublisherListener(this);
        // Launch publishers.
        String[] publisherNames = mContext.getResources().getStringArray(
                R.array.vmsPublisherClients);
        for (String publisherName : publisherNames) {
            if (TextUtils.isEmpty(publisherName)) {
                Log.e(TAG, "empty publisher name");
                continue;
            }
            ComponentName name = ComponentName.unflattenFromString(publisherName);
            if (name == null) {
                Log.e(TAG, "invalid publisher name: " + publisherName);
            }
            mPublisherManager.bind(name);
        }
    }

    @Override
    public void release() {
        mPublisherManager.release();
        mHal.removePublisherListener(this);
    }

    @Override
    public void dump(PrintWriter writer) {
    }

    // Implements IVmsPublisherService interface.
    @Override
    public void publish(int layerId, int layerVersion, byte[] payload) {
        if (DBG) {
            Log.d(TAG, "Publishing for layer ID: " + layerId + " Version: " + layerVersion);
        }
        ICarImpl.assertVmsPublisherPermission(mContext);
        VmsLayer layer = new VmsLayer(layerId, layerVersion);

        // Sned the message to application listeners.
        Set<IOnVmsMessageReceivedListener> listeners = mHal.getListeners(layer);

        if (DBG) {
            Log.d(TAG, "Number of subscribed apps: " + listeners.size());
        }
        for (IOnVmsMessageReceivedListener listener : listeners) {
            try {
                listener.onVmsMessageReceived(layerId, layerVersion, payload);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to publish to listener: " + listener);
            }
        }

        // Send the message to HAL
        if (mHal.isHalSubscribed(layer)) {
            Log.d(TAG, "HAL is subscribed");
            mHal.setDataMessage(layerId, layerVersion, payload);
        } else {
            Log.d(TAG, "HAL is NOT subscribed");
        }
    }

    @Override
    public boolean hasSubscribers(int layerId, int layerVersion) {
        ICarImpl.assertVmsPublisherPermission(mContext);
        VmsLayer layer = new VmsLayer(layerId, layerVersion);
        return mHal.isHalSubscribed(layer) || mHal.hasLayerSubscriptions(layer);
    }

    // Implements VmsHalListener interface
    @Override
    public void onChange(int layerId, int layerVersion, boolean hasSubscribers) {
        if (hasSubscribers) {
            mHal.addHalSubscription(new VmsLayer(layerId, layerVersion));
        } else {
            mHal.removeHalSubscription(new VmsLayer(layerId, layerVersion));
        }
    }

    /**
     * Keeps track of publishers that are using this service.
     */
    private static class VmsPublisherManager {
        /**
         * Allows to modify mPublisherMap and mPublisherConnectionMap as a single unit.
         */
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private final Map<String, PublisherConnection> mPublisherConnectionMap = new HashMap<>();
        @GuardedBy("mLock")
        private final Map<String, IVmsPublisherClient> mPublisherMap = new HashMap<>();
        private final WeakReference<VmsPublisherService> mPublisherService;

        public VmsPublisherManager(VmsPublisherService publisherService) {
            mPublisherService = new WeakReference<>(publisherService);
        }

        /**
         * Tries to bind to a publisher.
         *
         * @param name publisher component name (e.g. android.car.vms.logger/.LoggingService).
         */
        public void bind(ComponentName name) {
            VmsPublisherService publisherService = mPublisherService.get();
            if (publisherService == null) return;
            String publisherName = name.flattenToString();
            if (DBG) {
                Log.d(TAG, "binding to: " + publisherName);
            }
            synchronized (mLock) {
                if (mPublisherConnectionMap.containsKey(publisherName)) {
                    // Already registered, nothing to do.
                    return;
                }
                Intent intent = new Intent();
                intent.setComponent(name);
                PublisherConnection connection = new PublisherConnection();
                if (publisherService.mContext.bindService(intent, connection,
                        Context.BIND_AUTO_CREATE)) {
                    mPublisherConnectionMap.put(publisherName, connection);
                } else {
                    Log.e(TAG, "unable to bind to: " + publisherName);
                }
            }
        }

        /**
         * Removes the publisher and associated connection.
         *
         * @param name publisher component name (e.g. android.car.vms.Logger).
         */
        public void unbind(ComponentName name) {
            VmsPublisherService publisherService = mPublisherService.get();
            if (publisherService == null) return;
            String publisherName = name.flattenToString();
            if (DBG) {
                Log.d(TAG, "unbinding from: " + publisherName);
            }
            synchronized (mLock) {
                boolean found = mPublisherMap.remove(publisherName) != null;
                if (found) {
                    PublisherConnection connection = mPublisherConnectionMap.get(publisherName);
                    publisherService.mContext.unbindService(connection);
                    mPublisherConnectionMap.remove(publisherName);
                } else {
                    Log.e(TAG, "unbind: unknown publisher." + publisherName);
                }
            }
        }

        /**
         * Returns the list of publishers currently registered.
         *
         * @return list of publishers.
         */
        public List<IVmsPublisherClient> getClients() {
            synchronized (mLock) {
                return new ArrayList<>(mPublisherMap.values());
            }
        }

        public void release() {
            VmsPublisherService publisherService = mPublisherService.get();
            if (publisherService == null) return;
            for (PublisherConnection connection : mPublisherConnectionMap.values()) {
                publisherService.mContext.unbindService(connection);
            }
            mPublisherConnectionMap.clear();
            mPublisherMap.clear();
        }

        class PublisherConnection implements ServiceConnection {
            /**
             * Once the service binds to a publisher service, the publisher binder is added to
             * mPublisherMap
             * and the publisher is configured to use this service.
             */
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                VmsPublisherService publisherService = mPublisherService.get();
                if (publisherService == null) return;
                if (DBG) {
                    Log.d(TAG, "onServiceConnected, name: " + name + ", binder: " + binder);
                }
                IVmsPublisherClient service = IVmsPublisherClient.Stub.asInterface(binder);
                synchronized (mLock) {
                    mPublisherMap.put(name.flattenToString(), service);
                }
                try {
                    service.setVmsPublisherService(publisherService);
                } catch (RemoteException e) {
                    Log.e(TAG, "unable to configure publisher: " + name);
                }
            }

            /**
             * Tries to rebind to the publisher service.
             */
            @Override
            public void onServiceDisconnected(ComponentName name) {
                String publisherName = name.flattenToString();
                Log.d(TAG, "onServiceDisconnected, name: " + publisherName);
                VmsPublisherManager.this.unbind(name);
                VmsPublisherManager.this.bind(name);
            }
        }
    }
}
