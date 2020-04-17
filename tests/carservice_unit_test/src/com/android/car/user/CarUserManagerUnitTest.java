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

import static android.os.UserHandle.USER_SYSTEM;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.car.Car;
import android.car.ICarUserService;
import android.car.user.CarUserManager;
import android.car.user.UserSwitchResult;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserManager;

import com.android.internal.infra.AndroidFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public final class CarUserManagerUnitTest {

    private static final long ASYNC_TIMEOUT_MS = 500;

    @Mock
    private Car mCar;
    @Mock
    private UserManager mUserManager;
    @Mock
    private ICarUserService mService;

    private MockitoSession mSession;
    private CarUserManager mMgr;

    @Before
    public void setFixtures() {
        mSession = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(UserManager.class)
                .initMocks(this)
                .startMocking();
        mMgr = new CarUserManager(mCar, mService, mUserManager);
    }

    @After
    public void finishSession() throws Exception {
        mSession.finishMocking();
    }

    @Test
    public void testIsValidUser_headlessSystemUser() {
        setHeadlessSystemUserMode(true);
        setExistingUsers(USER_SYSTEM);

        assertThat(mMgr.isValidUser(USER_SYSTEM)).isFalse();
    }

    @Test
    public void testIsValidUser_nonHeadlessSystemUser() {
        setHeadlessSystemUserMode(false);
        setExistingUsers(USER_SYSTEM);

        assertThat(mMgr.isValidUser(USER_SYSTEM)).isTrue();
    }

    @Test
    public void testIsValidUser_found() {
        setExistingUsers(1, 2, 3);

        assertThat(mMgr.isValidUser(1)).isTrue();
        assertThat(mMgr.isValidUser(2)).isTrue();
        assertThat(mMgr.isValidUser(3)).isTrue();
    }

    @Test
    public void testIsValidUser_notFound() {
        setExistingUsers(1, 2, 3);

        assertThat(mMgr.isValidUser(4)).isFalse();
    }

    @Test
    public void testIsValidUser_emptyUsers() {
        assertThat(mMgr.isValidUser(666)).isFalse();
    }

    @Test
    public void testSwitchUser_success() throws Exception {
        expectServiceSwitchUserSucceeds(11, UserSwitchResult.STATUS_SUCCESSFUL,
                "D'OH!");

        AndroidFuture<UserSwitchResult> future = mMgr.switchUser(11);

        assertThat(future).isNotNull();
        UserSwitchResult result = getResult(future);
        assertThat(result.getStatus()).isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);
        assertThat(result.getErrorMessage()).isEqualTo("D'OH!");
    }

    @Test
    public void testSwitchUser_remoteException() throws Exception {
        expectServiceSwitchUserSucceeds(11);
        expectCarHandleExceptionReturnsDefaultValue();

        AndroidFuture<UserSwitchResult> future = mMgr.switchUser(11);

        assertThat(future).isNotNull();
        UserSwitchResult result = getResult(future);
        assertThat(result.getStatus()).isEqualTo(UserSwitchResult.STATUS_HAL_INTERNAL_FAILURE);
        assertThat(result.getErrorMessage()).isNull();
    }

    private void expectServiceSwitchUserSucceeds(@UserIdInt int userId,
            @UserSwitchResult.Status int status, @Nullable String errorMessage)
            throws RemoteException {
        doAnswer((invocation) -> {
            @SuppressWarnings("unchecked")
            AndroidFuture<UserSwitchResult> future = (AndroidFuture<UserSwitchResult>) invocation
                    .getArguments()[2];
            future.complete(new UserSwitchResult(status, errorMessage));
            return null;
        }).when(mService).switchUser(eq(userId), anyInt(), notNull());
    }

    private void expectServiceSwitchUserSucceeds(@UserIdInt int userId) throws RemoteException {
        doThrow(new RemoteException("D'OH!")).when(mService)
            .switchUser(eq(userId), anyInt(), notNull());
    }

    @NonNull
    private static <T> T getResult(@NonNull AndroidFuture<T> future) throws Exception {
        try {
            return future.get(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("not called in " + ASYNC_TIMEOUT_MS + "ms", e);
        }
    }

    private void setExistingUsers(int... userIds) {
        List<UserInfo> users = toUserInfoList(userIds);
        when(mUserManager.getUsers()).thenReturn(users);
    }

    private static List<UserInfo> toUserInfoList(int... userIds) {
        return Arrays.stream(userIds)
                .mapToObj(id -> toUserInfo(id))
                .collect(Collectors.toList());
    }

    // TODO(b/149099817): move to common code

    private static UserInfo toUserInfo(int userId) {
        UserInfo user = new UserInfo();
        user.id = userId;
        return user;
    }

    private static void setHeadlessSystemUserMode(boolean mode) {
        doReturn(mode).when(() -> UserManager.isHeadlessSystemUserMode());
    }

    private void expectCarHandleExceptionReturnsDefaultValue() {
        doAnswer((invocation) -> {
            return invocation.getArguments()[1];
        }).when(mCar).handleRemoteExceptionFromCarService(isA(RemoteException.class), any());
    }
}
