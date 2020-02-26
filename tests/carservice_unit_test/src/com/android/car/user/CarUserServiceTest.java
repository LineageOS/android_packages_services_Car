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
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.car.CarOccupantZoneManager.OccupantTypeEnum;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.settings.CarSettings;
import android.car.userlib.CarUserManagerHelper;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponse;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponseAction;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.hardware.automotive.vehicle.V2_0.UsersInfo;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;

import com.android.car.hal.UserHalService;
import com.android.car.hal.UserHalService.HalCallback;
import com.android.internal.R;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.Preconditions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
@RunWith(MockitoJUnitRunner.class)
public class CarUserServiceTest {

    private static final String TAG = CarUserServiceTest.class.getSimpleName();
    private static final int NO_USER_INFO_FLAGS = 0;

    @Mock private Context mMockContext;
    @Mock private Context mApplicationContext;
    @Mock private LocationManager mLocationManager;
    @Mock private UserHalService mUserHal;
    @Mock private CarUserManagerHelper mMockedCarUserManagerHelper;
    @Mock private IActivityManager mMockedIActivityManager;
    @Mock private UserManager mMockedUserManager;
    @Mock private Resources mMockedResources;
    @Mock private Drawable mMockedDrawable;

    private MockitoSession mSession;
    private CarUserService mCarUserService;
    private boolean mUser0TaskExecuted;
    private FakeCarOccupantZoneService mFakeCarOccupantZoneService;

    private final int mGetUserInfoRequestType = InitialUserInfoRequestType.COLD_BOOT;
    private final int mAsyncCallTimeoutMs = 100;
    private final BlockingResultReceiver mReceiver =
            new BlockingResultReceiver(mAsyncCallTimeoutMs);
    private final InitialUserInfoResponse mGetUserInfoResponse = new InitialUserInfoResponse();

    private final @NonNull UserInfo mSystemUser = UserInfoBuilder.newSystemUserInfo();
    private final @NonNull UserInfo mAdminUser = new UserInfoBuilder(10)
            .setAdmin(true)
            .build();
    private final @NonNull UserInfo mGuestUser = new UserInfoBuilder(11)
            .setGuest(true)
            .setEphemeral(true)
            .build();
    private final List<UserInfo> mExistingUsers = Arrays.asList(mSystemUser, mAdminUser,
            mGuestUser);

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUpMocks() {
        mSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(ActivityManager.class)
                .mockStatic(Settings.Global.class)
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
                        mUserHal,
                        mMockedCarUserManagerHelper,
                        mMockedUserManager,
                        mMockedIActivityManager,
                        3);

        mFakeCarOccupantZoneService = new FakeCarOccupantZoneService(mCarUserService);
        // Restore default value at the beginning of each test.
        mockSettingsGlobal();
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
                eq(UserManager.USER_TYPE_PROFILE_MANAGED), eq(0), eq(driverId));
        UserInfo driverInfo = new UserInfo(driverId, "driver", NO_USER_INFO_FLAGS);
        doReturn(driverInfo).when(mMockedUserManager).getUserInfo(driverId);
        assertEquals(userInfo, mCarUserService.createPassenger(userName, driverId));
    }

    @Test
    public void testCreatePassenger_IfMaximumProfileAlreadyCreated() {
        int driverId = 90;
        String userName = "testUser";
        doReturn(null).when(mMockedUserManager).createProfileForUser(eq(userName),
                eq(UserManager.USER_TYPE_PROFILE_MANAGED), anyInt(), anyInt());
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

    @Test
    public void testStartPassenger() throws RemoteException {
        int passenger1Id = 91;
        int passenger2Id = 92;
        int passenger3Id = 93;
        int zone1Id = 1;
        int zone2Id = 2;
        doReturn(true).when(mMockedIActivityManager)
                .startUserInBackgroundWithListener(anyInt(), eq(null));
        assertTrue(mCarUserService.startPassenger(passenger1Id, zone1Id));
        assertTrue(mCarUserService.startPassenger(passenger2Id, zone2Id));
        assertFalse(mCarUserService.startPassenger(passenger3Id, zone2Id));
    }

    @Test
    public void testStopPassenger() throws RemoteException {
        int user1Id = 11;
        int passenger1Id = 91;
        int passenger2Id = 92;
        int zoneId = 1;
        UserInfo user1Info = new UserInfo(user1Id, "user1", NO_USER_INFO_FLAGS);
        UserInfo passenger1Info =
                new UserInfo(passenger1Id, "passenger1", UserInfo.FLAG_MANAGED_PROFILE);
        associateParentChild(user1Info, passenger1Info);
        doReturn(passenger1Info).when(mMockedUserManager).getUserInfo(passenger1Id);
        doReturn(null).when(mMockedUserManager).getUserInfo(passenger2Id);
        when(ActivityManager.getCurrentUser()).thenReturn(user1Id);
        doReturn(true).when(mMockedIActivityManager)
                .startUserInBackgroundWithListener(anyInt(), eq(null));
        assertTrue(mCarUserService.startPassenger(passenger1Id, zoneId));
        assertTrue(mCarUserService.stopPassenger(passenger1Id));
        // Test of stopping an already stopped passenger.
        assertTrue(mCarUserService.stopPassenger(passenger1Id));
        // Test of stopping a non-existing passenger.
        assertFalse(mCarUserService.stopPassenger(passenger2Id));
    }

    private static void associateParentChild(UserInfo parent, UserInfo child) {
        parent.profileGroupId = parent.id;
        child.profileGroupId = parent.id;
    }

    private static List<UserInfo> prepareUserList() {
        List<UserInfo> users = new ArrayList<>(Arrays.asList(
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
        associateParentChild(users.get(0), users.get(2));
        // Parent: test13, child: test17
        associateParentChild(users.get(3), users.get(7));
        // Parent: test13, child: test18
        associateParentChild(users.get(3), users.get(8));
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

    @Test
    public void testGetUserInfo_nullReceiver() throws Exception {
        assertThrows(NullPointerException.class, () -> mCarUserService
                .getInitialUserInfo(mGetUserInfoRequestType, mAsyncCallTimeoutMs, null));
    }

    @Test
    public void testGetUserInfo_defaultResponse() throws Exception {
        mockCurrentUsers(mAdminUser);

        mGetUserInfoResponse.action = InitialUserInfoResponseAction.DEFAULT;
        mockGetInitialInfo(mAdminUser.id, mGetUserInfoResponse);

        mCarUserService.getInitialUserInfo(mGetUserInfoRequestType, mAsyncCallTimeoutMs, mReceiver);

        assertThat(mReceiver.getResultCode()).isEqualTo(HalCallback.STATUS_OK);
        assertThat(mReceiver.getResultData()).isNull();
    }

    @Test
    public void testGetUserInfo_switchUserResponse() throws Exception {
        int switchUserId = mGuestUser.id;
        mockCurrentUsers(mAdminUser);

        mGetUserInfoResponse.action = InitialUserInfoResponseAction.SWITCH;
        mGetUserInfoResponse.userToSwitchOrCreate.userId = switchUserId;
        mockGetInitialInfo(mAdminUser.id, mGetUserInfoResponse);

        mCarUserService.getInitialUserInfo(mGetUserInfoRequestType, mAsyncCallTimeoutMs, mReceiver);

        assertThat(mReceiver.getResultCode()).isEqualTo(HalCallback.STATUS_OK);
        Bundle resultData = mReceiver.getResultData();
        assertThat(resultData).isNotNull();
        assertInitialInfoAction(resultData, mGetUserInfoResponse.action);
        assertUserId(resultData, switchUserId);
        assertNoUserFlags(resultData);
        assertNoUserName(resultData);
    }


    @Test
    public void testGetUserInfo_createUserResponse() throws Exception {
        int newUserFlags = 42;
        String newUserName = "TheDude";

        mockCurrentUsers(mAdminUser);

        mGetUserInfoResponse.action = InitialUserInfoResponseAction.CREATE;
        mGetUserInfoResponse.userToSwitchOrCreate.flags = newUserFlags;
        mGetUserInfoResponse.userNameToCreate = newUserName;
        mockGetInitialInfo(mAdminUser.id, mGetUserInfoResponse);

        mCarUserService.getInitialUserInfo(mGetUserInfoRequestType, mAsyncCallTimeoutMs, mReceiver);

        assertThat(mReceiver.getResultCode()).isEqualTo(HalCallback.STATUS_OK);
        Bundle resultData = mReceiver.getResultData();
        assertThat(resultData).isNotNull();
        assertInitialInfoAction(resultData, mGetUserInfoResponse.action);
        assertNoUserId(resultData);
        assertUserFlags(resultData, newUserFlags);
        assertUserName(resultData, newUserName);
    }

    /**
     * Mock calls that generate a {@code UsersInfo}.
     */
    private void mockCurrentUsers(@NonNull UserInfo user) throws Exception {
        when(mMockedIActivityManager.getCurrentUser()).thenReturn(user);
        when(mMockedUserManager.getUsers()).thenReturn(mExistingUsers);
    }

    private void mockGetInitialInfo(@UserIdInt int currentUserId,
            @NonNull InitialUserInfoResponse response) {
        UsersInfo usersInfo = newUsersInfo(currentUserId);
        doAnswer((invocation) -> {
            Log.d(TAG, "Answering " + invocation + " with " + response);
            @SuppressWarnings("unchecked")
            HalCallback<InitialUserInfoResponse> callback =
                    (HalCallback<InitialUserInfoResponse>) invocation.getArguments()[3];
            callback.onResponse(HalCallback.STATUS_OK, response);
            return null;
        }).when(mUserHal).getInitialUserInfo(eq(mGetUserInfoRequestType), eq(mAsyncCallTimeoutMs),
                eq(usersInfo), notNull());
    }

    @NonNull
    private UsersInfo newUsersInfo(@UserIdInt int currentUserId) {
        UsersInfo infos = new UsersInfo();
        infos.numberUsers = mExistingUsers.size();
        boolean foundCurrentUser = false;
        for (UserInfo info : mExistingUsers) {
            android.hardware.automotive.vehicle.V2_0.UserInfo existingUser =
                    new android.hardware.automotive.vehicle.V2_0.UserInfo();
            int flags = UserFlags.NONE;
            if (info.id == UserHandle.USER_SYSTEM) {
                flags |= UserFlags.SYSTEM;
            }
            if (info.isAdmin()) {
                flags |= UserFlags.ADMIN;
            }
            if (info.isGuest()) {
                flags |= UserFlags.GUEST;
            }
            if (info.isEphemeral()) {
                flags |= UserFlags.EPHEMERAL;
            }
            existingUser.userId = info.id;
            existingUser.flags = flags;
            if (info.id == currentUserId) {
                foundCurrentUser = true;
                infos.currentUser.userId = info.id;
                infos.currentUser.flags = flags;
            }
            infos.existingUsers.add(existingUser);
        }
        Preconditions.checkArgument(foundCurrentUser,
                "no user with id " + currentUserId + " on " + mExistingUsers);
        return infos;
    }

    private void assertUserId(@NonNull Bundle resultData, int expectedUserId) {
        int actualUserId = resultData.getInt(CarUserService.BUNDLE_USER_ID);
        assertWithMessage("wrong user id on bundle extra %s", CarUserService.BUNDLE_USER_ID)
                .that(actualUserId).isEqualTo(expectedUserId);
    }

    private void assertNoUserId(@NonNull Bundle resultData) {
        assertNoExtra(resultData, CarUserService.BUNDLE_USER_ID);
    }

    private void assertUserFlags(@NonNull Bundle resultData, int expectedUserFlags) {
        int actualUserFlags = resultData.getInt(CarUserService.BUNDLE_USER_FLAGS);
        assertWithMessage("wrong user flags on bundle extra %s", CarUserService.BUNDLE_USER_FLAGS)
                .that(actualUserFlags).isEqualTo(expectedUserFlags);
    }

    private void assertNoUserFlags(@NonNull Bundle resultData) {
        assertNoExtra(resultData, CarUserService.BUNDLE_USER_FLAGS);
    }

    private void assertUserName(@NonNull Bundle resultData, @NonNull String expectedName) {
        String actualName = resultData.getString(CarUserService.BUNDLE_USER_NAME);
        assertWithMessage("wrong user name on bundle extra %s",
                CarUserService.BUNDLE_USER_FLAGS).that(actualName).isEqualTo(expectedName);
    }

    private void assertNoUserName(@NonNull Bundle resultData) {
        assertNoExtra(resultData, CarUserService.BUNDLE_USER_NAME);
    }

    private void assertNoExtra(@NonNull Bundle resultData, @NonNull String extra) {
        Object value = resultData.get(extra);
        assertWithMessage("should not have extra %s", extra).that(value).isNull();
    }

    private void assertInitialInfoAction(@NonNull Bundle resultData, int expectedAction) {
        int actualAction = resultData.getInt(CarUserService.BUNDLE_INITIAL_INFO_ACTION);
        assertWithMessage("wrong request type on bundle extra %s",
                CarUserService.BUNDLE_INITIAL_INFO_ACTION).that(actualAction)
            .isEqualTo(expectedAction);
    }

    static final class FakeCarOccupantZoneService {
        private final SparseArray<Integer> mZoneUserMap = new SparseArray<Integer>();
        private final CarUserService.ZoneUserBindingHelper mZoneUserBindigHelper =
                new CarUserService.ZoneUserBindingHelper() {
                    @Override
                    @NonNull
                    public List<OccupantZoneInfo> getOccupantZones(
                            @OccupantTypeEnum int occupantType) {
                        return null;
                    }

                    @Override
                    public boolean assignUserToOccupantZone(@UserIdInt int userId, int zoneId) {
                        if (mZoneUserMap.get(zoneId) != null) {
                            return false;
                        }
                        mZoneUserMap.put(zoneId, userId);
                        return true;
                    }

                    @Override
                    public boolean unassignUserFromOccupantZone(@UserIdInt int userId) {
                        for (int index = 0; index < mZoneUserMap.size(); index++) {
                            if (mZoneUserMap.valueAt(index) == userId) {
                                mZoneUserMap.removeAt(index);
                                break;
                            }
                        }
                        return true;
                    }

                    @Override
                    public boolean isPassengerDisplayAvailable() {
                        return true;
                    }
                };

        FakeCarOccupantZoneService(CarUserService carUserService) {
            carUserService.setZoneUserBindingHelper(mZoneUserBindigHelper);
        }
    }


    // TODO(b/148403316): Refactor to use common fake settings provider
    private void mockSettingsGlobal() {
        when(Settings.Global.putInt(any(), eq(CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET),
                anyInt())).thenAnswer(invocation -> {
                            int value = (int) invocation.getArguments()[2];
                            when(Settings.Global.getInt(any(),
                                    eq(CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET), anyInt()))
                                    .thenReturn(value);
                            return null;
                        }
        );
    }

    private void putSettingsInt(String key, int value) {
        Settings.Global.putInt(InstrumentationRegistry.getTargetContext().getContentResolver(),
                key, value);
    }

    private int getSettingsInt(String key) {
        return Settings.Global.getInt(
                InstrumentationRegistry.getTargetContext().getContentResolver(),
                key, /* default= */ 0);
    }

    // TODO(b/149099817): move stuff below to common code

    /**
     * Builder for {@link UserInfo} objects.
     *
     */
    public static final class UserInfoBuilder {

        @UserIdInt
        private final int mUserId;

        @Nullable
        private String mName;

        private boolean mGuest;
        private boolean mEphemeral;
        private boolean mAdmin;

        /**
         * Default constructor.
         */
        public UserInfoBuilder(@UserIdInt int userId) {
            mUserId = userId;
        }

        /**
         * Sets the user name.
         */
        @NonNull
        public UserInfoBuilder setName(@Nullable String name) {
            mName = name;
            return this;
        }

        /**
         * Sets whether the user is a guest.
         */
        @NonNull
        public UserInfoBuilder setGuest(boolean guest) {
            mGuest = guest;
            return this;
        }

        /**
         * Sets whether the user is ephemeral.
         */
        @NonNull
        public UserInfoBuilder setEphemeral(boolean ephemeral) {
            mEphemeral = ephemeral;
            return this;
        }

        /**
         * Sets whether the user is an admin.
         */
        @NonNull
        public UserInfoBuilder setAdmin(boolean admin) {
            mAdmin = admin;
            return this;
        }

        /**
         * Creates a new {@link UserInfo}.
         */
        @NonNull
        public UserInfo build() {
            int flags = 0;
            if (mEphemeral) {
                flags |= UserInfo.FLAG_EPHEMERAL;
            }
            if (mAdmin) {
                flags |= UserInfo.FLAG_ADMIN;
            }
            UserInfo info = new UserInfo(mUserId, mName, flags);
            if (mGuest) {
                info.userType = UserManager.USER_TYPE_FULL_GUEST;
            }
            return info;
        }

        /**
         * Creates a new {@link UserInfo} for a system user.
         */
        @NonNull
        public static UserInfo newSystemUserInfo() {
            UserInfo info = new UserInfo();
            info.id = UserHandle.USER_SYSTEM;
            return info;
        }
    }

    /**
     * Implementation of {@link IResultReceiver} that blocks waiting for the result.
     */
    public static final class BlockingResultReceiver extends IResultReceiver.Stub {

        private final CountDownLatch mLatch = new CountDownLatch(1);
        private final long mTimeoutMs;

        private int mResultCode;
        @Nullable private Bundle mResultData;

        /**
         * Default constructor.
         *
         * @param timeoutMs how long to wait for before failing.
         */
        public BlockingResultReceiver(long timeoutMs) {
            mTimeoutMs = timeoutMs;
        }

        @Override
        public void send(int resultCode, Bundle resultData) {
            Log.d(TAG, "send() received: code=" + resultCode + ", data=" + resultData + ", count="
                    + mLatch.getCount());
            Preconditions.checkState(mLatch.getCount() == 1,
                    "send() already called (code=" + mResultCode + ", data=" + mResultData);
            mResultCode = resultCode;
            mResultData = resultData;
            mLatch.countDown();
        }

        private void assertCalled() throws InterruptedException {
            boolean called = mLatch.await(mTimeoutMs, TimeUnit.MILLISECONDS);
            Log.d(TAG, "assertCalled(): " + called);
            assertWithMessage("receiver not called in %sms", mTimeoutMs).that(called).isTrue();
        }

        /**
         * Gets the {@code resultCode} or fails if it times out before {@code send()} is called.
         */
        public int getResultCode() throws InterruptedException {
            assertCalled();
            return mResultCode;
        }

        /**
         * Gets the {@code resultData} or fails if it times out before {@code send()} is called.
         */
        @Nullable
        public Bundle getResultData() throws InterruptedException {
            assertCalled();
            return mResultData;
        }
    }
}
