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

import static com.android.car.CarServiceUtils.assertPermission;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

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
    private final ArraySet<ClientToken> mConnectingReceiverServices;

    /**
     * A map of connected receiver services. The key is the clientToken of the receiver service,
     * while the value is to the binder of the receiver service.
     */
    @GuardedBy("mLock")
    private final BinderKeyValueContainer<ClientToken, IBackendReceiver>
            mConnectedReceiverServiceMap;

    /** A map of receiver services to their ServiceConnections. */
    @GuardedBy("mLock")
    private final ArrayMap<ClientToken, ServiceConnection> mReceiverServiceConnectionMap;

    /**
     * A map of receiver endpoints to be registered when the {@link
     * android.car.occupantconnection.AbstractReceiverService} is connected. The key is its token,
     * and the value is its IPayloadCallback. When a receiver endpoint is registered successfully,
     * it will be removed from this map and added into {@link #mRegisteredReceiverEndpointMap}.
     */
    @GuardedBy("mLock")
    private final BinderKeyValueContainer<ReceiverEndpointToken, IPayloadCallback>
            mPreregisteredReceiverEndpointMap;

    /**
     * A map of receiver endpoints that have been registered into the {@link
     * android.car.occupantconnection.AbstractReceiverService}. The key is its token,
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
    private final BinderKeyValueContainer<ReceiverEndpointToken, IPayloadCallback>
            mRegisteredReceiverEndpointMap;

    /**
     * A class to handle the connection to {@link
     * android.car.occupantconnection.AbstractReceiverService} in the receiver app.
     */
    private final class ReceiverServiceConnection implements ServiceConnection {

        private final ClientToken mReceiverClient;
        private final IBackendConnectionResponder mResponder;

        private ReceiverServiceConnection(ClientToken receiverClient) {
            mReceiverClient = receiverClient;
            mResponder = new IBackendConnectionResponder.Stub() {
                @Override
                public void acceptConnection(OccupantZoneInfo senderZone) {
                    Slogf.v(TAG, "receiver service acceptConnection "
                            + mReceiverClient);
                    // TODO(b/257117236): implement this method.
                }

                @Override
                public void rejectConnection(OccupantZoneInfo senderZone,
                        int rejectionReason) {
                    Slogf.v(TAG, "receiver service rejectConnection "
                            + mReceiverClient);
                    // TODO(b/257117236): implement this method.
                }
            };
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Slogf.v(TAG, "onServiceConnected " + service);
            IBackendReceiver receiverService = IBackendReceiver.Stub.asInterface(service);
            try {
                receiverService.registerBackendConnectionResponder(mResponder);
            } catch (RemoteException e) {
                Slogf.e(TAG, "Failed to register IBackendConnectionResponder", e);
            }

            synchronized (mLock) {
                // Update receiver service maps.
                mConnectedReceiverServiceMap.put(mReceiverClient, receiverService);
                mConnectingReceiverServices.remove(mReceiverClient);

                // Register cached callbacks into AbstractReceiverService, and update receiver
                // endpoint maps.
                registerCachedReceiverEndpointsLocked(receiverService, mReceiverClient);

                // TODO(b/257117236): If there are cached connection requests, notify the
                //  AbstractReceiverService now.
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Slogf.v(TAG, "onServiceDisconnected " + name);
            synchronized (mLock) {
                mConnectedReceiverServiceMap.remove(mReceiverClient);
                mConnectingReceiverServices.remove(mReceiverClient);
            }
            // TODO(b/257117236): update connection map, invoke disconnect(), etc.
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
                /* registeredReceiverEndpointMap= */ new BinderKeyValueContainer<>());
    }

    @VisibleForTesting
    CarOccupantConnectionService(Context context,
            CarOccupantZoneService occupantZoneService,
            CarPowerManagementService powerManagementService,
            ArraySet<ClientToken> connectingReceiverServices,
            BinderKeyValueContainer<ClientToken, IBackendReceiver> connectedReceiverServiceMap,
            ArrayMap<ClientToken, ServiceConnection> receiverServiceConnectionMap,
            BinderKeyValueContainer<ReceiverEndpointToken, IPayloadCallback>
                    preregisteredReceiverEndpointMap,
            BinderKeyValueContainer<ReceiverEndpointToken, IPayloadCallback>
                    registeredReceiverEndpointMap) {
        mContext = context;
        mOccupantZoneService = occupantZoneService;
        mPowerManagementService = powerManagementService;
        mConnectingReceiverServices = connectingReceiverServices;
        mConnectedReceiverServiceMap = connectedReceiverServiceMap;
        mReceiverServiceConnectionMap = receiverServiceConnectionMap;
        mPreregisteredReceiverEndpointMap = preregisteredReceiverEndpointMap;
        mRegisteredReceiverEndpointMap = registeredReceiverEndpointMap;
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
            for (ClientToken token : mConnectingReceiverServices) {
                writer.printf("%s%s\n", INDENTATION_4, token);
            }
            writer.printf("%smConnectedReceiverServiceMap:\n", INDENTATION_2);
            for (int i = 0; i < mConnectedReceiverServiceMap.size(); i++) {
                ClientToken token = mConnectedReceiverServiceMap.keyAt(i);
                IBackendReceiver service = mConnectedReceiverServiceMap.valueAt(i);
                writer.printf("%s%s, receiver service:%s\n", INDENTATION_4, token, service);
            }
            writer.printf("%smReceiverServiceConnectionMap:\n", INDENTATION_2);
            for (int i = 0; i < mReceiverServiceConnectionMap.size(); i++) {
                ClientToken token = mReceiverServiceConnectionMap.keyAt(i);
                ServiceConnection connection = mReceiverServiceConnectionMap.valueAt(i);
                writer.printf("%s%s, connection:%s\n", INDENTATION_4, token, connection);
            }
            writer.printf("%smPreregisteredReceiverEndpointMap:\n", INDENTATION_2);
            for (int i = 0; i < mPreregisteredReceiverEndpointMap.size(); i++) {
                ReceiverEndpointToken token = mPreregisteredReceiverEndpointMap.keyAt(i);
                IPayloadCallback callback = mPreregisteredReceiverEndpointMap.valueAt(i);
                writer.printf("%s%s, callback:%s\n", INDENTATION_4, token, callback);
            }
            writer.printf("%smRegisteredReceiverEndpointMap:\n", INDENTATION_2);
            for (int i = 0; i < mRegisteredReceiverEndpointMap.size(); i++) {
                ReceiverEndpointToken token = mRegisteredReceiverEndpointMap.keyAt(i);
                IPayloadCallback callback = mRegisteredReceiverEndpointMap.valueAt(i);
                writer.printf("%s%s, callback:%s\n", INDENTATION_4, token, callback);
            }
        }
    }

    @Override
    public void registerStateCallback(IStateCallback callback) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void unregisterStateCallback() {
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public PackageInfo getEndpointPackageInfo(int occupantZoneId, String packageName) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_REMOTE_DEVICE);

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

        ClientToken receiverClient = getCallingClientToken(packageName);
        ReceiverEndpointToken receiverEndpoint =
                new ReceiverEndpointToken(receiverClient, receiverEndpointId);
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
    public void unregisterReceiver(String receiverEndpointId) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void requestConnection(String packageName, OccupantZoneInfo receiverZone,
            IConnectionRequestCallback callback) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void cancelConnection(OccupantZoneInfo receiverZone) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void sendPayload(OccupantZoneInfo receiverZone,
            Payload payload) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public void disconnect(OccupantZoneInfo receiverZone) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
    }

    @Override
    public boolean isConnected(OccupantZoneInfo receiverZone) {
        assertPermission(mContext, Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
        // TODO(b/257117236): implement this method.
        return false;
    }

    @GuardedBy("mLock")
    private void registerCachedReceiverEndpointsLocked(IBackendReceiver receiverService,
            ClientToken receiverClient) {
        for (int i = mPreregisteredReceiverEndpointMap.size() - 1; i >= 0; i--) {
            ReceiverEndpointToken receiverEndpoint = mPreregisteredReceiverEndpointMap.keyAt(i);
            if (!receiverClient.equals(receiverEndpoint.clientToken)) {
                // This endpoint belongs to another client, so skip it.
                continue;
            }
            String receiverEndpointId = receiverEndpoint.receiverEndpointId;
            IPayloadCallback callback = mPreregisteredReceiverEndpointMap.valueAt(i);
            try {
                receiverService.registerReceiver(receiverEndpointId, callback);
                // Only update the maps after registration succeeded. This allows to
                // retry.
                mPreregisteredReceiverEndpointMap.removeAt(i);
                mRegisteredReceiverEndpointMap.put(receiverEndpoint, callback);
            } catch (RemoteException e) {
                Slogf.e(TAG, "Failed to register receiver", e);
            }
        }
    }

    @VisibleForTesting
    ClientToken getCallingClientToken(String packageName) {
        UserHandle callingUserHandle = Binder.getCallingUserHandle();
        int callingUserId = callingUserHandle.getIdentifier();
        OccupantZoneInfo occupantZone =
                mOccupantZoneService.getOccupantZoneForUser(callingUserHandle);
        // Note: the occupantZone is not null because the calling user must be a valid user.
        return new ClientToken(occupantZone, callingUserId, packageName);
    }

    @GuardedBy("mLock")
    private void assertNoDuplicateReceiverEndpointLocked(ReceiverEndpointToken receiverEndpoint) {
        if (hasReceiverEndpointLocked(receiverEndpoint)) {
            throw new IllegalStateException("The receiver endpoint was registered already: "
                    + receiverEndpoint);
        }
    }

    @GuardedBy("mLock")
    private boolean hasReceiverEndpointLocked(ReceiverEndpointToken receiverEndpoint) {
        return mPreregisteredReceiverEndpointMap.containsKey(receiverEndpoint)
                || mRegisteredReceiverEndpointMap.containsKey(receiverEndpoint);
    }

    @GuardedBy("mLock")
    private void registerReceiverEndpointLocked(IBackendReceiver receiverService,
            ReceiverEndpointToken receiverEndpoint,
            IPayloadCallback callback) {
        try {
            receiverService.registerReceiver(receiverEndpoint.receiverEndpointId, callback);
            mRegisteredReceiverEndpointMap.put(receiverEndpoint, callback);
        } catch (RemoteException e) {
            Slogf.e(TAG, "Failed to register receiver", e);
        }
    }

    @GuardedBy("mLock")
    private void maybeBindReceiverServiceLocked(ClientToken receiverClient) {
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
    private void bindReceiverServiceLocked(ClientToken receiverClient) {
        Intent intent = new Intent(CAR_INTENT_ACTION_RECEIVER_SERVICE);
        intent.setPackage(receiverClient.packageName);
        ReceiverServiceConnection connection = new ReceiverServiceConnection(receiverClient);
        UserHandle userHandle = UserHandle.of(receiverClient.userId);
        mContext.bindServiceAsUser(intent, connection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, userHandle);
        mReceiverServiceConnectionMap.put(receiverClient, connection);
    }
}
