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
import static org.mockito.Mockito.when;

import android.car.user.CarUserManagerHelper;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.test.runner.AndroidJUnit4;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
    private CarUserManagerHelper mCarUserManagerHelper;

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getApplicationContext()).thenReturn(mApplicationContext);

        mCarUserService = new CarUserService(mMockContext, mCarUserManagerHelper);
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
        List<UserInfo> users = new ArrayList<>();

        int adminUserId = 10;
        UserInfo admin = new UserInfo(adminUserId, CarUserService.OWNER_NAME, UserInfo.FLAG_ADMIN);

        doReturn(users).when(mCarUserManagerHelper).getAllUsers();
        doReturn(admin).when(mCarUserManagerHelper).createNewAdminUser(CarUserService.OWNER_NAME);
        doReturn(true).when(mCarUserManagerHelper).switchToUser(admin);

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
        List<UserInfo> users = new ArrayList<>();

        UserInfo user0 = new UserInfo();
        user0.id = UserHandle.USER_SYSTEM;
        int adminUserId = 10;
        UserInfo admin = new UserInfo(adminUserId, CarUserService.OWNER_NAME, UserInfo.FLAG_ADMIN);

        doReturn(users).when(mCarUserManagerHelper).getAllUsers();
        doReturn(user0).when(mCarUserManagerHelper).getSystemUserInfo();
        doReturn(admin).when(mCarUserManagerHelper).createNewAdminUser(CarUserService.OWNER_NAME);
        doReturn(true).when(mCarUserManagerHelper).switchToUser(admin);

        mCarUserService.onReceive(mMockContext,
                new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));

        verify(mCarUserManagerHelper)
                .setUserRestriction(user0, UserManager.DISALLOW_MODIFY_ACCOUNTS, true);
        verify(mCarUserManagerHelper)
                .setLastActiveUser(adminUserId, /* skipGlobalSetting= */ false);
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

        doReturn(false).when(mCarUserManagerHelper).isForegroundUserEphemeral();

        Intent intent = new Intent(Intent.ACTION_USER_SWITCHED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, lastActiveUserId);
        mCarUserService.onReceive(mMockContext, intent);

        verify(mCarUserManagerHelper).setLastActiveUser(
                lastActiveUserId, /* skipGlobalSetting= */ false);
    }
}
