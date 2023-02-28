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

import static android.car.CarOccupantZoneManager.INVALID_USER_ID;
import static android.car.CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER;
import static android.car.VehicleAreaSeat.SEAT_ROW_1_LEFT;
import static android.car.test.mocks.AndroidMockitoHelper.mockContextCreateContextAsUser;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.android.car.CarLocalServices;
import com.android.car.CarOccupantZoneService;
import com.android.car.power.CarPowerManagementService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class CarOccupantConnectionServiceTest {

    private static final String PACKAGE_NAME = "my_package_name";

    @Mock
    private Context mContext;
    @Mock
    private CarOccupantZoneService mOccupantZoneService;
    @Mock
    private CarPowerManagementService mPowerManagementService;

    private CarOccupantConnectionService mService;

    @Before
    public void setUp() {
        // Stored as static: Other tests can leave things behind and fail this test in add call.
        // So just remove as safety guard.
        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
        CarLocalServices.addService(CarOccupantZoneService.class, mOccupantZoneService);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.addService(CarPowerManagementService.class, mPowerManagementService);

        mService = new CarOccupantConnectionService(mContext, mOccupantZoneService,
                mPowerManagementService);
    }

    @After
    public void tearDown() {
        CarLocalServices.removeServiceForTest(CarOccupantZoneService.class);
    }

    @Test
    public void testGetEndpointPackageInfoWithoutPermission() {
        int occupantZoneId = 0;
        when(mContext.checkCallingOrSelfPermission(eq(Car.PERMISSION_MANAGE_REMOTE_DEVICE)))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertThrows(SecurityException.class,
                () -> mService.getEndpointPackageInfo(occupantZoneId, PACKAGE_NAME));
    }

    @Test
    public void testGetEndpointPackageInfoWithInvalidUserId() {
        mService.init();
        int occupantZoneId = 0;
        when(mOccupantZoneService.getUserForOccupant(occupantZoneId)).thenReturn(
                INVALID_USER_ID);

        assertThat(mService.getEndpointPackageInfo(occupantZoneId, PACKAGE_NAME)).isNull();
    }

    @Test
    public void testGetEndpointPackageInfo() throws PackageManager.NameNotFoundException {
        mService.init();
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

        mService.controlOccupantZonePower(occupantZoneInfo, true);
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

        mService.controlOccupantZonePower(occupantZoneInfo, false);
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
}
