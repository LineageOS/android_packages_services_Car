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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import android.os.UserHandle;
import android.os.UserManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class InitialUserSetterTest {

    @UserInfoFlag
    private static final int NO_FLAGS = 0;

    private static final String OWNER_NAME = "OwnerOfALonelyDevice";

    @Mock
    private CarUserManagerHelper mHelper;

    @Mock
    private UserManager mUm;

    // Spy used in tests that need to verify the default behavior as fallback
    private InitialUserSetter mSetter;

    @Before
    public void setFixtures() {
        mSetter = spy(new InitialUserSetter(mHelper, mUm, OWNER_NAME,
                /* supportsOverrideUserIdProperty= */ false));
    }

    @Test
    public void testSwitchUser_ok() throws Exception {
        expectSwitchUser(10);

        mSetter.switchUser(10);

        verifyUserSwitched(10);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
    }

    @Test
    public void testSwitchUser_ok_systemUser() throws Exception {
        expectSwitchUser(UserHandle.USER_SYSTEM);

        mSetter.switchUser(UserHandle.USER_SYSTEM);

        verifyUserSwitched(UserHandle.USER_SYSTEM);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserNeverUnlocked();
    }

    @Test
    public void testSwitchUser_fail() throws Exception {

        // No need to set switchUser() expectations - will return false by default

        mSetter.switchUser(10);

        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserUnlocked();
        verifyLastActiverUserNevertSet();
    }

    @Test
    public void testCreateUser_ok_noflags() throws Exception {
        expectCreateFullUser(10, "TheDude", NO_FLAGS);
        expectSwitchUser(10);

        mSetter.createUser("TheDude", UserFlags.NONE);

        verifyUserSwitched(10);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
    }

    @Test
    public void testCreateUser_ok_admin() throws Exception {
        expectCreateFullUser(10, "TheDude", UserInfo.FLAG_ADMIN);
        expectSwitchUser(10);

        mSetter.createUser("TheDude", UserFlags.ADMIN);

        verifyUserSwitched(10);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
    }

    @Test
    public void testCreateUser_ok_ephemeralGuest() throws Exception {
        expectCreateGuestUser(10, "TheDude", UserInfo.FLAG_EPHEMERAL);
        expectSwitchUser(10);

        mSetter.createUser("TheDude", UserFlags.EPHEMERAL | UserFlags.GUEST);

        verifyUserSwitched(10);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
    }

    @Test
    public void testCreateUser_fail_systemUser() throws Exception {

        // No need to set mUm.createUser() expectation - it shouldn't be called

        mSetter.createUser("TheDude", UserFlags.SYSTEM);

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserNeverUnlocked();
    }

    @Test
    public void testCreateUser_fail_guestAdmin() throws Exception {

        // No need to set mUm.createUser() expectation - it shouldn't be called

        mSetter.createUser("TheDude", UserFlags.GUEST | UserFlags.ADMIN);

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
    }

    @Test
    public void testCreateUser_fail_ephemeralAdmin() throws Exception {

        // No need to set mUm.createUser() expectation - it shouldn't be called

        mSetter.createUser("TheDude", UserFlags.EPHEMERAL | UserFlags.ADMIN);

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
    }

    @Test
    public void testCreateUser_fail_createFail() throws Exception {

        // No need to set mUm.createUser() expectation - it shouldn't be called

        mSetter.createUser("TheDude", UserFlags.NONE);

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
    }

    @Test
    public void testCreateUser_fail_switchFail() throws Exception {
        expectCreateFullUser(10, "TheDude", NO_FLAGS);

        // No need to set switchUser() expectations - will return false by default

        mSetter.createUser("TheDude", UserFlags.NONE);

        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserUnlocked();
        verifyLastActiverUserNevertSet();
    }

    @Test
    public void testDefaultBehavior_firstBoot_ok() throws Exception {
        // no need to mock hasInitialUser(), it will return false by default
        expectCreateFullUser(10, OWNER_NAME, UserInfo.FLAG_ADMIN);
        expectSwitchUser(10);

        mSetter.executeDefaultBehavior();

        verifyUserSwitched(10);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
    }

    @Test
    public void testDefaultBehavior_firstBoot_fail_createUserFailed() throws Exception {

        // no need to mock hasInitialUser(), it will return false by default
        // no need to mock createUser(), it will return null by default

        mSetter.executeDefaultBehavior();

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromDefaultBehavior();
        verifySystemUserNeverUnlocked();
    }

    @Test
    public void testDefaultBehavior_firstBoot_fail_switchFailed() throws Exception {

        // no need to mock hasInitialUser(), it will return false by default
        expectCreateFullUser(10, OWNER_NAME, UserInfo.FLAG_ADMIN);
        // no need to mock switchUser(), it will return false by default

        mSetter.executeDefaultBehavior();

        verifyFallbackDefaultBehaviorCalledFromDefaultBehavior();
        verifySystemUserUnlocked();
        verifyLastActiverUserNevertSet();
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_ok() throws Exception {
        expectHasInitialUser(10);
        expectSwitchUser(10);

        mSetter.executeDefaultBehavior();

        verifyUserSwitched(10);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_fail_switchFail() throws Exception {
        expectHasInitialUser(10);
        // no need to mock switchUser(), it will return false by default

        mSetter.executeDefaultBehavior();

        verifyFallbackDefaultBehaviorCalledFromDefaultBehavior();
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
        verifyLastActiverUserNevertSet();
    }

    @Test
    public void testDefaultBehavior_testDefaultBehavior_nonFirstBoot_ok_withOverriddenProperty()
            throws Exception {
        boolean supportsOverrideUserIdProperty = true;
        // Must use a different helper as the property is set on constructor
        InitialUserSetter setter = spy(new InitialUserSetter(mHelper, mUm, OWNER_NAME,
                supportsOverrideUserIdProperty));
        expectHasInitialUser(10, supportsOverrideUserIdProperty);
        expectSwitchUser(10);

        setter.executeDefaultBehavior();

        verifyUserSwitched(10);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
    }

    private void expectHasInitialUser(@UserIdInt int userId) {
        expectHasInitialUser(userId, /* supportsOverrideUserIdProperty= */ false);
    }

    private void expectHasInitialUser(@UserIdInt int userId,
            boolean supportsOverrideUserIdProperty) {
        when(mHelper.hasInitialUser()).thenReturn(true);
        when(mHelper.getInitialUser(supportsOverrideUserIdProperty)).thenReturn(userId);
    }

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

    private void verifyUserSwitched(@UserIdInt int userId) throws Exception {
        verify(mHelper).startForegroundUser(userId);
        verify(mHelper).setLastActiveUser(userId);
    }

    private void verifyUserNeverSwitched() throws Exception {
        verify(mHelper, never()).startForegroundUser(anyInt());
        verifyLastActiverUserNevertSet();
    }

    private void verifyUserNeverCreated() {
        verify(mUm, never()).createUser(anyString(), anyString(), anyInt());
    }

    private void verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch() {
        verify(mSetter).fallbackDefaultBehavior(eq(true), anyString());
    }

    private void verifyFallbackDefaultBehaviorCalledFromDefaultBehavior() {
        verify(mSetter).fallbackDefaultBehavior(eq(false), anyString());
    }

    private void verifyFallbackDefaultBehaviorNeverCalled() {
        verify(mSetter, never()).fallbackDefaultBehavior(anyBoolean(), anyString());
    }

    private void verifySystemUserUnlocked() {
        verify(mHelper).unlockSystemUser();
    }

    private void verifySystemUserNeverUnlocked() {
        verify(mHelper, never()).unlockSystemUser();
    }

    private void verifyLastActiverUserNevertSet() {
        verify(mHelper, never()).setLastActiveUser(anyInt());
    }
}
