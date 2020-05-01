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
package android.car.test.mocks;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.car.test.util.UserTestingHelper;
import android.content.pm.UserInfo;
import android.content.pm.UserInfo.UserInfoFlag;
import android.os.UserManager;

/**
 * Provides common Mockito calls for core Android classes.
 */
public final class AndroidMockitoHelper {

    /**
     * Mocks a call to {@link ActivityManager#getCurrentUser()}.
     *
     * <p><b>Note: </b>it must be made inside a
     * {@linkcom.android.dx.mockito.inline.extended.StaticMockitoSession} built with
     * {@code spyStatic(ActivityManager.class)}.
     *
     * @param userId result of such call
     */
    public static void mockAmGetCurrentUser(@UserIdInt int userId) {
        doReturn(userId).when(() -> ActivityManager.getCurrentUser());
    }

    /**
     * Mocks a call to {@link UserManager#isHeadlessSystemUserMode()}.
     *
     * <p><b>Note: </b>it must be made inside a
     * {@linkcom.android.dx.mockito.inline.extended.StaticMockitoSession} built with
     * {@code spyStatic(UserManager.class)}.
     *
     * @param mode result of such call
     */
    public static void mockUmIsHeadlessSystemUserMode(boolean mode) {
        doReturn(mode).when(() -> UserManager.isHeadlessSystemUserMode());
    }

    /**
     * Mocks {@code UserManager.getuserInfo(userId)} to return a {@link UserInfo} with the given
     * {@code flags}.
     */
    @NonNull
    public static UserInfo mockUmGetUserInfo(@NonNull UserManager um, @UserIdInt int userId,
            @UserInfoFlag int flags) {
        UserInfo userInfo = new UserTestingHelper.UserInfoBuilder(userId).setFlags(flags).build();
        when(um.getUserInfo(userId)).thenReturn(userInfo);

        return userInfo;
    }

    private AndroidMockitoHelper() {
        throw new UnsupportedOperationException("contains only static methods");
    }
}
