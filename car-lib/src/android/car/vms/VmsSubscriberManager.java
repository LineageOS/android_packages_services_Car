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

import android.annotation.SystemApi;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.CarNotConnectedException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * API for interfacing with the VmsSubscriberService. It supports a single client callback that can
 * (un)subscribe to different layers. Getting notifactions and managing subscriptions is enabled
 * after setting the client callback with #registerClientCallback.
 * SystemApi candidate
 *
 * @hide
 */
@SystemApi
public final class VmsSubscriberManager implements CarManagerBase {
    private static final boolean DBG = true;
    private static final String TAG = "VmsSubscriberManager";

    private final Handler mHandler;
    private final IVmsSubscriberService mVmsSubscriberService;
    private final IVmsSubscriberClient mSubscriberManagerClient;
    private final Object mClientCallbackLock = new Object();
    @GuardedBy("mClientCallbackLock")
    private VmsSubscriberClientCallback mClientCallback;

    /**
     * Interface exposed to VMS subscribers: it is a wrapper of IVmsSubscriberClient.
     */
    public interface VmsSubscriberClientCallback {
        /**
         * Called when the property is updated
         */
        void onVmsMessageReceived(VmsLayer layer, byte[] payload);

        /**
         * Called when layers availability change
         */
        void onLayersAvailabilityChanged(VmsAvailableLayers availableLayers);
    }

    /**
     * Allows to asynchronously dispatch onVmsMessageReceived events.
     */
    private final static class VmsEventHandler extends Handler {
        /**
         * Constants handled in the handler
         */
        private static final int ON_RECEIVE_MESSAGE_EVENT = 0;
        private static final int ON_AVAILABILITY_CHANGE_EVENT = 1;

        private final WeakReference<VmsSubscriberManager> mMgr;

        VmsEventHandler(VmsSubscriberManager mgr, Looper looper) {
            super(looper);
            mMgr = new WeakReference<>(mgr);
        }

        @Override
        public void handleMessage(Message msg) {
            VmsSubscriberManager mgr = mMgr.get();
            switch (msg.what) {
                case ON_RECEIVE_MESSAGE_EVENT:
                    if (mgr != null) {
                        // Parse the message
                        VmsDataMessage vmsDataMessage = (VmsDataMessage) msg.obj;

                        // Dispatch the parsed message
                        mgr.dispatchOnReceiveMessage(vmsDataMessage.getLayer(),
                                vmsDataMessage.getPayload());
                    }
                    break;
                case ON_AVAILABILITY_CHANGE_EVENT:
                    if (mgr != null) {
                        // Parse the message
                        VmsAvailableLayers vmsAvailabilityChangeMessage =
                                (VmsAvailableLayers) msg.obj;

                        // Dispatch the parsed message
                        mgr.dispatchOnAvailabilityChangeMessage(vmsAvailabilityChangeMessage);
                    }
                    break;

                default:
                    Log.e(VmsSubscriberManager.TAG, "Event type not handled:  " + msg.what);
                    break;
            }
        }
    }

    public VmsSubscriberManager(IBinder service, Handler handler) {
        mVmsSubscriberService = IVmsSubscriberService.Stub.asInterface(service);
        mHandler = new VmsEventHandler(this, handler.getLooper());
        mSubscriberManagerClient = new IVmsSubscriberClient.Stub() {
            @Override
            public void onVmsMessageReceived(VmsLayer layer, byte[] payload)
                    throws RemoteException {
                // Create the data message
                VmsDataMessage vmsDataMessage = new VmsDataMessage(layer, payload);
                mHandler.sendMessage(
                        mHandler.obtainMessage(
                                VmsEventHandler.ON_RECEIVE_MESSAGE_EVENT,
                                vmsDataMessage));
            }

            @Override
            public void onLayersAvailabilityChanged(VmsAvailableLayers availableLayers) {
                mHandler.sendMessage(
                        mHandler.obtainMessage(
                                VmsEventHandler.ON_AVAILABILITY_CHANGE_EVENT,
                                availableLayers));
            }
        };
    }

    /**
     * Registers the client callback in order to enable communication with the client.
     * By registering, the client will start getting notifications, and will be able to subscribe
     * to layers.
     * <p>
     *
     * @param clientCallback subscriber callback that will handle onVmsMessageReceived events.
     * @throws IllegalStateException if the client callback was already set.
     */
    public void registerClientCallback(VmsSubscriberClientCallback clientCallback)
            throws CarNotConnectedException {
        synchronized (mClientCallbackLock) {
            if (mClientCallback != null) {
                throw new IllegalStateException("Client callback is already configured.");
            }
            mClientCallback = clientCallback;
        }
        try {
            mVmsSubscriberService.addVmsSubscriberToNotifications(mSubscriberManagerClient);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not connect: ", e);
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Unregisters the client callback which disables communication with the client.
     *
     * @throws CarNotConnectedException, IllegalStateException
     */
    public void unregisterClientCallback()
            throws CarNotConnectedException {

        try {
            mVmsSubscriberService.removeVmsSubscriberToNotifications(mSubscriberManagerClient);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not connect: ", e);
            throw new CarNotConnectedException(e);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Could not unsubscribe from notifications");
            throw e;
        }

        synchronized (mClientCallbackLock) {
            mClientCallback = null;
        }
    }

    /**
     * Returns a serialized publisher information for a publisher ID.
     */
    public byte[] getPublisherInfo(int publisherId)
            throws CarNotConnectedException, IllegalStateException {
        try {
            return mVmsSubscriberService.getPublisherInfo(publisherId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not connect: ", e);
            throw new CarNotConnectedException(e);
        } catch (IllegalStateException ex) {
            Car.checkCarNotConnectedExceptionFromCarService(ex);
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Returns the available layers.
     */
    public VmsAvailableLayers getAvailableLayers()
            throws CarNotConnectedException, IllegalStateException {
        try {
            return mVmsSubscriberService.getAvailableLayers();
        } catch (RemoteException e) {
            Log.e(TAG, "Could not connect: ", e);
            throw new CarNotConnectedException(e);
        } catch (IllegalStateException ex) {
            Car.checkCarNotConnectedExceptionFromCarService(ex);
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Subscribes to listen to the layer specified.
     *
     * @param layer the layer to subscribe to.
     * @throws IllegalStateException if the client callback was not set via
     *                               {@link #registerClientCallback}.
     */
    public void subscribe(VmsLayer layer) throws CarNotConnectedException {
        verifySubscriptionIsAllowed();
        try {
            mVmsSubscriberService.addVmsSubscriber(mSubscriberManagerClient, layer);
            VmsOperationRecorder.get().subscribe(layer);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not connect: ", e);
            throw new CarNotConnectedException(e);
        } catch (IllegalStateException ex) {
            Car.checkCarNotConnectedExceptionFromCarService(ex);
        }
    }

    /**
     * Subscribes to listen to the layer specified from the publisher specified.
     *
     * @param layer       the layer to subscribe to.
     * @param publisherId the publisher of the layer.
     * @throws IllegalStateException if the client callback was not set via
     *                               {@link #registerClientCallback}.
     */
    public void subscribe(VmsLayer layer, int publisherId) throws CarNotConnectedException {
        verifySubscriptionIsAllowed();
        try {
            mVmsSubscriberService.addVmsSubscriberToPublisher(
                    mSubscriberManagerClient, layer, publisherId);
            VmsOperationRecorder.get().subscribe(layer, publisherId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not connect: ", e);
            throw new CarNotConnectedException(e);
        } catch (IllegalStateException ex) {
            Car.checkCarNotConnectedExceptionFromCarService(ex);
        }
    }

    public void startMonitoring() throws CarNotConnectedException {
        verifySubscriptionIsAllowed();
        try {
            mVmsSubscriberService.addVmsSubscriberPassive(mSubscriberManagerClient);
            VmsOperationRecorder.get().startMonitoring();
        } catch (RemoteException e) {
            Log.e(TAG, "Could not connect: ", e);
            throw new CarNotConnectedException(e);
        } catch (IllegalStateException ex) {
            Car.checkCarNotConnectedExceptionFromCarService(ex);
        }
    }

    /**
     * Unsubscribes from the layer/version specified.
     *
     * @param layer the layer to unsubscribe from.
     * @throws IllegalStateException if the client callback was not set via
     *                               {@link #registerClientCallback}.
     */
    public void unsubscribe(VmsLayer layer) {
        verifySubscriptionIsAllowed();
        try {
            mVmsSubscriberService.removeVmsSubscriber(mSubscriberManagerClient, layer);
            VmsOperationRecorder.get().unsubscribe(layer);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to unregister subscriber", e);
            // ignore
        } catch (IllegalStateException ex) {
            Car.hideCarNotConnectedExceptionFromCarService(ex);
        }
    }

    /**
     * Unsubscribes from the layer/version specified.
     *
     * @param layer       the layer to unsubscribe from.
     * @param publisherId the pubisher of the layer.
     * @throws IllegalStateException if the client callback was not set via
     *                               {@link #registerClientCallback}.
     */
    public void unsubscribe(VmsLayer layer, int publisherId) {
        try {
            mVmsSubscriberService.removeVmsSubscriberToPublisher(
                    mSubscriberManagerClient, layer, publisherId);
            VmsOperationRecorder.get().unsubscribe(layer, publisherId);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to unregister subscriber", e);
            // ignore
        } catch (IllegalStateException ex) {
            Car.hideCarNotConnectedExceptionFromCarService(ex);
        }
    }

    public void stopMonitoring() {
        try {
            mVmsSubscriberService.removeVmsSubscriberPassive(mSubscriberManagerClient);
            VmsOperationRecorder.get().stopMonitoring();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to unregister subscriber ", e);
            // ignore
        } catch (IllegalStateException ex) {
            Car.hideCarNotConnectedExceptionFromCarService(ex);
        }
    }

    private void dispatchOnReceiveMessage(VmsLayer layer, byte[] payload) {
        VmsSubscriberClientCallback clientCallback = getClientCallbackThreadSafe();
        if (clientCallback == null) {
            Log.e(TAG, "Cannot dispatch received message.");
            return;
        }
        clientCallback.onVmsMessageReceived(layer, payload);
    }

    private void dispatchOnAvailabilityChangeMessage(VmsAvailableLayers availableLayers) {
        VmsSubscriberClientCallback clientCallback = getClientCallbackThreadSafe();
        if (clientCallback == null) {
            Log.e(TAG, "Cannot dispatch availability change message.");
            return;
        }
        clientCallback.onLayersAvailabilityChanged(availableLayers);
    }

    private VmsSubscriberClientCallback getClientCallbackThreadSafe() {
        VmsSubscriberClientCallback clientCallback;
        synchronized (mClientCallbackLock) {
            clientCallback = mClientCallback;
        }
        if (clientCallback == null) {
            Log.e(TAG, "client callback not set.");
        }
        return clientCallback;
    }

    /*
     * Verifies that the subscriber is in a state where it is allowed to subscribe.
     */
    private void verifySubscriptionIsAllowed() {
        VmsSubscriberClientCallback clientCallback = getClientCallbackThreadSafe();
        if (clientCallback == null) {
            throw new IllegalStateException("Cannot subscribe.");
        }
    }

    /**
     * @hide
     */
    @Override
    public void onCarDisconnected() {
    }

    private static final class VmsDataMessage {
        private final VmsLayer mLayer;
        private final byte[] mPayload;

        public VmsDataMessage(VmsLayer layer, byte[] payload) {
            mLayer = layer;
            mPayload = payload;
        }

        public VmsLayer getLayer() {
            return mLayer;
        }

        public byte[] getPayload() {
            return mPayload;
        }
    }
}
