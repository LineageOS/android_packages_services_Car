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

import com.android.car.hal.VmsHalService;
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
public class VmsPublisherService extends IVmsPublisherService.Stub implements CarServiceBase,
        VmsBrokerService.PublisherListener {
    private static final boolean DBG = true;
    private static final String TAG = "VmsPublisherService";

    private final Context mContext;
    private final VmsClientManager mClientManager;
    private final VmsBrokerService mBrokerService;
    private final VmsHalService mHal;
    private final VmsListener mClientListener = new VmsListener();
    private final Map<String, IVmsPublisherClient> mPublisherMap = Collections.synchronizedMap(
            new ArrayMap<>());

    public VmsPublisherService(Context context, VmsBrokerService brokerService,
            VmsClientManager clientManager,
            VmsHalService hal) {
        mContext = context;
        mClientManager = clientManager;
        mBrokerService = brokerService;
        mHal = hal;
    }

    @Override
    public void init() {
        mClientListener.onClientConnected("VmsHalService", mHal.getPublisherClient());
        mClientManager.registerConnectionListener(mClientListener);
        mBrokerService.addPublisherListener(this);
    }

    @Override
    public void release() {
        mClientListener.onClientDisconnected("VmsHalService");
        mClientManager.unregisterConnectionListener(mClientListener);
        mBrokerService.removePublisherListener(this);
        mPublisherMap.clear();
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*" + getClass().getSimpleName() + "*");
        writer.println("mPublisherMap:" + mPublisherMap.keySet());
    }

    @Override
    public void setLayersOffering(IBinder token, VmsLayersOffering offering) {
        ICarImpl.assertVmsPublisherPermission(mContext);
        mBrokerService.setPublisherLayersOffering(token, offering);
    }

    @Override
    public void publish(IBinder token, VmsLayer layer, int publisherId, byte[] payload) {
        if (DBG) {
            Log.d(TAG, "Publishing for layer: " + layer);
        }
        ICarImpl.assertVmsPublisherPermission(mContext);

        // Send the message to application listeners.
        Set<IVmsSubscriberClient> listeners =
                mBrokerService.getSubscribersForLayerFromPublisher(layer, publisherId);

        if (DBG) {
            Log.d(TAG, "Number of subscribed apps: " + listeners.size());
        }
        for (IVmsSubscriberClient listener : listeners) {
            try {
                listener.onVmsMessageReceived(layer, payload);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to publish to listener: " + listener);
            }
        }
    }

    @Override
    public VmsSubscriptionState getSubscriptions() {
        ICarImpl.assertVmsPublisherPermission(mContext);
        return mBrokerService.getSubscriptionState();
    }

    @Override
    public int getPublisherId(byte[] publisherInfo) {
        ICarImpl.assertVmsPublisherPermission(mContext);
        return mBrokerService.getPublisherId(publisherInfo);
    }

    @Override
    public void onSubscriptionChange(VmsSubscriptionState subscriptionState) {
        // Send the message to application listeners.
        synchronized (mPublisherMap) {
            for (IVmsPublisherClient client : mPublisherMap.values()) {
                try {
                    client.onVmsSubscriptionChange(subscriptionState);
                } catch (RemoteException ex) {
                    Log.e(TAG, "unable to send notification to: " + client, ex);
                }
            }
        }
    }

    private class VmsListener implements VmsClientManager.ConnectionListener {
        /**
         * Once the manager binds to a publisher client, the client's binder is added to
         * {@code mPublisherMap} and the client is configured to use this service.
         */
        @Override
        public void onClientConnected(String publisherName, IBinder binder) {
            if (DBG) Log.d(TAG, "onClientConnected: " + publisherName);
            IVmsPublisherClient service = IVmsPublisherClient.Stub.asInterface(binder);
            mPublisherMap.put(publisherName, service);
            try {
                service.setVmsPublisherService(new Binder(), VmsPublisherService.this);
            } catch (RemoteException e) {
                Log.e(TAG, "unable to configure publisher: " + publisherName, e);
            }
        }

        /**
         * Removes disconnected clients from {@code mPublisherMap}.
         */
        @Override
        public void onClientDisconnected(String publisherName) {
            if (DBG) Log.d(TAG, "onClientDisconnected: " + publisherName);
            mPublisherMap.remove(publisherName);
        }
    }
}
