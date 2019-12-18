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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

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
@RunWith(MockitoJUnitRunner.class)
@SmallTest
public class CarUserManagerHelperTest {
    @Mock private Context mContext;
    @Mock private UserManager mUserManager;
    @Mock private ActivityManager mActivityManager;
    @Mock private TestableFrameworkWrapper mTestableFrameworkWrapper;

    private static final String TEST_USER_NAME = "testUser";

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
        doReturn(UserManager.SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED)
                .when(mUserManager).getUserSwitchability();
        assertThat(mCarUserManagerHelper.switchToUserId(userIdToSwitchTo)).isFalse();
        verify(mActivityManager, never()).switchUser(userIdToSwitchTo);
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
