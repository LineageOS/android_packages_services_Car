/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.car.occupantconnection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.Service;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.annotation.ApiRequirements;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.List;

/**
 * A service used to respond to connection requests from peer clients on other occupant zones,
 * receive {@link Payload} from peer clients, cache the received Payload, and dispatch it to the
 * receiver endpoints in this client.
 * <p>
 * The client app must extend this service to receiver Payload from peer clients.
 * This service lives inside the client app, and is a singleton for the client app.
 * The lifecycle of this service is managed by car service ({@link
 * com.android.car.occupantconnection.CarOccupantConnectionService}).
 * <p>
 * This service can be started and bound by car service in two ways:
 * <ul>
 *   <li> A sender endpoint in the peer client calls {@link
 *        CarOccupantConnectionManager#requestConnection} to connect to this client.
 *   <li> A receiver endpoint in this client calls {@link
 *        CarOccupantConnectionManager#registerReceiver}.
 * </ul>
 * <p>
 * Once all the senders have disconnected from this client and there is no receiver endpoints
 * registered in this client, this service will be unbound and destroyed automatically.
 * <p>
 * When this service is crashed, all connections to this client will be terminated. As a result,
 * all senders that were connected to this client will be notified via {@link
 * CarOccupantConnectionManager.ConnectionRequestCallback#onDisconnected}. In addition, the cached
 * Payload will be lost, if any. The senders are responsible for resending the Payload if needed.
 *
 * @hide
 */
@SystemApi
public abstract class AbstractReceiverService extends Service {

    private IBackendConnectionResponder mBackendConnectionResponder;

    private final IBackendReceiver.Stub mBackendReceiver = new IBackendReceiver.Stub() {
        // There is no need to accept an Executor here because the callback is supposed to run
        // on main thread.
        @SuppressLint("ExecutorRegistration")
        @Override
        public void registerReceiver(String receiverEndpointId, IPayloadCallback callback) {
            // TODO(b/257117236): implement this method.
        }

        @Override
        public void unregisterReceiver(String receiverEndpointId) {
            // TODO(b/257117236): implement this method.
        }

        // There is no need to accept an Executor here because the callback is supposed to run
        // on main thread.
        @SuppressLint("ExecutorRegistration")
        @Override
        public void registerBackendConnectionResponder(IBackendConnectionResponder responder) {
            mBackendConnectionResponder = responder;
        }

        @Override
        public void onPayloadReceived(OccupantZoneInfo senderZone, Payload payload) {
            AbstractReceiverService.this.onPayloadReceived(senderZone, payload);
        }

        @Override
        public void onConnectionInitiated(OccupantZoneInfo senderZone) {
            AbstractReceiverService.this.onConnectionInitiated(senderZone);
        }

        @Override
        public void onConnected(OccupantZoneInfo senderZone) {
            AbstractReceiverService.this.onConnected(senderZone);
        }

        @Override
        public void onConnectionCanceled(OccupantZoneInfo senderZone) {
            AbstractReceiverService.this.onConnectionCanceled(senderZone);
        }

        @Override
        public void onDisconnected(OccupantZoneInfo senderZone) {
            AbstractReceiverService.this.onDisconnected(senderZone);
        }
    };

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return mBackendReceiver.asBinder();
    }

    /**
     * Invoked when this service has received {@code payload} from its peer client on
     * {@code senderZone}.
     * <p>
     * The inheritance of this service should override this method to
     * <ul>
     *   <li> forward the {@code payload} to the corresponding receiver endpoint(s), if any, and/or
     *   <li> cache the {@code payload}, then dispatch it when a new receiver endpoint is
     *        registered. The inheritance should clear the cache once it is no longer needed.
     * </ul>
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public abstract void onPayloadReceived(@NonNull OccupantZoneInfo senderZone,
            @NonNull Payload payload);

    /**
     * Invoked when a receiver endpoint is registered.
     * <p>
     * In this method, the inheritance of this service can forward the cached Payload (if any) to
     * the newly registered endpoint.
     *
     * @param receiverEndpointId the ID of the newly registered endpoint
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public abstract void onReceiverRegistered(@NonNull String receiverEndpointId);

    /**
     * Invoked when the sender endpoint on {@code senderZone} has requested a connection to this
     * client.
     * <p>
     * When the connection is initiated, the inheritance of this service can call {@link
     * #acceptConnection} or {@link #rejectConnection}.
     * <ul>
     *   <li> If user confirmation is not needed to establish the connection, the inheritance can
     *        just call {@link #acceptConnection}.
     *   <li> Otherwise, the inheritance can call {@link
     *        android.app.Activity#startActivityForResult} to launch a permission activity, and call
     *        {@link #acceptConnection} or {@link #rejectConnection} based on the activity result.
     *        For driving safety, the permission activity must be distraction optimized. If this
     *        is infeasible, the inheritance should just call {@link #rejectConnection}.
     * </ul>
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public abstract void onConnectionInitiated(@NonNull OccupantZoneInfo senderZone);

    /**
     * Invoked when the one-way connection has been established.
     * <p>
     * In order to establish the connection, the inheritance of this service must call
     * {@link #acceptConnection}, and the sender must NOT call {@link
     * android.car.occupantconnection.CarOccupantConnectionManager#cancelConnection} before the
     * connection is established.
     * <p>
     * Once the connection is established, the sender can send {@link Payload} to this client.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public abstract void onConnected(@NonNull OccupantZoneInfo senderZone);

    /** Invoked when the connection request has been canceled by the sender. */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public abstract void onConnectionCanceled(@NonNull OccupantZoneInfo senderZone);

    /**
     * Invoked when the connection is terminated. For example, the sender on {@code senderZone}
     * has called {@link android.car.occupantconnection.CarOccupantConnectionManager#disconnect},
     * or the sender has become unreachable.
     * <p>
     * When disconnected, the sender can no longer send {@link Payload} to this client.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public abstract void onDisconnected(@NonNull OccupantZoneInfo senderZone);

    /** Accepts the connection request from {@code senderZone}. */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void acceptConnection(@NonNull OccupantZoneInfo senderZone) {
        try {
            mBackendConnectionResponder.acceptConnection(senderZone);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Rejects the connection request from {@code senderZone}.
     *
     * @param rejectionReason the reason for rejection, such as user rejected, UX restricted.
     *                        The client app is responsible for defining this value
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void rejectConnection(@NonNull OccupantZoneInfo senderZone, int rejectionReason) {
        try {
            mBackendConnectionResponder.rejectConnection(senderZone, rejectionReason);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Forwards the {@code payload} to the given receiver endpoint in this client.
     * <p>
     * Note: different receiver endpoints in the same client app are identified by their IDs,
     * while different sender endpoints in the same client app are treated as the same sender.
     * If the senders need to differentiate themselves, they can put the identity info into the
     * {@code payload} it sends.
     *
     * @param senderZone         the occupant zone that the Payload was sent from
     * @param receiverEndpointId the ID of the receiver endpoint
     * @param payload            the Payload
     * @return whether the Payload has been forwarded to the receiver endpoint
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public boolean forwardPayload(@NonNull OccupantZoneInfo senderZone,
            @NonNull String receiverEndpointId,
            @NonNull Payload payload) {
        // TODO(b/257117236): implement this method.
        return true;
    }

    /**
     * Returns a list containing all the IDs of the receiver endpoints. Returns an empty list if
     * there is no receiver endpoint registered.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @NonNull
    public List<String> getAllReceiverEndpoints() {
        // TODO(b/257117236): implement this method.
        return null;
    }

    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @Override
    public int onStartCommand(@NonNull Intent intent, int flags, int startId) {
        return START_STICKY;
    }
}
