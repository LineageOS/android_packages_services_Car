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

import static android.car.test.mocks.CarArgumentMatchers.isUserHandle;
import static android.os.UserHandle.USER_SYSTEM;

import static com.android.car.user.MockedUserHandleBuilder.expectAdminUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectEphemeralUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectGuestUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectManagedProfileExists;
import static com.android.car.user.MockedUserHandleBuilder.expectRegularUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectSystemUserExists;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.car.builtin.os.UserManagerHelper;
import android.car.settings.CarSettings;
import android.car.test.util.UserTestingHelper;
import android.content.Context;
import android.hardware.automotive.vehicle.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.ravenwood.RavenwoodRule;
import android.provider.Settings;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Optional;
import java.util.function.Consumer;

@RunWith(MockitoJUnitRunner.Silent.class)
public final class InitialUserSetterTest {

    private static final int NO_FLAGS = 0;

    private static final String OWNER_NAME = "OwnerOfALonelyDevice";
    private static final String GUEST_NAME = "GuessWhot";

    private static final int USER_ID = 100;
    private static final int NEW_USER_ID = 101;
    private static final int CURRENT_USER_ID = 102;

    private static final InitialUserSetter.InitialUserInfo INITIAL_USER_INFO_RESUME =
            new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE,
                    InitialUserSetter.ON_RESUME)
                    .build();

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder().setProvideMainThread(true)
            .build();

    @Mock
    private Context mContext;
    @Mock
    private UserManager mUm;
    @Mock
    private CarUserService mCarUserService;
    @Mock
    private UserHandleHelper mMockedUserHandleHelper;
    @Mock
    private InitialUserSetter.LockPatternHelperIntf mLockPatternHelper;
    @Mock
    private InitialUserSetter.ActivityManagerHelperIntf mActivityManagerHelper;
    @Mock
    private InitialUserSetter.CarSystemPropertiesIntf mCarSystemProperties;
    @Mock
    private CurrentUserFetcher mCurrentUserFetcher;
    @Mock
    private com.android.car.provider.Settings mSettings;

    // Spy used in tests that need to verify the default behavior as fallback
    private InitialUserSetter mSetter;

    private final MyListener mListener = new MyListener();

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private int mCurrentUserId = 0;
    @GuardedBy("mLock")
    private final ArrayMap<String, Integer> mFakeGlobalSettings = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, String> mFakeSystemSettings = new ArrayMap<>();

    private void mockGetCurrentUser(int userId) {
        synchronized (mLock) {
            mCurrentUserId = userId;
        }
    }

    private InitialUserSetter newSetter(boolean isHeadlessSystemUserMode) {
        return spy(new InitialUserSetter(mContext, mCarUserService, mListener,
                mMockedUserHandleHelper, new InitialUserSetter.Deps(
                        mUm, OWNER_NAME, GUEST_NAME, isHeadlessSystemUserMode,
                        mActivityManagerHelper, mCarSystemProperties, mLockPatternHelper,
                        mCurrentUserFetcher, mSettings
                )));
    }

    @Before
    public void setFixtures() {
        when(mSettings.putIntGlobal(any(), any(), anyInt())).thenAnswer((inv) -> {
            synchronized (mLock) {
                mFakeGlobalSettings.put(inv.getArgument(1), inv.getArgument(2));
            }
            return true;
        });
        when(mSettings.getIntGlobal(any(), any(), anyInt())).thenAnswer((inv) -> {
            synchronized (mLock) {
                String key = inv.getArgument(1);
                Integer result = mFakeGlobalSettings.get(key);
                if (result == null) {
                    return inv.getArgument(2);
                }
                return result;
            }
        });
        when(mSettings.putStringSystem(any(), any(), any())).thenAnswer((inv) -> {
            synchronized (mLock) {
                mFakeSystemSettings.put(inv.getArgument(1), inv.getArgument(2));
            }
            return true;
        });
        when(mCurrentUserFetcher.getCurrentUser()).thenAnswer((inv) -> {
            synchronized (mLock) {
                return mCurrentUserId;
            }
        });
        when(mContext.createContextAsUser(any(), anyInt())).thenReturn(mContext);
        when(mLockPatternHelper.isSecure(any(), anyInt())).thenReturn(false);
        mockGetCurrentUser(CURRENT_USER_ID);

        mSetter = newSetter(/* isHeadlessSystemUserMode= */ true);
    }

    @Test
    public void testSet_null() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mSetter.set(null));
    }

    @Test
    public void testInitialUserInfoBuilder_invalidType() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new InitialUserSetter.Builder(-1,
                InitialUserSetter.ON_BOOT));
    }

    @Test
    public void testInitialUserInfoBuilder_invalidRequestType() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE, -1));
    }

    @Test
    public void testInitialUserInfoBuilder_invalidSetSwitchUserId() throws Exception {
        InitialUserSetter.Builder builder = new InitialUserSetter.Builder(
                InitialUserSetter.TYPE_CREATE, InitialUserSetter.ON_BOOT);
        assertThrows(IllegalArgumentException.class, () -> builder.setSwitchUserId(USER_ID));
    }

    @Test
    public void testInitialUserInfoBuilder_invalidSetNewUserName() throws Exception {
        InitialUserSetter.Builder builder = new InitialUserSetter.Builder(
                InitialUserSetter.TYPE_SWITCH, InitialUserSetter.ON_BOOT);
        assertThrows(IllegalArgumentException.class, () -> builder.setNewUserName(OWNER_NAME));
    }

    @Test
    public void testInitialUserInfoBuilder_invalidSetNewUserFlags() throws Exception {
        InitialUserSetter.Builder builder = new InitialUserSetter.Builder(
                InitialUserSetter.TYPE_SWITCH, InitialUserSetter.ON_BOOT);
        assertThrows(IllegalArgumentException.class,
                () -> builder.setNewUserFlags(UserInfo.USER_FLAG_ADMIN));
    }

    @Test
    public void testSwitchUser_ok_nonGuest() throws Exception {
        UserHandle user = expectRegularUserExists(mMockedUserHandleHelper, USER_ID);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_SWITCH,
                InitialUserSetter.ON_BOOT)
                .setSwitchUserId(USER_ID)
                .build());

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(user);
    }

    @Test
    public void testSwitchUser_ok_systemUser() throws Exception {
        UserHandle user = expectSystemUserExists(mMockedUserHandleHelper, UserHandle.USER_SYSTEM);
        expectSwitchUser(UserHandle.USER_SYSTEM);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_SWITCH,
                InitialUserSetter.ON_BOOT)
                .setSwitchUserId(UserHandle.USER_SYSTEM)
                .build());

        verifyUserSwitched(UserHandle.USER_SYSTEM);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserNeverUnlocked();
        assertInitialUserSet(user);
    }

    @Test
    public void testSwitchUser_ok_guestReplaced() throws Exception {
        boolean ephemeral = true;
        mockGetCurrentUser(CURRENT_USER_ID);
        expectGuestUserExists(mMockedUserHandleHelper, USER_ID, ephemeral);
        UserHandle newGuest = expectGuestUserExists(mMockedUserHandleHelper, NEW_USER_ID,
                ephemeral);

        expectGuestReplaced(USER_ID, newGuest);
        expectSwitchUser(NEW_USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_SWITCH,
                InitialUserSetter.ON_BOOT)
                .setSwitchUserId(USER_ID)
                .setReplaceGuest(true)
                .build());

        verifyUserSwitched(NEW_USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        verifyUserDeleted(USER_ID);
        assertInitialUserSet(newGuest);
    }

    @Test
    public void testSwitchUser_ok_guestDoesNotNeedToBeReplaced() throws Exception {
        boolean ephemeral = true;
        UserHandle existingGuest = expectGuestUserExists(mMockedUserHandleHelper, USER_ID,
                ephemeral);

        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_SWITCH,
                InitialUserSetter.ON_BOOT)
                .setSwitchUserId(USER_ID)
                .setReplaceGuest(false)
                .build());

        verifyUserSwitched(USER_ID);
        verifyGuestNeverMarkedForDeletion();
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(existingGuest);
    }

    @Test
    public void testSwitchUser_fail_guestReplacementFailed() throws Exception {
        expectGuestUserExists(mMockedUserHandleHelper, USER_ID, /* isEphemeral= */ true);
        expectGuestReplaced(USER_ID, /* newGuest= */ null);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_SWITCH,
                InitialUserSetter.ON_BOOT)
                .setSwitchUserId(USER_ID)
                .setReplaceGuest(true)
                .build());

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserNeverUnlocked();
    }

    @Test
    public void testSwitchUser_fail_switchFail() throws Exception {
        expectRegularUserExists(mMockedUserHandleHelper, USER_ID);
        expectSwitchUserFails(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_SWITCH,
                InitialUserSetter.ON_BOOT)
                .setSwitchUserId(USER_ID)
                .build());

        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserUnlocked();
        verifyLastActiveUserNeverSet();
    }

    @Test
    public void testSwitchUser_fail_userDoesntExist() throws Exception {
        // No need to set user exists expectation / will return null by default

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_SWITCH,
                InitialUserSetter.ON_BOOT)
                .setSwitchUserId(USER_ID)
                .build());

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserNeverUnlocked();
    }

    @Test
    public void testSwitchUser_fail_switchThrowsException() throws Exception {
        expectRegularUserExists(mMockedUserHandleHelper, USER_ID);
        expectSwitchUserThrowsException(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_SWITCH,
                InitialUserSetter.ON_BOOT)
                .setSwitchUserId(USER_ID)
                .build());

        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserUnlocked();
        verifyLastActiveUserNeverSet();
    }

    @Test
    public void testSwitchUser_ok_targetIsCurrentUser() throws Exception {
        mockGetCurrentUser(CURRENT_USER_ID);
        UserHandle currentUser = expectRegularUserExists(mMockedUserHandleHelper, CURRENT_USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_SWITCH,
                InitialUserSetter.ON_BOOT)
                .setSwitchUserId(CURRENT_USER_ID)
                .build());

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
        UserHandle user = expectRegularUserExists(mMockedUserHandleHelper, USER_ID);

        assertThat(mSetter.replaceGuestIfNeeded(user)).isSameInstanceAs(user);

        verifyGuestNeverMarkedForDeletion();
        verifyUserNeverCreated();
    }

    @Test
    public void testReplaceGuestIfNeeded_ok_nonEphemeralGuest() {
        UserHandle newGuest = expectGuestUserExists(mMockedUserHandleHelper, NEW_USER_ID,
                /* isEphemeral= */ false);
        expectCreateGuestUser(GUEST_NAME, NO_FLAGS, newGuest);
        UserHandle user = expectGuestUserExists(mMockedUserHandleHelper, USER_ID,
                /* isEphemeral= */ false);

        assertThat(mSetter.replaceGuestIfNeeded(user)).isSameInstanceAs(newGuest);

        verifyGuestMarkedForDeletion(USER_ID);
    }

    @Test
    public void testReplaceGuestIfNeeded_ok_hasCredentials() throws Exception {
        UserHandle user = expectGuestUserExists(mMockedUserHandleHelper, USER_ID,
                /* isEphemeral= */ false);
        expectUserIsSecure(USER_ID);
        assertThat(mSetter.replaceGuestIfNeeded(user)).isSameInstanceAs(user);

        verifyGuestNeverMarkedForDeletion();
        verifyUserNeverCreated();
    }

    @Test
    public void testReplaceGuestIfNeeded_ok_ephemeralGuest() {
        UserHandle newGuest = expectGuestUserExists(mMockedUserHandleHelper, NEW_USER_ID,
                /* isEphemeral= */ true);
        expectCreateGuestUser(GUEST_NAME, UserManagerHelper.FLAG_EPHEMERAL, newGuest);
        UserHandle user = expectGuestUserExists(mMockedUserHandleHelper, USER_ID,
                /* isEphemeral= */ true);

        assertThat(mSetter.replaceGuestIfNeeded(user)).isSameInstanceAs(newGuest);

        verifyGuestMarkedForDeletion(USER_ID);
    }

    @Test
    public void testReplaceGuestIfNeeded_fail_ephemeralGuest_createFailed() {
        // don't set create guest expectation, so it returns null
        UserHandle user = expectGuestUserExists(mMockedUserHandleHelper, USER_ID,
                /* isEphemeral= */ true);

        assertThat(mSetter.replaceGuestIfNeeded(user)).isEqualTo(null);

        verifyGuestMarkedForDeletion(USER_ID);
    }

    @Test
    public void testCanReplaceGuestUser_fail_notGuest() {
        UserHandle user = expectRegularUserExists(mMockedUserHandleHelper, USER_ID);

        assertThat(mSetter.canReplaceGuestUser(user)).isFalse();
    }

    @Test
    public void testCanReplaceGuestUser_ok() {
        UserHandle user = expectGuestUserExists(mMockedUserHandleHelper, USER_ID,
                /* isEphemeral= */ true);

        assertThat(mSetter.canReplaceGuestUser(user)).isTrue();
    }

    @Test
    public void testCanReplaceGuestUser_fail_guestHasCredentials() {
        UserHandle user = expectGuestUserExists(mMockedUserHandleHelper, USER_ID,
                /* isEphemeral= */ true);
        expectUserIsSecure(USER_ID);

        assertThat(mSetter.canReplaceGuestUser(user)).isFalse();
    }

    @Test
    public void testCreateUser_ok_noflags() throws Exception {
        UserHandle newUser = expectRegularUserExists(mMockedUserHandleHelper, USER_ID);

        expectCreateFullUser("TheDude", NO_FLAGS, newUser);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE,
                InitialUserSetter.ON_BOOT)
                .setNewUserName("TheDude")
                .setNewUserFlags(0)
                .build());

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newUser);
    }

    @Test
    public void testCreateUser_ok_admin() throws Exception {
        UserHandle newUser = expectAdminUserExists(mMockedUserHandleHelper, USER_ID);

        expectCreateFullUser("TheDude", UserManagerHelper.FLAG_ADMIN, newUser);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE,
                InitialUserSetter.ON_BOOT)
                .setNewUserName("TheDude")
                .setNewUserFlags(UserInfo.USER_FLAG_ADMIN)
                .build());

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newUser);
    }

    @Test
    public void testCreateUser_ok_admin_setLocale() throws Exception {
        UserHandle newUser = expectAdminUserExists(mMockedUserHandleHelper, USER_ID);

        expectCreateFullUser("TheDude", UserManagerHelper.FLAG_ADMIN, newUser);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE,
                InitialUserSetter.ON_BOOT)
                .setNewUserName("TheDude")
                .setNewUserFlags(UserInfo.USER_FLAG_ADMIN)
                .setUserLocales("LOL")
                .build());

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newUser);
        assertSystemLocales("LOL");
    }

    @Test
    public void testCreateUser_ok_ephemeralGuest() throws Exception {
        UserHandle newGuest = expectGuestUserExists(mMockedUserHandleHelper, USER_ID,
                /* isEphemeral= */ true);
        expectCreateGuestUser("TheDude", UserManagerHelper.FLAG_EPHEMERAL, newGuest);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE,
                InitialUserSetter.ON_BOOT)
                .setNewUserName("TheDude")
                .setNewUserFlags(UserInfo.USER_FLAG_EPHEMERAL | UserInfo.USER_FLAG_GUEST)
                .build());

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newGuest);
    }

    @Test
    public void testCreateUser_fail_systemUser() throws Exception {
        // No need to mock createUser() expectation - it shouldn't be called
        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE,
                InitialUserSetter.ON_BOOT)
                .setNewUserName("TheDude")
                .setNewUserFlags(UserInfo.USER_FLAG_SYSTEM)
                .build());

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserNeverUnlocked();
    }

    @Test
    public void testCreateUser_fail_guestAdmin() throws Exception {
        // No need to set createUser() expectation - it shouldn't be called
        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE,
                InitialUserSetter.ON_BOOT)
                .setNewUserName("TheDude")
                .setNewUserFlags(UserInfo.USER_FLAG_GUEST | UserInfo.USER_FLAG_ADMIN)
                .build());

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
    }

    @Test
    public void testCreateUser_fail_ephemeralAdmin() throws Exception {
        // No need to set createUser() expectation - it shouldn't be called
        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE,
                InitialUserSetter.ON_BOOT)
                .setNewUserName("TheDude")
                .setNewUserFlags(UserInfo.USER_FLAG_EPHEMERAL | UserInfo.USER_FLAG_ADMIN)
                .build());

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
    }

    @Test
    public void testCreateUser_fail_createFail() throws Exception {
        // No need to set createUser() expectation - it will return false by default
        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE,
                InitialUserSetter.ON_BOOT)
                .setNewUserName("TheDude")
                .setNewUserFlags(0)
                .build());

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
    }

    @Test
    public void testCreateUser_fail_createThrowsException() throws Exception {
        expectCreateUserThrowsException("TheDude", 0);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE,
                InitialUserSetter.ON_BOOT)
                .setNewUserName("TheDude")
                .setNewUserFlags(0)
                .build());

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
    }

    @Test
    public void testCreateUser_fail_switchFail() throws Exception {
        UserHandle user = expectRegularUserExists(mMockedUserHandleHelper, USER_ID);
        expectCreateFullUser("TheDude", NO_FLAGS, user);
        expectSwitchUserFails(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_CREATE,
                InitialUserSetter.ON_BOOT)
                .setNewUserName("TheDude")
                .setNewUserFlags(0)
                .build());

        verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch();
        verifySystemUserUnlocked();
        verifyLastActiveUserNeverSet();
    }

    @Test
    public void testReplaceUser_ok() throws Exception {
        mockGetCurrentUser(CURRENT_USER_ID);
        expectGuestUserExists(mMockedUserHandleHelper, CURRENT_USER_ID, /* isEphemeral= */ true);
        UserHandle newGuest = expectGuestUserExists(mMockedUserHandleHelper, NEW_USER_ID,
                /* isEphemeral= */ true);

        expectGuestReplaced(CURRENT_USER_ID, newGuest);
        expectSwitchUser(NEW_USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_REPLACE_GUEST,
                InitialUserSetter.ON_SUSPEND)
                .build());

        verifyUserSwitched(NEW_USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        verifyUserDeleted(CURRENT_USER_ID);
        assertInitialUserSet(newGuest);
    }

    @Test
    public void testReplaceUser_fail_cantCreate() throws Exception {
        mockGetCurrentUser(CURRENT_USER_ID);
        expectGuestUserExists(mMockedUserHandleHelper, CURRENT_USER_ID, /* isEphemeral= */ true);
        expectGuestReplaced(CURRENT_USER_ID, null);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_REPLACE_GUEST,
                InitialUserSetter.ON_SUSPEND)
                .build());

        verifyFallbackDefaultBehaviorCalledFromReaplceUser();
    }

    @Test
    public void testReplaceUser_ok_sameUser() throws Exception {
        mockGetCurrentUser(CURRENT_USER_ID);
        UserHandle guest = expectGuestUserExists(mMockedUserHandleHelper, CURRENT_USER_ID,
                /* isEphemeral= */ true);
        expectGuestReplaced(CURRENT_USER_ID, guest);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_REPLACE_GUEST,
                InitialUserSetter.ON_SUSPEND)
                .build());

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(guest);
    }

    @Test
    public void testDefaultBehavior_firstBoot_ok() throws Exception {
        // no need to mock hasInitialUser(), it will return false by default
        UserHandle newUser = expectAdminUserExists(mMockedUserHandleHelper, USER_ID);
        expectCreateFullUser(OWNER_NAME, UserManagerHelper.FLAG_ADMIN, newUser);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT).build());

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newUser);
    }

    @Test
    public void testDefaultBehavior_firstBoot_ok_setLocale() throws Exception {
        // no need to mock hasInitialUser(), it will return false by default
        UserHandle newUser = expectAdminUserExists(mMockedUserHandleHelper, USER_ID);
        expectCreateFullUser(OWNER_NAME, UserManagerHelper.FLAG_ADMIN, newUser);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT)
                .setUserLocales("LOL")
                .build());

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newUser);
        assertSystemLocales("LOL");
    }

    @Test
    public void testDefaultBehavior_firstBoot_ok_setEmptyLocale() throws Exception {
        // no need to mock hasInitialUser(), it will return false by default
        UserHandle newUser = expectAdminUserExists(mMockedUserHandleHelper, USER_ID);
        expectCreateFullUser(OWNER_NAME, UserManagerHelper.FLAG_ADMIN, newUser);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT)
                .setUserLocales("")
                .build());

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newUser);
        assertSystemLocalesToBeNull();
    }

    @Test
    public void testDefaultBehavior_firstBoot_ok_setBlankLocale() throws Exception {
        // no need to mock hasInitialUser(), it will return false by default
        UserHandle newUser = expectAdminUserExists(mMockedUserHandleHelper, USER_ID);
        expectCreateFullUser(OWNER_NAME, UserManagerHelper.FLAG_ADMIN, newUser);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT)
                .setUserLocales(" ")
                .build());

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newUser);
        assertSystemLocalesToBeNull();
    }

    @Test
    public void testDefaultBehavior_firstBoot_fail_createUserFailed() throws Exception {
        // no need to mock hasInitialUser(), it will return false by default
        // no need to mock createUser(), it will return null by default

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT).build());

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromDefaultBehavior();
        verifySystemUserNeverUnlocked();
    }

    @Test
    public void testDefaultBehavior_firstBoot_fail_switchFailed() throws Exception {
        // no need to mock hasInitialUser(), it will return false by default
        UserHandle user = expectAdminUserExists(mMockedUserHandleHelper, USER_ID);
        expectCreateFullUser(OWNER_NAME, UserManagerHelper.FLAG_ADMIN, user);
        expectSwitchUserFails(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT).build());

        verifyFallbackDefaultBehaviorCalledFromDefaultBehavior();
        verifySystemUserUnlocked();
        verifyLastActiveUserNeverSet();
    }

    @Test
    public void testDefaultBehavior_firstBoot_fail_switchFailed_setLocale() throws Exception {
        // no need to mock hasInitialUser(), it will return false by default
        UserHandle user = expectAdminUserExists(mMockedUserHandleHelper, USER_ID);
        expectCreateFullUser(OWNER_NAME, UserManagerHelper.FLAG_ADMIN, user);
        expectSwitchUserFails(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT)
                .setUserLocales("LOL")
                .build());

        verifyFallbackDefaultBehaviorCalledFromDefaultBehavior();
        verifySystemUserUnlocked();
        verifyLastActiveUserNeverSet();
        assertSystemLocales("LOL");
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_ok() throws Exception {
        UserHandle existingUser = expectHasInitialUser(USER_ID);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT).build());

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
        assertInitialUserSet(existingUser);
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_ok_targetIsCurrentUser() throws Exception {
        UserHandle currentUser = expectHasInitialUser(CURRENT_USER_ID);
        expectSwitchUser(CURRENT_USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT).build());

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorNeverCalled();
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
        assertInitialUserSet(currentUser);
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_ok_targetIsCurrentUserWhichIsEphemeral()
            throws Exception {
        mockGetAliveUsers(CURRENT_USER_ID);
        expectEphemeralUserExists(mMockedUserHandleHelper, CURRENT_USER_ID);
        UserHandle newUser = expectAdminUserExists(mMockedUserHandleHelper, USER_ID);
        expectCreateFullUser(OWNER_NAME, UserManagerHelper.FLAG_ADMIN, newUser);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT).build());

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled();
        verifySystemUserUnlocked();
        assertInitialUserSet(newUser);
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_fail_switchFail() throws Exception {
        expectHasInitialUser(USER_ID);
        expectSwitchUserFails(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT).build());

        verifyFallbackDefaultBehaviorCalledFromDefaultBehavior();
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
        verifyLastActiveUserNeverSet();
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_ok_guestReplaced() throws Exception {
        boolean ephemeral = true; // ephemeral doesn't really matter in this test
        expectHasInitialUser(USER_ID);
        expectGuestUserExists(mMockedUserHandleHelper, USER_ID, ephemeral);
        UserHandle newGuest = expectGuestUserExists(mMockedUserHandleHelper, NEW_USER_ID,
                ephemeral);
        expectGuestReplaced(USER_ID, newGuest);
        expectSwitchUser(NEW_USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT)
                .setReplaceGuest(true)
                .build());

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
        expectGuestUserExists(mMockedUserHandleHelper, USER_ID, /* isEphemeral= */ true);
        expectGuestReplaced(USER_ID, /* newGuest= */ null);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT)
                .setReplaceGuest(true)
                .build());

        verifyUserNeverSwitched();
        verifyFallbackDefaultBehaviorCalledFromDefaultBehavior();
        verifyUserNeverCreated();
        verifySystemUserNeverUnlocked();
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_ok_withOverriddenProperty() throws Exception {
        boolean supportsOverrideUserIdProperty = true;
        UserHandle user = expectHasInitialUser(USER_ID, supportsOverrideUserIdProperty);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT)
                .setSupportsOverrideUserIdProperty(true)
                .build());

        verifyUserSwitched(USER_ID);
        verifyFallbackDefaultBehaviorNeverCalled(supportsOverrideUserIdProperty);
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
        assertInitialUserSet(user);
    }

    @Test
    public void testDefaultBehavior_nonFirstBoot_ok_guestDoesNotNeedToBeReplaced()
            throws Exception {
        UserHandle existingGuest = expectHasInitialGuest(USER_ID);
        expectSwitchUser(USER_ID);

        mSetter.set(new InitialUserSetter.Builder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR,
                InitialUserSetter.ON_BOOT)
                .setReplaceGuest(false)
                .build());

        verifyUserSwitched(USER_ID);
        verifyGuestNeverMarkedForDeletion();
        verifyFallbackDefaultBehaviorNeverCalled();
        verifyUserNeverCreated();
        verifySystemUserUnlocked();
        assertInitialUserSet(existingGuest);
    }

    @Test
    public void testStartForegroundUser_ok() throws Exception {
        expectAmStartFgUser(10, /* toBeReturned= */ true);

        assertThat(mSetter.startForegroundUser(INITIAL_USER_INFO_RESUME, 10)).isTrue();
    }

    @Test
    public void testStartForegroundUser_fail() throws Exception {
        expectAmStartFgUser(10, /* toBeReturned= */ false);

        assertThat(mSetter.startForegroundUser(INITIAL_USER_INFO_RESUME, 10)).isFalse();
    }

    private void mockIsHeadlessSystemUserMode(boolean isHeadlessSystemUserMode) {
        mSetter = newSetter(isHeadlessSystemUserMode);
    }

    @Test
    public void testStartForegroundUser_nonHeadlessSystemUser() throws Exception {
        mockIsHeadlessSystemUserMode(false);

        expectAmStartFgUser(UserHandle.USER_SYSTEM, /* toBeReturned= */ true);

        assertThat(mSetter.startForegroundUser(INITIAL_USER_INFO_RESUME, UserHandle.USER_SYSTEM))
                .isTrue();
    }

    @Test
    public void testStartForegroundUser_headlessSystemUser() throws Exception {
        mockIsHeadlessSystemUserMode(true);

        assertThat(mSetter.startForegroundUser(INITIAL_USER_INFO_RESUME, UserHandle.USER_SYSTEM))
                .isFalse();

        verify(mActivityManagerHelper, never()).startUserInForeground(UserHandle.USER_SYSTEM);
    }

    @Test
    public void testGetInitialUser_WithValidLastActiveUser_ReturnsLastActiveUser() {
        setLastActiveUser(12);
        mockGetAliveUsers(USER_SYSTEM, 10, 11, 12);

        assertThat(mSetter.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(12);
    }

    @Test
    public void testGetInitialUser_WithNonExistLastActiveUser_ReturnsLastPersistentUser() {
        setLastActiveUser(120);
        setLastPersistentActiveUser(110);
        mockGetAliveUsers(USER_SYSTEM, 100, 110);

        assertThat(mSetter.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(110);
        // should have reset last active user
        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_USER_ID))
                .isEqualTo(UserHandle.USER_NULL);
    }

    @Test
    public void testGetInitialUser_WithNonExistLastActiveAndPersistentUsers_ReturnsSmallestUser() {
        setLastActiveUser(120);
        setLastPersistentActiveUser(120);
        mockGetAliveUsers(USER_SYSTEM, 100, 110);

        assertThat(mSetter.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(100);
        // should have reset both settions
        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_USER_ID))
                .isEqualTo(UserHandle.USER_NULL);
        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_PERSISTENT_USER_ID))
                .isEqualTo(UserHandle.USER_NULL);
    }

    @Test
    public void testGetInitialUser_WithOverrideId_ReturnsOverrideId() {
        setDefaultBootUserOverride(11);
        setLastActiveUser(12);
        mockGetAliveUsers(USER_SYSTEM, 10, 11, 12);

        assertThat(mSetter.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(11);
    }

    @Test
    public void testGetInitialUser_WithInvalidOverrideId_ReturnsLastActiveUserId() {
        setDefaultBootUserOverride(15);
        setLastActiveUser(12);
        mockGetAliveUsers(USER_SYSTEM, 10, 11, 12);

        assertThat(mSetter.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(12);
    }

    @Test
    public void testGetInitialUser_WithInvalidOverrideAndLastActiveUserIds_ReturnsSmallestUserId() {
        int minimumUserId = 10;
        int invalidLastActiveUserId = 14;
        int invalidOverrideUserId = 15;

        setDefaultBootUserOverride(invalidOverrideUserId);
        setLastActiveUser(invalidLastActiveUserId);
        mockGetAliveUsers(USER_SYSTEM, minimumUserId, minimumUserId + 1, minimumUserId + 2);

        assertThat(mSetter.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(minimumUserId);
    }

    @Test
    public void testGetInitialUser_WhenOverrideIdIsIgnored() {
        setDefaultBootUserOverride(11);
        setLastActiveUser(12);
        mockGetAliveUsers(USER_SYSTEM, 10, 11, 12);

        assertThat(mSetter.getInitialUser(/* usesOverrideUserIdProperty= */ false))
                .isEqualTo(12);
    }

    @Test
    public void testGetInitialUser_WithEmptyReturnNull() {
        assertThat(mSetter.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(UserHandle.USER_NULL);
    }

    @Test
    public void testHasInitialUser_onlyHeadlessSystemUser() {
        mockIsHeadlessSystemUserMode(true);
        mockGetAliveUsers(USER_SYSTEM);

        assertThat(mSetter.hasInitialUser()).isFalse();
    }

    @Test
    public void testHasInitialUser_onlyNonHeadlessSystemUser() {
        mockIsHeadlessSystemUserMode(false);
        mockGetAliveUsers(USER_SYSTEM);

        assertThat(mSetter.hasInitialUser()).isTrue();
    }

    @Test
    public void testHasInitialUser_hasNormalUser() {
        mockIsHeadlessSystemUserMode(true);
        mockGetAliveUsers(USER_SYSTEM, 10);

        assertThat(mSetter.hasInitialUser()).isTrue();
    }

    @Test
    public void testHasInitialUser_hasOnlyEphemeralUser() {
        mockIsHeadlessSystemUserMode(true);
        mockGetAliveUsers(USER_SYSTEM, 10);
        expectEphemeralUserExists(mMockedUserHandleHelper, 10);

        // TODO(b/231473748): should call hasInitialUser() instead
        assertThat(mSetter.hasValidInitialUser()).isFalse();
    }

    @Test
    public void testHasInitialUser_hasOnlyWorkProfile() {
        mockIsHeadlessSystemUserMode(true);

        UserHandle systemUser = expectRegularUserExists(mMockedUserHandleHelper,
                UserHandle.USER_SYSTEM);

        UserHandle workProfile = expectManagedProfileExists(mMockedUserHandleHelper, 10);

        mockGetAliveUsers(systemUser, workProfile);

        assertThat(mSetter.hasInitialUser()).isFalse();
    }

    @Test
    public void testSetLastActiveUser_headlessSystem() {
        mockIsHeadlessSystemUserMode(true);
        expectSystemUserExists(mMockedUserHandleHelper, UserHandle.USER_SYSTEM);

        mSetter.setLastActiveUser(UserHandle.USER_SYSTEM);

        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_USER_ID)).isNull();
        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_PERSISTENT_USER_ID))
                .isNull();
    }

    @Test
    public void testSetLastActiveUser_nonHeadlessSystem() {
        mockIsHeadlessSystemUserMode(false);
        expectSystemUserExists(mMockedUserHandleHelper, UserHandle.USER_SYSTEM);

        mSetter.setLastActiveUser(UserHandle.USER_SYSTEM);

        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_USER_ID))
                .isEqualTo(UserHandle.USER_SYSTEM);
        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_PERSISTENT_USER_ID))
                .isEqualTo(UserHandle.USER_SYSTEM);
    }

    @Test
    public void testSetLastActiveUser_nonExistingUser() {
        // Don't need to mock um.getUser(), it will return null by default
        mSetter.setLastActiveUser(42);

        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_USER_ID)).isEqualTo(42);
        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_PERSISTENT_USER_ID))
                .isNull();
    }

    @Test
    public void testSetLastActiveUser_ephemeralUser() {
        int persistentUserId = 42;
        int ephemeralUserid = 108;
        expectRegularUserExists(mMockedUserHandleHelper, persistentUserId);
        expectEphemeralUserExists(mMockedUserHandleHelper, ephemeralUserid);

        mSetter.setLastActiveUser(persistentUserId);
        mSetter.setLastActiveUser(ephemeralUserid);

        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_USER_ID))
                .isEqualTo(ephemeralUserid);
        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_PERSISTENT_USER_ID))
                .isEqualTo(persistentUserId);
    }

    @Test
    public void testSetLastActiveUser_nonEphemeralUser() {
        expectRegularUserExists(mMockedUserHandleHelper, 42);
        mSetter.setLastActiveUser(42);

        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_USER_ID)).isEqualTo(42);
        assertThat(getFromFakeGlobalSettings(CarSettings.Global.LAST_ACTIVE_PERSISTENT_USER_ID))
                .isEqualTo(42);
    }

    private void mockGetAliveUsers(@UserIdInt int... userIds) {
        when(mUm.getUserHandles(/* excludeDying= */ false)).thenReturn(
                UserTestingHelper.newUserHandles(userIds));
    }

    private void mockGetAliveUsers(UserHandle... users) {
        when(mUm.getUserHandles(/* excludeDying= */ false)).thenReturn(
                UserTestingHelper.toList(users));
    }

    private void setLastActiveUser(@UserIdInt int userId) {
        synchronized (mLock) {
            mFakeGlobalSettings.put(CarSettings.Global.LAST_ACTIVE_USER_ID, userId);
        }
    }

    private void setLastPersistentActiveUser(@UserIdInt int userId) {
        synchronized (mLock) {
            mFakeGlobalSettings.put(CarSettings.Global.LAST_ACTIVE_PERSISTENT_USER_ID, userId);
        }
    }

    private void setDefaultBootUserOverride(@UserIdInt int userId) {
        when(mCarSystemProperties.getBootUserOverrideId()).thenReturn(Optional.of(userId));
    }

    private UserHandle expectHasInitialUser(@UserIdInt int userId) {
        return expectHasInitialUser(userId, /* supportsOverrideUserIdProperty= */ false);
    }

    private UserHandle expectHasInitialUser(@UserIdInt int userId,
            boolean supportsOverrideUserIdProperty) {
        return expectHasInitialUser(userId, /* isGuest= */ false, supportsOverrideUserIdProperty);
    }

    private UserHandle expectHasInitialGuest(int userId) {
        return expectHasInitialUser(userId, /* isGuest= */ true,
                /* supportsOverrideUserIdProperty= */ false);
    }
    private UserHandle expectHasInitialUser(@UserIdInt int userId, boolean isGuest,
            boolean supportsOverrideUserIdProperty) {
        doReturn(true).when(mSetter).hasInitialUser();
        doReturn(true).when(mSetter).hasValidInitialUser();
        doReturn(userId).when(mSetter).getInitialUser(supportsOverrideUserIdProperty);
        return isGuest
                ? expectGuestUserExists(mMockedUserHandleHelper, userId, /* isEphemeral= */ true)
                : expectRegularUserExists(mMockedUserHandleHelper, userId);
    }

    private void expectUserIsSecure(@UserIdInt int userId) {
        when(mLockPatternHelper.isSecure(any(), eq(userId))).thenReturn(true);
    }

    private void expectGuestReplaced(int existingGuestId, UserHandle newGuest) {
        doReturn(newGuest).when(mSetter).replaceGuestIfNeeded(isUserHandle(existingGuestId));
    }

    private void expectSwitchUser(@UserIdInt int userId) throws Exception {
        doReturn(true).when(mSetter).startForegroundUser(any(), eq(userId));
    }

    private void expectSwitchUserFails(@UserIdInt int userId) {
        doReturn(false).when(mSetter).startForegroundUser(any(), eq(userId));
    }

    private void expectSwitchUserThrowsException(@UserIdInt int userId) {
        doThrow(new RuntimeException("D'OH! Cannot switch to " + userId)).when(mSetter)
                .startForegroundUser(any(), eq(userId));
    }

    private UserHandle expectCreateFullUser(@Nullable String name, int flags, UserHandle user) {
        when(mCarUserService.createUserEvenWhenDisallowed(name,
                UserManager.USER_TYPE_FULL_SECONDARY, flags)).thenReturn(user);
        return user;
    }

    private UserHandle expectCreateGuestUser(@Nullable String name,
            int flags, UserHandle user) {
        when(mCarUserService.createUserEvenWhenDisallowed(name,
                UserManager.USER_TYPE_FULL_GUEST, flags)).thenReturn(user);
        return user;
    }

    private void expectCreateUserThrowsException(String name, @UserIdInt int flags) {
        when(mCarUserService.createUserEvenWhenDisallowed(eq(name), anyString(), eq(flags)))
                .thenThrow(new RuntimeException("Cannot create user. D'OH!"));
    }

    private void expectAmStartFgUser(@UserIdInt int userId, boolean toBeReturned) throws Exception {
        when(mActivityManagerHelper.startUserInForeground(userId)).thenReturn(toBeReturned);
    }

    private void verifyUserSwitched(@UserIdInt int userId) throws Exception {
        verify(mSetter).startForegroundUser(any(), eq(userId));
        verify(mSetter).setLastActiveUser(userId);
    }

    private void verifyUserNeverSwitched() throws Exception {
        verify(mSetter, never()).startForegroundUser(any(), anyInt());
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
        verify(mUm).removeUser(UserHandle.of(userId));
    }

    private void verifyFallbackDefaultBehaviorCalledFromCreateOrSwitch() {
        verify(mSetter).fallbackDefaultBehavior(isInitialInfo(false), eq(true), anyString());
        assertInitialUserSet(null);
    }

    private void verifyFallbackDefaultBehaviorCalledFromDefaultBehavior() {
        verify(mSetter).fallbackDefaultBehavior(isInitialInfo(false), eq(false), anyString());
        assertInitialUserSet(null);
    }

    private void verifyFallbackDefaultBehaviorCalledFromReaplceUser() {
        verify(mSetter).fallbackDefaultBehavior(isInitialInfo(false), eq(true), anyString());
        assertInitialUserSet(null);
    }

    private void verifyFallbackDefaultBehaviorNeverCalled() {
        verifyFallbackDefaultBehaviorNeverCalled(/* supportsOverrideUserIdProperty= */ false);
    }

    private void verifyFallbackDefaultBehaviorNeverCalled(boolean supportsOverrideUserIdProperty) {
        verify(mSetter, never()).fallbackDefaultBehavior(
                isInitialInfo(supportsOverrideUserIdProperty), anyBoolean(), anyString());
    }

    private static InitialUserSetter.InitialUserInfo isInitialInfo(
            boolean supportsOverrideUserIdProperty) {
        return argThat((info) -> {
            return info.supportsOverrideUserIdProperty == supportsOverrideUserIdProperty;
        });
    }

    private void verifySystemUserUnlocked() {
        // TODO(b/261913541): Add more unit test to check unlockSystemUser as it is only relevant
        // for T platform.
        //verify(mSetter).unlockSystemUser();
    }

    private void verifySystemUserNeverUnlocked() {
        // TODO(b/261913541): Add more unit test to check unlockSystemUser as it is only relevant
        // for T platform.
        //verify(mSetter, never()).unlockSystemUser();
    }

    private void verifyLastActiveUserNeverSet() {
        verify(mSetter, never()).setLastActiveUser(anyInt());
    }

    private void assertInitialUserSet(UserHandle expectedUser) {
        assertWithMessage("number of listener ").that(mListener.numberCalls).isEqualTo(1);
        assertWithMessage("initial user on listener").that(mListener.initialUser)
            .isSameInstanceAs(expectedUser);
    }

    private void assertSystemLocales(String expected) {
        // TODO(b/156033195): should test specific userId
        assertThat(getFromFakeSystemSettings(Settings.System.SYSTEM_LOCALES)).isEqualTo(expected);
    }

    private void assertSystemLocalesToBeNull() {
        assertThat(getFromFakeSystemSettings(Settings.System.SYSTEM_LOCALES)).isNull();
    }

    private Integer getFromFakeGlobalSettings(String key) {
        synchronized (mLock) {
            return mFakeGlobalSettings.get(key);
        }
    }

    private String getFromFakeSystemSettings(String key) {
        synchronized (mLock) {
            return mFakeSystemSettings.get(key);
        }
    }

    private static final class MyListener implements Consumer<UserHandle> {
        public int numberCalls;
        public UserHandle initialUser;

        @Override
        public void accept(UserHandle initialUser) {
            this.initialUser = initialUser;
            numberCalls++;
        }
    }
}
