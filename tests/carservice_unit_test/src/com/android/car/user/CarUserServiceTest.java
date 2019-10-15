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

package com.android.car.user;

import static android.content.pm.UserInfo.FLAG_EPHEMERAL;
import static android.content.pm.UserInfo.FLAG_GUEST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.car.settings.CarSettings;
import android.car.userlib.CarUserManagerHelper;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This class contains unit tests for the {@link CarUserService}.
 *
 * The following mocks are used:
 * <ol>
 * <li> {@link Context} provides system services and resources.
 * <li> {@link IActivityManager} provides current user.
 * <li> {@link UserManager} provides user creation and user info.
 * <li> {@link Resources} provides user icon.
 * <li> {@link Drawable} provides bitmap of user icon.
 * <ol/>
 */
@RunWith(AndroidJUnit4.class)
public class CarUserServiceTest {
    private static final int NO_USER_INFO_FLAGS = 0;

    @Mock private Context mMockContext;
    @Mock private Context mApplicationContext;
    @Mock private LocationManager mLocationManager;
    @Mock private CarUserManagerHelper mMockedCarUserManagerHelper;
    @Mock private IActivityManager mMockedIActivityManager;
    @Mock private UserManager mMockedUserManager;
    @Mock private Resources mMockedResources;
    @Mock private Drawable mMockedDrawable;

    private MockitoSession mSession;
    private CarUserService mCarUserService;
    private boolean mUser0TaskExecuted;

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUpMocks() {
        mSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(ActivityManager.class)
                .startMocking();

        doReturn(mApplicationContext).when(mMockContext).getApplicationContext();
        doReturn(mLocationManager).when(mMockContext).getSystemService(Context.LOCATION_SERVICE);
        doReturn(InstrumentationRegistry.getTargetContext().getContentResolver())
                .when(mMockContext).getContentResolver();
        doReturn(false).when(mMockedUserManager).isUserUnlockingOrUnlocked(anyInt());
        doReturn(mMockedResources).when(mMockContext).getResources();
        doReturn(mMockedDrawable).when(mMockedResources)
                .getDrawable(eq(R.drawable.ic_account_circle), eq(null));
        doReturn(mMockedDrawable).when(mMockedDrawable).mutate();
        doReturn(1).when(mMockedDrawable).getIntrinsicWidth();
        doReturn(1).when(mMockedDrawable).getIntrinsicHeight();
        mCarUserService =
                new CarUserService(
                        mMockContext,
                        mMockedCarUserManagerHelper,
                        mMockedUserManager,
                        mMockedIActivityManager,
                        3);

        // Restore default value at the beginning of each test.
        putSettingsInt(CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, 0);
    }

    /**
     *  Clean up before running the next test
     */
    @After
    public void tearDown() {
        mSession.finishMocking();
    }

    /**
     * Test that the {@link CarUserService} does set the disable modify account permission for
     * user 0 upon user 0 unlock when user 0 is headless.
     */
    @Test
    public void testDisableModifyAccountsForHeadlessSystemUserOnFirstRun() {
        mCarUserService.setUserLockStatus(UserHandle.USER_SYSTEM, true);
        verify(mMockedUserManager)
                .setUserRestriction(
                        UserManager.DISALLOW_MODIFY_ACCOUNTS,
                        true,
                        UserHandle.of(UserHandle.USER_SYSTEM));
    }

    /**
     * Test that the {@link CarUserService} does not set restrictions on user 0 if they have already
     * been set.
     */
    @Test
    public void testDoesNotSetSystemUserRestrictions_IfRestrictionsAlreadySet() {
        putSettingsInt(CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, 1);
        mCarUserService.setUserLockStatus(UserHandle.USER_SYSTEM, true);
        verify(mMockedUserManager, never())
                .setUserRestriction(
                        UserManager.DISALLOW_MODIFY_ACCOUNTS,
                        true,
                        UserHandle.of(UserHandle.USER_SYSTEM));
    }

    /**
     * Test that the {@link CarUserService} disables the location service for headless user 0 upon
     * first run.
     */
    @Test
    public void testDisableLocationForHeadlessSystemUserOnFirstRun() {
        mCarUserService.setUserLockStatus(UserHandle.USER_SYSTEM, true);
        verify(mLocationManager).setLocationEnabledForUser(
                /* enabled= */ false, UserHandle.of(UserHandle.USER_SYSTEM));
    }

    /**
     * Test that the {@link CarUserService} updates last active user on user switch.
     */
    @Test
    public void testLastActiveUserUpdatedOnUserSwitch() {
        int lastActiveUserId = 99;
        UserInfo persistentUser = new UserInfo(lastActiveUserId, "persistent user",
                NO_USER_INFO_FLAGS);
        doReturn(persistentUser).when(mMockedUserManager).getUserInfo(lastActiveUserId);

        mCarUserService.onSwitchUser(lastActiveUserId);

        verify(mMockedCarUserManagerHelper).setLastActiveUser(lastActiveUserId);
    }

    /**
     * Test that the {@link CarUserService} sets default guest restrictions on first boot.
     */
    @Test
    public void testInitializeGuestRestrictions_IfNotAlreadySet() {
        mCarUserService.setUserLockStatus(UserHandle.USER_SYSTEM, true);
        assertThat(getSettingsInt(CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET)).isEqualTo(1);
    }

    /**
     * Test that the {@link CarUserService} does not set restrictions after they have been set once.
     */
    @Test
    public void test_DoesNotInitializeGuestRestrictions_IfAlreadySet() {
        putSettingsInt(CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, 1);
        mCarUserService.setUserLockStatus(UserHandle.USER_SYSTEM, true);
        verify(mMockedUserManager, never()).setDefaultGuestRestrictions(any(Bundle.class));
    }

    @Test
    public void testRunOnUser0UnlockImmediate() {
        mUser0TaskExecuted = false;
        mCarUserService.setUserLockStatus(UserHandle.USER_SYSTEM, true);
        mCarUserService.runOnUser0Unlock(() -> {
            mUser0TaskExecuted = true;
        });
        assertTrue(mUser0TaskExecuted);
    }

    @Test
    public void testRunOnUser0UnlockLater() {
        mUser0TaskExecuted = false;
        mCarUserService.runOnUser0Unlock(() -> {
            mUser0TaskExecuted = true;
        });
        assertFalse(mUser0TaskExecuted);
        mCarUserService.setUserLockStatus(UserHandle.USER_SYSTEM, true);
        assertTrue(mUser0TaskExecuted);
    }

    /**
     * Test is lengthy as it is testing LRU logic.
     */
    @Test
    public void testBackgroundUserList() throws RemoteException {
        int user1 = 101;
        int user2 = 102;
        int user3 = 103;
        int user4Guest = 104;
        int user5 = 105;

        UserInfo user1Info = new UserInfo(user1, "user1", NO_USER_INFO_FLAGS);
        UserInfo user2Info = new UserInfo(user2, "user2", NO_USER_INFO_FLAGS);
        UserInfo user3Info = new UserInfo(user3, "user3", NO_USER_INFO_FLAGS);
        UserInfo user4GuestInfo = new UserInfo(
                user4Guest, "user4Guest", FLAG_EPHEMERAL | FLAG_GUEST);
        UserInfo user5Info = new UserInfo(user5, "user5", NO_USER_INFO_FLAGS);

        doReturn(user1Info).when(mMockedUserManager).getUserInfo(user1);
        doReturn(user2Info).when(mMockedUserManager).getUserInfo(user2);
        doReturn(user3Info).when(mMockedUserManager).getUserInfo(user3);
        doReturn(user4GuestInfo).when(mMockedUserManager).getUserInfo(user4Guest);
        doReturn(user5Info).when(mMockedUserManager).getUserInfo(user5);

        when(ActivityManager.getCurrentUser()).thenReturn(user1);
        mCarUserService.setUserLockStatus(UserHandle.USER_SYSTEM, true);
        // user 0 should never go to that list.
        assertTrue(mCarUserService.getBackgroundUsersToRestart().isEmpty());

        mCarUserService.setUserLockStatus(user1, true);
        assertEquals(new Integer[]{user1},
                mCarUserService.getBackgroundUsersToRestart().toArray());

        // user 2 background, ignore in restart list
        mCarUserService.setUserLockStatus(user2, true);
        mCarUserService.setUserLockStatus(user1, false);
        assertEquals(new Integer[]{user1},
                mCarUserService.getBackgroundUsersToRestart().toArray());

        when(ActivityManager.getCurrentUser()).thenReturn(user3);
        mCarUserService.setUserLockStatus(user3, true);
        mCarUserService.setUserLockStatus(user2, false);
        assertEquals(new Integer[]{user3, user1},
                mCarUserService.getBackgroundUsersToRestart().toArray());

        when(ActivityManager.getCurrentUser()).thenReturn(user4Guest);
        mCarUserService.setUserLockStatus(user4Guest, true);
        mCarUserService.setUserLockStatus(user3, false);
        assertEquals(new Integer[]{user3, user1},
                mCarUserService.getBackgroundUsersToRestart().toArray());

        when(ActivityManager.getCurrentUser()).thenReturn(user5);
        mCarUserService.setUserLockStatus(user5, true);
        mCarUserService.setUserLockStatus(user4Guest, false);
        assertEquals(new Integer[]{user5, user3},
                mCarUserService.getBackgroundUsersToRestart().toArray());
    }

    /**
     * Test is lengthy as it is testing LRU logic.
     */
    @Test
    public void testBackgroundUsersStartStopKeepBackgroundUserList() throws Exception {
        int user1 = 101;
        int user2 = 102;
        int user3 = 103;

        UserInfo user1Info = new UserInfo(user1, "user1", NO_USER_INFO_FLAGS);
        UserInfo user2Info = new UserInfo(user2, "user2", NO_USER_INFO_FLAGS);
        UserInfo user3Info = new UserInfo(user3, "user3", NO_USER_INFO_FLAGS);

        doReturn(user1Info).when(mMockedUserManager).getUserInfo(user1);
        doReturn(user2Info).when(mMockedUserManager).getUserInfo(user2);
        doReturn(user3Info).when(mMockedUserManager).getUserInfo(user3);

        when(ActivityManager.getCurrentUser()).thenReturn(user1);
        mCarUserService.setUserLockStatus(UserHandle.USER_SYSTEM, true);
        mCarUserService.setUserLockStatus(user1, true);
        when(ActivityManager.getCurrentUser()).thenReturn(user2);
        mCarUserService.setUserLockStatus(user2, true);
        mCarUserService.setUserLockStatus(user1, false);
        when(ActivityManager.getCurrentUser()).thenReturn(user3);
        mCarUserService.setUserLockStatus(user3, true);
        mCarUserService.setUserLockStatus(user2, false);

        assertEquals(new Integer[]{user3, user2},
                mCarUserService.getBackgroundUsersToRestart().toArray());

        doReturn(true).when(mMockedIActivityManager).startUserInBackground(user2);
        doReturn(true).when(mMockedIActivityManager).unlockUser(user2,
                null, null, null);
        assertEquals(new Integer[]{user2},
                mCarUserService.startAllBackgroundUsers().toArray());
        mCarUserService.setUserLockStatus(user2, true);
        assertEquals(new Integer[]{user3, user2},
                mCarUserService.getBackgroundUsersToRestart().toArray());

        doReturn(ActivityManager.USER_OP_SUCCESS).when(mMockedIActivityManager).stopUser(user2,
                true, null);
        // should not stop the current fg user
        assertFalse(mCarUserService.stopBackgroundUser(user3));
        assertTrue(mCarUserService.stopBackgroundUser(user2));
        assertEquals(new Integer[]{user3, user2},
                mCarUserService.getBackgroundUsersToRestart().toArray());
        mCarUserService.setUserLockStatus(user2, false);
        assertEquals(new Integer[]{user3, user2},
                mCarUserService.getBackgroundUsersToRestart().toArray());
    }

    @Test
    public void testStopBackgroundUserForSystemUser() {
        assertFalse(mCarUserService.stopBackgroundUser(UserHandle.USER_SYSTEM));
    }

    @Test
    public void testStopBackgroundUserForFgUser() throws RemoteException {
        int user1 = 101;
        when(ActivityManager.getCurrentUser()).thenReturn(user1);
        assertFalse(mCarUserService.stopBackgroundUser(UserHandle.USER_SYSTEM));
    }

    @Test
    public void testCreateAdminDriver_IfCurrentUserIsAdminUser() {
        doReturn(true).when(mMockedUserManager).isSystemUser();
        String userName = "testUser";
        UserInfo userInfo = new UserInfo();
        doReturn(userInfo).when(mMockedUserManager).createUser(userName, UserInfo.FLAG_ADMIN);
        assertEquals(userInfo, mCarUserService.createDriver(userName, true));
    }

    @Test
    public void testCreateAdminDriver_IfCurrentUserIsNotSystemUser() {
        doReturn(false).when(mMockedUserManager).isSystemUser();
        assertEquals(null, mCarUserService.createDriver("testUser", true));
    }

    @Test
    public void testCreateNonAdminDriver() {
        String userName = "testUser";
        UserInfo userInfo = new UserInfo();
        doReturn(userInfo).when(mMockedCarUserManagerHelper).createNewNonAdminUser(userName);
        assertEquals(userInfo, mCarUserService.createDriver(userName, false));
    }

    @Test
    public void testCreateNonAdminDriver_IfMaximumUserAlreadyCreated() {
        String userName = "testUser";
        doReturn(null).when(mMockedUserManager).createUser(userName, NO_USER_INFO_FLAGS);
        assertEquals(null, mCarUserService.createDriver(userName, false));
    }

    @Test
    public void testCreatePassenger() {
        int driverId = 90;
        int passengerId = 99;
        String userName = "testUser";
        UserInfo userInfo = new UserInfo(passengerId, userName, NO_USER_INFO_FLAGS);
        doReturn(userInfo).when(mMockedUserManager).createProfileForUser(eq(userName),
                eq(UserInfo.FLAG_MANAGED_PROFILE), eq(driverId));
        UserInfo driverInfo = new UserInfo(driverId, "driver", NO_USER_INFO_FLAGS);
        doReturn(driverInfo).when(mMockedUserManager).getUserInfo(driverId);
        assertEquals(userInfo, mCarUserService.createPassenger(userName, driverId));
    }

    @Test
    public void testCreatePassenger_IfMaximumProfileAlreadyCreated() {
        int driverId = 90;
        String userName = "testUser";
        doReturn(null).when(mMockedUserManager).createProfileForUser(eq(userName),
                eq(UserInfo.FLAG_MANAGED_PROFILE), anyInt());
        UserInfo driverInfo = new UserInfo(driverId, "driver", NO_USER_INFO_FLAGS);
        doReturn(driverInfo).when(mMockedUserManager).getUserInfo(driverId);
        assertEquals(null, mCarUserService.createPassenger(userName, driverId));
    }

    @Test
    public void testCreatePassenger_IfDriverIsGuest() {
        int driverId = 90;
        String userName = "testUser";
        UserInfo driverInfo = new UserInfo(driverId, "driver", UserInfo.FLAG_GUEST);
        doReturn(driverInfo).when(mMockedUserManager).getUserInfo(driverId);
        assertEquals(null, mCarUserService.createPassenger(userName, driverId));
    }

    @Test
    public void testSwitchDriver() throws RemoteException {
        int currentId = 11;
        int targetId = 12;
        when(ActivityManager.getCurrentUser()).thenReturn(currentId);
        doReturn(true).when(mMockedIActivityManager).switchUser(targetId);
        doReturn(false).when(mMockedUserManager)
                .hasUserRestriction(UserManager.DISALLOW_USER_SWITCH);
        assertTrue(mCarUserService.switchDriver(targetId));
    }

    @Test
    public void testSwitchDriver_IfUserSwitchIsNotAllowed() throws RemoteException {
        int currentId = 11;
        int targetId = 12;
        when(ActivityManager.getCurrentUser()).thenReturn(currentId);
        doReturn(true).when(mMockedIActivityManager).switchUser(targetId);
        doReturn(UserManager.SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED).when(mMockedUserManager)
                .getUserSwitchability();
        assertFalse(mCarUserService.switchDriver(targetId));
    }

    @Test
    public void testSwitchDriver_IfSwitchedToCurrentUser() throws RemoteException {
        int currentId = 11;
        when(ActivityManager.getCurrentUser()).thenReturn(currentId);
        doReturn(false).when(mMockedUserManager)
                .hasUserRestriction(UserManager.DISALLOW_USER_SWITCH);
        assertTrue(mCarUserService.switchDriver(11));
    }

    private static void associateParentChild(UserInfo parent, UserInfo child) {
        parent.profileGroupId = parent.id;
        child.profileGroupId = parent.id;
    }

    private static List<UserInfo> prepareUserList() {
        List<UserInfo> users = new ArrayList<>(Arrays.asList(
                new UserInfo(0, "test0", UserInfo.FLAG_SYSTEM),
                new UserInfo(10, "test10", UserInfo.FLAG_PRIMARY | UserInfo.FLAG_ADMIN),
                new UserInfo(11, "test11", NO_USER_INFO_FLAGS),
                new UserInfo(12, "test12", UserInfo.FLAG_MANAGED_PROFILE),
                new UserInfo(13, "test13", NO_USER_INFO_FLAGS),
                new UserInfo(14, "test14", UserInfo.FLAG_GUEST),
                new UserInfo(15, "test15", UserInfo.FLAG_EPHEMERAL),
                new UserInfo(16, "test16", UserInfo.FLAG_DISABLED),
                new UserInfo(17, "test17", UserInfo.FLAG_MANAGED_PROFILE),
                new UserInfo(18, "test18", UserInfo.FLAG_MANAGED_PROFILE)
        ));
        // Parent: test10, child: test12
        associateParentChild(users.get(1), users.get(3));
        // Parent: test13, child: test17
        associateParentChild(users.get(4), users.get(8));
        // Parent: test13, child: test18
        associateParentChild(users.get(4), users.get(9));
        return users;
    }

    @Test
    public void testGetAllPossibleDrivers() {
        Set<Integer> expected = new HashSet<Integer>(Arrays.asList(10, 11, 13, 14));
        doReturn(prepareUserList()).when(mMockedUserManager).getUsers(true);
        for (UserInfo user : mCarUserService.getAllDrivers()) {
            assertTrue(expected.contains(user.id));
            expected.remove(user.id);
        }
        assertEquals(0, expected.size());
    }

    @Test
    public void testGetAllPassengers() {
        SparseArray<HashSet<Integer>> testCases = new SparseArray<HashSet<Integer>>() {
            {
                put(0, new HashSet<Integer>());
                put(10, new HashSet<Integer>(Arrays.asList(12)));
                put(11, new HashSet<Integer>());
                put(13, new HashSet<Integer>(Arrays.asList(17, 18)));
            }
        };
        for (int i = 0; i < testCases.size(); i++) {
            doReturn(prepareUserList()).when(mMockedUserManager).getUsers(true);
            List<UserInfo> passengers = mCarUserService.getPassengers(testCases.keyAt(i));
            HashSet<Integer> expected = testCases.valueAt(i);
            for (UserInfo user : passengers) {
                assertTrue(expected.contains(user.id));
                expected.remove(user.id);
            }
            assertEquals(0, expected.size());
        }
    }

    // TODO(b/139190199): add tests for startPassenger() and stopPassenger().

    private void putSettingsInt(String key, int value) {
        Settings.Global.putInt(InstrumentationRegistry.getTargetContext().getContentResolver(),
                key, value);
    }

    private int getSettingsInt(String key) {
        return Settings.Global.getInt(
                InstrumentationRegistry.getTargetContext().getContentResolver(),
                key, /* default= */ 0);
    }
}
