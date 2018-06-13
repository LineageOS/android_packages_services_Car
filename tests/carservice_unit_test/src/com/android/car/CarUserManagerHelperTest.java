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

package com.android.car;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.car.user.CarUserManagerHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This class contains unit tests for the {@link CarUserManagerHelper}.
 * It tests that {@link CarUserManagerHelper} does the right thing for user management flows.
 *
 * The following mocks are used:
 * 1. {@link Context} provides system services and resources.
 * 2. {@link UserManager} provides dummy users and user info.
 * 3. {@link ActivityManager} to verify user switch is invoked.
 * 4. {@link CarUserManagerHelper.OnUsersUpdateListener} registers a listener for user updates.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class CarUserManagerHelperTest {
    @Mock
    private Context mContext;
    @Mock
    private UserManager mUserManager;
    @Mock
    private ActivityManager mActivityManager;
    @Mock
    private CarUserManagerHelper.OnUsersUpdateListener mTestListener;

    private CarUserManagerHelper mHelper;
    private UserInfo mCurrentProcessUser;
    private UserInfo mSystemUser;
    private String mGuestUserName = "testGuest";
    private String mTestUserName = "testUser";
    private int mForegroundUserId;
    private UserInfo mForegroundUser;

    @Before
    public void setUpMocksAndVariables() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mContext.getSystemService(Context.ACTIVITY_SERVICE)).thenReturn(mActivityManager);
        when(mContext.getResources())
            .thenReturn(InstrumentationRegistry.getTargetContext().getResources());
        when(mContext.getApplicationContext()).thenReturn(mContext);
        mHelper = new CarUserManagerHelper(mContext);

        mCurrentProcessUser = createUserInfoForId(UserHandle.myUserId());
        mSystemUser = createUserInfoForId(UserHandle.USER_SYSTEM);
        when(mUserManager.getUserInfo(UserHandle.myUserId())).thenReturn(mCurrentProcessUser);

        // Get the ID of the foreground user running this test. This should be non-root (non
        // user 0) secondary user.
        // We cannot mock the foreground user since getCurrentUser is static.
        mForegroundUserId = ActivityManager.getCurrentUser();
        mForegroundUser = createUserInfoForId(mForegroundUserId);

        // Restore the non-headless state before every test. Individual tests can set the property
        // to true to test the headless system user scenario.
        SystemProperties.set("android.car.systemuser.headless", "false");
    }

    @Test
    public void checkIsSystemUser() {
        UserInfo testInfo = new UserInfo();

        testInfo.id = UserHandle.USER_SYSTEM;
        assertThat(mHelper.isSystemUser(testInfo)).isTrue();

        testInfo.id = UserHandle.USER_SYSTEM + 2; // Make it different than system id.
        assertThat(mHelper.isSystemUser(testInfo)).isFalse();
    }

    // System user will not be returned when calling get all users.
    @Test
    public void testHeadlessUser0GetAllUsers_NotReturnSystemUser() {
        SystemProperties.set("android.car.systemuser.headless", "true");
        UserInfo otherUser1 = createUserInfoForId(mForegroundUserId + 1);
        UserInfo otherUser2 = createUserInfoForId(mForegroundUserId + 2);
        UserInfo otherUser3 = createUserInfoForId(mForegroundUserId + 3);

        List<UserInfo> testUsers = new ArrayList<>();
        testUsers.add(mSystemUser);
        testUsers.add(mForegroundUser);
        testUsers.add(otherUser1);
        testUsers.add(otherUser2);
        testUsers.add(otherUser3);

        when(mUserManager.getUsers(true)).thenReturn(testUsers);

        assertThat(mHelper.getAllUsers()).hasSize(4);
        assertThat(mHelper.getAllUsers())
            .containsExactly(mForegroundUser, otherUser1, otherUser2, otherUser3);
    }

    @Test
    public void testGetAllSwitchableUsers() {
        // Create two non-foreground users.
        UserInfo user1 = createUserInfoForId(mForegroundUserId + 1);
        UserInfo user2 = createUserInfoForId(mForegroundUserId + 2);

        List<UserInfo> testUsers = Arrays.asList(mForegroundUser, mSystemUser, user1, user2);

        when(mUserManager.getUsers(true)).thenReturn(new ArrayList<>(testUsers));

        // Should return all 4 users.
        assertThat(mHelper.getAllUsers()).hasSize(4);

        // Should return all non-foreground users.
        assertThat(mHelper.getAllSwitchableUsers()).hasSize(3);
        assertThat(mHelper.getAllSwitchableUsers()).containsExactly(mSystemUser, user1, user2);
    }

    // Get all users for headless user 0 model should exclude system user by default.
    @Test
    public void testHeadlessUser0GetAllSwitchableUsers() {
        SystemProperties.set("android.car.systemuser.headless", "true");

        // Create three non-foreground users.
        UserInfo user1 = createUserInfoForId(mForegroundUserId + 1);
        UserInfo user2 = createUserInfoForId(mForegroundUserId + 2);
        UserInfo user3 = createUserInfoForId(mForegroundUserId + 3);

        List<UserInfo> testUsers = Arrays.asList(mForegroundUser, mSystemUser, user1, user2, user3);

        when(mUserManager.getUsers(true)).thenReturn(new ArrayList<>(testUsers));

        // Should return all 4 non-system users.
        assertThat(mHelper.getAllUsers()).hasSize(4);

        // Should return all non-system, non-foreground users.
        assertThat(mHelper.getAllSwitchableUsers()).containsExactly(user1, user2, user3);
    }

    @Test
    public void testUserCanBeRemoved() {
        UserInfo testInfo = new UserInfo();

        // System user cannot be removed.
        testInfo.id = UserHandle.USER_SYSTEM;
        assertThat(mHelper.canUserBeRemoved(testInfo)).isFalse();

        testInfo.id = UserHandle.USER_SYSTEM + 2; // Make it different than system id.
        assertThat(mHelper.canUserBeRemoved(testInfo)).isTrue();
    }

    @Test
    public void testCurrentProcessCanAddUsers() {
        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_ADD_USER)).thenReturn(false);
        assertThat(mHelper.canCurrentProcessAddUsers()).isTrue();

        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_ADD_USER)).thenReturn(true);
        assertThat(mHelper.canCurrentProcessAddUsers()).isFalse();
    }

    @Test
    public void testCurrentProcessCanRemoveUsers() {
        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_REMOVE_USER)).thenReturn(false);
        assertThat(mHelper.canCurrentProcessRemoveUsers()).isTrue();

        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_REMOVE_USER)).thenReturn(true);
        assertThat(mHelper.canCurrentProcessRemoveUsers()).isFalse();
    }

    @Test
    public void testCurrentProcessCanSwitchUsers() {
        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_USER_SWITCH)).thenReturn(false);
        assertThat(mHelper.canCurrentProcessSwitchUsers()).isTrue();

        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_USER_SWITCH)).thenReturn(true);
        assertThat(mHelper.canCurrentProcessSwitchUsers()).isFalse();
    }

    @Test
    public void testCurrentGuestProcessCannotModifyAccounts() {
        assertThat(mHelper.canCurrentProcessModifyAccounts()).isTrue();

        when(mUserManager.isGuestUser()).thenReturn(true);
        assertThat(mHelper.canCurrentProcessModifyAccounts()).isFalse();
    }

    @Test
    public void testCurrentDemoProcessCannotModifyAccounts() {
        assertThat(mHelper.canCurrentProcessModifyAccounts()).isTrue();

        when(mUserManager.isDemoUser()).thenReturn(true);
        assertThat(mHelper.canCurrentProcessModifyAccounts()).isFalse();
    }

    @Test
    public void testCurrentDisallowModifyAccountsProcessIsEnforced() {
        assertThat(mHelper.canCurrentProcessModifyAccounts()).isTrue();

        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS))
            .thenReturn(true);
        assertThat(mHelper.canCurrentProcessModifyAccounts()).isFalse();
    }

    @Test
    public void testCreateNewAdminUser() {
        // Verify createUser on UserManager gets called.
        mHelper.createNewAdminUser(mTestUserName);
        verify(mUserManager).createUser(mTestUserName, UserInfo.FLAG_ADMIN);

        when(mUserManager.createUser(mTestUserName, UserInfo.FLAG_ADMIN)).thenReturn(null);
        assertThat(mHelper.createNewAdminUser(mTestUserName)).isNull();

        UserInfo newUser = new UserInfo();
        newUser.name = mTestUserName;
        when(mUserManager.createUser(mTestUserName, UserInfo.FLAG_ADMIN)).thenReturn(newUser);
        assertThat(mHelper.createNewAdminUser(mTestUserName)).isEqualTo(newUser);
    }

    @Test
    public void testCreateNewNonAdminUser() {
        // Verify createUser on UserManager gets called.
        mHelper.createNewNonAdminUser(mTestUserName);
        verify(mUserManager).createUser(mTestUserName, 0);

        when(mUserManager.createUser(mTestUserName, 0)).thenReturn(null);
        assertThat(mHelper.createNewNonAdminUser(mTestUserName)).isNull();

        UserInfo newUser = new UserInfo();
        newUser.name = mTestUserName;
        when(mUserManager.createUser(mTestUserName, 0)).thenReturn(newUser);
        assertThat(mHelper.createNewNonAdminUser(mTestUserName)).isEqualTo(newUser);
    }

    @Test
    public void testRemoveUser() {
        // Cannot remove system user.
        assertThat(mHelper.removeUser(mSystemUser, mGuestUserName)).isFalse();

        // Removing non-current, non-system user, simply calls removeUser.
        UserInfo userToRemove = createUserInfoForId(mCurrentProcessUser.id + 2);

        mHelper.removeUser(userToRemove, mGuestUserName);
        verify(mUserManager).removeUser(mCurrentProcessUser.id + 2);
    }

    @Test
    public void testSwitchToGuest() {
        mHelper.startNewGuestSession(mGuestUserName);
        verify(mUserManager).createGuest(mContext, mGuestUserName);

        UserInfo guestInfo = new UserInfo(21, mGuestUserName, UserInfo.FLAG_GUEST);
        when(mUserManager.createGuest(mContext, mGuestUserName)).thenReturn(guestInfo);
        mHelper.startNewGuestSession(mGuestUserName);
        verify(mActivityManager).switchUser(21);
    }

    @Test
    public void testGetUserIcon() {
        mHelper.getUserIcon(mCurrentProcessUser);
        verify(mUserManager).getUserIcon(mCurrentProcessUser.id);
    }

    @Test
    public void testScaleUserIcon() {
        Bitmap fakeIcon = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Drawable scaledIcon = mHelper.scaleUserIcon(fakeIcon, 300);
        assertThat(scaledIcon.getIntrinsicWidth()).isEqualTo(300);
        assertThat(scaledIcon.getIntrinsicHeight()).isEqualTo(300);
    }

    @Test
    public void testSetUserName() {
        UserInfo testInfo = createUserInfoForId(mCurrentProcessUser.id + 3);
        String newName = "New Test Name";
        mHelper.setUserName(testInfo, newName);
        verify(mUserManager).setUserName(mCurrentProcessUser.id + 3, newName);
    }

    @Test
    public void testIsCurrentProcessSystemUser() {
        when(mUserManager.isAdminUser()).thenReturn(true);
        assertThat(mHelper.isCurrentProcessAdminUser()).isTrue();

        when(mUserManager.isAdminUser()).thenReturn(false);
        assertThat(mHelper.isCurrentProcessAdminUser()).isFalse();
    }

    @Test
    public void testAssignAdminPrivileges() {
        int userId = 30;
        UserInfo testInfo = createUserInfoForId(userId);

        // Test that non-admins cannot assign admin privileges.
        when(mUserManager.isAdminUser()).thenReturn(false); // Current user non-admin.
        mHelper.assignAdminPrivileges(testInfo);
        verify(mUserManager, never()).setUserAdmin(userId);

        // Admins can assign admin privileges.
        when(mUserManager.isAdminUser()).thenReturn(true);
        mHelper.assignAdminPrivileges(testInfo);
        verify(mUserManager).setUserAdmin(userId);
    }

    @Test
    public void testSetUserRestriction() {
        int userId = 20;
        UserInfo testInfo = createUserInfoForId(userId);

        mHelper.setUserRestriction(testInfo, UserManager.DISALLOW_ADD_USER, /* enable= */ true);
        verify(mUserManager).setUserRestriction(
                UserManager.DISALLOW_ADD_USER, true, UserHandle.of(userId));

        mHelper.setUserRestriction(testInfo, UserManager.DISALLOW_REMOVE_USER, /* enable= */ false);
        verify(mUserManager).setUserRestriction(
                UserManager.DISALLOW_REMOVE_USER, false, UserHandle.of(userId));
    }

    @Test
    public void testDefaultNonAdminRestrictions() {
        String testUserName = "Test User";
        int testUserId = 20;
        boolean restrictionEnabled = true;
        UserInfo newNonAdmin = createUserInfoForId(testUserId);
        when(mUserManager.createUser(testUserName, /* flags= */ 0)).thenReturn(newNonAdmin);

        mHelper.createNewNonAdminUser(testUserName);

        verify(mUserManager).setUserRestriction(
                UserManager.DISALLOW_REMOVE_USER, restrictionEnabled, UserHandle.of(testUserId));
        verify(mUserManager).setUserRestriction(
                UserManager.DISALLOW_FACTORY_RESET, restrictionEnabled, UserHandle.of(testUserId));
    }

    @Test
    public void testAssigningAdminPrivilegesRemovesNonAdminRestrictions() {
        int testUserId = 30;
        boolean restrictionEnabled = false;
        UserInfo testInfo = createUserInfoForId(testUserId);
        when(mUserManager.isAdminUser()).thenReturn(true); // Only admins can assign privileges.

        mHelper.assignAdminPrivileges(testInfo);

        verify(mUserManager).setUserRestriction(
                UserManager.DISALLOW_REMOVE_USER, restrictionEnabled, UserHandle.of(testUserId));
        verify(mUserManager).setUserRestriction(
                UserManager.DISALLOW_FACTORY_RESET, restrictionEnabled, UserHandle.of(testUserId));
    }

    @Test
    public void testRegisterUserChangeReceiver() {
        mHelper.registerOnUsersUpdateListener(mTestListener);

        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        ArgumentCaptor<UserHandle> handleCaptor = ArgumentCaptor.forClass(UserHandle.class);
        ArgumentCaptor<IntentFilter> filterCaptor = ArgumentCaptor.forClass(IntentFilter.class);
        ArgumentCaptor<String> permissionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Handler> handlerCaptor = ArgumentCaptor.forClass(Handler.class);

        verify(mContext).registerReceiverAsUser(
                receiverCaptor.capture(),
                handleCaptor.capture(),
                filterCaptor.capture(),
                permissionCaptor.capture(),
                handlerCaptor.capture());

        // Verify we're listening to Intents from ALL users.
        assertThat(handleCaptor.getValue()).isEqualTo(UserHandle.ALL);

        // Verify the presence of each intent in the filter.
        // Verify the exact number of filters. Every time a new intent is added, this test should
        // get updated.
        assertThat(filterCaptor.getValue().countActions()).isEqualTo(6);
        assertThat(filterCaptor.getValue().hasAction(Intent.ACTION_USER_REMOVED)).isTrue();
        assertThat(filterCaptor.getValue().hasAction(Intent.ACTION_USER_ADDED)).isTrue();
        assertThat(filterCaptor.getValue().hasAction(Intent.ACTION_USER_INFO_CHANGED)).isTrue();
        assertThat(filterCaptor.getValue().hasAction(Intent.ACTION_USER_SWITCHED)).isTrue();
        assertThat(filterCaptor.getValue().hasAction(Intent.ACTION_USER_STOPPED)).isTrue();
        assertThat(filterCaptor.getValue().hasAction(Intent.ACTION_USER_UNLOCKED)).isTrue();

        // Verify that calling the receiver calls the listener.
        receiverCaptor.getValue().onReceive(mContext, new Intent());
        verify(mTestListener).onUsersUpdate();

        assertThat(permissionCaptor.getValue()).isNull();
        assertThat(handlerCaptor.getValue()).isNull();

        // Unregister the receiver.
        mHelper.unregisterOnUsersUpdateListener();
        verify(mContext).unregisterReceiver(receiverCaptor.getValue());
    }

    private UserInfo createUserInfoForId(int id) {
        UserInfo userInfo = new UserInfo();
        userInfo.id = id;
        return userInfo;
    }
}
