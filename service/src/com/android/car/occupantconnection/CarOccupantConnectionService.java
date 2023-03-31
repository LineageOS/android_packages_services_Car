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

package com.android.car.occupantconnection;

import static android.car.Car.CAR_INTENT_ACTION_RECEIVER_SERVICE;
import static android.car.CarOccupantZoneManager.INVALID_USER_ID;
import static android.car.CarRemoteDeviceManager.FLAG_CLIENT_INSTALLED;
import static android.car.CarRemoteDeviceManager.FLAG_CLIENT_IN_FOREGROUND;
import static android.car.CarRemoteDeviceManager.FLAG_CLIENT_RUNNING;
import static android.car.CarRemoteDeviceManager.FLAG_CLIENT_SAME_SIGNATURE;
import static android.car.CarRemoteDeviceManager.FLAG_CLIENT_SAME_VERSION;
import static android.car.occupantconnection.CarOccupantConnectionManager.CONNECTION_ERROR_UNKNOWN;

import static com.android.car.CarServiceUtils.assertPermission;
import static com.android.car.CarServiceUtils.checkCalledByPackage;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.Nullable;
import android.car.Car;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.builtin.util.Slogf;
import android.car.occupantconnection.IBackendConnectionResponder;
import android.car.occupantconnection.IBackendReceiver;
import android.car.occupantconnection.ICarOccupantConnection;
import android.car.occupantconnection.IConnectionRequestCallback;
import android.car.occupantconnection.IPayloadCallback;
import android.car.occupantconnection.IStateCallback;
import android.car.occupantconnection.Payload;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.car.CarOccupantZoneService;
import com.android.car.CarServiceBase;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.BinderKeyValueContainer;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.power.CarPowerManagementService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Set;

/**
 * Service to implement API defined in
 * {@link android.car.occupantconnection.CarOccupantConnectionManager} and
 * {@link android.car.CarRemoteDeviceManager}.
 */
public class CarOccupantConnectionService extends ICarOccupantConnection.Stub implements
        CarServiceBase {

    private static final String TAG = CarOccupantConnectionService.class.getSimpleName();
    private static final String INDENTATION_2 = "  ";
    private static final String INDENTATION_4 = "    ";

    private final Context mContext;
    private final Object mLock = new Object();
    private final CarOccupantZoneService mOccupantZoneService;
    private final CarPowerManagementService mPowerManagementService;

    /**
     * A set of receiver services that this service has requested to bind but has not connected
     * yet. Once a receiver service is connected, it will be removed from this set and put into
     * {@link #mConnectedReceiverServiceMap}.
     */
    @GuardedBy("mLock")
    private final ArraySet<ClientId> mConnectingReceiverServices;

    /**
     * A map of connected receiver services. The key is the clientId of the receiver service,
     * while the value is to the binder of the receiver service.
     */
    @GuardedBy("mLock")
    private final BinderKeyValueContainer<ClientId, IBackendReceiver>
            mConnectedReceiverServiceMap;

    /** A map of receiver services to their ServiceConnections. */
    @GuardedBy("mLock")
    private final ArrayMap<ClientId, ServiceConnection> mReceiverServiceConnectionMap;

    /**
     * A map of receiver endpoints to be registered when the {@link
     * android.car.occupantconnection.AbstractReceiverService} is connected. The key is its ID,
     * and the value is its IPayloadCallback. When a receiver endpoint is registered successfully,
     * it will be removed from this map and added into {@link #mRegisteredReceiverEndpointMap}.
     */
    @GuardedBy("mLock")
    private final BinderKeyValueContainer<ReceiverEndpointId, IPayloadCallback>
            mPreregisteredReceiverEndpointMap;

    /**
     * A map of receiver endpoints that have been registered into the {@link
     * android.car.occupantconnection.AbstractReceiverService}. The key is its ID,
     * and the value is its IPayloadCallback.
     * <p>
     * Once this service has registered the receiver endpoint into the receiver service, it still
     * stores the receiver endpoint ID and callback in this map.
     * It stores the ID to avoid registering duplicate IDs (see {@link
     * #assertNoDuplicateReceiverEndpointLocked}) and decides when to unbind the receiver service.
     * If the receiver client crashes, it needs to remove the stale ID (otherwise it will be in a
     * broken state permanently). To do that, it stores the callback in
     * this BinderKeyValueContainer, thus the stale ID will be removed automatically once the
     * callback dies.
     */
    @GuardedBy("mLock")
    private final BinderKeyValueContainer<ReceiverEndpointId, IPayloadCallback>
            mRegisteredReceiverEndpointMap;

    /**
     * A map of connection requests that have not received any response from the receiver app yet.
     * The request was not responded because the {@link
     * android.car.occupantconnection.AbstractReceiverService} in the receiver app was not bound,
     * or was bound but didn't respond to the request yet.
     * The key is its ID, and the value is its IConnectionRequestCallback.
     * <p>
     * When a connection request has been responded by the receiver, the request will be
     * removed from this map; what's more, if the response is acceptation, the request
     * will be added into {@link #mAcceptedConnectionRequestMap}.
     */
    @GuardedBy("mLock")
    private final BinderKeyValueContainer<ConnectionId, IConnectionRequestCallback>
            mPendingConnectionRequestMap;

    /**
     * A map of accepted connection requests. The key is its ID, and the value is its
     * IConnectionRequestCallback.
     */
    @GuardedBy("mLock")
    private final BinderKeyValueContainer<ConnectionId, IConnectionRequestCallback>
            mAcceptedConnectionRequestMap;

    // TODO(b/257117236): update this map when the sender dies.
    /** A set of established connection records. */
    @GuardedBy("mLock")
    private final ArraySet<ConnectionRecord> mEstablishedConnections;

    /**
     * A class to handle the connection to {@link
     * android.car.occupantconnection.AbstractReceiverService} in the receiver app.
     */
    private final class ReceiverServiceConnection implements ServiceConnection {

        private final ClientId mReceiverClient;
        private final IBackendConnectionResponder mResponder;
        @Nullable
        private IBackendReceiver mReceiverService;

        private ReceiverServiceConnection(ClientId receiverClient) {
            mReceiverClient = receiverClient;
            mResponder = new IBackendConnectionResponder.Stub() {
                @Override
                public void acceptConnection(OccupantZoneInfo senderZone) {
                    ClientId senderClient = getClientIdInOccupantZone(senderZone,
                            receiverClient.packageName);
                    if (senderClient == null) {
                        // senderClient can't be null because it requested a connection, but let's
                        // be cautious.
                        return;
                    }
                    ConnectionId connectionId = new ConnectionId(senderClient, receiverClient);
                    synchronized (mLock) {
                        IConnectionRequestCallback callback =
                                extractRequestCallbackToNotifyLocked(senderZone, receiverClient);
                        if (callback == null) {
                            return;
                        }
                        if (mReceiverService == null) {
                            // mReceiverService can't be null because mResponder is registered
                            // after onServiceConnected() in invoked, where mReceiverService will
                            // be initialized to a non-null value. But let's be cautious.
                            Slogf.wtf(TAG, "The receiver service accepted the connection request"
                                    + " but mReceiverService is null: " + mReceiverClient);
                            return;
                        }
                        try {
                            // Both the sender and receiver should be notified for connection
                            // success.
                            callback.onConnected(receiverClient.occupantZone);
                            mReceiverService.onConnected(senderZone);

                            mAcceptedConnectionRequestMap.put(connectionId, callback);
                            mEstablishedConnections.add(new ConnectionRecord(
                                    receiverClient.packageName,
                                    senderZone.zoneId,
                                    receiverClient.occupantZone.zoneId));
                        } catch (RemoteException e) {
                            Slogf.e(TAG, "Failed to notify connection success", e);
                        }
                    }
                }

                @Override
                public void rejectConnection(OccupantZoneInfo senderZone, int rejectionReason) {
                    synchronized (mLock) {
                        IConnectionRequestCallback callback =
                                extractRequestCallbackToNotifyLocked(senderZone, receiverClient);
                        if (callback == null) {
                            return;
                        }
                        try {
                            // Only the sender needs to be notified for connection rejection
                            // since the connection was rejected by the receiver.
                            callback.onRejected(receiverClient.occupantZone, rejectionReason);
                        } catch (RemoteException e) {
                            Slogf.e(TAG, "Failed to notify the sender for connection"
                                    + " rejection", e);
                        }
                    }
                }
            };
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Slogf.v(TAG, "onServiceConnected " + service);
            mReceiverService = IBackendReceiver.Stub.asInterface(service);
            try {
                mReceiverService.registerBackendConnectionResponder(mResponder);
            } catch (RemoteException e) {
                Slogf.e(TAG, "Failed to register IBackendConnectionResponder", e);
            }

            synchronized (mLock) {
                // Update receiver service maps.
                mConnectedReceiverServiceMap.put(mReceiverClient, mReceiverService);
                mConnectingReceiverServices.remove(mReceiverClient);

                // Register cached callbacks into AbstractReceiverService, and update receiver
                // endpoint maps.
                registerPreregisteredReceiverEndpointsLocked(mReceiverService, mReceiverClient);

                // If there are cached connection requests, notify the AbstractReceiverService now.
                sendCachedConnectionRequestLocked(mReceiverService, mReceiverClient);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Slogf.v(TAG, "onServiceDisconnected " + name);
            mReceiverService = null;
            synchronized (mLock) {
                mConnectingReceiverServices.remove(mReceiverClient);
                mConnectedReceiverServiceMap.remove(mReceiverClient);
                mReceiverServiceConnectionMap.remove(mReceiverClient);

                for (int i = mPreregisteredReceiverEndpointMap.size() - 1; i >= 0; i--) {
                    ReceiverEndpointId receiverEndpoint =
                            mPreregisteredReceiverEndpointMap.keyAt(i);
                    if (receiverEndpoint.clientId.equals(mReceiverClient)) {
                        mPreregisteredReceiverEndpointMap.removeAt(i);
                    }
                }

                for (int i = mRegisteredReceiverEndpointMap.size() - 1; i >= 0; i--) {
                    ReceiverEndpointId receiverEndpoint =
                            mRegisteredReceiverEndpointMap.keyAt(i);
                    if (receiverEndpoint.clientId.equals(mReceiverClient)) {
                        mRegisteredReceiverEndpointMap.removeAt(i);
                    }
                }

                notifySenderOfReceiverServiceDisconnect(mPendingConnectionRequestMap,
                        mReceiverClient);
                notifySenderOfReceiverServiceDisconnect(mAcceptedConnectionRequestMap,
                        mReceiverClient);

                for (int i = mEstablishedConnections.size() - 1; i >= 0; i--) {
                    ConnectionRecord connectionRecord = mEstablishedConnections.valueAt(i);
                    if (connectionRecord.packageName.equals(mReceiverClient.packageName)
                            && connectionRecord.receiverZoneId
                            == mReceiverClient.occupantZone.zoneId) {
                        mEstablishedConnections.removeAt(i);
                    }
                }
            }
        }
    }

    public CarOccupantConnectionService(Context context,
            CarOccupantZoneService occupantZoneService,
            CarPowerManagementService powerManagementService) {
        this(context,
                occupantZoneService,
                powerManagementService,
                /* connectingReceiverServices= */ new ArraySet<>(),
                /* connectedReceiverServiceMap= */ new BinderKeyValueContainer<>(),
                /* receiverServiceConnectionMap= */ new ArrayMap<>(),
                /* preregisteredReceiverEndpointMap= */ new BinderKeyValueContainer<>(),
                /* registeredReceiverEndpointMap= */ new BinderKeyValueContainer<>(),
                /* pendingConnectionRequestMap= */ new BinderKeyValueContainer<>(),
                /* acceptedConnectionRequestMap= */ new BinderKeyValueContainer<>(),
                /* establishConnections= */ new ArraySet<>());
    }

    @VisibleForTesting
    CarOccupantConnectionService(Context context,
            CarOccupantZoneService occupantZoneService,
            CarPowerManagementService powerManagementService,
            ArraySet<ClientId> connectingReceiverServices,
            BinderKeyValueContainer<ClientId, IBackendReceiver> connectedReceiverServiceMap,
            ArrayMap<ClientId, ServiceConnection> receiverServiceConnectionMap,
            BinderKeyValueContainer<ReceiverEndpointId, IPayloadCallback>
                    preregisteredReceiverEndpointMap,
            BinderKeyValueContainer<ReceiverEndpointId, IPayloadCallback>
                    registeredReceiverEndpointMap,
            BinderKeyValueContainer<ConnectionId, IConnectionRequestCallback>
                    pendingConnectionRequestMap,
            BinderKeyValueContainer<ConnectionId, IConnectionRequestCallback>
                    acceptedConnectionRequestMap,
            ArraySet<ConnectionRecord> establishedConnections) {
        mContext = context;
        mOccupantZoneService = occupantZoneService;
        mPowerManagementService = powerManagementService;
        mConnectingReceiverServices = connectingReceiverServices;
        mConnectedReceiverServiceMap = connectedReceiverServiceMap;
        mReceiverServiceConnectionMap = receiverServiceConnectionMap;
        mPreregisteredReceiverEndpointMap = preregisteredReceiverEndpointMap;
        mRegisteredReceiverEndpointMap = registeredReceiverEndpointMap;
        mPendingConnectionRequestMap = pendingConnectionRequestMap;
        mAcceptedConnectionRequestMap = acceptedConnectionRequestMap;
        mEstablishedConnections = establishedConnections;
    }

    @Override
    public void init() {
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void release() {
        // TODO(b/257117236): implement this method.
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    /** Run `adb shell dumpsys car_service --services CarOccupantConnectionService` to dump. */
    public void dump(IndentingPrintWriter writer) {
        writer.println("*CarOccupantConnectionService*");
        synchronized (mLock) {
            writer.printf("%smConnectingReceiverServices:\n", INDENTATION_2);
            for (int i = 0; i < mConnectingReceiverServices.size(); i++) {
                writer.printf("%s%s\n", INDENTATION_4, mConnectingReceiverServices.valueAt(i));
            }
            writer.printf("%smConnectedReceiverServiceMap:\n", INDENTATION_2);
            for (int i = 0; i < mConnectedReceiverServiceMap.size(); i++) {
                ClientId id = mConnectedReceiverServiceMap.keyAt(i);
                IBackendReceiver service = mConnectedReceiverServiceMap.valueAt(i);
                writer.printf("%s%s, receiver service:%s\n", INDENTATION_4, id, service);
            }
            writer.printf("%smReceiverServiceConnectionMap:\n", INDENTATION_2);
            for (int i = 0; i < mReceiverServiceConnectionMap.size(); i++) {
                ClientId id = mReceiverServiceConnectionMap.keyAt(i);
                ServiceConnection connection = mReceiverServiceConnectionMap.valueAt(i);
                writer.printf("%s%s, connection:%s\n", INDENTATION_4, id, connection);
            }
            writer.printf("%smPreregisteredReceiverEndpointMap:\n", INDENTATION_2);
            for (int i = 0; i < mPreregisteredReceiverEndpointMap.size(); i++) {
                ReceiverEndpointId id = mPreregisteredReceiverEndpointMap.keyAt(i);
                IPayloadCallback callback = mPreregisteredReceiverEndpointMap.valueAt(i);
                writer.printf("%s%s, callback:%s\n", INDENTATION_4, id, callback);
            }
            writer.printf("%smRegisteredReceiverEndpointMap:\n", INDENTATION_2);
            for (int i = 0; i < mRegisteredReceiverEndpointMap.size(); i++) {
                ReceiverEndpointId id = mRegisteredReceiverEndpointMap.keyAt(i);
                IPayloadCallback callback = mRegisteredReceiverEndpointMap.valueAt(i);
                writer.printf("%s%s, callback:%s\n", INDENTATION_4, id, callback);
            }
            writer.printf("%smPendingConnectionRequestMap:\n", INDENTATION_2);
            for (int i = 0; i < mPendingConnectionRequestMap.size(); i++) {
                ConnectionId id = mPendingConnectionRequestMap.keyAt(i);
                IConnectionRequestCallback callback = mPendingConnectionRequestMap.valueAt(i);
                writer.printf("%s%s, callback:%s\n", INDENTATION_4, id, callback);
            }
            writer.printf("%smAcceptedConnectionRequestMap:\n", INDENTATION_2);
            for (int i = 0; i < mAcceptedConnectionRequestMap.size(); i++) {
                ConnectionId id = mAcceptedConnectionRequestMap.keyAt(i);
                IConnectionRequestCallback callback = mAcceptedConnectionRequestMap.valueAt(i);
                writer.printf("%s%s, callback:%s\n", INDENTATION_4, id, callback);
            }
            writer.printf("%smEstablishConnections:\n", INDENTATION_2);
            for (int i = 0; i < mEstablishedConnections.size(); i++) {
                writer.printf("%s%s\n", INDENTATION_4, mEstablishedConnections.valueAt(i));
            }
        }
    }

    @Override
    public void registerStateCallback(String packageName, IStateCallback callback) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void unregisterStateCallback(String packageName) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public PackageInfo getEndpointPackageInfo(int occupantZoneId, String packageName) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);
        checkCalledByPackage(mContext, packageName);

        // PackageManager#getPackageInfoAsUser() can do this with few lines, but it's hidden API.
        int userId = mOccupantZoneService.getUserForOccupant(occupantZoneId);
        if (userId == INVALID_USER_ID) {
            Slogf.e(TAG, "Invalid user ID for occupant zone " + occupantZoneId);
            return null;
        }
        UserHandle userHandle = UserHandle.of(userId);
        Context userContext = mContext.createContextAsUser(userHandle, /* flags= */ 0);
        PackageManager pm = userContext.getPackageManager();
        if (pm == null) {
            Slogf.e(TAG, "Failed to get PackageManager as user " + userId);
            return null;
        }
        PackageInfo packageInfo = null;
        try {
            packageInfo = pm.getPackageInfo(packageName,
                    PackageManager.PackageInfoFlags.of(/* value= */ 0));
        } catch (PackageManager.NameNotFoundException e) {
            // The client app should be installed in all occupant zones, so log an error.
            Slogf.e(TAG, "Didn't find " + packageName + " in occupant zone " + occupantZoneId);
        }
        return packageInfo;
    }

    @Override
    public void setOccupantZonePower(OccupantZoneInfo occupantZone, boolean powerOn) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);

        int[] displayIds = mOccupantZoneService.getAllDisplaysForOccupantZone(occupantZone.zoneId);
        for (int id : displayIds) {
            mPowerManagementService.setDisplayPowerState(id, powerOn);
        }
    }

    @Override
    public boolean isOccupantZonePowerOn(OccupantZoneInfo occupantZone) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);

        return mOccupantZoneService.areDisplaysOnForOccupantZone(occupantZone.zoneId);
    }

    @Override
    public void registerReceiver(String packageName, String receiverEndpointId,
            IPayloadCallback callback) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        checkCalledByPackage(mContext, packageName);

        ClientId receiverClient = getCallingClientId(packageName);
        ReceiverEndpointId receiverEndpoint =
                new ReceiverEndpointId(receiverClient, receiverEndpointId);
        synchronized (mLock) {
            assertNoDuplicateReceiverEndpointLocked(receiverEndpoint);
            // If the AbstractReceiverService of the receiver app is connected already, register
            // this receiver into AbstractReceiverService now.
            IBackendReceiver receiverService = mConnectedReceiverServiceMap.get(receiverClient);
            if (receiverService != null) {
                registerReceiverEndpointLocked(receiverService, receiverEndpoint, callback);
                return;
            }

            // Otherwise, cache this receiver callback for now. The cached receiver callback(s)
            // will be registered into the AbstractReceiverService once it is connected.
            mPreregisteredReceiverEndpointMap.put(receiverEndpoint, callback);

            // And bind to the AbstractReceiverService if was not bound yet.
            maybeBindReceiverServiceLocked(receiverClient);
        }
    }

    @Override
    public void unregisterReceiver(String packageName, String receiverEndpointId) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        checkCalledByPackage(mContext, packageName);

        ClientId receiverClient = getCallingClientId(packageName);
        ReceiverEndpointId receiverEndpoint =
                new ReceiverEndpointId(receiverClient, receiverEndpointId);
        IBackendReceiver receiverService;
        synchronized (mLock) {
            assertHasReceiverEndpointLocked(receiverEndpoint);
            receiverService = mConnectedReceiverServiceMap.get(receiverClient);
            if (receiverService == null) {
                // This could happen when unregisterReceiver() is called immediately after
                // registerReceiver(). In this case, the receiver service is not connected yet.
                Slogf.d(TAG, "The receiver service in " + receiverClient + " is being bound");
                mPreregisteredReceiverEndpointMap.remove(receiverEndpoint);
                maybeUnbindReceiverServiceLocked(receiverClient);
                return;
            }
            try {
                receiverService.unregisterReceiver(receiverEndpointId);
                mRegisteredReceiverEndpointMap.remove(receiverEndpoint);
                maybeUnbindReceiverServiceLocked(receiverClient);
            } catch (RemoteException e) {
                Slogf.e(TAG, "Failed the unregister the receiver " + receiverEndpoint + e);
            }
        }
    }

    @Override
    public void requestConnection(String packageName, OccupantZoneInfo receiverZone,
            IConnectionRequestCallback callback) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        checkCalledByPackage(mContext, packageName);

        ClientId senderClient = getCallingClientId(packageName);
        ClientId receiverClient = getClientIdInOccupantZone(receiverZone, packageName);
        if (receiverClient == null) {
            throw new IllegalStateException("Don't connect to the receiver zone because it is not "
                    + " ready for connection: " + receiverZone);
        }
        ConnectionId connectionId = new ConnectionId(senderClient, receiverClient);
        synchronized (mLock) {
            assertNoDuplicateConnectionRequestLocked(connectionId);

            // Save the callback in mPendingConnectionRequestMap.
            // The requester will be notified when there is a response from the receiver app.
            mPendingConnectionRequestMap.put(connectionId, callback);

            // If the AbstractReceiverService of the receiver app is bound, notify it of the
            // request now.
            IBackendReceiver receiverService = mConnectedReceiverServiceMap.get(receiverClient);
            if (receiverService != null) {
                try {
                    // Since the sender client is requesting connection, it must be running in the
                    // foreground.
                    // TODO(b/257118072): maybe we should evaluate it before setting
                    //  FLAG_CLIENT_IN_FOREGROUND.
                    // In single-SoC model, the sender client is guaranteed to have the same
                    // signing info and long version code.
                    // TODO(b/257118327): support multiple-SoC.
                    receiverService.onConnectionInitiated(senderClient.occupantZone,
                            FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION
                                    | FLAG_CLIENT_SAME_SIGNATURE | FLAG_CLIENT_RUNNING
                                    | FLAG_CLIENT_IN_FOREGROUND);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "Failed to notify the receiver for connection request", e);
                }
                return;
            }
            // Otherwise, bind to it, and notify the requester once it is bound.
            maybeBindReceiverServiceLocked(receiverClient);
        }
    }

    @Override
    public void cancelConnection(String packageName, OccupantZoneInfo receiverZone) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        checkCalledByPackage(mContext, packageName);

        ClientId senderClient = getCallingClientId(packageName);
        ClientId receiverClient = getClientIdInOccupantZone(receiverZone, packageName);
        if (receiverClient == null) {
            // receiverClient can't be null (because the sender requested a connection to it, and
            // it didn't throw an exception), but let's be cautious.
            return;
        }
        ConnectionId connectionToCancel = new ConnectionId(senderClient, receiverClient);
        synchronized (mLock) {
            assertHasPendingConnectionRequestLocked(connectionToCancel);
            mPendingConnectionRequestMap.remove(connectionToCancel);

            // If the AbstractReceiverService of the receiver app is bound, notify it of the
            // cancellation now.
            IBackendReceiver receiverService = mConnectedReceiverServiceMap.get(receiverClient);
            if (receiverService != null) {
                try {
                    receiverService.onConnectionCanceled(senderClient.occupantZone);
                } catch (RemoteException e) {
                    // There is no need to propagate the Exception to the sender client because
                    // the connection was canceled successfully anyway.
                    Slogf.e(TAG, "Failed to notify the receiver of connection cancellation", e);
                }
            }

            maybeUnbindReceiverServiceLocked(receiverClient);
        }
    }

    @Override
    public void sendPayload(String packageName, OccupantZoneInfo receiverZone, Payload payload) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        checkCalledByPackage(mContext, packageName);

        ClientId senderClient = getCallingClientId(packageName);
        ClientId receiverClient = getClientIdInOccupantZone(receiverZone, packageName);
        IBackendReceiver receiverService;
        synchronized (mLock) {
            assertConnectedLocked(packageName, senderClient.occupantZone, receiverZone);
            // receiverClient can't be null because the sender is connected to it now.
            receiverService = mConnectedReceiverServiceMap.get(receiverClient);
        }
        if (receiverService == null) {
            // receiverService can't be null since it is connected, but let's be cautious.
            throw new IllegalStateException("The receiver service in " + receiverClient
                    + "is not bound yet");
        }
        try {
            receiverService.onPayloadReceived(senderClient.occupantZone, payload);
        } catch (RemoteException e) {
            throw new IllegalStateException("The receiver client is dead " + receiverClient, e);
        }
    }

    @Override
    public void disconnect(String packageName, OccupantZoneInfo receiverZone) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        checkCalledByPackage(mContext, packageName);

        ClientId senderClient = getCallingClientId(packageName);
        ClientId receiverClient = getClientIdInOccupantZone(receiverZone, packageName);
        synchronized (mLock) {
            assertConnectedLocked(packageName, senderClient.occupantZone, receiverZone);

            // Remove the connection callback.
            // receiverClient can't be null because the sender is connected to it now.
            ConnectionId connectionId = new ConnectionId(senderClient, receiverClient);
            mAcceptedConnectionRequestMap.remove(connectionId);

            // Remove the connection record.
            ConnectionRecord connectionRecord = new ConnectionRecord(packageName,
                    senderClient.occupantZone.zoneId, receiverZone.zoneId);
            mEstablishedConnections.remove(connectionRecord);

            // Notify the receiver service.
            IBackendReceiver receiverService = mConnectedReceiverServiceMap.get(receiverClient);
            if (receiverService == null) {
                // receiverService can't be null since it is connected, but let's be cautious.
                Slogf.e(TAG, "The receiver service in " + receiverClient + " is not bound yet");
                return;
            }
            try {
                receiverService.onDisconnected(senderClient.occupantZone);
            } catch (RemoteException e) {
                // There is no need to propagate the Exception to the sender client because
                // the connection was terminated successfully anyway.
                Slogf.e(TAG, "Failed to notify the receiver service of disconnection! "
                        + "senderClient:%s, receiverClient:", senderClient, receiverClient, e);
            }

            maybeUnbindReceiverServiceLocked(receiverClient);
        }
    }

    @Override
    public boolean isConnected(String packageName, OccupantZoneInfo receiverZone) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        checkCalledByPackage(mContext, packageName);

        UserHandle senderUserHandle = Binder.getCallingUserHandle();
        OccupantZoneInfo senderZone = mOccupantZoneService.getOccupantZoneForUser(senderUserHandle);
        synchronized (mLock) {
            return isConnectedLocked(packageName, senderZone, receiverZone);
        }
    }

    @GuardedBy("mLock")
    private void registerPreregisteredReceiverEndpointsLocked(IBackendReceiver receiverService,
            ClientId receiverClient) {
        for (int i = mPreregisteredReceiverEndpointMap.size() - 1; i >= 0; i--) {
            ReceiverEndpointId receiverEndpoint = mPreregisteredReceiverEndpointMap.keyAt(i);
            if (!receiverClient.equals(receiverEndpoint.clientId)) {
                // This endpoint belongs to another client, so skip it.
                continue;
            }
            String receiverEndpointId = receiverEndpoint.endpointId;
            IPayloadCallback callback = mPreregisteredReceiverEndpointMap.valueAt(i);
            try {
                receiverService.registerReceiver(receiverEndpointId, callback);
                // Only update the maps after registration succeeded. This allows to retry.
                mPreregisteredReceiverEndpointMap.removeAt(i);
                mRegisteredReceiverEndpointMap.put(receiverEndpoint, callback);
            } catch (RemoteException e) {
                Slogf.e(TAG, "Failed to register receiver", e);
            }
        }
    }

    @VisibleForTesting
    ClientId getCallingClientId(String packageName) {
        UserHandle callingUserHandle = Binder.getCallingUserHandle();
        int callingUserId = callingUserHandle.getIdentifier();
        OccupantZoneInfo occupantZone =
                mOccupantZoneService.getOccupantZoneForUser(callingUserHandle);
        // Note: the occupantZone is not null because the calling user must be a valid user.
        return new ClientId(occupantZone, callingUserId, packageName);
    }

    @Nullable
    private ClientId getClientIdInOccupantZone(OccupantZoneInfo occupantZone,
            String packageName) {
        int userId = mOccupantZoneService.getUserForOccupant(occupantZone.zoneId);
        if (userId == INVALID_USER_ID) {
            Slogf.e(TAG, "The user in %s is not assigned yet", occupantZone);
            return null;
        }
        return new ClientId(occupantZone, userId, packageName);
    }

    @GuardedBy("mLock")
    private void assertNoDuplicateReceiverEndpointLocked(ReceiverEndpointId receiverEndpoint) {
        if (hasReceiverEndpointLocked(receiverEndpoint)) {
            throw new IllegalStateException("The receiver endpoint was registered already: "
                    + receiverEndpoint);
        }
    }

    @GuardedBy("mLock")
    private void assertHasReceiverEndpointLocked(ReceiverEndpointId receiverEndpoint) {
        if (!hasReceiverEndpointLocked(receiverEndpoint)) {
            throw new IllegalStateException("The receiver endpoint was not registered before: "
                    + receiverEndpoint);
        }
    }

    @GuardedBy("mLock")
    private boolean hasReceiverEndpointLocked(ReceiverEndpointId receiverEndpoint) {
        return mPreregisteredReceiverEndpointMap.containsKey(receiverEndpoint)
                || mRegisteredReceiverEndpointMap.containsKey(receiverEndpoint);
    }

    @GuardedBy("mLock")
    private void registerReceiverEndpointLocked(IBackendReceiver receiverService,
            ReceiverEndpointId receiverEndpoint,
            IPayloadCallback callback) {
        try {
            receiverService.registerReceiver(receiverEndpoint.endpointId, callback);
            mRegisteredReceiverEndpointMap.put(receiverEndpoint, callback);
        } catch (RemoteException e) {
            Slogf.e(TAG, "Failed to register receiver", e);
        }
    }

    @GuardedBy("mLock")
    private void maybeBindReceiverServiceLocked(ClientId receiverClient) {
        if (mConnectedReceiverServiceMap.containsKey(receiverClient)) {
            Slogf.i(TAG, "Don't bind to the receiver service in %s because it's already bound",
                    receiverClient);
            return;
        }
        if (mConnectingReceiverServices.contains(receiverClient)) {
            Slogf.i(TAG, "Don't bind to the receiver service in %s because it's being bound",
                    receiverClient);
            return;
        }
        bindReceiverServiceLocked(receiverClient);
        mConnectingReceiverServices.add(receiverClient);
    }

    @GuardedBy("mLock")
    private void bindReceiverServiceLocked(ClientId receiverClient) {
        Intent intent = new Intent(CAR_INTENT_ACTION_RECEIVER_SERVICE);
        intent.setPackage(receiverClient.packageName);
        ReceiverServiceConnection connection = new ReceiverServiceConnection(receiverClient);
        UserHandle userHandle = UserHandle.of(receiverClient.userId);
        mContext.bindServiceAsUser(intent, connection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, userHandle);
        mReceiverServiceConnectionMap.put(receiverClient, connection);
    }

    /**
     * Unbinds the receiver service in {@code receiverClient} if there is no
     * preregistered/registered receiver endpoint in {@code receiverClient}, and no
     * pending/established connection to {@code receiverClient}.
     */
    @GuardedBy("mLock")
    private void maybeUnbindReceiverServiceLocked(ClientId receiverClient) {
        for (int i = 0; i < mRegisteredReceiverEndpointMap.size(); i++) {
            ReceiverEndpointId receiverEndpoint = mRegisteredReceiverEndpointMap.keyAt(i);
            if (receiverEndpoint.clientId.equals(receiverClient)) {
                Slogf.i(TAG, "Don't unbind the receiver service because it has a receiver"
                        + "endpoint registered: " + receiverEndpoint);
                return;
            }
        }
        for (int i = 0; i < mPreregisteredReceiverEndpointMap.size(); i++) {
            ReceiverEndpointId receiverEndpoint = mPreregisteredReceiverEndpointMap.keyAt(i);
            if (receiverEndpoint.clientId.equals(receiverClient)) {
                Slogf.i(TAG, "Don't unbind the receiver service because it has a receiver"
                        + "endpoint pending registered " + receiverEndpoint);
                return;
            }
        }
        for (int i = 0; i < mAcceptedConnectionRequestMap.size(); i++) {
            ConnectionId connectionId = mAcceptedConnectionRequestMap.keyAt(i);
            if (connectionId.receiverClient.equals(receiverClient)) {
                Slogf.i(TAG, "Don't unbind the receiver service because there is a connection"
                        + " to it:" + connectionId);
                return;
            }
        }
        for (int i = 0; i < mPendingConnectionRequestMap.size(); i++) {
            ConnectionId connectionId = mPendingConnectionRequestMap.keyAt(i);
            if (connectionId.receiverClient.equals(receiverClient)) {
                Slogf.i(TAG, "Don't unbind because there is a sender endpoint connecting"
                        + "to it:" + connectionId);
                return;
            }
        }

        unbindReceiverServiceLocked(receiverClient);
        mConnectingReceiverServices.remove(receiverClient);
        mConnectedReceiverServiceMap.remove(receiverClient);
    }

    @GuardedBy("mLock")
    private void unbindReceiverServiceLocked(ClientId receiverClient) {
        ServiceConnection connection = mReceiverServiceConnectionMap.get(receiverClient);
        if (connection == null) {
            Slogf.w(TAG, "Failed to unbind to the receiver service in " + receiverClient
                    + " because it was not bound");
            return;
        }
        mContext.unbindService(connection);
        mReceiverServiceConnectionMap.remove(receiverClient);
    }

    @GuardedBy("mLock")
    private void assertNoDuplicateConnectionRequestLocked(ConnectionId connectionId) {
        if (mPendingConnectionRequestMap.containsKey(connectionId)) {
            throw new IllegalStateException("The client " + connectionId.senderClient
                    + " already requested a connection to " + connectionId.receiverClient
                    + " and is waiting for response");
        }
        if (mAcceptedConnectionRequestMap.containsKey(connectionId)) {
            throw new IllegalStateException("The client " + connectionId.senderClient
                    + " already established a connection to " + connectionId.receiverClient);
        }
    }

    @GuardedBy("mLock")
    private void assertHasPendingConnectionRequestLocked(ConnectionId connectionId) {
        if (!mPendingConnectionRequestMap.containsKey(connectionId)) {
            throw new IllegalStateException("The client " + connectionId.senderClient
                    + " has no pending connection request to " + connectionId.receiverClient);
        }
    }

    private void notifySenderOfReceiverServiceDisconnect(
            BinderKeyValueContainer<ConnectionId, IConnectionRequestCallback>
                    connectionRequestMap, ClientId receiverClient) {
        for (int i = connectionRequestMap.size() - 1; i >= 0; i--) {
            ConnectionId connectionId = connectionRequestMap.keyAt(i);
            if (!connectionId.receiverClient.equals(receiverClient)) {
                continue;
            }
            IConnectionRequestCallback callback = connectionRequestMap.valueAt(i);
            try {
                callback.onFailed(receiverClient.occupantZone, CONNECTION_ERROR_UNKNOWN);
            } catch (RemoteException e) {
                Slogf.e(TAG, "Failed to notify the sender for connection failure", e);
            }
            connectionRequestMap.removeAt(i);
        }
    }

    /**
     * Returns whether the sender client is connected to the receiver client.
     */
    @GuardedBy("mLock")
    private boolean isConnectedLocked(String packageName, OccupantZoneInfo senderZone,
            OccupantZoneInfo receiverZone) {
        ConnectionRecord expectedConnection =
                new ConnectionRecord(packageName, senderZone.zoneId, receiverZone.zoneId);
        return mEstablishedConnections.contains(expectedConnection);
    }

    @GuardedBy("mLock")
    private void assertConnectedLocked(String packageName, OccupantZoneInfo senderZone,
            OccupantZoneInfo receiverZone) {
        if (!isConnectedLocked(packageName, senderZone, receiverZone)) {
            throw new IllegalStateException("The client " + packageName + " in " + senderZone
                    + " is not connected to " + receiverZone);
        }
    }

    @GuardedBy("mLock")
    private IConnectionRequestCallback extractRequestCallbackToNotifyLocked(
            OccupantZoneInfo senderZone, ClientId receiverClient) {
        ClientId senderClient = getClientIdInOccupantZone(senderZone, receiverClient.packageName);
        if (senderClient == null) {
            // senderClient can't be null because it requested a connection, but let's be cautious.
            return null;
        }
        ConnectionId connectionId = new ConnectionId(senderClient, receiverClient);
        IConnectionRequestCallback pendingCallback = mPendingConnectionRequestMap.get(connectionId);
        if (pendingCallback == null) {
            Slogf.e(TAG, "The connection requester no longer exists " + senderClient);
            return null;
        }
        mPendingConnectionRequestMap.remove(connectionId);
        return pendingCallback;
    }

    @GuardedBy("mLock")
    private void sendCachedConnectionRequestLocked(IBackendReceiver receiverService,
            ClientId receiverClient) {
        Set<ClientId> notifiedSenderClients = new ArraySet<>();
        for (int i = mPendingConnectionRequestMap.size() - 1; i >= 0; i--) {
            ConnectionId connectionId = mPendingConnectionRequestMap.keyAt(i);
            // If there is a pending request to the receiver service and the receiver service has
            // not been notified of the request before, notify the receiver service now.
            if (connectionId.receiverClient.equals(receiverClient)
                    && !notifiedSenderClients.contains(connectionId.senderClient)) {
                try {
                    // Since the sender client is requesting connection, it must be running in the
                    // foreground.
                    // In single-SoC model, the sender client is guaranteed to have the same
                    // signing info and long version code.
                    // TODO(b/257118327): support multiple-SoC.
                    receiverService.onConnectionInitiated(connectionId.senderClient.occupantZone,
                            FLAG_CLIENT_INSTALLED | FLAG_CLIENT_SAME_VERSION
                                    | FLAG_CLIENT_SAME_SIGNATURE | FLAG_CLIENT_RUNNING
                                    | FLAG_CLIENT_IN_FOREGROUND);
                    notifiedSenderClients.add(connectionId.senderClient);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "Failed to notify the receiver for connection request", e);
                }
            }
        }
    }
}
