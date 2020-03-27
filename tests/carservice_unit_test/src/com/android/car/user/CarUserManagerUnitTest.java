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

import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.user.CarUserManager;
import android.content.pm.UserInfo;
import android.os.IBinder;
import android.os.UserManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class CarUserManagerUnitTest {

    @Mock
    private Car mCar;
    @Mock
    private IBinder mService;
    @Mock
    private UserManager mUserManager;

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

    private void setExistingUsers(int... userIds) {
        List<UserInfo> users = toUserInfoList(userIds);
        when(mUserManager.getUsers()).thenReturn(users);
    }

    private static List<UserInfo> toUserInfoList(int... userIds) {
        return Arrays.stream(userIds)
                .mapToObj(id -> toUserInfo(id))
                .collect(Collectors.toList());
    }

    private static UserInfo toUserInfo(int userId) {
        UserInfo user = new UserInfo();
        user.id = userId;
        return user;
    }

    private static void setHeadlessSystemUserMode(boolean mode) {
        doReturn(mode).when(() -> UserManager.isHeadlessSystemUserMode());
    }

}
