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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.pm.UserInfo;
import android.content.pm.UserInfo.UserInfoFlag;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.os.UserHandle;
import android.os.UserManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.function.Consumer;

public final class InitialUserSetterTest {

    @UserInfoFlag
    private static final int NO_FLAGS = 0;

    private static final String OWNER_NAME = "OwnerOfALonelyDevice";
    private static final String GUEST_NAME = "GuessWhot";

    private static final int USER_ID = 10;
    private static final int NEW_USER_ID = 11;
    private static final int CURRENT_USER_ID = 12;

    @Mock
    private CarUserManagerHelper mHelper;

    @Mock
    private UserManager mUm;

    // Spy used in tests that need to verify the default behavior as fallback
    private InitialUserSetter mSetter;

    private MockitoSession mSession;

    private final MyListener mListener = new MyListener();

    @Before
    public void setFixtures() {
        mSession = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(ActivityManager.class)
                .initMocks(this)
                .startMocking();
        mSetter = spy(new InitialUserSetter(mHelper, mUm, mListener, OWNER_NAME, GUEST_NAME,
                /* supportsOverrideUserIdProperty= */ false));

        expectCurrentUser(CURRENT_USER_ID);
    }

    @After
    public void finishSession() throws Exception {
        mSession.finishMocking();
    }

    @Test
    public void testSwitchUser_ok_nonGuest() throws Exception {
        UserInfo user = expectUserExists(USER_ID);
        expectSwitchUser(USER_ID);

        mSetter.switchUser(USER_ID);

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(user);
    }

    @Test
    public void testSwitchUser_ok_systemUser() throws Exception {
        UserInfo user = expectUserExists(UserHandle.USER_SYSTEM);
        expectSwitchUser(UserHandle.USER_SYSTEM);

        mSetter.switchUser(UserHandle.USER_SYSTEM);

        verifyUserSwitched(UserHandle.USER_SYSTEM);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserNeverUnlocked();
        assertInitialUserSet(user);
    }

    @Test
    public void testSwitchUser_ok_guestReplaced() throws Exception {
        boolean ephemeral = true; // ephemeral doesn't really matter in this test
        expectCurrentUser(CURRENT_USER_ID);
        expectGuestExists(USER_ID, ephemeral); // ephemeral doesn't matter
        UserInfo newGuest = newGuestUser(NEW_USER_ID, ephemeral);
        expectGuestReplaced(USER_ID, newGuest);
        expectSwitchUser(NEW_USER_ID);

        mSetter.switchUser(USER_ID);

        verifyUserSwitched(NEW_USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        verifyUserDeleted(USER_ID);
        assertInitialUserSet(newGuest);
    }

    @Test
    public void testSwitchUser_fail_guestReplacementFailed() throws Exception {
        expectGuestExists(USER_ID, /* isEphemeral= */ true); // ephemeral doesn't matter
        expectGuestReplaced(USER_ID, /* newGuest= */ null);

        mSetter.switchUser(USER_ID);

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserNeverUnlocked();
    }

    @Test
    public void testSwitchUser_fail_switchFail() throws Exception {
        expectUserExists(USER_ID);
        // No need to set switchUser() expectations - will return false by default

        mSetter.switchUser(USER_ID);

        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserUnlocked();
        verifyLastActiveUserNeverSet();
    }

    @Test
    public void testSwitchUser_fail_userDoesntExist() throws Exception {
        // No need to set user exists expectation / will return null by default

        mSetter.switchUser(USER_ID);

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserNeverUnlocked();
    }

    @Test
    public void testSwitchUser_ok_targetIsCurrentUser() throws Exception {
        expectCurrentUser(CURRENT_USER_ID);
        UserInfo currentUser = expectUserExists(CURRENT_USER_ID);

        mSetter.switchUser(CURRENT_USER_ID);

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(currentUser);
    }

    @Test
    public void testReplaceGuestIfNeeded_null() {
        assertThrows(IllegalArgumentException.class, () -> mSetter.replaceGuestIfNeeded(null));
    }

    @Test
    public void testReplaceGuestIfNeeded_nonGuest() {
        UserInfo user = newSecondaryUser(USER_ID);

        assertThat(mSetter.replaceGuestIfNeeded(user)).isSameAs(user);

        verifyGuestNeverMarkedForDeletion();
        verifyUserNeverCreated();
    }

    @Test
    public void testReplaceGuestIfNeeded_ok_nonEphemeralGuest() {
        UserInfo newGuest = expectCreateGuestUser(NEW_USER_ID, GUEST_NAME, NO_FLAGS);
        UserInfo user = newGuestUser(USER_ID, /* ephemeral= */ false);

        assertThat(mSetter.replaceGuestIfNeeded(user)).isSameAs(newGuest);

        verifyGuestMarkedForDeletion(USER_ID);
    }

    @Test
    public void testReplaceGuestIfNeeded_ok_ephemeralGuest() {
        UserInfo newGuest = expectCreateGuestUser(NEW_USER_ID, GUEST_NAME, UserInfo.FLAG_EPHEMERAL);
        UserInfo user = newGuestUser(USER_ID, /* ephemeral= */ true);

        assertThat(mSetter.replaceGuestIfNeeded(user)).isSameAs(newGuest);

        verifyGuestMarkedForDeletion(USER_ID);
    }

    @Test
    public void testReplaceGuestIfNeeded_fail_ephemeralGuest_createFailed() {
        // don't set create guest expectation, so it returns null

        UserInfo user = newGuestUser(USER_ID, /* ephemeral= */ true);
        assertThat(mSetter.replaceGuestIfNeeded(user)).isEqualTo(null);

        verifyGuestMarkedForDeletion(USER_ID);
    }

    @Test
    public void testCreateUser_ok_noflags() throws Exception {
        UserInfo newUser = expectCreateFullUser(USER_ID, "TheDude", NO_FLAGS);
        expectSwitchUser(USER_ID);

        mSetter.createUser("TheDude", UserFlags.NONE);

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newUser);
    }

    @Test
    public void testCreateUser_ok_admin() throws Exception {
        UserInfo newUser = expectCreateFullUser(USER_ID, "TheDude", UserInfo.FLAG_ADMIN);
        expectSwitchUser(USER_ID);

        mSetter.createUser("TheDude", UserFlags.ADMIN);

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newUser);
    }

    @Test
    public void testCreateUser_ok_ephemeralGuest() throws Exception {
        UserInfo newGuest = expectCreateGuestUser(USER_ID, "TheDude", UserInfo.FLAG_EPHEMERAL);
        expectSwitchUser(USER_ID);

        mSetter.createUser("TheDude", UserFlags.EPHEMERAL | UserFlags.GUEST);

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newGuest);
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
        expectCreateFullUser(USER_ID, "TheDude", NO_FLAGS);

        // No need to set switchUser() expectations - will return false by default

        mSetter.createUser("TheDude", UserFlags.NONE);

        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserUnlocked();
        verifyLastActiveUserNeverSet();
    }

    @Test
    public void testDefaultBehavior_firstBoot_ok() throws Exception {
        // no need to mock hasInitialUser(), it will return false by default
        UserInfo newUser = expectCreateFullUser(USER_ID, OWNER_NAME, UserInfo.FLAG_ADMIN);
        expectSwitchUser(USER_ID);

        mSetter.executeDefaultBehavior();

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newUser);
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
        expectCreateFullUser(USER_ID, OWNER_NAME, UserInfo.FLAG_ADMIN);
        // no need to mock switchUser(), it will return false by default

        mSetter.executeDefaultBehavior();

        verifyFallbackDefaultBehaviorCalledFromDefaultBehavior();
        verifySystemUserUnlocked();
        verifyLastActiveUserNeverSet();
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_ok() throws Exception {
        UserInfo existingUser = expectHasInitialUser(USER_ID);
        expectSwitchUser(USER_ID);

        mSetter.executeDefaultBehavior();

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
        assertInitialUserSet(existingUser);
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_ok_targetIsCurrentUser() throws Exception {
        UserInfo currentUser = expectHasInitialUser(CURRENT_USER_ID);
        expectSwitchUser(CURRENT_USER_ID);

        mSetter.executeDefaultBehavior();

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorNeverCalled();
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
        assertInitialUserSet(currentUser);
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_fail_switchFail() throws Exception {
        expectHasInitialUser(USER_ID);
        // no need to mock switchUser(), it will return false by default

        mSetter.executeDefaultBehavior();

        verifyFallbackDefaultBehaviorCalledFromDefaultBehavior();
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
        verifyLastActiveUserNeverSet();
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_ok_guestReplaced() throws Exception {
        boolean ephemeral = true; // ephemeral doesn't really matter in this test
        expectHasInitialUser(USER_ID);
        expectGuestExists(USER_ID, ephemeral);
        UserInfo newGuest = newGuestUser(NEW_USER_ID, ephemeral);
        expectGuestReplaced(USER_ID, newGuest);
        expectSwitchUser(NEW_USER_ID);

        mSetter.executeDefaultBehavior();

        verifyUserSwitched(NEW_USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
        verifyUserDeleted(USER_ID);
        assertInitialUserSet(newGuest);
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_fail_guestReplacementFailed() throws Exception {
        expectHasInitialUser(USER_ID);
        expectGuestExists(USER_ID, /* isEphemeral= */ true); // ephemeral doesn't matter
        expectGuestReplaced(USER_ID, /* newGuest= */ null);

        mSetter.executeDefaultBehavior();

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromDefaultBehavior();
        verifyUserNeverCreated();
        verifySystemUserNeverUnlocked();
    }

    @Test
    public void testDefaultBehavior_testDefaultBehavior_nonFirstBoot_ok_withOverriddenProperty()
            throws Exception {
        boolean supportsOverrideUserIdProperty = true;
        // Must use a different helper as the property is set on constructor
        InitialUserSetter setter = spy(new InitialUserSetter(mHelper, mUm, mListener,
                OWNER_NAME, GUEST_NAME, supportsOverrideUserIdProperty));
        UserInfo user = expectHasInitialUser(USER_ID, supportsOverrideUserIdProperty);
        expectSwitchUser(USER_ID);

        setter.executeDefaultBehavior();

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
        assertInitialUserSet(user);
    }

    private UserInfo expectHasInitialUser(@UserIdInt int userId) {
        return expectHasInitialUser(userId, /* supportsOverrideUserIdProperty= */ false);
    }

    private UserInfo expectHasInitialUser(@UserIdInt int userId,
            boolean supportsOverrideUserIdProperty) {
        when(mHelper.hasInitialUser()).thenReturn(true);
        when(mHelper.getInitialUser(supportsOverrideUserIdProperty)).thenReturn(userId);
        return expectUserExists(userId);
    }

    private UserInfo expectUserExists(@UserIdInt int userId) {
        UserInfo user = new UserInfo();
        user.id = userId;
        when(mUm.getUserInfo(userId)).thenReturn(user);
        return user;
    }

    private void expectGuestExists(@UserIdInt int userId, boolean isEphemeral) {
        UserInfo user = new UserInfo();
        user.id = userId;
        user.userType = UserManager.USER_TYPE_FULL_GUEST;
        if (isEphemeral) {
            user.flags = UserInfo.FLAG_EPHEMERAL;
        }
        when(mUm.getUserInfo(userId)).thenReturn(user);
    }

    private void expectGuestReplaced(int existingGuestId, UserInfo newGuest) {
        doReturn(newGuest).when(mSetter).replaceGuestIfNeeded(isUserInfo(existingGuestId));
    }

    private void expectSwitchUser(@UserIdInt int userId) throws Exception {
        when(mHelper.startForegroundUser(userId)).thenReturn(true);
    }

    private UserInfo expectCreateFullUser(@UserIdInt int userId, @Nullable String name,
            @UserInfoFlag int flags) {
        return expectCreateUserOfType(UserManager.USER_TYPE_FULL_SECONDARY, userId, name, flags);
    }

    private UserInfo expectCreateGuestUser(@UserIdInt int userId, @Nullable String name,
            @UserInfoFlag int flags) {
        return expectCreateUserOfType(UserManager.USER_TYPE_FULL_GUEST, userId, name, flags);
    }

    private UserInfo expectCreateUserOfType(@NonNull String type, @UserIdInt int userId,
            @Nullable String name, @UserInfoFlag int flags) {
        UserInfo userInfo = new UserInfo(userId, name, flags);
        when(mUm.createUser(name, type, flags)).thenReturn(userInfo);
        // Once user is created, it should exist...
        when(mUm.getUserInfo(userId)).thenReturn(userInfo);
        return userInfo;
    }

    private void verifyUserSwitched(@UserIdInt int userId) throws Exception {
        verify(mHelper).startForegroundUser(userId);
        verify(mHelper).setLastActiveUser(userId);
    }

    private void verifyUserNeverSwitched() throws Exception {
        verify(mHelper, never()).startForegroundUser(anyInt());
        verifyLastActiveUserNeverSet();
    }

    private void verifyUserNeverCreated() {
        verify(mUm, never()).createUser(anyString(), anyString(), anyInt());
    }

    private void verifyGuestMarkedForDeletion(@UserIdInt int userId) {
        verify(mUm).markGuestForDeletion(userId);
    }

    private void verifyGuestNeverMarkedForDeletion() {
        verify(mUm, never()).markGuestForDeletion(anyInt());
    }

    private void verifyUserDeleted(@UserIdInt int userId) {
        verify(mUm).removeUser(userId);
    }

    private void verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch() {
        verify(mSetter).fallbackDefaultBehavior(eq(true), anyString());
        assertInitialUserSet(null);
    }

    private void verifyFallbackDefaultBehaviorCalledFromDefaultBehavior() {
        verify(mSetter).fallbackDefaultBehavior(eq(false), anyString());
        assertInitialUserSet(null);
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

    private void verifyLastActiveUserNeverSet() {
        verify(mHelper, never()).setLastActiveUser(anyInt());
    }

    private void assertInitialUserSet(@NonNull UserInfo expectedUser) {
        assertWithMessage("listener called wrong number of times").that(mListener.numberCalls)
            .isEqualTo(1);
        assertWithMessage("wrong initial user set on listener").that(mListener.initialUser)
            .isSameAs(expectedUser);
    }

    private final class MyListener implements Consumer<UserInfo> {
        public int numberCalls;
        public UserInfo initialUser;

        @Override
        public void accept(UserInfo initialUser) {
            this.initialUser = initialUser;
            numberCalls++;
        }
    }

    // TODO(b/149099817): move stuff below (and some from above) to common testing code

    public static void expectCurrentUser(@UserIdInt int userId) {
        doReturn(userId).when(() -> ActivityManager.getCurrentUser());
    }

    @NonNull
    public static UserInfo newSecondaryUser(@UserIdInt int userId) {
        UserInfo userInfo = new UserInfo();
        userInfo.userType = UserManager.USER_TYPE_FULL_SECONDARY;
        userInfo.id = userId;
        return userInfo;
    }

    @NonNull
    public static UserInfo newGuestUser(@UserIdInt int userId, boolean ephemeral) {
        UserInfo userInfo = new UserInfo();
        userInfo.userType = UserManager.USER_TYPE_FULL_GUEST;
        userInfo.id = userId;
        if (ephemeral) {
            userInfo.flags = UserInfo.FLAG_EPHEMERAL;
        }
        return userInfo;
    }

    /**
     * Custom Mockito matcher to check if a {@link UserInfo} has the given {@code userId}.
     */
    public static UserInfo isUserInfo(@UserIdInt int userId) {
        return argThat(new UserInfoMatcher(userId));
    }

    private static class UserInfoMatcher implements ArgumentMatcher<UserInfo> {

        public final @UserIdInt int userId;

        private UserInfoMatcher(@UserIdInt int userId) {
            this.userId = userId;
        }

        @Override
        public boolean matches(@Nullable UserInfo argument) {
            return argument != null && argument.id == userId;
        }

        @Override
        public String toString() {
            return "UserInfo(userId=" + userId + ")";
        }
    }

}
