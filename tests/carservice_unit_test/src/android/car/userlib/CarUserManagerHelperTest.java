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

package android.car.userlib;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

/**
 * This class contains unit tests for the {@link CarUserManagerHelper}.
 * It tests that {@link CarUserManagerHelper} does the right thing for user management flows.
 *
 * The following mocks are used:
 * 1. {@link Context} provides system services and resources.
 * 2. {@link UserManager} provides dummy users and user info.
 * 3. {@link ActivityManager} to verify user switch is invoked.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class CarUserManagerHelperTest {
    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();
    @Mock private Context mContext;
    @Mock private UserManager mUserManager;
    @Mock private ActivityManager mActivityManager;
    @Mock private TestableFrameworkWrapper mTestableFrameworkWrapper;

    private static final String GUEST_USER_NAME = "testGuest";
    private static final String TEST_USER_NAME = "testUser";
    private static final String DEFAULT_ADMIN_NAME = "defaultAdminName";

    private CarUserManagerHelper mCarUserManagerHelper;
    private UserInfo mCurrentProcessUser;
    private UserInfo mSystemUser;
    private int mForegroundUserId;

    @Before
    public void setUpMocksAndVariables() {
        doReturn(mUserManager).when(mContext).getSystemService(Context.USER_SERVICE);
        doReturn(mActivityManager).when(mContext).getSystemService(Context.ACTIVITY_SERVICE);
        doReturn(InstrumentationRegistry.getTargetContext().getResources())
                .when(mContext).getResources();
        doReturn(InstrumentationRegistry.getTargetContext().getContentResolver())
                .when(mContext).getContentResolver();
        doReturn(mContext).when(mContext).getApplicationContext();
        mCarUserManagerHelper = new CarUserManagerHelper(mContext, mTestableFrameworkWrapper);
        mCarUserManagerHelper.setDefaultAdminName(DEFAULT_ADMIN_NAME);

        mCurrentProcessUser = createUserInfoForId(UserHandle.myUserId());
        mSystemUser = createUserInfoForId(UserHandle.USER_SYSTEM);
        doReturn(mCurrentProcessUser).when(mUserManager).getUserInfo(UserHandle.myUserId());

        // Get the ID of the foreground user running this test.
        // We cannot mock the foreground user since getCurrentUser is static.
        // We cannot rely on foreground_id != system_id, they could be the same user.
        mForegroundUserId = ActivityManager.getCurrentUser();

        // Clear boot override for every test by returning the default value passed to the method
        when(mTestableFrameworkWrapper.getBootUserOverrideId(anyInt()))
                .thenAnswer(stub -> stub.getArguments()[0]);
    }

    @Test
    public void testGetMaxSupportedRealUsers() {
        setMaxSupportedUsers(7);

        // Create three managed profiles, and two normal users.
        UserInfo user1 = createUserInfoForId(10);
        UserInfo user2 =
                new UserInfo(/* id= */ 11, /* name = */ "user11", UserInfo.FLAG_MANAGED_PROFILE);
        UserInfo user3 =
                new UserInfo(/* id= */ 12, /* name = */ "user12", UserInfo.FLAG_MANAGED_PROFILE);
        UserInfo user4 = createUserInfoForId(13);
        UserInfo user5 =
                new UserInfo(/* id= */ 14, /* name = */ "user14", UserInfo.FLAG_MANAGED_PROFILE);

        mockGetUsers(user1, user2, user3, user4, user5);

        // Max users - # managed profiles - headless system user.
        assertThat(mCarUserManagerHelper.getMaxSupportedRealUsers()).isEqualTo(3);
    }

    @Test
    public void testCreateNewNonAdminUser() {
        // Verify createUser on UserManager gets called.
        mCarUserManagerHelper.createNewNonAdminUser(TEST_USER_NAME);
        verify(mUserManager).createUser(TEST_USER_NAME, 0);

        doReturn(null).when(mUserManager).createUser(TEST_USER_NAME, 0);
        assertThat(mCarUserManagerHelper.createNewNonAdminUser(TEST_USER_NAME)).isNull();

        UserInfo newUser = new UserInfo();
        newUser.name = TEST_USER_NAME;
        doReturn(newUser).when(mUserManager).createUser(TEST_USER_NAME, 0);
        assertThat(mCarUserManagerHelper.createNewNonAdminUser(TEST_USER_NAME)).isEqualTo(newUser);
    }

    @Test
    public void testCannotRemoveSystemUser() {
        assertThat(mCarUserManagerHelper.removeUser(mSystemUser, GUEST_USER_NAME)).isFalse();
    }

    @Test
    public void testAdminsCanRemoveOtherUsers() {
        int idToRemove = mCurrentProcessUser.id + 2;
        UserInfo userToRemove = createUserInfoForId(idToRemove);

        doReturn(true).when(mUserManager).removeUser(idToRemove);

        // If Admin is removing non-current, non-system user, simply calls removeUser.
        doReturn(true).when(mUserManager).isAdminUser();
        assertThat(mCarUserManagerHelper.removeUser(userToRemove, GUEST_USER_NAME)).isTrue();
        verify(mUserManager).removeUser(idToRemove);
    }

    @Test
    public void testNonAdminsCanNotRemoveOtherUsers() {
        UserInfo otherUser = createUserInfoForId(mCurrentProcessUser.id + 2);

        // Make current user non-admin.
        doReturn(false).when(mUserManager).isAdminUser();

        // Mock so that removeUser always pretends it's successful.
        doReturn(true).when(mUserManager).removeUser(anyInt());

        // If Non-Admin is trying to remove someone other than themselves, they should fail.
        assertThat(mCarUserManagerHelper.removeUser(otherUser, GUEST_USER_NAME)).isFalse();
        verify(mUserManager, never()).removeUser(otherUser.id);
    }

    @Test
    public void testRemoveLastActiveUser() {
        // Cannot remove system user.
        assertThat(mCarUserManagerHelper.removeUser(mSystemUser, GUEST_USER_NAME)).isFalse();

        UserInfo adminInfo = new UserInfo(/* id= */10, "admin", UserInfo.FLAG_ADMIN);
        mockGetUsers(adminInfo);

        assertThat(mCarUserManagerHelper.removeUser(adminInfo, GUEST_USER_NAME))
                .isEqualTo(false);
    }

    @Test
    public void testRemoveLastAdminUser() {
        // Make current user admin.
        doReturn(true).when(mUserManager).isAdminUser();

        UserInfo adminInfo = new UserInfo(/* id= */10, "admin", UserInfo.FLAG_ADMIN);
        UserInfo nonAdminInfo = new UserInfo(/* id= */11, "non-admin", 0);
        mockGetUsers(adminInfo, nonAdminInfo);

        UserInfo newAdminInfo = new UserInfo(/* id= */12, DEFAULT_ADMIN_NAME, UserInfo.FLAG_ADMIN);
        doReturn(newAdminInfo)
                .when(mUserManager).createUser(DEFAULT_ADMIN_NAME, UserInfo.FLAG_ADMIN);

        mCarUserManagerHelper.removeUser(adminInfo, GUEST_USER_NAME);
        verify(mUserManager).createUser(DEFAULT_ADMIN_NAME, UserInfo.FLAG_ADMIN);
        verify(mActivityManager).switchUser(newAdminInfo.id);
        verify(mUserManager).removeUser(adminInfo.id);
    }

    @Test
    public void testRemoveLastAdminUserFailsToCreateNewUser() {
        // Make current user admin.
        doReturn(true).when(mUserManager).isAdminUser();

        UserInfo adminInfo = new UserInfo(/* id= */10, "admin", UserInfo.FLAG_ADMIN);
        UserInfo nonAdminInfo = new UserInfo(/* id= */11, "non-admin", 0);
        mockGetUsers(adminInfo, nonAdminInfo);

        UserInfo newAdminInfo = new UserInfo(/* id= */12, DEFAULT_ADMIN_NAME, UserInfo.FLAG_ADMIN);
        doReturn(newAdminInfo)
                .when(mUserManager).createUser(DEFAULT_ADMIN_NAME, UserInfo.FLAG_ADMIN);

        // Fail to create a new user to force a failure case
        doReturn(null)
                .when(mUserManager).createUser(DEFAULT_ADMIN_NAME, UserInfo.FLAG_ADMIN);

        mCarUserManagerHelper.removeUser(adminInfo, GUEST_USER_NAME);
        verify(mUserManager).createUser(DEFAULT_ADMIN_NAME, UserInfo.FLAG_ADMIN);
        verify(mActivityManager, never()).switchUser(anyInt());
        verify(mUserManager, never()).removeUser(adminInfo.id);
    }

    @Test
    public void testSwitchToGuest() {
        mCarUserManagerHelper.startGuestSession(GUEST_USER_NAME);
        verify(mUserManager).createGuest(mContext, GUEST_USER_NAME);

        UserInfo guestInfo = new UserInfo(/* id= */21, GUEST_USER_NAME, UserInfo.FLAG_GUEST);
        doReturn(guestInfo).when(mUserManager).createGuest(mContext, GUEST_USER_NAME);
        mCarUserManagerHelper.startGuestSession(GUEST_USER_NAME);
        verify(mActivityManager).switchUser(21);
    }

    @Test
    public void testSwitchToId() {
        int userIdToSwitchTo = mForegroundUserId + 2;
        doReturn(true).when(mActivityManager).switchUser(userIdToSwitchTo);

        assertThat(mCarUserManagerHelper.switchToUserId(userIdToSwitchTo)).isTrue();
        verify(mActivityManager).switchUser(userIdToSwitchTo);
    }

    @Test
    public void testSwitchToForegroundIdExitsEarly() {
        doReturn(true).when(mActivityManager).switchUser(mForegroundUserId);

        assertThat(mCarUserManagerHelper.switchToUserId(mForegroundUserId)).isFalse();
        verify(mActivityManager, never()).switchUser(mForegroundUserId);
    }

    @Test
    public void testCannotSwitchIfSwitchingNotAllowed() {
        int userIdToSwitchTo = mForegroundUserId + 2;
        doReturn(true).when(mActivityManager).switchUser(userIdToSwitchTo);
        doReturn(true).when(mUserManager).hasUserRestriction(UserManager.DISALLOW_USER_SWITCH);
        assertThat(mCarUserManagerHelper.switchToUserId(userIdToSwitchTo)).isFalse();
        verify(mActivityManager, never()).switchUser(userIdToSwitchTo);
    }

    @Test
    public void testGetUserIcon() {
        mCarUserManagerHelper.getUserIcon(mCurrentProcessUser);
        verify(mUserManager).getUserIcon(mCurrentProcessUser.id);
    }

    @Test
    public void testGrantAdminPermissions() {
        int userId = 30;
        UserInfo testInfo = createUserInfoForId(userId);

        // Test that non-admins cannot grant admin permissions.
        doReturn(false).when(mUserManager).isAdminUser(); // Current user non-admin.
        mCarUserManagerHelper.grantAdminPermissions(testInfo);
        verify(mUserManager, never()).setUserAdmin(userId);

        // Admins can grant admin permissions.
        doReturn(true).when(mUserManager).isAdminUser();
        mCarUserManagerHelper.grantAdminPermissions(testInfo);
        verify(mUserManager).setUserAdmin(userId);
    }

    @Test
    public void testDefaultNonAdminRestrictions() {
        String testUserName = "Test User";
        int userId = 20;
        UserInfo newNonAdmin = createUserInfoForId(userId);

        doReturn(newNonAdmin).when(mUserManager).createUser(testUserName, /* flags= */ 0);

        mCarUserManagerHelper.createNewNonAdminUser(testUserName);

        verify(mUserManager).setUserRestriction(
                UserManager.DISALLOW_FACTORY_RESET, /* enable= */ true, UserHandle.of(userId));
        verify(mUserManager).setUserRestriction(
                UserManager.DISALLOW_SMS, /* enable= */ false, UserHandle.of(userId));
        verify(mUserManager).setUserRestriction(
                UserManager.DISALLOW_OUTGOING_CALLS, /* enable= */ false, UserHandle.of(userId));
    }

    @Test
    public void testDefaultGuestRestrictions() {
        int guestRestrictionsExpectedCount = 6;

        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        mCarUserManagerHelper.initDefaultGuestRestrictions();

        verify(mUserManager).setDefaultGuestRestrictions(bundleCaptor.capture());
        Bundle guestRestrictions = bundleCaptor.getValue();

        assertThat(guestRestrictions.keySet()).hasSize(guestRestrictionsExpectedCount);
        assertThat(guestRestrictions.getBoolean(UserManager.DISALLOW_FACTORY_RESET)).isTrue();
        assertThat(guestRestrictions.getBoolean(UserManager.DISALLOW_REMOVE_USER)).isTrue();
        assertThat(guestRestrictions.getBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS)).isTrue();
        assertThat(guestRestrictions.getBoolean(UserManager.DISALLOW_INSTALL_APPS)).isTrue();
        assertThat(guestRestrictions.getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES))
                .isTrue();
        assertThat(guestRestrictions.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS)).isTrue();
    }

    @Test
    public void testGrantingAdminPermissionsRemovesNonAdminRestrictions() {
        int testUserId = 30;
        boolean restrictionEnabled = false;
        UserInfo testInfo = createUserInfoForId(testUserId);

        // Only admins can grant permissions.
        doReturn(true).when(mUserManager).isAdminUser();

        mCarUserManagerHelper.grantAdminPermissions(testInfo);

        verify(mUserManager).setUserRestriction(
                UserManager.DISALLOW_FACTORY_RESET, restrictionEnabled, UserHandle.of(testUserId));
    }

    @Test
    public void test_GetInitialUserWithValidLastActiveUser_ReturnsLastActiveUser() {
        int lastActiveUserId = 12;

        UserInfo user10 = createUserInfoForId(10);
        UserInfo user11 = createUserInfoForId(11);
        UserInfo user12 = createUserInfoForId(12);

        setLastActiveUser(lastActiveUserId);
        mockGetUsers(mSystemUser, user10, user11, user12);

        assertThat(mCarUserManagerHelper.getInitialUser()).isEqualTo(lastActiveUserId);
    }

    @Test
    public void test_GetInitialUserWithNonExistLastActiveUser_ReturnsSmallestUserId() {
        int lastActiveUserId = 12;
        int minimumUserId = 10;

        UserInfo smallestUser = createUserInfoForId(minimumUserId);
        UserInfo notSmallestUser = createUserInfoForId(minimumUserId + 1);

        setLastActiveUser(lastActiveUserId);
        mockGetUsers(mSystemUser, smallestUser, notSmallestUser);

        assertThat(mCarUserManagerHelper.getInitialUser()).isEqualTo(minimumUserId);
    }

    @Test
    public void test_GetInitialUserWithOverrideId_ReturnsOverrideId() {
        int lastActiveUserId = 12;
        int overrideUserId = 11;

        UserInfo user10 = createUserInfoForId(10);
        UserInfo user11 = createUserInfoForId(11);
        UserInfo user12 = createUserInfoForId(12);

        setDefaultBootUserOverride(overrideUserId);
        setLastActiveUser(lastActiveUserId);
        mockGetUsers(mSystemUser, user10, user11, user12);

        assertThat(mCarUserManagerHelper.getInitialUser()).isEqualTo(overrideUserId);
    }

    @Test
    public void test_GetInitialUserWithInvalidOverrideId_ReturnsLastActiveUserId() {
        int lastActiveUserId = 12;
        int overrideUserId = 15;

        UserInfo user10 = createUserInfoForId(10);
        UserInfo user11 = createUserInfoForId(11);
        UserInfo user12 = createUserInfoForId(12);

        setDefaultBootUserOverride(overrideUserId);
        setLastActiveUser(lastActiveUserId);
        mockGetUsers(mSystemUser, user10, user11, user12);

        assertThat(mCarUserManagerHelper.getInitialUser()).isEqualTo(lastActiveUserId);
    }

    @Test
    public void test_GetInitialUserWithInvalidOverrideAndLastActiveUserIds_ReturnsSmallestUserId() {
        int minimumUserId = 10;
        int invalidLastActiveUserId = 14;
        int invalidOverrideUserId = 15;

        UserInfo minimumUser = createUserInfoForId(minimumUserId);
        UserInfo user11 = createUserInfoForId(minimumUserId + 1);
        UserInfo user12 = createUserInfoForId(minimumUserId + 2);

        setDefaultBootUserOverride(invalidOverrideUserId);
        setLastActiveUser(invalidLastActiveUserId);
        mockGetUsers(mSystemUser, minimumUser, user11, user12);

        assertThat(mCarUserManagerHelper.getInitialUser()).isEqualTo(minimumUserId);
    }

    @Test
    public void test_CreateNewOrFindExistingGuest_ReturnsExistingGuest() {
        // Create two users and a guest user.
        UserInfo user1 = createUserInfoForId(10);
        UserInfo user2 = createUserInfoForId(12);
        UserInfo user3 = new UserInfo(/* id= */ 13, /* name = */ "user13", UserInfo.FLAG_GUEST);

        mockGetUsers(user1, user2, user3);
        doReturn(null).when(mUserManager).createGuest(any(), any());

        UserInfo guest = mCarUserManagerHelper.createNewOrFindExistingGuest(GUEST_USER_NAME);
        assertThat(guest).isEqualTo(user3);
    }

    @Test
    public void test_CreateNewOrFindExistingGuest_CreatesNewGuest_IfNoExisting() {
        // Create two users.
        UserInfo user1 = createUserInfoForId(10);
        UserInfo user2 = createUserInfoForId(12);

        mockGetUsers(user1, user2);

        // Create a user for the "new guest" user.
        UserInfo guestInfo = new UserInfo(/* id= */21, GUEST_USER_NAME, UserInfo.FLAG_GUEST);
        doReturn(guestInfo).when(mUserManager).createGuest(mContext, GUEST_USER_NAME);

        UserInfo guest = mCarUserManagerHelper.createNewOrFindExistingGuest(GUEST_USER_NAME);
        verify(mUserManager).createGuest(mContext, GUEST_USER_NAME);
        assertThat(guest).isEqualTo(guestInfo);
    }

    private UserInfo createUserInfoForId(int id) {
        UserInfo userInfo = new UserInfo();
        userInfo.id = id;
        return userInfo;
    }

    private void mockGetUsers(UserInfo... users) {
        List<UserInfo> testUsers = new ArrayList<>();
        for (UserInfo user : users) {
            testUsers.add(user);
        }
        doReturn(testUsers).when(mUserManager).getUsers(true);
    }

    private void setLastActiveUser(int userId) {
        Settings.Global.putInt(InstrumentationRegistry.getTargetContext().getContentResolver(),
                Settings.Global.LAST_ACTIVE_USER_ID, userId);
    }

    private void setDefaultBootUserOverride(int userId) {
        doReturn(userId).when(mTestableFrameworkWrapper)
                .getBootUserOverrideId(anyInt());
    }

    private void setMaxSupportedUsers(int maxValue) {
        doReturn(maxValue).when(mTestableFrameworkWrapper).userManagerGetMaxSupportedUsers();
    }
}
