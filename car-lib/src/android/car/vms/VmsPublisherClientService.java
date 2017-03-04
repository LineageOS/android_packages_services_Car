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

package android.car.vms;


import android.app.Service;
import android.car.annotation.FutureFeature;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * Services that need VMS publisher services need to inherit from this class and also need to be
 * declared in the array vmsPublisherClients located in
 * packages/services/Car/service/res/values/config.xml (most likely, this file will be in an overlay
 * of the target product.
 *
 * The {@link com.android.car.VmsPublisherService} will start this service. The callback
 * {@link #onVmsPublisherServiceReady()} notifies when VMS publisher services (i.e.
 * {@link #publish(int, int, byte[])} and {@link #hasSubscribers(int, int)}) can be used.
 *
 * SystemApi candidate.
 *
 * @hide
 */
@FutureFeature
public abstract class VmsPublisherClientService extends Service {
    private static final boolean DBG = true;
    private static final String TAG = "VmsPublisherClient";

    private Handler mHandler = new VmsEventHandler(this);
    private final VmsPublisherClientBinder mVmsPublisherClient = new VmsPublisherClientBinder(this);
    private volatile IVmsPublisherService mVmsPublisherService = null;

    @Override
    public final IBinder onBind(Intent intent) {
        if (DBG) {
            Log.d(TAG, "onBind, intent: " + intent);
        }
        return mVmsPublisherClient.asBinder();
    }

    @Override
    public final boolean onUnbind(Intent intent) {
        if (DBG) {
            Log.d(TAG, "onUnbind, intent: " + intent);
        }
        stopSelf();
        return super.onUnbind(intent);
    }

    /**
     * Notifies that the publisher services are ready.
     */
    public abstract void onVmsPublisherServiceReady();

    /**
     * Publishers need to implement this method to receive notifications of subscription changes.
     * TODO(antoniocortes): evaluate adding subscriber id to the list of parameters. This should be
     * implemented together with the routing in VmsPublisherService.
     *
     * @param layer          the layer id of this notification.
     * @param version        the layer's version of this notification.
     * @param hasSubscribers if this layer/version has subscribers or not.
     */
    public abstract void onVmsSubscriptionChange(int layer, int version, boolean hasSubscribers);

    /**
     * Uses the VmsPublisherService binder to publish messages.
     *
     * @param layerId   the layer to publish to.
     * @param layerVersion the layer's version to publish to.
     * @param payload the message to be sent.
     * @return if the call to the method VmsPublisherService.publish was successful.
     */
    public final boolean publish(int layerId, int layerVersion, byte[] payload) {
        if (DBG) {
            Log.d(TAG, "Publishing for layer ID: " + layerId + " Version: " + layerVersion);
        }
        if (mVmsPublisherService == null) {
            throw new IllegalStateException("VmsPublisherService not set.");
        }
        try {
            mVmsPublisherService.publish(layerId, layerVersion, payload);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "unable to publish message: " + payload, e);
        }
        return false;
    }

    /**
     * Uses the VmsPublisherService binder to query whether this layer/version has any subscribers.
     *
     * @return true if the call to VmsPublisherService.layerHasSubscribers was successful and the
     * layer/version has subscribers.
     */
    public final boolean hasSubscribers(int layer, int version) {
        if (mVmsPublisherService == null) {
            throw new IllegalStateException("VmsPublisherService not set.");
        }
        try {
            return mVmsPublisherService.hasSubscribers(layer, version);
        } catch (RemoteException e) {
            Log.e(TAG, "unable to invoke binder method.", e);
        }
        return false;
    }

    private void setVmsPublisherService(IVmsPublisherService service) {
        mVmsPublisherService = service;
        onVmsPublisherServiceReady();
    }

    /**
     * Implements the interface that the VMS service uses to communicate with this client.
     */
    private static class VmsPublisherClientBinder extends IVmsPublisherClient.Stub {
        private final WeakReference<VmsPublisherClientService> mVmsPublisherClientService;

        public VmsPublisherClientBinder(VmsPublisherClientService vmsPublisherClientService) {
            mVmsPublisherClientService = new WeakReference<>(vmsPublisherClientService);
        }

        @Override
        public void setVmsPublisherService(IVmsPublisherService service) throws RemoteException {
            VmsPublisherClientService vmsPublisherClientService = mVmsPublisherClientService.get();
            if (vmsPublisherClientService == null) return;
            if (DBG) {
                Log.d(TAG, "setting VmsPublisherService.");
            }
            Handler handler = vmsPublisherClientService.mHandler;
            handler.sendMessage(
                    handler.obtainMessage(VmsEventHandler.SET_SERVICE_CALLBACK, service));
        }

        @Override
        public void onVmsSubscriptionChange(int layer, int version, boolean hasSubscribers)
                throws RemoteException {
            VmsPublisherClientService vmsPublisherClientService = mVmsPublisherClientService.get();
            if (vmsPublisherClientService == null) return;
            if (DBG) {
                Log.d(TAG, "subscription event, layer: " + layer + ", version: " + version
                        + ", hasSubscribers: " + hasSubscribers);
            }
            Handler handler = vmsPublisherClientService.mHandler;
            handler.sendMessage(
                    handler.obtainMessage(VmsEventHandler.ON_SUBSCRIPTION_CHANGE_EVENT,
                            new OnVmsSubscriptionChangeData(layer, version, hasSubscribers)));
        }
    }

    /**
     * Receives events from the binder thread and dispatches them.
     */
    private final static class VmsEventHandler extends Handler {
        /** Constants handled in the handler */
        private static final int ON_SUBSCRIPTION_CHANGE_EVENT = 0;
        private static final int SET_SERVICE_CALLBACK = 1;

        private final WeakReference<VmsPublisherClientService> mVmsPublisherClientService;

        VmsEventHandler(VmsPublisherClientService service) {
            super(Looper.getMainLooper());
            mVmsPublisherClientService = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            VmsPublisherClientService service = mVmsPublisherClientService.get();
            if (service == null) return;
            switch (msg.what) {
                case ON_SUBSCRIPTION_CHANGE_EVENT:
                    OnVmsSubscriptionChangeData data = (OnVmsSubscriptionChangeData) msg.obj;
                    service.onVmsSubscriptionChange(data.getLayer(), data.getVersion(),
                            data.hasSubscribers());
                    break;
                case SET_SERVICE_CALLBACK:
                    service.setVmsPublisherService((IVmsPublisherService) msg.obj);
                    break;
                default:
                    Log.e(TAG, "Event type not handled:  " + msg.what);
                    break;
            }
        }
    }

    /**
     * Used to forward data from the binder thread to the main thread.
     */
    private static final class OnVmsSubscriptionChangeData {
        private int mLayer;
        private int mVersion;
        private boolean mHasSubscribers;

        public OnVmsSubscriptionChangeData(int layer, int version, boolean hasSubscribers) {
            mLayer = layer;
            mVersion = version;
            mHasSubscribers = hasSubscribers;
        }

        public int getLayer() {
            return mLayer;
        }

        public int getVersion() {
            return mVersion;
        }

        public boolean hasSubscribers() {
            return mHasSubscribers;
        }
    }
}
