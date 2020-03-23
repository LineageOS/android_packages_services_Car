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
package android.car.userlib;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.UserInfo;
import android.content.pm.UserInfo.UserInfoFlag;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.os.UserManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class InitialUserSetterTest {

    @Mock
    private CarUserManagerHelper mHelper;

    @Mock
    private UserManager mUm;

    // Spy used in tests that need to verify the default behavior as fallback
    private InitialUserSetter mSetter;

    @Before
    public void setFixtures() {
        mSetter = spy(new InitialUserSetter(mHelper, mUm,
                /* supportsOverrideUserIdProperty= */ false));
    }

    @Test
    public void testSwitchUser_ok() throws Exception {
        expectSwitchUser(10);

        mSetter.switchUser(10);

        verifyUserSwitched(10);
        verifyFallbackDefaultBehaviorNeverCalled();
    }

    @Test
    public void testSwitchUser_fail() throws Exception {
        expectFallbackDefaultBehavior();
        // No need to set switchUser() expectations - will return false by default

        mSetter.switchUser(10);

        verifyFallbackDefaultBehaviorCalled();
    }

    @Test
    public void testCreateUser_ok_noflags() throws Exception {
        expectCreateFullUser(10, "TheDude", /* flags= */ 0);

        mSetter.createUser("TheDude", UserFlags.NONE);

        verifyUserSwitched(10);
    }

    @Test
    public void testCreateUser_ok_admin() throws Exception {
        expectCreateFullUser(10, "TheDude", UserInfo.FLAG_ADMIN);

        mSetter.createUser("TheDude", UserFlags.ADMIN);

        verifyUserSwitched(10);
    }

    @Test
    public void testCreateUser_ok_ephemeralGuest() throws Exception {
        expectCreateGuestUser(10, "TheDude", UserInfo.FLAG_EPHEMERAL);

        mSetter.createUser("TheDude", UserFlags.EPHEMERAL | UserFlags.GUEST);

        verifyUserSwitched(10);
    }

    @Test
    public void testCreateUser_fail_systemUser() throws Exception {
        expectFallbackDefaultBehavior();
        // No need to set mUm.createUser() expectation - it shouldn't be called

        mSetter.createUser("TheDude", UserFlags.SYSTEM);

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalled();
    }

    @Test
    public void testCreateUser_fail_guestAdmin() throws Exception {
        expectFallbackDefaultBehavior();
        // No need to set mUm.createUser() expectation - it shouldn't be called

        mSetter.createUser("TheDude", UserFlags.GUEST | UserFlags.ADMIN);

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalled();
    }

    @Test
    public void testCreateUser_fail_ephemeralAdmin() throws Exception {
        expectFallbackDefaultBehavior();
        // No need to set mUm.createUser() expectation - it shouldn't be called

        mSetter.createUser("TheDude", UserFlags.EPHEMERAL | UserFlags.ADMIN);

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalled();
    }

    @Test
    public void testCreateUser_fail_createFail() throws Exception {
        expectFallbackDefaultBehavior();
        // No need to set mUm.createUser() expectation - it shouldn't be called

        mSetter.createUser("TheDude", UserFlags.NONE);

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalled();
    }

    @Test
    public void testCreateUser_fail_switchFail() throws Exception {
        expectCreateFullUser(10, "TheDude", /* flags= */ 0);
        expectFallbackDefaultBehavior();
        // No need to set switchUser() expectations - will return false by default

        mSetter.createUser("TheDude", UserFlags.NONE);

        verifyFallbackDefaultBehaviorCalled();
    }

    // TODO(b/151758646): implement default behavior (including override option)

    private void expectSwitchUser(@UserIdInt int userId) throws Exception {
        when(mHelper.startForegroundUser(userId)).thenReturn(true);
    }

    private void expectCreateFullUser(@UserIdInt int userId, @Nullable String name,
            @UserInfoFlag int flags) {
        expectCreateUserOfType(UserManager.USER_TYPE_FULL_SECONDARY, userId, name, flags);
    }

    private void expectCreateGuestUser(@UserIdInt int userId, @Nullable String name,
            @UserInfoFlag int flags) {
        expectCreateUserOfType(UserManager.USER_TYPE_FULL_GUEST, userId, name, flags);
    }

    private void expectCreateUserOfType(@NonNull String type, @UserIdInt int userId,
            @Nullable String name, @UserInfoFlag int flags) {
        UserInfo userInfo = new UserInfo(userId, name, flags);
        when(mUm.createUser(name, type, flags)).thenReturn(userInfo);
    }

    private void expectFallbackDefaultBehavior() {
        doNothing().when(mSetter).fallbackDefaultBehavior(anyString());
    }

    private void verifyUserSwitched(int userId) throws Exception {
        verify(mHelper).startForegroundUser(userId);
    }

    private void verifyUserNeverSwitched() throws Exception {
        verify(mHelper, never()).startForegroundUser(anyInt());
    }

    private void verifyFallbackDefaultBehaviorCalled() {
        verify(mSetter).fallbackDefaultBehavior(anyString());
    }

    private void verifyFallbackDefaultBehaviorNeverCalled() {
        verify(mSetter, never()).fallbackDefaultBehavior(anyString());
    }
}
