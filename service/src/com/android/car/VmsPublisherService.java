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

import com.android.car.stats.CarStatsService;
import com.android.car.vms.VmsBrokerService;
import com.android.car.vms.VmsClientManager;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;


/**
 * Receives HAL updates by implementing VmsHalService.VmsHalListener.
 * Binds to publishers and configures them to use this service.
 * Notifies publishers of subscription changes.
 */
public class VmsPublisherService implements CarServiceBase {
    private static final boolean DBG = false;
    private static final String TAG = "VmsPublisherService";

    private final Context mContext;
    private final CarStatsService mStatsService;
    private final VmsBrokerService mBrokerService;
    private final VmsClientManager mClientManager;
    private final IntSupplier mGetCallingUid;
    private final Map<String, PublisherProxy> mPublisherProxies = Collections.synchronizedMap(
            new ArrayMap<>());

    VmsPublisherService(
            Context context,
            CarStatsService statsService,
            VmsBrokerService brokerService,
            VmsClientManager clientManager) {
        this(context, statsService, brokerService, clientManager, Binder::getCallingUid);
    }

    @VisibleForTesting
    VmsPublisherService(
            Context context,
            CarStatsService statsService,
            VmsBrokerService brokerService,
            VmsClientManager clientManager,
            IntSupplier getCallingUid) {
        mContext = context;
        mStatsService = statsService;
        mBrokerService = brokerService;
        mClientManager = clientManager;
        mGetCallingUid = getCallingUid;

        mClientManager.setPublisherService(this);
    }

    @Override
    public void init() {}

    @Override
    public void release() {
        mPublisherProxies.values().forEach(PublisherProxy::unregister);
        mPublisherProxies.clear();
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*" + getClass().getSimpleName() + "*");
        writer.println("mPublisherProxies: " + mPublisherProxies.size());
    }

    /**
     * Called when a client connection is established or re-established.
     *
     * @param publisherName    String that uniquely identifies the service and user.
     * @param publisherClient The client's communication channel.
     */
    public void onClientConnected(String publisherName, IVmsPublisherClient publisherClient) {
        if (DBG) Log.d(TAG, "onClientConnected: " + publisherName);
        IBinder publisherToken = new Binder();

        PublisherProxy publisherProxy = new PublisherProxy(publisherName, publisherToken,
                publisherClient);
        publisherProxy.register();
        try {
            publisherClient.setVmsPublisherService(publisherToken, publisherProxy);
        } catch (Throwable e) {
            Log.e(TAG, "unable to configure publisher: " + publisherName, e);
            return;
        }

        PublisherProxy existingProxy = mPublisherProxies.put(publisherName, publisherProxy);
        if (existingProxy != null) {
            existingProxy.unregister();
        }
    }

    /**
     * Called when a client connection is terminated.
     *
     * @param publisherName String that uniquely identifies the service and user.
     */
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

            if (layer == null) {
                return;
            }

            int payloadLength = payload != null ? payload.length : 0;
            mStatsService.getVmsClientLogger(mGetCallingUid.getAsInt())
                    .logPacketSent(layer, payloadLength);

            // Send the message to subscribers
            Set<IVmsSubscriberClient> listeners =
                    mBrokerService.getSubscribersForLayerFromPublisher(layer, publisherId);

            if (DBG) Log.d(TAG, String.format("Number of subscribers: %d", listeners.size()));

            if (listeners.size() == 0) {
                // A negative UID signals that the packet had zero subscribers
                mStatsService.getVmsClientLogger(-1)
                        .logPacketDropped(layer, payloadLength);
            }

            for (IVmsSubscriberClient listener : listeners) {
                int subscriberUid = mClientManager.getSubscriberUid(listener);
                try {
                    listener.onVmsMessageReceived(layer, payload);
                    mStatsService.getVmsClientLogger(subscriberUid)
                            .logPacketReceived(layer, payloadLength);
                } catch (RemoteException ex) {
                    mStatsService.getVmsClientLogger(subscriberUid)
                            .logPacketDropped(layer, payloadLength);
                    String subscriberName = mClientManager.getPackageName(listener);
                    Log.e(TAG, String.format("Unable to publish to listener: %s", subscriberName));
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
            } catch (Throwable e) {
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
