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

package com.android.car;

import static android.car.app.CarActivityManager.RESULT_SUCCESS;
import static android.car.builtin.os.UserManagerHelper.USER_NULL;

import static com.android.car.CarServiceHelperWrapperTimeout.createWithImmediateTimeout;
import static com.android.car.internal.common.CommonConstants.INVALID_PID;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.app.CarActivityManager;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.ComponentName;
import android.os.RemoteException;
import android.os.UserHandle;
import android.view.Display;

import com.android.car.internal.ICarServiceHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public final class CarServiceHelperWrapperUnitTest extends AbstractExtendedMockitoTestCase {

    @Mock
    private ICarServiceHelper mICarServiceHelper;
    @Mock
    private Runnable mRunnable;

    private CarServiceHelperWrapper mDefaultWrapper;


    @Before
    public void setUp() throws Exception {
        mDefaultWrapper = CarServiceHelperWrapper.create();
    }

    @After
    public void tearDown() throws Exception {
        CarLocalServices.removeServiceForTest(CarServiceHelperWrapper.class);
    }

    @Test
    public void testRunOnConnection_alreadyConnected() {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);

        mDefaultWrapper.runOnConnection(mRunnable);

        verify(mRunnable).run();
    }

    @Test
    public void testRunOnConnection_connectLater() {
        mDefaultWrapper.runOnConnection(mRunnable);

        verify(mRunnable, never()).run();

        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);

        verify(mRunnable).run();
    }

    @Test
    public void testFetchAidlVhalPid() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);

        mDefaultWrapper.fetchAidlVhalPid();

        verify(mICarServiceHelper).fetchAidlVhalPid();
    }

    @Test
    public void testFetchAidlVhalPid_remoteExceptionFromService() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.fetchAidlVhalPid()).thenThrow(new RemoteException());

        assertThat(mDefaultWrapper.fetchAidlVhalPid()).isEqualTo(INVALID_PID);
    }

    @Test
    public void assignUserToExtraDisplay() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);

        mDefaultWrapper.assignUserToExtraDisplay(10, 0);

        verify(mICarServiceHelper).assignUserToExtraDisplay(10, 0);
    }

    @Test
    public void assignUserToExtraDisplay_remoteExceptionFromService() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.assignUserToExtraDisplay(10, 0)).thenThrow(new RemoteException());

        assertThat(mDefaultWrapper.assignUserToExtraDisplay(10, 0)).isFalse();
    }

    @Test
    public void testUnassignUserFromExtraDisplay() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);

        mDefaultWrapper.unassignUserFromExtraDisplay(10, 0);

        verify(mICarServiceHelper).unassignUserFromExtraDisplay(10, 0);
    }

    @Test
    public void testUnassignUserFromExtraDisplay_remoteExceptionFromService() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.unassignUserFromExtraDisplay(10, 0)).thenThrow(
                new RemoteException());

        assertThat(mDefaultWrapper.unassignUserFromExtraDisplay(10, 0)).isFalse();
    }

    @Test
    public void testStartUserInBackgroundVisibleOnDisplay() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);

        mDefaultWrapper.startUserInBackgroundVisibleOnDisplay(10, 0);

        verify(mICarServiceHelper).startUserInBackgroundVisibleOnDisplay(10, 0);
    }

    @Test
    public void testStartUserInBackgroundVisibleOnDisplay_remoteExceptionFromService()
            throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.startUserInBackgroundVisibleOnDisplay(10, 0)).thenThrow(
                new RemoteException());

        assertThat(mDefaultWrapper.startUserInBackgroundVisibleOnDisplay(10, 0)).isFalse();
    }

    @Test
    public void testGetMainDisplayAssignedToUser() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.getMainDisplayAssignedToUser(10)).thenReturn(1);

        assertThat(mDefaultWrapper.getMainDisplayAssignedToUser(10)).isEqualTo(1);
        verify(mICarServiceHelper).getMainDisplayAssignedToUser(10);
    }

    @Test
    public void testGetMainDisplayAssignedToUser_remoteExceptionFromService() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.getMainDisplayAssignedToUser(10)).thenThrow(new RemoteException());

        assertThat(mDefaultWrapper.getMainDisplayAssignedToUser(10)).isEqualTo(
                Display.INVALID_DISPLAY);
    }

    @Test
    public void testGetProcessGroup() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.getProcessGroup(123)).thenReturn(456);

        assertThat(mDefaultWrapper.getProcessGroup(123)).isEqualTo(456);
        verify(mICarServiceHelper).getProcessGroup(123);
    }

    @Test
    public void testGetProcessGroup_remoteExceptionFromService() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.getProcessGroup(123)).thenThrow(new RemoteException());

        assertThat(mDefaultWrapper.getProcessGroup(123)).isEqualTo(-1);
    }

    @Test
    public void testGetUserAssignedToDisplay() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.getUserAssignedToDisplay(1)).thenReturn(10);

        assertThat(mDefaultWrapper.getUserAssignedToDisplay(1)).isEqualTo(10);
        verify(mICarServiceHelper).getUserAssignedToDisplay(1);
    }

    @Test
    public void testGetUserAssignedToDisplay_remoteExceptionFromService() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.getUserAssignedToDisplay(1)).thenThrow(new RemoteException());

        assertThat(mDefaultWrapper.getUserAssignedToDisplay(1)).isEqualTo(USER_NULL);
    }

    @Test
    public void testRequiresDisplayCompatForUser() throws Exception {
        int userId = 20;
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.requiresDisplayCompatForUser("com.example.app", userId))
                .thenReturn(true);

        assertThat(
                mDefaultWrapper.requiresDisplayCompatForUser("com.example.app", userId)).isTrue();
        verify(mICarServiceHelper).requiresDisplayCompatForUser("com.example.app", userId);
    }

    @Test
    public void testRequiresDisplayCompatForUser_remoteExceptionFromService() throws Exception {
        int userId = 20;
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.requiresDisplayCompatForUser("com.example.app", userId))
                .thenThrow(new RemoteException());

        assertThat(
                mDefaultWrapper.requiresDisplayCompatForUser("com.example.app", userId)).isFalse();
    }

    @Test
    public void testSetDisplayAllowlistForUser() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        int[] displayIds = {1, 2, 3};

        mDefaultWrapper.setDisplayAllowlistForUser(10, displayIds);

        verify(mICarServiceHelper).setDisplayAllowlistForUser(10, displayIds);
    }

    @Test
    public void testSetDisplayAllowlistForUser_remoteExceptionFromService() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        doThrow(new RemoteException()).when(mICarServiceHelper).setDisplayAllowlistForUser(anyInt(),
                any(int[].class));
        int[] displayIds = {1, 2, 3};

        mDefaultWrapper.setDisplayAllowlistForUser(10, displayIds);
        // Nothing should happen
    }

    @Test
    public void testSetPassengerDisplays() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        int[] displayIds = {1, 2, 3};

        mDefaultWrapper.setPassengerDisplays(displayIds);

        verify(mICarServiceHelper).setPassengerDisplays(displayIds);
    }

    @Test
    public void testSetProcessProfile() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);

        mDefaultWrapper.setProcessProfile(123, 456, "profile");

        verify(mICarServiceHelper).setProcessProfile(123, 456, "profile");
    }

    @Test
    public void testSetProcessProfile_remoteExceptionFromService() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        doThrow(new RemoteException()).when(mICarServiceHelper).setProcessProfile(anyInt(),
                anyInt(), anyString());

        mDefaultWrapper.setProcessProfile(123, 456, "profile");
        // Nothing should happen
    }

    @Test
    public void testSetSafetyMode() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);

        mDefaultWrapper.setSafetyMode(true);

        verify(mICarServiceHelper).setSafetyMode(true);
    }

    @Test
    public void testSetSafetyMode_remoteExceptionFromService() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        doThrow(new RemoteException()).when(mICarServiceHelper).setSafetyMode(anyBoolean());

        mDefaultWrapper.setSafetyMode(true);
        // Nothing should happen
    }

    @Test
    public void testSetPersistentActivity() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        ComponentName activity = new ComponentName("com.example", "Activity1");
        when(mICarServiceHelper.setPersistentActivity(activity, 1, 10)).thenReturn(RESULT_SUCCESS);

        assertThat(mDefaultWrapper.setPersistentActivity(activity, 1, 10)).isEqualTo(
                CarActivityManager.RESULT_SUCCESS);
    }

    @Test
    public void testSetPersistentActivity_remoteExceptionFromService() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        ComponentName activity = new ComponentName("com.example", "Activity1");
        when(mICarServiceHelper.setPersistentActivity(activity, 1, 10)).thenThrow(
                new RemoteException());

        assertThat(mDefaultWrapper.setPersistentActivity(activity, 1, 10)).isEqualTo(
                CarActivityManager.RESULT_FAILURE);
    }

    @Test
    public void testSendInitialUser() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);
        UserHandle userHandle = new UserHandle(42);

        mDefaultWrapper.sendInitialUser(userHandle);

        verify(mICarServiceHelper).sendInitialUser(userHandle);
    }

    @Test
    public void testSetProcessGroup() throws Exception {
        mDefaultWrapper.setCarServiceHelper(mICarServiceHelper);

        mDefaultWrapper.setProcessGroup(0, 0);

        verify(mICarServiceHelper).setProcessGroup(0, 0);
    }

    @Test
    public void testThrowWhenNotConnected() {
        CarServiceHelperWrapper wrapper = createWithImmediateTimeout();

        // Check exception when not connected. All arguments do not matter.
        assertThrows(IllegalStateException.class,
                () -> wrapper.createUserEvenWhenDisallowed("", "", 0));
        assertThrows(IllegalStateException.class, () -> wrapper.getMainDisplayAssignedToUser(0));
        assertThrows(IllegalStateException.class, () -> wrapper.getProcessGroup(0));
        assertThrows(IllegalStateException.class, () -> wrapper.getUserAssignedToDisplay(0));
        assertThrows(IllegalStateException.class,
                () -> wrapper.setDisplayAllowlistForUser(0, null));
        assertThrows(IllegalStateException.class, () -> wrapper.sendInitialUser(UserHandle.SYSTEM));
        assertThrows(IllegalStateException.class, () -> wrapper.setPassengerDisplays(null));
        assertThrows(IllegalStateException.class, () -> wrapper.setPersistentActivity(null, 0, 0));
        assertThrows(IllegalStateException.class, () -> wrapper.setProcessGroup(0, 0));
        assertThrows(IllegalStateException.class, () -> wrapper.setProcessProfile(0, 0, ""));
        assertThrows(IllegalStateException.class, () -> wrapper.setSafetyMode(true));
        assertThrows(IllegalStateException.class,
                () -> wrapper.startUserInBackgroundVisibleOnDisplay(0, 0));
    }
}
