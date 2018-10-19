/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.car.user.CarUserManagerHelper;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.location.LocationManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * This class contains unit tests for the {@link CarUserService}.
 *
 * The following mocks are used:
 * <ol>
 *   <li> {@link Context} provides system services and resources.
 *   <li> {@link CarUserManagerHelper} provides user info and actions.
 * <ol/>
 */
@RunWith(AndroidJUnit4.class)
public class CarUserServiceTest {
    private CarUserService mCarUserService;

    @Mock
    private Context mMockContext;

    @Mock
    private Context mApplicationContext;

    @Mock
    private LocationManager mLocationManager;

    @Mock
    private CarUserManagerHelper mCarUserManagerHelper;

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUpMocks() throws Exception {
        MockitoAnnotations.initMocks(this);
        doReturn(mApplicationContext).when(mMockContext).getApplicationContext();
        doReturn(mLocationManager).when(mMockContext).getSystemService(Context.LOCATION_SERVICE);

        mCarUserService = new CarUserService(mMockContext, mCarUserManagerHelper);

        doReturn(new ArrayList<>()).when(mCarUserManagerHelper).getAllUsers();
    }

    /**
     * Test that the {@link CarUserService} registers to receive the locked boot completed
     * intent.
     */
    @Test
    public void testRegistersToReceiveEvents() {
        ArgumentCaptor<IntentFilter> argument = ArgumentCaptor.forClass(IntentFilter.class);
        mCarUserService.init();
        verify(mMockContext).registerReceiver(eq(mCarUserService), argument.capture());
        IntentFilter intentFilter = argument.getValue();
        assertThat(intentFilter.countActions()).isEqualTo(2);

        assertThat(intentFilter.getAction(0)).isEqualTo(Intent.ACTION_LOCKED_BOOT_COMPLETED);
        assertThat(intentFilter.getAction(1)).isEqualTo(Intent.ACTION_USER_SWITCHED);
    }

    /**
     * Test that the {@link CarUserService} unregisters its event receivers.
     */
    @Test
    public void testUnregistersEventReceivers() {
        mCarUserService.release();
        verify(mMockContext).unregisterReceiver(mCarUserService);
    }

    /**
     * Test that the {@link CarUserService} starts up a secondary admin user upon first run.
     */
    @Test
    public void testStartsSecondaryAdminUserOnFirstRun() {
        UserInfo admin = mockAdmin(/* adminId= */ 10);

        mCarUserService.onReceive(mMockContext,
                new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));

        verify(mCarUserManagerHelper).createNewAdminUser(CarUserService.OWNER_NAME);
        verify(mCarUserManagerHelper).switchToUser(admin);
    }

    /**
     * Test that the {@link CarUserService} disable modify account for user 0 upon first run.
     */
    @Test
    public void testDisableModifyAccountsForSystemUserOnFirstRun() {
        // Mock system user.
        UserInfo systemUser = new UserInfo();
        systemUser.id = UserHandle.USER_SYSTEM;
        doReturn(systemUser).when(mCarUserManagerHelper).getSystemUserInfo();

        mockAdmin(10);

        mCarUserService.onReceive(mMockContext,
                new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));

        verify(mCarUserManagerHelper)
                .setUserRestriction(systemUser, UserManager.DISALLOW_MODIFY_ACCOUNTS, true);
    }

    /**
     * Test that the {@link CarUserService} disable location service for user 0 upon first run.
     */
    @Test
    public void testDisableLocationForSystemUserOnFirstRun() {
        mockAdmin(/* adminId= */ 10);

        mCarUserService.onReceive(mMockContext,
                new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));

        verify(mLocationManager).setLocationEnabledForUser(
                /* enabled= */ false, UserHandle.of(UserHandle.USER_SYSTEM));
    }

    /**
     * Test that the {@link CarUserService} updates last active user to the first admin user
     * on first run.
     */
    @Test
    public void testUpdateLastActiveUserOnFirstRun() {
        UserInfo admin = mockAdmin(/* adminId= */ 10);

        mCarUserService.onReceive(mMockContext,
                new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));

        verify(mCarUserManagerHelper)
                .setLastActiveUser(admin.id, /* skipGlobalSetting= */ false);
    }

    /**
     * Test that the {@link CarUserService} starts up the last active user on reboot.
     */
    @Test
    public void testStartsLastActiveUserOnReboot() {
        List<UserInfo> users = new ArrayList<>();

        int adminUserId = 10;
        UserInfo admin = new UserInfo(adminUserId, CarUserService.OWNER_NAME, UserInfo.FLAG_ADMIN);

        int secUserId = 11;
        UserInfo secUser =
                new UserInfo(secUserId, CarUserService.OWNER_NAME, UserInfo.FLAG_ADMIN);

        users.add(admin);
        users.add(secUser);

        doReturn(users).when(mCarUserManagerHelper).getAllUsers();
        doReturn(secUserId).when(mCarUserManagerHelper).getInitialUser();

        mCarUserService.onReceive(mMockContext,
                new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));

        verify(mCarUserManagerHelper).switchToUserId(secUserId);
    }

    /**
     * Test that the {@link CarUserService} updates last active user on user switch intent.
     */
    @Test
    public void testLastActiveUserUpdatedOnUserSwitch() {
        int lastActiveUserId = 11;

        Intent intent = new Intent(Intent.ACTION_USER_SWITCHED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, lastActiveUserId);

        doReturn(true).when(mCarUserManagerHelper).isPersistentUser(lastActiveUserId);

        mCarUserService.onReceive(mMockContext, intent);

        verify(mCarUserManagerHelper).setLastActiveUser(
                lastActiveUserId, /* skipGlobalSetting= */ false);
    }

    /**
     * Test that the {@link CarUserService} sets default guest restrictions on first boot.
     */
    @Test
    public void testInitializeGuestRestrictionsOnFirstRun() {
        mockAdmin(/* adminId= */ 10);

        mCarUserService.onReceive(mMockContext,
                new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));

        verify(mCarUserManagerHelper).initDefaultGuestRestrictions();
    }

    private UserInfo mockAdmin(int adminId) {
        UserInfo admin = new UserInfo(adminId, CarUserService.OWNER_NAME, UserInfo.FLAG_ADMIN);
        doReturn(admin).when(mCarUserManagerHelper).createNewAdminUser(CarUserService.OWNER_NAME);
        return admin;
    }
}
