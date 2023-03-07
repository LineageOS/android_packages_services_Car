/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static android.car.CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER;
import static android.car.VehicleAreaSeat.SEAT_ROW_1_LEFT;
import static android.car.test.mocks.AndroidMockitoHelper.mockContextCreateContextAsUser;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.occupantconnection.IBackendReceiver;
import android.car.occupantconnection.IPayloadCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.car.CarLocalServices;
import com.android.car.CarOccupantZoneService;
import com.android.car.internal.util.BinderKeyValueContainer;
import com.android.car.power.CarPowerManagementService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class CarOccupantConnectionServiceTest {

    private static final String PACKAGE_NAME = "my_package_name";
    private static final String RECEIVER_ENDPOINT_ID = "test_receiver_endpoint";

    @Mock
    private Context mContext;
    @Mock
    private CarOccupantZoneService mOccupantZoneService;
    @Mock
    private CarPowerManagementService mPowerManagementService;
    @Mock
    private IPayloadCallback mPayloadCallback;
    @Mock
    private IBinder mPayloadCallbackBinder;

    private final OccupantZoneInfo mReceiverZone =
            new OccupantZoneInfo(/* zoneId= */ 0, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);

    private final ArraySet<ClientToken> mConnectingReceiverServices = new ArraySet<>();
    private final BinderKeyValueContainer<ClientToken, IBackendReceiver>
            mConnectedReceiverServiceMap = new BinderKeyValueContainer<>();
    private final ArrayMap<ClientToken, ServiceConnection> mReceiverServiceConnectionMap =
            new ArrayMap<>();
    private final BinderKeyValueContainer<ReceiverEndpointToken, IPayloadCallback>
            mCachedReceiverEndpointMap = new BinderKeyValueContainer<>();
    private final BinderKeyValueContainer<ReceiverEndpointToken, IPayloadCallback>
            mRegisteredReceiverEndpointMap = new BinderKeyValueContainer<>();

    private CarOccupantConnectionService mService;

    @Before
    public void setUp() {
        // Stored as static: Other tests can leave things behind and fail this test in add call.
        // So just remove as safety guard.
        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
        CarLocalServices.addService(CarOccupantZoneService.class, mOccupantZoneService);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.addService(CarPowerManagementService.class, mPowerManagementService);

        mService = new CarOccupantConnectionService(mContext,
                mOccupantZoneService,
                mPowerManagementService,
                mConnectingReceiverServices,
                mConnectedReceiverServiceMap,
                mReceiverServiceConnectionMap,
                mCachedReceiverEndpointMap,
                mRegisteredReceiverEndpointMap);
        mService.init();
        when(mPayloadCallback.asBinder()).thenReturn(mPayloadCallbackBinder);
    }

    @After
    public void tearDown() {
        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
    }

    @Test
    public void testGetEndpointPackageInfoWithoutPermission_throwsException() {
        int occupantZoneId = 0;
        when(mContext.checkCallingOrSelfPermission(eq(Car.PERMISSION_MANAGE_REMOTE_DEVICE)))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertThrows(SecurityException.class,
                () -> mService.getEndpointPackageInfo(occupantZoneId, PACKAGE_NAME));
    }

    @Test
    public void testGetEndpointPackageInfoWithInvalidUserId() {
        int occupantZoneId = 0;
        when(mOccupantZoneService.getUserForOccupant(occupantZoneId)).thenReturn(
                INVALID_USER_ID);

        assertThat(mService.getEndpointPackageInfo(occupantZoneId, PACKAGE_NAME)).isNull();
    }

    @Test
    public void testGetEndpointPackageInfo() throws PackageManager.NameNotFoundException {
        int occupantZoneId = 0;
        int userId = 123;
        Context userContext = mock(Context.class);
        PackageManager packageManager = mock(PackageManager.class);
        PackageInfo packageInfo = mock(PackageInfo.class);

        when(mOccupantZoneService.getUserForOccupant(occupantZoneId)).thenReturn(userId);
        mockContextCreateContextAsUser(mContext, userContext, userId);
        when(userContext.getPackageManager()).thenReturn(packageManager);
        when(packageManager.getPackageInfo(eq(PACKAGE_NAME), any())).thenReturn(packageInfo);

        assertThat(mService.getEndpointPackageInfo(occupantZoneId, PACKAGE_NAME))
                .isEqualTo(packageInfo);
    }

    @Test
    public void testChangePowerStateOn() {
        mService.init();
        int occupantZoneId = 0;
        int displayId = 1;
        OccupantZoneInfo occupantZoneInfo =
                new OccupantZoneInfo(occupantZoneId, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        int[] displays = {displayId};
        when(mOccupantZoneService.getAllDisplaysForOccupantZone(occupantZoneId))
                .thenReturn(displays);

        mService.setOccupantZonePower(occupantZoneInfo, true);
        verify(mPowerManagementService).setDisplayPowerState(displayId, true);
    }

    @Test
    public void testChangePowerStateOff() {
        mService.init();
        int occupantZoneId = 0;
        int displayId = 1;
        OccupantZoneInfo occupantZoneInfo =
                new OccupantZoneInfo(occupantZoneId, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        int[] displays = {displayId};
        when(mOccupantZoneService.getAllDisplaysForOccupantZone(occupantZoneId))
                .thenReturn(displays);

        mService.setOccupantZonePower(occupantZoneInfo, false);
        verify(mPowerManagementService).setDisplayPowerState(displayId, false);
    }

    @Test
    public void testGetPowerStateOn() {
        mService.init();
        int occupantZoneId = 0;
        OccupantZoneInfo occupantZoneInfo =
                new OccupantZoneInfo(occupantZoneId, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        when(mOccupantZoneService.areDisplaysOnForOccupantZone(occupantZoneId))
                .thenReturn(true);

        assertThat(mService.isOccupantZonePowerOn(occupantZoneInfo)).isTrue();
    }

    @Test
    public void testGetPowerStateOff() {
        mService.init();
        int occupantZoneId = 0;
        OccupantZoneInfo occupantZoneInfo =
                new OccupantZoneInfo(occupantZoneId, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        when(mOccupantZoneService.areDisplaysOnForOccupantZone(occupantZoneId))
                .thenReturn(false);

        assertThat(mService.isOccupantZonePowerOn(occupantZoneInfo)).isFalse();
    }

    @Test
    public void testRegisterReceiverWithoutPermission_throwsException() {
        when(mContext.checkCallingOrSelfPermission(eq(Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION)))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertThrows(SecurityException.class,
                () -> mService.registerReceiver(PACKAGE_NAME, RECEIVER_ENDPOINT_ID,
                        mPayloadCallback));
    }

    @Test
    public void testRegisterReceiverWithDuplicateId_throwsException() {
        UserHandle receiverUserHandle = Binder.getCallingUserHandle();
        when(mOccupantZoneService.getOccupantZoneForUser(receiverUserHandle))
                .thenReturn(mReceiverZone);
        mService.registerReceiver(PACKAGE_NAME, RECEIVER_ENDPOINT_ID, mPayloadCallback);

        IPayloadCallback payloadCallback2 = mock(IPayloadCallback.class);
        IBinder payloadCallbackBinder2 = mock(IBinder.class);
        when(payloadCallback2.asBinder()).thenReturn(payloadCallbackBinder2);

        assertThrows(IllegalStateException.class,
                () -> mService.registerReceiver(PACKAGE_NAME, RECEIVER_ENDPOINT_ID,
                        payloadCallback2));
    }

    @Test
    public void testRegisterTwoReceiversWithoutReceiverServiceConnected() {
        UserHandle receiverUserHandle = Binder.getCallingUserHandle();
        when(mOccupantZoneService.getOccupantZoneForUser(receiverUserHandle))
                .thenReturn(mReceiverZone);
        mService.registerReceiver(PACKAGE_NAME, RECEIVER_ENDPOINT_ID, mPayloadCallback);

        // It should have a receiver service that is not connected yet, and a receiver endpoint
        // pending registration.
        assertThat(mConnectingReceiverServices.size()).isEqualTo(1);
        assertThat(mConnectedReceiverServiceMap.size()).isEqualTo(0);
        assertThat(mCachedReceiverEndpointMap.size()).isEqualTo(1);
        assertThat(mRegisteredReceiverEndpointMap.size()).isEqualTo(0);

        ClientToken clientToken = mConnectingReceiverServices.valueAt(0);
        assertThat(clientToken.packageName).isEqualTo(PACKAGE_NAME);

        ReceiverEndpointToken receiverEndpointToken =
                new ReceiverEndpointToken(clientToken, RECEIVER_ENDPOINT_ID);
        assertThat(mCachedReceiverEndpointMap.get(receiverEndpointToken))
                .isEqualTo(mPayloadCallback);

        // One more receiver endpoint pending registration.
        String receiverEndpointId2 = "ID2";
        IPayloadCallback payloadCallback2 = mock(IPayloadCallback.class);
        IBinder payloadCallbackBinder2 = mock(IBinder.class);
        when(payloadCallback2.asBinder()).thenReturn(payloadCallbackBinder2);
        mService.registerReceiver(PACKAGE_NAME, receiverEndpointId2, payloadCallback2);

        assertThat(mConnectingReceiverServices.size()).isEqualTo(1);
        assertThat(mConnectedReceiverServiceMap.size()).isEqualTo(0);
        assertThat(mCachedReceiverEndpointMap.size()).isEqualTo(2);
        assertThat(mRegisteredReceiverEndpointMap.size()).isEqualTo(0);

        ReceiverEndpointToken receiverEndpointToken2 =
                new ReceiverEndpointToken(clientToken, receiverEndpointId2);
        assertThat(mCachedReceiverEndpointMap.get(receiverEndpointToken2))
                .isEqualTo(payloadCallback2);
    }

    @Test
    public void testRegisterReceiverThenConnectReceiverService() {
        ServiceConnection[] connection = new ServiceConnection[1];
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            Intent intent = (Intent) args[0];

            assertThat(intent.getAction()).isEqualTo(CAR_INTENT_ACTION_RECEIVER_SERVICE);

            connection[0] = (ServiceConnection) args[1];
            return null;
        }).when(mContext).bindServiceAsUser(any(Intent.class), any(ServiceConnection.class),
                anyInt(), any(UserHandle.class));

        // Since the receiver service is not connected, the receiver endpoint is
        // pending registration.
        UserHandle receiverUserHandle = Binder.getCallingUserHandle();
        when(mOccupantZoneService.getOccupantZoneForUser(receiverUserHandle))
                .thenReturn(mReceiverZone);
        mService.registerReceiver(PACKAGE_NAME, RECEIVER_ENDPOINT_ID, mPayloadCallback);

        // The receiver service is connected.
        ComponentName componentName = mock(ComponentName.class);
        IBinder binder = mock(IBinder.class);
        IBackendReceiver receiverService = mock(IBackendReceiver.class);
        when(binder.queryLocalInterface(anyString())).thenReturn(receiverService);
        when(receiverService.asBinder()).thenReturn(binder);
        connection[0].onServiceConnected(componentName, binder);

        // The receiver endpoint should be registered.
        assertThat(mConnectingReceiverServices.size()).isEqualTo(0);
        assertThat(mConnectedReceiverServiceMap.size()).isEqualTo(1);
        assertThat(mCachedReceiverEndpointMap.size()).isEqualTo(0);
        assertThat(mRegisteredReceiverEndpointMap.size()).isEqualTo(1);

        ClientToken clientToken = mConnectedReceiverServiceMap.keyAt(0);
        assertThat(mConnectedReceiverServiceMap.get(clientToken))
                .isEqualTo(receiverService);

        ReceiverEndpointToken receiverEndpointToken =
                new ReceiverEndpointToken(clientToken, RECEIVER_ENDPOINT_ID);
        assertThat(mRegisteredReceiverEndpointMap.get(receiverEndpointToken))
                .isEqualTo(mPayloadCallback);
    }

    @Test
    public void testRegisterReceiverWithReceiverServiceConnected() {
        ServiceConnection[] connection = new ServiceConnection[1];
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            Intent intent = (Intent) args[0];

            assertThat(intent.getAction()).isEqualTo(CAR_INTENT_ACTION_RECEIVER_SERVICE);

            connection[0] = (ServiceConnection) args[1];
            return null;
        }).when(mContext).bindServiceAsUser(any(Intent.class), any(ServiceConnection.class),
                anyInt(), any(UserHandle.class));

        // Register the first receiver endpoint.
        UserHandle receiverUserHandle = Binder.getCallingUserHandle();
        when(mOccupantZoneService.getOccupantZoneForUser(receiverUserHandle))
                .thenReturn(mReceiverZone);
        mService.registerReceiver(PACKAGE_NAME, RECEIVER_ENDPOINT_ID, mPayloadCallback);

        // The receiver service is connected.
        ComponentName componentName = mock(ComponentName.class);
        IBinder binder = mock(IBinder.class);
        IBackendReceiver receiverService = mock(IBackendReceiver.class);
        when(binder.queryLocalInterface(anyString())).thenReturn(receiverService);
        when(receiverService.asBinder()).thenReturn(binder);
        connection[0].onServiceConnected(componentName, binder);

        // Register the second receiver endpoint.
        String receiverEndpointId2 = "ID2";
        IPayloadCallback payloadCallback2 = mock(IPayloadCallback.class);
        IBinder payloadCallbackBinder2 = mock(IBinder.class);
        when(payloadCallback2.asBinder()).thenReturn(payloadCallbackBinder2);
        mService.registerReceiver(PACKAGE_NAME, receiverEndpointId2, payloadCallback2);

        assertThat(mConnectingReceiverServices.size()).isEqualTo(0);
        assertThat(mConnectedReceiverServiceMap.size()).isEqualTo(1);
        assertThat(mCachedReceiverEndpointMap.size()).isEqualTo(0);
        assertThat(mRegisteredReceiverEndpointMap.size()).isEqualTo(2);

        ClientToken clientToken = mConnectedReceiverServiceMap.keyAt(0);
        ReceiverEndpointToken receiverEndpointToken2 =
                new ReceiverEndpointToken(clientToken, receiverEndpointId2);
        assertThat(mRegisteredReceiverEndpointMap.get(receiverEndpointToken2))
                .isEqualTo(payloadCallback2);

        // The receiver service is disconnected.
        connection[0].onServiceDisconnected(componentName);

        assertThat(mConnectingReceiverServices.size()).isEqualTo(0);
        assertThat(mConnectedReceiverServiceMap.size()).isEqualTo(0);
    }
}
