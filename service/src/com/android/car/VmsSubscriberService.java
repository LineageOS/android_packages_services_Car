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

import android.car.vms.IVmsSubscriberClient;
import android.car.vms.IVmsSubscriberService;
import android.car.vms.VmsAvailableLayers;
import android.car.vms.VmsLayer;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import com.android.car.hal.VmsHalService;
import com.android.car.vms.VmsBrokerService;
import com.android.car.vms.VmsClientManager;

import java.io.PrintWriter;

/**
 * Offers subscriber services by implementing IVmsSubscriberService.Stub.
 */
public class VmsSubscriberService extends IVmsSubscriberService.Stub implements CarServiceBase,
        VmsBrokerService.SubscriberListener {
    private static final String TAG = "VmsSubscriberService";

    private final Context mContext;
    private final VmsBrokerService mBrokerService;
    private final VmsClientManager mClientManager;

    /**
     * Constructor for client manager.
     *
     * @param context           Context to use for registering receivers and binding services.
     * @param brokerService     Service managing the VMS publisher/subscriber state.
     * @param clientManager     Service for monitoring VMS subscriber clients.
     * @param hal               Service providing the HAL client interface
     */
    VmsSubscriberService(Context context, VmsBrokerService brokerService,
            VmsClientManager clientManager, VmsHalService hal) {
        mContext = context;
        mBrokerService = brokerService;
        mClientManager = clientManager;
        mBrokerService.addSubscriberListener(this);
        hal.setVmsSubscriberService(this);
    }

    @Override
    public void init() {}

    @Override
    public void release() {}

    @Override
    public void dump(PrintWriter writer) {
    }

    @Override
    public void addVmsSubscriberToNotifications(IVmsSubscriberClient subscriber) {
        ICarImpl.assertVmsSubscriberPermission(mContext);
        mClientManager.addSubscriber(subscriber);
    }

    @Override
    public void removeVmsSubscriberToNotifications(IVmsSubscriberClient subscriber) {
        ICarImpl.assertVmsSubscriberPermission(mContext);
        mClientManager.removeSubscriber(subscriber);
    }

    @Override
    public void addVmsSubscriber(IVmsSubscriberClient subscriber, VmsLayer layer) {
        ICarImpl.assertVmsSubscriberPermission(mContext);
        mClientManager.addSubscriber(subscriber);
        mBrokerService.addSubscription(subscriber, layer);
    }

    @Override
    public void removeVmsSubscriber(IVmsSubscriberClient subscriber, VmsLayer layer) {
        ICarImpl.assertVmsSubscriberPermission(mContext);
        mBrokerService.removeSubscription(subscriber, layer);
    }

    @Override
    public void addVmsSubscriberToPublisher(IVmsSubscriberClient subscriber,
            VmsLayer layer,
            int publisherId) {
        ICarImpl.assertVmsSubscriberPermission(mContext);
        mClientManager.addSubscriber(subscriber);
        mBrokerService.addSubscription(subscriber, layer, publisherId);
    }

    @Override
    public void removeVmsSubscriberToPublisher(IVmsSubscriberClient subscriber,
            VmsLayer layer,
            int publisherId) {
        ICarImpl.assertVmsSubscriberPermission(mContext);
        mBrokerService.removeSubscription(subscriber, layer, publisherId);
    }

    @Override
    public void addVmsSubscriberPassive(IVmsSubscriberClient subscriber) {
        ICarImpl.assertVmsSubscriberPermission(mContext);
        mClientManager.addSubscriber(subscriber);
        mBrokerService.addSubscription(subscriber);
    }

    @Override
    public void removeVmsSubscriberPassive(IVmsSubscriberClient subscriber) {
        ICarImpl.assertVmsSubscriberPermission(mContext);
        mBrokerService.removeSubscription(subscriber);
    }

    @Override
    public byte[] getPublisherInfo(int publisherId) {
        ICarImpl.assertVmsSubscriberPermission(mContext);
        return mBrokerService.getPublisherInfo(publisherId);
    }

    @Override
    public VmsAvailableLayers getAvailableLayers() {
        ICarImpl.assertVmsSubscriberPermission(mContext);
        return mBrokerService.getAvailableLayers();
    }

    @Override
    public void onLayersAvailabilityChange(VmsAvailableLayers availableLayers) {
        for (IVmsSubscriberClient subscriber : mClientManager.getAllSubscribers()) {
            try {
                subscriber.onLayersAvailabilityChanged(availableLayers);
            } catch (RemoteException e) {
                Log.e(TAG, "onLayersAvailabilityChanged failed: "
                        + mClientManager.getPackageName(subscriber), e);
            }
        }
    }
}
