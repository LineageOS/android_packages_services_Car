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

import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.CarRemoteDeviceManager;
import android.car.occupantconnection.ICarOccupantConnection;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class CarRemoteDeviceManagerUnitTest {

    private static final String PACKAGE_NAME = "my_package_name";

    @Mock
    private Car mCar;
    @Mock
    private IBinder mBinder;
    @Mock
    private ICarOccupantConnection mService;
    @Mock
    private Context mContext;

    private CarRemoteDeviceManager mRemoteDeviceManager;

    @Before
    public void setUp() {
        when(mBinder.queryLocalInterface(anyString())).thenReturn(mService);
        when(mCar.getContext()).thenReturn(mContext);
        when(mContext.getPackageName()).thenReturn(PACKAGE_NAME);

        mRemoteDeviceManager = new CarRemoteDeviceManager(mCar, mBinder);
    }

    @Test
    public void testGetEndpointPackageInfo() throws RemoteException {
        int zoneId = 0;
        OccupantZoneInfo zone =
                new OccupantZoneInfo(zoneId, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        PackageInfo expectedValue = mock(PackageInfo.class);
        when(mService.getEndpointPackageInfo(eq(zoneId), eq(PACKAGE_NAME)))
                .thenReturn(expectedValue);

        assertThat(mRemoteDeviceManager.getEndpointPackageInfo(zone)).isEqualTo(expectedValue);
    }

    @Test
    public void testControlOccupantZonePowerOn() throws RemoteException {
        int zoneId = 0;
        OccupantZoneInfo zone =
                new OccupantZoneInfo(zoneId, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);

        mRemoteDeviceManager.controlOccupantZonePower(zone, true);
        verify(mService).controlOccupantZonePower(zone, true);
    }

    @Test
    public void testControlOccupantZonePowerOff() throws RemoteException {
        int zoneId = 0;
        OccupantZoneInfo zone =
                new OccupantZoneInfo(zoneId, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);

        mRemoteDeviceManager.controlOccupantZonePower(zone, false);
        verify(mService).controlOccupantZonePower(zone, false);
    }

    @Test
    public void testIsOccupantZonePowerOn() throws RemoteException {
        int zoneId = 0;
        OccupantZoneInfo zone =
                new OccupantZoneInfo(zoneId, OCCUPANT_TYPE_DRIVER, SEAT_ROW_1_LEFT);
        boolean expectedValue = true;
        when(mService.isOccupantZonePowerOn(zone)).thenReturn(expectedValue);

        assertThat(mRemoteDeviceManager.isOccupantZonePowerOn(zone)).isEqualTo(expectedValue);
    }
}
