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

import android.car.vms.IVmsPublisherClient;
import android.car.vms.IVmsPublisherService;
import android.car.vms.IVmsSubscriberClient;
import android.car.vms.VmsLayer;
import android.car.vms.VmsLayersOffering;
import android.car.vms.VmsSubscriptionState;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import com.android.car.vms.VmsBrokerService;
import com.android.car.vms.VmsClientManager;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Receives HAL updates by implementing VmsHalService.VmsHalListener.
 * Binds to publishers and configures them to use this service.
 * Notifies publishers of subscription changes.
 */
public class VmsPublisherService implements CarServiceBase, VmsClientManager.ConnectionListener {
    private static final boolean DBG = true;
    private static final String TAG = "VmsPublisherService";

    private final Context mContext;
    private final VmsClientManager mClientManager;
    private final VmsBrokerService mBrokerService;
    private final Map<String, PublisherProxy> mPublisherProxies = Collections.synchronizedMap(
            new ArrayMap<>());

    public VmsPublisherService(
            Context context,
            VmsBrokerService brokerService,
            VmsClientManager clientManager) {
        mContext = context;
        mClientManager = clientManager;
        mBrokerService = brokerService;
    }

    @Override
    public void init() {
        mClientManager.registerConnectionListener(this);
    }

    @Override
    public void release() {
        mClientManager.unregisterConnectionListener(this);
        mPublisherProxies.values().forEach(PublisherProxy::unregister);
        mPublisherProxies.clear();
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*" + getClass().getSimpleName() + "*");
        writer.println("mPublisherProxies: " + mPublisherProxies.size());
    }

    @Override
    public void onClientConnected(String publisherName, IBinder binder) {
        if (DBG) Log.d(TAG, "onClientConnected: " + publisherName);
        IBinder publisherToken = new Binder();
        IVmsPublisherClient publisherClient = IVmsPublisherClient.Stub.asInterface(binder);

        PublisherProxy publisherProxy = new PublisherProxy(publisherName, publisherToken,
                publisherClient);
        publisherProxy.register();
        try {
            publisherClient.setVmsPublisherService(publisherToken, publisherProxy);
        } catch (RemoteException e) {
            Log.e(TAG, "unable to configure publisher: " + publisherName, e);
            return;
        }

        PublisherProxy existingProxy = mPublisherProxies.put(publisherName, publisherProxy);
        if (existingProxy != null) {
            existingProxy.unregister();
        }
    }

    @Override
    public void onClientDisconnected(String publisherName) {
        if (DBG) Log.d(TAG, "onClientDisconnected: " + publisherName);
        PublisherProxy proxy = mPublisherProxies.remove(publisherName);
        if (proxy != null) {
            proxy.unregister();
        }
    }

    private class PublisherProxy extends IVmsPublisherService.Stub implements
            VmsBrokerService.PublisherListener {
        private final String mName;
        private final IBinder mToken;
        private final IVmsPublisherClient mPublisherClient;
        private boolean mConnected;

        PublisherProxy(String name, IBinder token,
                IVmsPublisherClient publisherClient) {
            this.mName = name;
            this.mToken = token;
            this.mPublisherClient = publisherClient;
        }

        void register() {
            if (DBG) Log.d(TAG, "register: " + mName);
            mConnected = true;
            mBrokerService.addPublisherListener(this);
        }

        void unregister() {
            if (DBG) Log.d(TAG, "unregister: " + mName);
            mConnected = false;
            mBrokerService.removePublisherListener(this);
            mBrokerService.removeDeadPublisher(mToken);
        }

        @Override
        public void setLayersOffering(IBinder token, VmsLayersOffering offering) {
            assertPermission(token);
            mBrokerService.setPublisherLayersOffering(token, offering);
        }

        @Override
        public void publish(IBinder token, VmsLayer layer, int publisherId, byte[] payload) {
            assertPermission(token);
            if (DBG) {
                Log.d(TAG, String.format("Publishing to %s as %d (%s)", layer, publisherId, mName));
            }

            // Send the message to subscribers
            Set<IVmsSubscriberClient> listeners =
                    mBrokerService.getSubscribersForLayerFromPublisher(layer, publisherId);

            if (DBG) Log.d(TAG, String.format("Number of subscribers: %d", listeners.size()));
            for (IVmsSubscriberClient listener : listeners) {
                try {
                    listener.onVmsMessageReceived(layer, payload);
                } catch (RemoteException ex) {
                    Log.e(TAG, String.format("Unable to publish to listener: %s", listener));
                }
            }
        }

        @Override
        public VmsSubscriptionState getSubscriptions() {
            assertPermission();
            return mBrokerService.getSubscriptionState();
        }

        @Override
        public int getPublisherId(byte[] publisherInfo) {
            assertPermission();
            return mBrokerService.getPublisherId(publisherInfo);
        }

        @Override
        public void onSubscriptionChange(VmsSubscriptionState subscriptionState) {
            try {
                mPublisherClient.onVmsSubscriptionChange(subscriptionState);
            } catch (RemoteException e) {
                Log.e(TAG, String.format("Unable to send subscription state to: %s", mName), e);
            }
        }

        private void assertPermission(IBinder publisherToken) {
            if (mToken != publisherToken) {
                throw new SecurityException("Invalid publisher token");
            }
            assertPermission();
        }

        private void assertPermission() {
            if (!mConnected) {
                throw new SecurityException("Publisher has been disconnected");
            }
            ICarImpl.assertVmsPublisherPermission(mContext);
        }
    }
}
