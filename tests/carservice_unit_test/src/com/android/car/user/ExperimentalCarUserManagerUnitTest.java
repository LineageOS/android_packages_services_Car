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
package com.android.car.user;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;

import android.car.Car;
import android.car.ICarUserService;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.util.UserTestingHelper;
import android.car.user.CarUserManager;
import android.car.user.ExperimentalCarUserManager;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.List;

public final class ExperimentalCarUserManagerUnitTest extends AbstractExtendedMockitoTestCase {

    @Mock
    private Car mCar;
    @Mock
    private UserManager mUserManager;
    @Mock
    private ICarUserService mService;

    private ExperimentalCarUserManager mManager;

    @Before public void setFixtures() {
        mManager =
                ExperimentalCarUserManager.from(new CarUserManager(mCar, mService, mUserManager));
    }

    @Test
    public void testCreateDriver_Success_Admin() throws Exception {
        expectCreateDriverSucceed();
        int userId = mManager.createDriver("test driver", true);
        assertThat(userId).isNotEqualTo(UserHandle.USER_NULL);
    }

    @Test
    public void testCreateDriver_Success_NonAdmin() throws Exception {
        expectCreateDriverSucceed();
        int userId = mManager.createDriver("test driver", false);
        assertThat(userId).isNotEqualTo(UserHandle.USER_NULL);
    }

    @Test
    public void testCreateDriver_Error() throws Exception {
        expectCreateDriverFail();
        int userId = mManager.createDriver("test driver", false);
        assertThat(userId).isEqualTo(UserHandle.USER_NULL);
    }

    @Test
    public void testCreatePassenger_Success() throws Exception {
        expectCreatePassengerSucceed();
        int userId = mManager.createPassenger("test passenger", 10);
        assertThat(userId).isNotEqualTo(UserHandle.USER_NULL);
    }

    @Test
    public void testCreatePassenger_Error() throws Exception {
        expectCreatePassengerFail();
        int userId = mManager.createPassenger("test passenger", 20);
        assertThat(userId).isEqualTo(UserHandle.USER_NULL);
    }

    @Test
    public void testSwitchDriver_Success() throws Exception {
        expectSwitchDriverSucceed();
        boolean success = mManager.switchDriver(10);
        assertThat(success).isTrue();
    }

    @Test
    public void testSwitchDriver_Error() throws Exception {
        expectSwitchDriverFail();
        boolean success = mManager.switchDriver(20);
        assertThat(success).isFalse();
    }

    @Test
    public void testGetAllDrivers() throws Exception {
        List<UserInfo> userInfos = UserTestingHelper.newUsers(10, 20, 30);
        doReturn(userInfos).when(mService).getAllDrivers();
        List<Integer> drivers = mManager.getAllDrivers();
        assertThat(drivers.equals(Arrays.asList(10, 20, 30))).isTrue();
    }

    @Test
    public void testGetAllPassengers() throws Exception {
        List<UserInfo> userInfos = UserTestingHelper.newUsers(100, 101, 102);
        doReturn(userInfos).when(mService).getPassengers(10);
        doReturn(Arrays.asList()).when(mService).getPassengers(20);

        List<Integer> passengers = mManager.getPassengers(10);
        assertThat(passengers.equals(Arrays.asList(100, 101, 102))).isTrue();

        passengers = mManager.getPassengers(20);
        assertThat(passengers.size()).isEqualTo(0);
    }

    @Test
    public void testStartPassenger_Success() throws Exception {
        expectStartPassengerSucceed();
        boolean success = mManager.startPassenger(100, /* zoneId = */ 1);
        assertThat(success).isTrue();
    }

    @Test
    public void testStartPassenger_Error() throws Exception {
        expectStartPassengerFail();
        boolean success = mManager.startPassenger(200, /* zoneId = */ 1);
        assertThat(success).isFalse();
    }

    @Test
    public void testStopPassenger_Success() throws Exception {
        expectStopPassengerSucceed();
        boolean success = mManager.stopPassenger(100);
        assertThat(success).isTrue();
    }

    @Test
    public void testStopPassenger_Error() throws Exception {
        expectStopPassengerFail();
        boolean success = mManager.stopPassenger(200);
        assertThat(success).isFalse();
    }

    private void expectCreateDriverSucceed() throws Exception {
        UserInfo userInfo = UserTestingHelper.newUser(10);
        doReturn(userInfo).when(mService).createDriver(eq("test driver"), anyBoolean());
    }

    private void expectCreateDriverFail() throws Exception {
        doReturn(null).when(mService).createDriver(eq("test driver"), anyBoolean());
    }

    private void expectCreatePassengerSucceed() throws Exception {
        UserInfo userInfo = UserTestingHelper.newUser(100);
        doReturn(userInfo).when(mService).createPassenger("test passenger", 10);
    }

    private void expectCreatePassengerFail() throws Exception {
        doReturn(null).when(mService).createPassenger("test passenger", 10);
    }

    private void expectSwitchDriverSucceed() throws Exception {
        doReturn(true).when(mService).switchDriver(10);
    }

    private void expectSwitchDriverFail() throws Exception {
        doReturn(false).when(mService).switchDriver(20);
    }

    private void expectStartPassengerSucceed() throws Exception {
        doReturn(true).when(mService).startPassenger(100, 1);
    }

    private void expectStartPassengerFail() throws Exception {
        doReturn(false).when(mService).startPassenger(200, 1);
    }

    private void expectStopPassengerSucceed() throws Exception {
        doReturn(true).when(mService).stopPassenger(100);
    }

    private void expectStopPassengerFail() throws Exception {
        doReturn(false).when(mService).stopPassenger(200);
    }
}
