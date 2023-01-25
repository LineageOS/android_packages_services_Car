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

package com.android.car;

import static android.car.CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER;
import static android.car.VehicleAreaSeat.SEAT_ROW_1_LEFT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.occupantconnection.CarOccupantConnectionManager;
import android.car.occupantconnection.CarOccupantConnectionManager.ConnectionRequestCallback;
import android.car.occupantconnection.ICarOccupantConnection;
import android.car.occupantconnection.IConnectionRequestCallback;
import android.os.IBinder;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.concurrent.Executor;

@RunWith(MockitoJUnitRunner.class)
public final class CarOccupantConnectionManagerUnitTest {

    @Mock
    private Car mCar;
    @Mock
    private IBinder mBinder;
    @Mock
    private ICarOccupantConnection mService;
    @Mock
    private Executor mCallbackExecutor;
    @Mock
    private ConnectionRequestCallback mConnectionRequestCallback;

    private final OccupantZoneInfo mReceiverZone =
            new OccupantZoneInfo(/* zoneId= */ 0, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);

    private CarOccupantConnectionManager mOccupantConnectionManager;

    @Before
    public void setUp() {
        when(mBinder.queryLocalInterface(anyString())).thenReturn(mService);
        mOccupantConnectionManager = new CarOccupantConnectionManager(mCar, mBinder);
    }

    @Test
    public void testRequestConnectionWithNullParameters() {
        assertThrows(NullPointerException.class,
                () -> mOccupantConnectionManager.requestConnection(
                        /* receiverZone= */ null, mCallbackExecutor, mConnectionRequestCallback));
        assertThrows(NullPointerException.class,
                () -> mOccupantConnectionManager.requestConnection(
                        mReceiverZone, /* executor= */ null, mConnectionRequestCallback));
        assertThrows(NullPointerException.class,
                () -> mOccupantConnectionManager.requestConnection(
                        mReceiverZone, mCallbackExecutor, /* callback= */ null));
    }

    @Test
    public void testRequestConnectionWithDuplicateCallbacks() {
        mOccupantConnectionManager.requestConnection(mReceiverZone, mCallbackExecutor,
                mConnectionRequestCallback);

        // Duplicate callbacks are not allowed.
        assertThrows(IllegalStateException.class,
                () -> mOccupantConnectionManager.requestConnection(
                        mReceiverZone, mCallbackExecutor, mConnectionRequestCallback));
    }

    @Test
    public void testRequestConnectionWithRemoteException() throws RemoteException {
        // The first call fails due to a RemoteException.
        doThrow(new RemoteException())
                .when(mService).requestConnection(anyInt(), eq(mReceiverZone), any());
        mOccupantConnectionManager.requestConnection(mReceiverZone, mCallbackExecutor,
                mConnectionRequestCallback);

        // The second call succeeds. It should not trigger IllegalStateException because
        // mConnectionRequestCallback was not added previously.
        doNothing().when(mService).requestConnection(anyInt(), eq(mReceiverZone), any());
        mOccupantConnectionManager.requestConnection(mReceiverZone, mCallbackExecutor,
                mConnectionRequestCallback);
    }

    @Test
    public void testRequestConnectionCallbackInvoked() throws RemoteException {
        IConnectionRequestCallback[] binderCallback = new IConnectionRequestCallback[1];
        doAnswer((invocation) -> {
            Object[] args = invocation.getArguments();
            OccupantZoneInfo receiverZone = (OccupantZoneInfo) args[1];
            assertThat(receiverZone).isEqualTo(mReceiverZone);
            binderCallback[0] = (IConnectionRequestCallback) args[2];
            // Don't call binderCallback[0].onConnected() here, because mConnectionRequestCallback
            // is added AFTER this method returns. So if we call binderCallback.onConnected() here,
            // mConnectionRequestCallback will not be invoked.
            return null;
        }).when(mService).requestConnection(anyInt(), any(), any());

        mOccupantConnectionManager.requestConnection(mReceiverZone, command -> {
            command.run();
            // Verify that mConnectionRequestCallback is invoked on the Executor.
            verify(mConnectionRequestCallback, timeout(1000)).onConnected(mReceiverZone);
        }, mConnectionRequestCallback);
        binderCallback[0].onConnected(/* requestId= */ 0, mReceiverZone);
    }
}
