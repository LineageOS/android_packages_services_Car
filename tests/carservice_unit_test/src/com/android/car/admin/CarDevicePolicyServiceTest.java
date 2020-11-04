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
package com.android.car.admin;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.car.admin.CarDevicePolicyManager;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.user.UserCreationResult;
import android.content.pm.UserInfo;
import android.content.pm.UserInfo.UserInfoFlag;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

import com.android.car.user.CarUserService;
import com.android.internal.infra.AndroidFuture;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public final class CarDevicePolicyServiceTest extends AbstractExtendedMockitoTestCase {

    private static final String TAG = CarDevicePolicyServiceTest.class.getSimpleName();

    private static final int TIMEOUT_MS = 100;

    @Mock
    private CarUserService mCarUserService;

    private CarDevicePolicyService mService;

    @Before
    public void setFixtures() {
        mService = new CarDevicePolicyService(mCarUserService, /* futureTimeoutMs= */ TIMEOUT_MS);
    }

    @Test
    public void testRemoveUser() {
        mService.removeUser(42);

        verify(mCarUserService).removeUser(42, /* hasCallerRestrictions= */ true);
    }

    @Test
    public void testCreateUser_failure_invalidTypes() {
        invalidCreateUserTypeTest(CarDevicePolicyManager.FIRST_USER_TYPE - 1);
        invalidCreateUserTypeTest(CarDevicePolicyManager.LAST_USER_TYPE + 1);
    }

    private void invalidCreateUserTypeTest(@CarDevicePolicyManager.UserType int type) {
        UserCreationResult result = mService.createUser("name", type);

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo(UserCreationResult.STATUS_INVALID_REQUEST);
        assertThat(result.getUser()).isNull();
    }

    @Test
    public void testCreateUser_ok_normalUser() {
        createUserOkTest(/* userInfoFlags=*/ 0, CarDevicePolicyManager.USER_TYPE_REGULAR,
                UserManager.USER_TYPE_FULL_SECONDARY);
    }

    @Test
    public void testCreateUser_ok_admin() {
        createUserOkTest(UserInfo.FLAG_ADMIN, CarDevicePolicyManager.USER_TYPE_ADMIN,
                UserManager.USER_TYPE_FULL_SECONDARY);
    }

    @Test
    public void testCreateUser_ok_guest() {
        createUserOkTest(/* userInfoFlags=*/ 0, CarDevicePolicyManager.USER_TYPE_GUEST,
                UserManager.USER_TYPE_FULL_GUEST);
    }

    private void createUserOkTest(@UserInfoFlag int flags,
            @CarDevicePolicyManager.UserType int carDpmUserType, @NonNull String userType) {
        UserCreationResult result = new UserCreationResult(UserCreationResult.STATUS_SUCCESSFUL);
        doAnswer((inv) -> {
            Log.d(TAG, "returning " + result + " for user " + userType + " and flags " + flags);
            @SuppressWarnings("unchecked")
            AndroidFuture<UserCreationResult> receiver = (AndroidFuture<UserCreationResult>) inv
                    .getArguments()[4];
            receiver.complete(result);
            return null;
        }).when(mCarUserService).createUser(eq("name"), eq(userType), eq(flags),
                /* timeoutMs= */ anyInt(), /* receiver= */ any());

        UserCreationResult actualResult = mService.createUser("name", carDpmUserType);

        assertThat(actualResult).isSameInstanceAs(result);
    }

    @Test
    public void testCreateUser_timeout() {
        doAnswer((inv) -> {
            int sleep = TIMEOUT_MS + 1_000;
            Log.d(TAG, "sleeping " + sleep + "ms so AndroidFuture times out");
            SystemClock.sleep(sleep);
            Log.d(TAG, "wokkkkke up!!");
            return null;
        }).when(mCarUserService).createUser(eq("name"), eq(UserManager.USER_TYPE_FULL_SECONDARY),
                /* flags= */ eq(0), /* timeoutMs= */ anyInt(), /* receiver= */ any());

        UserCreationResult actualResult = mService.createUser("name", /* flags= */ 0);

        assertThat(actualResult).isNotNull();
        assertThat(actualResult.isSuccess()).isFalse();
        assertThat(actualResult.getStatus())
                .isEqualTo(UserCreationResult.STATUS_HAL_INTERNAL_FAILURE);
        assertThat(actualResult.getUser()).isNull();
    }
}
