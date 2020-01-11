/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.car.Car;
import android.car.vms.VmsClientManager.VmsClientCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * API implementation for use by Vehicle Map Service clients.
 *
 * This API can be obtained by registering a callback with {@link VmsClientManager}.
 *
 * @hide
 */
public final class VmsClient {
    private static final boolean DBG = false;
    private static final String TAG = VmsClient.class.getSimpleName();

    private static final VmsAvailableLayers DEFAULT_AVAILABLE_LAYERS =
            new VmsAvailableLayers(Collections.emptySet(), 0);
    private static final VmsSubscriptionState DEFAULT_SUBSCRIPTIONS =
            new VmsSubscriptionState(0, Collections.emptySet(), Collections.emptySet());

    private final IVmsBrokerService mService;
    private final Executor mExecutor;
    private final VmsClientCallback mCallback;
    private final Consumer<RemoteException> mExceptionHandler;
    private final IBinder mClientToken;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private VmsAvailableLayers mAvailableLayers = DEFAULT_AVAILABLE_LAYERS;
    @GuardedBy("mLock")
    private VmsSubscriptionState mSubscriptionState = DEFAULT_SUBSCRIPTIONS;
    @GuardedBy("mLock")
    private boolean mMonitoringEnabled;

    VmsClient(IVmsBrokerService service, Executor executor, VmsClientCallback callback,
            Consumer<RemoteException> exceptionHandler) {
        mService = service;
        mExecutor = executor;
        mCallback = callback;
        mExceptionHandler = exceptionHandler;
        mClientToken = new Binder();
    }

    /**
     * Retrieves registered information about a Vehicle Map Service data provider.
     *
     * <p>Data layers may be associated with multiple providers in updates received via
     * {@link VmsClientCallback#onLayerAvailabilityChanged(VmsAvailableLayers)}, and clients can use
     * this query to determine a provider or subset of providers to subscribe to.
     *
     * @param providerId Provider ID
     * @return Provider's registration information, or null if unavailable
     */
    @Nullable
    @RequiresPermission(anyOf = {Car.PERMISSION_VMS_PUBLISHER, Car.PERMISSION_VMS_SUBSCRIBER})
    public byte[] getProviderDescription(int providerId) {
        if (DBG) Log.d(TAG, "Getting provider information for " + providerId);
        try {
            return mService.getProviderInfo(mClientToken, providerId).getDescription();
        } catch (RemoteException e) {
            Log.e(TAG, "While getting publisher information for " + providerId, e);
            mExceptionHandler.accept(e);
            return null;
        }
    }

    /**
     * Sets this client's data layer subscriptions
     *
     * <p>Existing subscriptions for the client will be replaced.
     *
     * @param layers Data layers to be subscribed
     */
    @RequiresPermission(Car.PERMISSION_VMS_SUBSCRIBER)
    public void setSubscriptions(@NonNull Set<VmsAssociatedLayer> layers) {
        if (DBG) Log.d(TAG, "Setting subscriptions to " + layers);
        try {
            mService.setSubscriptions(mClientToken, new ArrayList<>(layers));
        } catch (RemoteException e) {
            Log.e(TAG, "While setting subscriptions", e);
            mExceptionHandler.accept(e);
        }
    }

    /**
     * Enables the monitoring of Vehicle Map Service messages by this client.
     *
     * <p>If monitoring is enabled, the client will receive all messages, regardless of any
     * subscriptions. Enabling monitoring does not affect the client's existing subscriptions.
     */
    @RequiresPermission(Car.PERMISSION_VMS_SUBSCRIBER)
    public void setMonitoringEnabled(boolean enabled) {
        if (DBG) Log.d(TAG, "Setting monitoring state to " + enabled);
        try {
            mService.setMonitoringEnabled(mClientToken, enabled);
            synchronized (mLock) {
                mMonitoringEnabled = enabled;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "While setting monitoring state to " + enabled, e);
            mExceptionHandler.accept(e);
        }
    }

    /**
     * Disables the monitoring of Vehicle Map Service messages by this client.
     *
     * <p>If monitoring is disabled, this client will receive only messages for its subscriptions.
     * Disabling monitoring does not affect the client's existing subscriptions.
     */
    @RequiresPermission(Car.PERMISSION_VMS_SUBSCRIBER)
    public boolean isMonitoringEnabled() {
        synchronized (mLock) {
            return mMonitoringEnabled;
        }
    }

    /**
     * Returns the most recently received data layer availability.
     */
    @NonNull
    @RequiresPermission(anyOf = {Car.PERMISSION_VMS_PUBLISHER, Car.PERMISSION_VMS_SUBSCRIBER})
    public VmsAvailableLayers getAvailableLayers() {
        synchronized (mLock) {
            return mAvailableLayers;
        }
    }

    /**
     * Registers a data provider with the Vehicle Map Service.
     *
     * @param providerDescription Identifying information about the data provider
     * @return Provider ID to use for setting offerings and publishing messages, or -1 on
     * connection error
     */
    @RequiresPermission(Car.PERMISSION_VMS_PUBLISHER)
    public int registerProvider(@NonNull byte[] providerDescription) {
        if (DBG) Log.d(TAG, "Registering provider");
        Objects.requireNonNull(providerDescription, "providerDescription cannot be null");
        try {
            return mService.registerProvider(mClientToken,
                    new VmsProviderInfo(providerDescription));
        } catch (RemoteException e) {
            Log.e(TAG, "While registering provider", e);
            mExceptionHandler.accept(e);
            return -1;
        }
    }

    /**
     * Sets the data layer offerings for a provider registered through
     * {@link #registerProvider(byte[])}.
     *
     * <p>Existing offerings for the provider ID will be replaced.
     *
     * @param providerId Provider ID
     * @param offerings  Data layer offerings
     * @throws IllegalArgumentException if the client has not registered the provider
     */
    @RequiresPermission(Car.PERMISSION_VMS_PUBLISHER)
    public void setProviderOfferings(int providerId, @NonNull Set<VmsLayerDependency> offerings) {
        if (DBG) Log.d(TAG, "Setting provider offerings for " + providerId);
        Objects.requireNonNull(offerings, "offerings cannot be null");
        try {
            mService.setProviderOfferings(mClientToken, providerId, new ArrayList<>(offerings));
        } catch (RemoteException e) {
            Log.e(TAG, "While setting provider offerings for " + providerId, e);
            mExceptionHandler.accept(e);
        }
    }

    /**
     * Publishes a data message.
     *
     * @param providerId Message provider
     * @param layer      Message layer
     * @param message    Message payload
     * @throws IllegalArgumentException if the client does not offer the layer as the provider
     */
    @RequiresPermission(Car.PERMISSION_VMS_PUBLISHER)
    public void publish(int providerId, @NonNull VmsLayer layer, @NonNull byte[] message) {
        if (DBG) Log.d(TAG, "Publishing packet as " + providerId);
        Objects.requireNonNull(layer, "layer cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        try {
            mService.publish(mClientToken, providerId, layer, message);
        } catch (RemoteException e) {
            Log.e(TAG, "While publishing packet as " + providerId);
            mExceptionHandler.accept(e);
        }
    }

    /**
     * Returns the most recently received data layer subscription state.
     */
    @NonNull
    @RequiresPermission(anyOf = {Car.PERMISSION_VMS_PUBLISHER, Car.PERMISSION_VMS_SUBSCRIBER})
    public VmsSubscriptionState getSubscriptionState() {
        synchronized (mLock) {
            return mSubscriptionState;
        }
    }

    /**
     * Registers this client with the Vehicle Map Service.
     */
    void register() throws RemoteException {
        VmsRegistrationInfo registrationInfo = mService.registerClient(mClientToken,
                new IVmsClientCallbackImpl(this));
        synchronized (mLock) {
            mAvailableLayers = registrationInfo.getAvailableLayers();
            mSubscriptionState = registrationInfo.getSubscriptionState();
        }
    }

    /**
     * Unregisters this client from the Vehicle Map Service.
     */
    void unregister() throws RemoteException {
        mService.unregisterClient(mClientToken);
    }

    private static class IVmsClientCallbackImpl extends IVmsClientCallback.Stub {
        private final WeakReference<VmsClient> mClient;

        private IVmsClientCallbackImpl(VmsClient client) {
            mClient = new WeakReference<>(client);
        }

        @Override
        public void onLayerAvailabilityChanged(VmsAvailableLayers availableLayers) {
            if (DBG) Log.d(TAG, "Received new layer availability: " + availableLayers);
            executeCallback((client, callback) -> {
                synchronized (client.mLock) {
                    client.mAvailableLayers = availableLayers;
                }
                callback.onLayerAvailabilityChanged(availableLayers);
            });
        }

        @Override
        public void onSubscriptionStateChanged(VmsSubscriptionState subscriptionState) {
            if (DBG) Log.d(TAG, "Received new subscription state: " + subscriptionState);
            executeCallback((client, callback) -> {
                synchronized (client.mLock) {
                    client.mSubscriptionState = subscriptionState;
                }
                callback.onSubscriptionStateChanged(subscriptionState);
            });
        }

        @Override
        public void onMessageReceived(int providerId, VmsLayer layer, byte[] message) {
            if (DBG) Log.d(TAG, "Received message from " + providerId + " for: " + layer);
            executeCallback((client, callback) ->
                    callback.onMessageReceived(providerId, layer, message));
        }

        private void executeCallback(BiConsumer<VmsClient, VmsClientCallback> callbackOperation) {
            final VmsClient client = mClient.get();
            if (client == null) {
                Log.w(TAG, "VmsClient unavailable");
                return;
            }

            long token = Binder.clearCallingIdentity();
            try {
                client.mExecutor.execute(() -> callbackOperation.accept(client, client.mCallback));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
