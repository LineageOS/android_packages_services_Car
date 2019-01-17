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
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import com.android.car.hal.VmsHalService;
import com.android.car.hal.VmsHalService.VmsHalPublisherListener;
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
public class VmsPublisherService extends IVmsPublisherService.Stub implements CarServiceBase {
    private static final boolean DBG = true;
    private static final String TAG = "VmsPublisherService";

    private static final int MSG_HAL_SUBSCRIPTION_CHANGED = 1;

    private final Context mContext;
    private final VmsClientManager mClientManager;
    private final VmsListener mClientListener = new VmsListener();
    private final VmsHalService mHal;
    private final VmsHalPublisherListener mHalPublisherListener;
    private final Map<String, IVmsPublisherClient> mPublisherMap = Collections.synchronizedMap(
            new ArrayMap<>());
    private final Handler mHandler = new EventHandler();

    public VmsPublisherService(Context context, VmsClientManager clientManager, VmsHalService hal) {
        mContext = context;
        mClientManager = clientManager;
        mHal = hal;
        mHalPublisherListener = subscriptionState -> mHandler.sendMessage(
                mHandler.obtainMessage(MSG_HAL_SUBSCRIPTION_CHANGED, subscriptionState));
    }

    @Override
    public void init() {
        mClientManager.registerConnectionListener(mClientListener);
        mHal.addPublisherListener(mHalPublisherListener);
        mHal.signalPublisherServiceIsReady();
    }

    @Override
    public void release() {
        mClientManager.unregisterConnectionListener(mClientListener);
        mHal.removePublisherListener(mHalPublisherListener);
        mPublisherMap.clear();
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*" + getClass().getSimpleName() + "*");
        writer.println("mPublisherMap:" + mPublisherMap.keySet());
    }

    /* Called in arbitrary binder thread */
    @Override
    public void setLayersOffering(IBinder token, VmsLayersOffering offering) {
        mHal.setPublisherLayersOffering(token, offering);
    }

    /* Called in arbitrary binder thread */
    @Override
    public void publish(IBinder token, VmsLayer layer, int publisherId, byte[] payload) {
        if (DBG) {
            Log.d(TAG, "Publishing for layer: " + layer);
        }
        ICarImpl.assertVmsPublisherPermission(mContext);

        // Send the message to application listeners.
        Set<IVmsSubscriberClient> listeners =
                mHal.getSubscribersForLayerFromPublisher(layer, publisherId);

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

        // Send the message to HAL
        if (mHal.isHalSubscribed(layer)) {
            Log.d(TAG, "HAL is subscribed");
            mHal.setDataMessage(layer, payload);
        } else {
            Log.d(TAG, "HAL is NOT subscribed");
        }
    }

    /* Called in arbitrary binder thread */
    @Override
    public VmsSubscriptionState getSubscriptions() {
        ICarImpl.assertVmsPublisherPermission(mContext);
        return mHal.getSubscriptionState();
    }

    /* Called in arbitrary binder thread */
    @Override
    public int getPublisherId(byte[] publisherInfo) {
        ICarImpl.assertVmsPublisherPermission(mContext);
        return mHal.getPublisherId(publisherInfo);
    }

    /**
     * This method is only invoked by VmsHalService.notifyPublishers which is synchronized.
     * Therefore this method only sees a non-decreasing sequence.
     */
    private void handleHalSubscriptionChanged(VmsSubscriptionState subscriptionState) {
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

    private class EventHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_HAL_SUBSCRIPTION_CHANGED:
                    handleHalSubscriptionChanged((VmsSubscriptionState) msg.obj);
                    return;
            }
            super.handleMessage(msg);
        }
    }
}
