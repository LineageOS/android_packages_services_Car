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

import static android.car.userlib.InitialUserSetterTest.setHeadlessSystemUserMode;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.car.test.mocks.AbstractExtendMockitoTestCase;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.sysprop.CarProperties;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * This class contains unit tests for the {@link CarUserManagerHelper}.
 * It tests that {@link CarUserManagerHelper} does the right thing for user management flows.
 *
 * The following mocks are used:
 * 1. {@link Context} provides system services and resources.
 * 2. {@link UserManager} provides dummy users and user info.
 * 3. {@link ActivityManager} to verify user switch is invoked.
 */
@SmallTest
public class CarUserManagerHelperTest extends AbstractExtendMockitoTestCase {
    @Mock private Context mContext;
    @Mock private UserManager mUserManager;
    @Mock private ActivityManager mActivityManager;
    @Mock private ContentResolver mContentResolver;

    // Not worth to mock because it would need to mock a Drawable used by UserIcons.
    private final Resources mResources = InstrumentationRegistry.getTargetContext().getResources();

    private static final String TEST_USER_NAME = "testUser";
    private static final int NO_FLAGS = 0;

    private CarUserManagerHelper mCarUserManagerHelper;
    private UserInfo mSystemUser;
    private final int mForegroundUserId = 42;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session
            .spyStatic(ActivityManager.class)
            .spyStatic(CarProperties.class)
            .spyStatic(UserManager.class)
            .spyStatic(Settings.Global.class);
    }

    @Before
    public void setUpMocksAndVariables() {
        doReturn(mUserManager).when(mContext).getSystemService(Context.USER_SERVICE);
        doReturn(mActivityManager).when(mContext).getSystemService(Context.ACTIVITY_SERVICE);
        doReturn(mResources).when(mContext).getResources();
        doReturn(mContentResolver).when(mContext).getContentResolver();
        doReturn(mContext).when(mContext).getApplicationContext();
        mCarUserManagerHelper = new CarUserManagerHelper(mContext);

        mSystemUser = createUserInfoForId(UserHandle.USER_SYSTEM);

        mockGetCurrentUser(mForegroundUserId);
    }

    @Test
    public void testCreateNewNonAdminUser() {
        // Verify createUser on UserManager gets called.
        mCarUserManagerHelper.createNewNonAdminUser(TEST_USER_NAME);
        verify(mUserManager).createUser(TEST_USER_NAME, NO_FLAGS);

        doReturn(null).when(mUserManager).createUser(TEST_USER_NAME, NO_FLAGS);
        assertThat(mCarUserManagerHelper.createNewNonAdminUser(TEST_USER_NAME)).isNull();

        UserInfo newUser = new UserInfo();
        newUser.name = TEST_USER_NAME;
        doReturn(newUser).when(mUserManager).createUser(TEST_USER_NAME, NO_FLAGS);
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

        doReturn(newNonAdmin).when(mUserManager).createUser(testUserName, NO_FLAGS);

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
    public void testGetInitialUser_WithValidLastActiveUser_ReturnsLastActiveUser() {
        int lastActiveUserId = 12;

        UserInfo user10 = createUserInfoForId(10);
        UserInfo user11 = createUserInfoForId(11);
        UserInfo user12 = createUserInfoForId(12);

        setLastActiveUser(lastActiveUserId);
        mockGetUsers(mSystemUser, user10, user11, user12);

        assertThat(mCarUserManagerHelper.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(lastActiveUserId);
    }

    @Test
    public void testGetInitialUser_WithNonExistLastActiveUser_ReturnsSmallestUserId() {
        int lastActiveUserId = 12;
        int minimumUserId = 10;

        UserInfo smallestUser = createUserInfoForId(minimumUserId);
        UserInfo notSmallestUser = createUserInfoForId(minimumUserId + 1);

        setLastActiveUser(lastActiveUserId);
        mockGetUsers(mSystemUser, smallestUser, notSmallestUser);

        assertThat(mCarUserManagerHelper.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(minimumUserId);
    }

    @Test
    @FlakyTest
    public void testGetInitialUser_WithOverrideId_ReturnsOverrideId() {
        int lastActiveUserId = 12;
        int overrideUserId = 11;

        UserInfo user10 = createUserInfoForId(10);
        UserInfo user11 = createUserInfoForId(11);
        UserInfo user12 = createUserInfoForId(12);

        setDefaultBootUserOverride(overrideUserId);
        setLastActiveUser(lastActiveUserId);
        mockGetUsers(mSystemUser, user10, user11, user12);

        assertThat(mCarUserManagerHelper.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(overrideUserId);
    }

    @Test
    public void testGetInitialUser_WithInvalidOverrideId_ReturnsLastActiveUserId() {
        int lastActiveUserId = 12;
        int overrideUserId = 15;

        UserInfo user10 = createUserInfoForId(10);
        UserInfo user11 = createUserInfoForId(11);
        UserInfo user12 = createUserInfoForId(12);

        setDefaultBootUserOverride(overrideUserId);
        setLastActiveUser(lastActiveUserId);
        mockGetUsers(mSystemUser, user10, user11, user12);

        assertThat(mCarUserManagerHelper.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(lastActiveUserId);
    }

    @Test
    public void testGetInitialUser_WithInvalidOverrideAndLastActiveUserIds_ReturnsSmallestUserId() {
        int minimumUserId = 10;
        int invalidLastActiveUserId = 14;
        int invalidOverrideUserId = 15;

        UserInfo minimumUser = createUserInfoForId(minimumUserId);
        UserInfo user11 = createUserInfoForId(minimumUserId + 1);
        UserInfo user12 = createUserInfoForId(minimumUserId + 2);

        setDefaultBootUserOverride(invalidOverrideUserId);
        setLastActiveUser(invalidLastActiveUserId);
        mockGetUsers(mSystemUser, minimumUser, user11, user12);

        assertThat(mCarUserManagerHelper.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(minimumUserId);
    }

    @Test
    public void testGetInitialUser_WhenOverrideIdIsIgnored() {
        int lastActiveUserId = 12;
        int overrideUserId = 11;

        UserInfo user10 = createUserInfoForId(10);
        UserInfo user11 = createUserInfoForId(11);
        UserInfo user12 = createUserInfoForId(12);

        setDefaultBootUserOverride(overrideUserId);
        setLastActiveUser(lastActiveUserId);
        mockGetUsers(mSystemUser, user10, user11, user12);

        assertThat(mCarUserManagerHelper.getInitialUser(/* usesOverrideUserIdProperty= */ false))
                .isEqualTo(lastActiveUserId);
    }

    @Test
    public void testGetInitialUser_WithEmptyReturnNull() {
        assertThat(mCarUserManagerHelper.getInitialUser(/* usesOverrideUserIdProperty= */ true))
                .isEqualTo(UserHandle.USER_NULL);
    }

    @Test
    public void testHasInitialUser_onlyHeadlessSystemUser() {
        setHeadlessSystemUserMode(true);
        mockGetUsers(mSystemUser);

        assertThat(mCarUserManagerHelper.hasInitialUser()).isFalse();
    }

    @Test
    public void testHasInitialUser_onlyNonHeadlessSystemUser() {
        setHeadlessSystemUserMode(false);
        mockGetUsers(mSystemUser);

        assertThat(mCarUserManagerHelper.hasInitialUser()).isTrue();
    }

    @Test
    public void testHasInitialUser_hasNormalUser() {
        setHeadlessSystemUserMode(true);
        UserInfo normalUser = createUserInfoForId(10);
        mockGetUsers(mSystemUser, normalUser);

        assertThat(mCarUserManagerHelper.hasInitialUser()).isTrue();
    }

    @Test
    public void testHasInitialUser_hasOnlyWorkProfile() {
        setHeadlessSystemUserMode(true);
        UserInfo workProfile = createUserInfoForId(10);
        workProfile.userType = UserManager.USER_TYPE_PROFILE_MANAGED;
        assertThat(workProfile.isManagedProfile()).isTrue(); // Sanity check
        mockGetUsers(mSystemUser, workProfile);

        assertThat(mCarUserManagerHelper.hasInitialUser()).isFalse();
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
        doReturn(userId).when(() -> Settings.Global.getInt(mContentResolver,
                Settings.Global.LAST_ACTIVE_USER_ID, UserHandle.USER_SYSTEM));
    }

    private void setDefaultBootUserOverride(int userId) {
        doReturn(Optional.of(userId)).when(() -> CarProperties.boot_user_override_id());
    }
}
