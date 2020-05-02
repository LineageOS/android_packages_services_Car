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

import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetUserInfo;
import static android.car.test.util.UserTestingHelper.UserInfoBuilder;
import static android.content.pm.UserInfo.FLAG_EPHEMERAL;
import static android.content.pm.UserInfo.FLAG_GUEST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.car.CarOccupantZoneManager.OccupantTypeEnum;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.settings.CarSettings;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.BlockingAnswer;
import android.car.test.util.BlockingResultReceiver;
import android.car.testapi.BlockingUserLifecycleListener;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserLifecycleEvent;
import android.car.user.CarUserManager.UserLifecycleEventType;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.car.user.GetUserIdentificationAssociationResponse;
import android.car.user.UserSwitchResult;
import android.car.userlib.CarUserManagerHelper;
import android.car.userlib.HalCallback;
import android.car.userlib.UserHalHelper;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponse;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponseAction;
import android.hardware.automotive.vehicle.V2_0.SwitchUserResponse;
import android.hardware.automotive.vehicle.V2_0.SwitchUserStatus;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociation;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationGetRequest;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationResponse;
import android.hardware.automotive.vehicle.V2_0.UsersInfo;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;

import com.android.car.hal.UserHalService;
import com.android.internal.R;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.Preconditions;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
public final class CarUserServiceTest extends AbstractExtendedMockitoTestCase {

    private static final String TAG = CarUserServiceTest.class.getSimpleName();
    private static final int NO_USER_INFO_FLAGS = 0;

    private static final int NON_EXISTING_USER = 55; // must not be on mExistingUsers

    @Mock private Context mMockContext;
    @Mock private Context mApplicationContext;
    @Mock private LocationManager mLocationManager;
    @Mock private UserHalService mUserHal;
    @Mock private CarUserManagerHelper mMockedCarUserManagerHelper;
    @Mock private IActivityManager mMockedIActivityManager;
    @Mock private UserManager mMockedUserManager;
    @Mock private Resources mMockedResources;
    @Mock private Drawable mMockedDrawable;
    @Mock private UserMetrics mUserMetrics;

    private final BlockingUserLifecycleListener mUserLifecycleListener =
            BlockingUserLifecycleListener.newDefaultListener();

    @Captor private ArgumentCaptor<UsersInfo> mUsersInfoCaptor;

    private CarUserService mCarUserService;
    private boolean mUser0TaskExecuted;
    private FakeCarOccupantZoneService mFakeCarOccupantZoneService;

    private final int mGetUserInfoRequestType = InitialUserInfoRequestType.COLD_BOOT;
    private final AndroidFuture<UserSwitchResult> mUserSwitchFuture = new AndroidFuture<>();
    private final int mAsyncCallTimeoutMs = 100;
    private final BlockingResultReceiver mReceiver =
            new BlockingResultReceiver(mAsyncCallTimeoutMs);
    private final InitialUserInfoResponse mGetUserInfoResponse = new InitialUserInfoResponse();
    private final SwitchUserResponse mSwitchUserResponse = new SwitchUserResponse();

    private final @NonNull UserInfo mAdminUser = new UserInfoBuilder(100)
            .setAdmin(true)
            .build();
    private final @NonNull UserInfo mGuestUser = new UserInfoBuilder(111)
            .setGuest(true)
            .setEphemeral(true)
            .build();
    private final @NonNull UserInfo mRegularUser = new UserInfoBuilder(222)
            .build();
    private final List<UserInfo> mExistingUsers =
            Arrays.asList(mAdminUser, mGuestUser, mRegularUser);

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder
            .spyStatic(ActivityManager.class)
            .mockStatic(Settings.Global.class);
    }

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUpMocks() {
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
                        3, mUserMetrics);

        mFakeCarOccupantZoneService = new FakeCarOccupantZoneService(mCarUserService);
        // Restore default value at the beginning of each test.
        mockSettingsGlobal();
        putSettingsInt(CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, 0);
    }

    /**
     * Test that the {@link CarUserService} does not set restrictions on user 0 if they have already
     * been set.
     */
    @Test
    public void testDoesNotSetSystemUserRestrictions_IfRestrictionsAlreadySet() {
        putSettingsInt(CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, 1);
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
        verify(mMockedUserManager, never())
                .setUserRestriction(
                        UserManager.DISALLOW_MODIFY_ACCOUNTS,
                        true,
                        UserHandle.of(UserHandle.USER_SYSTEM));
    }

    @Test
    public void testAddUserLifecycleListener_checkNullParameter() {
        assertThrows(NullPointerException.class,
                () -> mCarUserService.addUserLifecycleListener(null));
    }

    @Test
    public void testRemoveUserLifecycleListener_checkNullParameter() {
        assertThrows(NullPointerException.class,
                () -> mCarUserService.removeUserLifecycleListener(null));
    }

    @Test
    public void testOnUserLifecycleEvent_nofityListener() throws Exception {
        // Arrange
        mCarUserService.addUserLifecycleListener(mUserLifecycleListener);

        // Act
        int userId = 11;
        sendUserLifecycleEvent(userId, CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING);

        // Verify
        verifyListenerOnEventInvoked(userId, CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING);
    }

    @Test
    public void testOnUserLifecycleEvent_ensureAllListenersAreNotified() throws Exception {
        // Arrange: add two listeners, one to fail on onEvent
        // Adding the failure listener first.
        UserLifecycleListener failureListener = mock(UserLifecycleListener.class);
        doThrow(new RuntimeException("Failed onEvent invocation")).when(
                failureListener).onEvent(any(UserLifecycleEvent.class));
        mCarUserService.addUserLifecycleListener(failureListener);

        // Adding the non-failure listener later.
        mCarUserService.addUserLifecycleListener(mUserLifecycleListener);

        // Act
        int userId = 11;
        sendUserLifecycleEvent(userId, CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING);

        // Verify
        verifyListenerOnEventInvoked(userId, CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING);
    }

    private void verifyListenerOnEventInvoked(int expectedNewUserId, int expectedEventType)
            throws Exception {
        UserLifecycleEvent actualEvent = mUserLifecycleListener.waitForEvent();
        assertThat(actualEvent.getEventType()).isEqualTo(expectedEventType);
        assertThat(actualEvent.getUserId()).isEqualTo(expectedNewUserId);
    }

    /**
     * Test that the {@link CarUserService} disables the location service for headless user 0 upon
     * first run.
     */
    @Test
    public void testDisableLocationForHeadlessSystemUserOnFirstRun() {
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
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

        sendUserSwitchingEvent(lastActiveUserId);

        verify(mMockedCarUserManagerHelper).setLastActiveUser(lastActiveUserId);
    }

    /**
     * Test that the {@link CarUserService} sets default guest restrictions on first boot.
     */
    @Test
    public void testInitializeGuestRestrictions_IfNotAlreadySet() {
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
        assertThat(getSettingsInt(CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET)).isEqualTo(1);
    }

    /**
     * Test that the {@link CarUserService} does not set restrictions after they have been set once.
     */
    @Test
    public void test_DoesNotInitializeGuestRestrictions_IfAlreadySet() {
        putSettingsInt(CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, 1);
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
        verify(mMockedUserManager, never()).setDefaultGuestRestrictions(any(Bundle.class));
    }

    @Test
    public void testRunOnUser0UnlockImmediate() {
        mUser0TaskExecuted = false;
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
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
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
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

        mockGetCurrentUser(user1);
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
        // user 0 should never go to that list.
        assertTrue(mCarUserService.getBackgroundUsersToRestart().isEmpty());

        sendUserUnlockedEvent(user1);
        assertEquals(new Integer[]{user1},
                mCarUserService.getBackgroundUsersToRestart().toArray());

        // user 2 background, ignore in restart list
        sendUserUnlockedEvent(user2);
        assertEquals(new Integer[]{user1},
                mCarUserService.getBackgroundUsersToRestart().toArray());

        mockGetCurrentUser(user3);
        sendUserUnlockedEvent(user3);
        assertEquals(new Integer[]{user3, user1},
                mCarUserService.getBackgroundUsersToRestart().toArray());

        mockGetCurrentUser(user4Guest);
        sendUserUnlockedEvent(user4Guest);
        assertEquals(new Integer[]{user3, user1},
                mCarUserService.getBackgroundUsersToRestart().toArray());

        mockGetCurrentUser(user5);
        sendUserUnlockedEvent(user5);
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

        mockGetCurrentUser(user1);
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
        sendUserUnlockedEvent(user1);
        mockGetCurrentUser(user2);
        sendUserUnlockedEvent(user2);
        sendUserUnlockedEvent(user1);
        mockGetCurrentUser(user3);
        sendUserUnlockedEvent(user3);

        assertEquals(new Integer[]{user3, user2},
                mCarUserService.getBackgroundUsersToRestart().toArray());

        doReturn(true).when(mMockedIActivityManager).startUserInBackground(user2);
        doReturn(true).when(mMockedIActivityManager).unlockUser(user2,
                null, null, null);
        assertEquals(new Integer[]{user2},
                mCarUserService.startAllBackgroundUsers().toArray());
        sendUserUnlockedEvent(user2);
        assertEquals(new Integer[]{user3, user2},
                mCarUserService.getBackgroundUsersToRestart().toArray());

        doReturn(ActivityManager.USER_OP_SUCCESS).when(mMockedIActivityManager).stopUser(user2,
                true, null);
        // should not stop the current fg user
        assertFalse(mCarUserService.stopBackgroundUser(user3));
        assertTrue(mCarUserService.stopBackgroundUser(user2));
        assertEquals(new Integer[]{user3, user2},
                mCarUserService.getBackgroundUsersToRestart().toArray());
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
        mockGetCurrentUser(user1);
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
        mockGetCurrentUser(currentId);
        doReturn(true).when(mMockedIActivityManager).switchUser(targetId);
        doReturn(false).when(mMockedUserManager)
                .hasUserRestriction(UserManager.DISALLOW_USER_SWITCH);
        assertTrue(mCarUserService.switchDriver(targetId));
    }

    @Test
    public void testSwitchDriver_IfUserSwitchIsNotAllowed() throws RemoteException {
        int currentId = 11;
        int targetId = 12;
        mockGetCurrentUser(currentId);
        doReturn(true).when(mMockedIActivityManager).switchUser(targetId);
        doReturn(UserManager.SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED).when(mMockedUserManager)
                .getUserSwitchability();
        assertFalse(mCarUserService.switchDriver(targetId));
    }

    @Test
    public void testSwitchDriver_IfSwitchedToCurrentUser() throws RemoteException {
        int currentId = 11;
        mockGetCurrentUser(currentId);
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
        mockGetCurrentUser(user1Id);
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
    public void testSwitchUser_nonExistingTarget() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mCarUserService
                .switchUser(NON_EXISTING_USER, mAsyncCallTimeoutMs, mUserSwitchFuture));
    }

    @Test
    public void testSwitchUser_targetSameAsCurrentUser() throws Exception {
        mockExistingUsers();
        mockGetCurrentUser(mAdminUser.id);
        mCarUserService.switchUser(mAdminUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);
        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_ALREADY_REQUESTED_USER);
    }

    @Test
    public void testSwitchUser_HalSuccessAndroidSuccess() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.id, mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mGuestUser, true);

        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);

        assertThat(getUserSwitchResult().getStatus()).isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);

        // update current user due to successful user switch
        mockCurrentUser(mGuestUser);
        sendUserUnlockedEvent(mGuestUser.id);
        assertPostSwitch(requestId, mGuestUser.id, mGuestUser.id);
    }

    @Test
    public void testSwitchUser_HalSuccessAndroidFailure() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.id, mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mGuestUser, false);

        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);

        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_ANDROID_FAILURE);
        assertPostSwitch(requestId, mAdminUser.id, mGuestUser.id);
    }

    @Test
    public void testSwitchUser_HalFailure() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mSwitchUserResponse.status = SwitchUserStatus.FAILURE;
        mSwitchUserResponse.errorMessage = "Error Message";
        mockHalSwitch(mAdminUser.id, mGuestUser, mSwitchUserResponse);

        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);

        UserSwitchResult result = getUserSwitchResult();
        assertThat(result.getStatus()).isEqualTo(UserSwitchResult.STATUS_HAL_FAILURE);
        assertThat(result.getErrorMessage()).isEqualTo(mSwitchUserResponse.errorMessage);
    }

    @Test
    public void testSwitchUser_error_badCallbackStatus() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mockHalSwitch(mAdminUser.id, HalCallback.STATUS_WRONG_HAL_RESPONSE, mSwitchUserResponse,
                mGuestUser);

        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);

        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_HAL_INTERNAL_FAILURE);
    }

    @Test
    public void testSwitchUser_multipleCallsDifferentUser_beforeFirstUserUnlocked()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.id, mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mGuestUser, true);
        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        int newRequestId = 43;
        SwitchUserResponse switchUserResponse = new SwitchUserResponse();
        switchUserResponse.status = SwitchUserStatus.SUCCESS;
        switchUserResponse.requestId = newRequestId;
        mockHalSwitch(mAdminUser.id, mRegularUser, switchUserResponse);
        mockAmSwitchUser(mRegularUser, true);
        AndroidFuture<UserSwitchResult> futureNewRequest = new AndroidFuture<>();
        mCarUserService.switchUser(mRegularUser.id, mAsyncCallTimeoutMs, futureNewRequest);
        assertThat(getResult(futureNewRequest).getStatus())
                .isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);

        assertNoPostSwitch();
        assertHalSwitch(mAdminUser.id, mGuestUser.id, mAdminUser.id, mRegularUser.id);
    }

    @Test
    public void testSwitchUser_multipleCallsDifferentUser_beforeFirstUserUnlock_abandonFirstCall()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.id, mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mGuestUser, true);
        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        int newRequestId = 43;
        SwitchUserResponse switchUserResponse = new SwitchUserResponse();
        switchUserResponse.status = SwitchUserStatus.SUCCESS;
        switchUserResponse.requestId = newRequestId;
        mockHalSwitch(mAdminUser.id, mRegularUser, switchUserResponse);
        mockAmSwitchUser(mRegularUser, true);
        AndroidFuture<UserSwitchResult> futureNewRequest = new AndroidFuture<>();
        mCarUserService.switchUser(mRegularUser.id, mAsyncCallTimeoutMs, futureNewRequest);
        mockCurrentUser(mRegularUser);
        sendUserUnlockedEvent(mRegularUser.id);

        assertPostSwitch(newRequestId, mRegularUser.id, mRegularUser.id);
        assertHalSwitch(mAdminUser.id, mGuestUser.id, mAdminUser.id, mRegularUser.id);
    }

    @Test
    public void testSwitchUser_multipleCallsDifferentUser_beforeHALResponded()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        int newRequestId = 43;
        SwitchUserResponse switchUserResponse = new SwitchUserResponse();
        switchUserResponse.status = SwitchUserStatus.SUCCESS;
        switchUserResponse.requestId = newRequestId;
        mockHalSwitch(mAdminUser.id, mRegularUser, switchUserResponse);
        mockAmSwitchUser(mRegularUser, true);
        AndroidFuture<UserSwitchResult> futureNewRequest = new AndroidFuture<>();
        mCarUserService.switchUser(mRegularUser.id, mAsyncCallTimeoutMs, futureNewRequest);
        assertThat(getResult(futureNewRequest).getStatus())
                .isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);

        assertNoPostSwitch();
        assertHalSwitch(mAdminUser.id, mGuestUser.id, mAdminUser.id, mRegularUser.id);
    }

    @Test
    public void testSwitchUser_multipleCallsDifferentUser_beforeHALResponded_abandonFirstCall()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        int newRequestId = 43;
        SwitchUserResponse switchUserResponse = new SwitchUserResponse();
        switchUserResponse.status = SwitchUserStatus.SUCCESS;
        switchUserResponse.requestId = newRequestId;
        mockHalSwitch(mAdminUser.id, mRegularUser, switchUserResponse);
        mockAmSwitchUser(mRegularUser, true);
        AndroidFuture<UserSwitchResult> futureNewRequest = new AndroidFuture<>();
        mCarUserService.switchUser(mRegularUser.id, mAsyncCallTimeoutMs, futureNewRequest);
        mockCurrentUser(mRegularUser);
        sendUserUnlockedEvent(mRegularUser.id);

        assertPostSwitch(newRequestId, mRegularUser.id, mRegularUser.id);
        assertHalSwitch(mAdminUser.id, mGuestUser.id, mAdminUser.id, mRegularUser.id);
    }

    @Test
    public void testSwitchUser_multipleCallsDifferentUser_HALRespondedLate_abandonFirstCall()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        BlockingAnswer<Void> blockingAnswer = mockHalSwitchLateResponse(mAdminUser.id, mGuestUser,
                mSwitchUserResponse);
        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        int newRequestId = 43;
        SwitchUserResponse switchUserResponse = new SwitchUserResponse();
        switchUserResponse.status = SwitchUserStatus.SUCCESS;
        switchUserResponse.requestId = newRequestId;
        mockHalSwitch(mAdminUser.id, mRegularUser, switchUserResponse);
        mockAmSwitchUser(mRegularUser, true);
        AndroidFuture<UserSwitchResult> futureNewRequest = new AndroidFuture<>();
        mCarUserService.switchUser(mRegularUser.id, mAsyncCallTimeoutMs, futureNewRequest);
        mockCurrentUser(mRegularUser);
        sendUserUnlockedEvent(mRegularUser.id);
        blockingAnswer.unblock();

        UserSwitchResult result = getUserSwitchResult();
        assertThat(result.getStatus())
                .isEqualTo(UserSwitchResult.STATUS_TARGET_USER_ABANDONED_DUE_TO_A_NEW_REQUEST);
        assertPostSwitch(newRequestId, mRegularUser.id, mRegularUser.id);
        assertHalSwitch(mAdminUser.id, mGuestUser.id, mAdminUser.id, mRegularUser.id);
    }

    @Test
    public void testSwitchUser_multipleCallsSameUser_beforeHALResponded() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;

        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);
        // calling another user switch before unlock
        AndroidFuture<UserSwitchResult> futureNewRequest = new AndroidFuture<>();
        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, futureNewRequest);

        assertThat(getResult(futureNewRequest).getStatus())
                .isEqualTo(UserSwitchResult.STATUS_TARGET_USER_ALREADY_BEING_SWITCHED_TO);
        assertNoPostSwitch();
        assertHalSwitch(mAdminUser.id, mGuestUser.id);
    }

    @Test
    public void testSwitchUser_multipleCallsSameUser_beforeFirstUserUnlocked() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.id, mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mGuestUser, true);

        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);
        // calling another user switch before unlock
        AndroidFuture<UserSwitchResult> futureNewRequest = new AndroidFuture<>();
        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, futureNewRequest);

        assertThat(getResult(futureNewRequest).getStatus())
                .isEqualTo(UserSwitchResult.STATUS_TARGET_USER_ALREADY_BEING_SWITCHED_TO);
        assertNoPostSwitch();
        assertHalSwitch(mAdminUser.id, mGuestUser.id);
    }

    @Test
    public void testSwitchUser_multipleCallsSameUser_beforeFirstUserUnlocked_noAffectOnFirstCall()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.id, mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mGuestUser, true);

        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture);
        int newRequestId = 43;
        mSwitchUserResponse.requestId = newRequestId;

        // calling another user switch before unlock
        AndroidFuture<UserSwitchResult> future = new AndroidFuture<>();
        mCarUserService.switchUser(mGuestUser.id, mAsyncCallTimeoutMs, future);
        mockCurrentUser(mGuestUser);
        sendUserUnlockedEvent(mGuestUser.id);

        assertPostSwitch(requestId, mGuestUser.id, mGuestUser.id);
        assertHalSwitch(mAdminUser.id, mGuestUser.id);
    }

    @Test
    public void testSwitchUser_InvalidPermission() throws Exception {
        mockManageUsersPermission(android.Manifest.permission.MANAGE_USERS, false);
        assertThrows(SecurityException.class, () -> mCarUserService
                .switchUser(mGuestUser.id, mAsyncCallTimeoutMs, mUserSwitchFuture));
    }

    @Test
    public void testGetUserInfo_nullReceiver() throws Exception {
        assertThrows(NullPointerException.class, () -> mCarUserService
                .getInitialUserInfo(mGetUserInfoRequestType, mAsyncCallTimeoutMs, null));
    }

    @Test
    public void testGetInitialUserInfo_validReceiver_invalidPermission() throws Exception {
        mockManageUsersPermission(android.Manifest.permission.MANAGE_USERS, false);
        assertThrows(SecurityException.class,
                () -> mCarUserService.getInitialUserInfo(42, 108, mReceiver));
    }

    @Test
    public void testGetUserInfo_defaultResponse() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);

        mGetUserInfoResponse.action = InitialUserInfoResponseAction.DEFAULT;
        mockGetInitialInfo(mAdminUser.id, mGetUserInfoResponse);

        mCarUserService.getInitialUserInfo(mGetUserInfoRequestType, mAsyncCallTimeoutMs, mReceiver);

        assertThat(mReceiver.getResultCode()).isEqualTo(HalCallback.STATUS_OK);
        Bundle resultData = mReceiver.getResultData();
        assertThat(resultData).isNotNull();
        assertInitialInfoAction(resultData, mGetUserInfoResponse.action);
    }

    @Test
    public void testGetUserInfo_switchUserResponse() throws Exception {
        int switchUserId = mGuestUser.id;
        mockExistingUsersAndCurrentUser(mAdminUser);

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

        mockExistingUsersAndCurrentUser(mAdminUser);

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
     * Tests the {@code getUserInfo()} that's used by other services.
     */
    @Test
    public void testGetInitialUserInfo() throws Exception {
        int requestType = 42;
        mockExistingUsersAndCurrentUser(mAdminUser);
        HalCallback<InitialUserInfoResponse> callback = (s, r) -> { };
        mCarUserService.getInitialUserInfo(requestType, callback);
        verify(mUserHal).getInitialUserInfo(eq(requestType), anyInt(), mUsersInfoCaptor.capture(),
                same(callback));
        assertDefaultUsersInfo(mUsersInfoCaptor.getValue(), mAdminUser);
    }

    @Test
    public void testGetInitialUserInfo_nullCallback() throws Exception {
        assertThrows(NullPointerException.class,
                () -> mCarUserService.getInitialUserInfo(42, null));
    }

    @Test
    public void testGetInitialUserInfo_invalidPermission() throws Exception {
        mockManageUsersPermission(android.Manifest.permission.MANAGE_USERS, false);
        assertThrows(SecurityException.class,
                () -> mCarUserService.getInitialUserInfo(42, (s, r) -> { }));
    }

    @Test
    public void testGetInitialUser_invalidPermission() throws Exception {
        mockManageUsersPermission(android.Manifest.permission.INTERACT_ACROSS_USERS, false);
        mockManageUsersPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, false);
        assertThrows(SecurityException.class, () -> mCarUserService.getInitialUser());
    }

    @Test
    public void testGetInitialUser_ok() throws Exception {
        assertThat(mCarUserService.getInitialUser()).isNull();
        UserInfo user = new UserInfo();
        mCarUserService.setInitialUser(user);
        assertThat(mCarUserService.getInitialUser()).isSameAs(user);
    }

    @Test
    public void testIsHalSupported() throws Exception {
        when(mUserHal.isSupported()).thenReturn(true);
        assertThat(mCarUserService.isUserHalSupported()).isTrue();
    }

    @Test
    public void testGetUserIdentificationAssociation_nullTypes() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mCarUserService.getUserIdentificationAssociation(null));
    }

    @Test
    public void testGetUserIdentificationAssociation_emptyTypes() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mCarUserService.getUserIdentificationAssociation(new int[] {}));
    }

    @Test
    public void testGetUserIdentificationAssociation_noPermission() throws Exception {
        mockManageUsersPermission(android.Manifest.permission.MANAGE_USERS, false);
        assertThrows(SecurityException.class,
                () -> mCarUserService.getUserIdentificationAssociation(new int[] { 42 }));
    }

    @Test
    public void testGetUserIdentificationAssociation_noSuchUser() throws Exception {
        // Should fail because we're not mocking UserManager.getUserInfo() to set the flag
        assertThrows(IllegalArgumentException.class,
                () -> mCarUserService.getUserIdentificationAssociation(new int[] { 42 }));
    }

    @Test
    public void testGetUserIdentificationAssociation_service_returnNull() throws Exception {
        // Must use the real user id - and not mock it - as the service will infer the id from
        // the Binder call - it's not worth the effort of mocking that.
        int currentUserId = ActivityManager.getCurrentUser();
        Log.d(TAG, "testGetUserIdentificationAssociation_ok(): current user is " + currentUserId);
        UserInfo currentUser = mockUmGetUserInfo(mMockedUserManager, currentUserId,
                UserInfo.FLAG_ADMIN);

        // Not mocking service call, so it will return null

        GetUserIdentificationAssociationResponse response = mCarUserService
                .getUserIdentificationAssociation(new int[] { 108 });

        assertThat(response).isNull();
    }

    @Test
    public void testGetUserIdentificationAssociation_ok() throws Exception {
        // Must use the real user id - and not mock it - as the service will infer the id from
        // the Binder call - it's not worth the effort of mocking that.
        int currentUserId = ActivityManager.getCurrentUser();
        Log.d(TAG, "testGetUserIdentificationAssociation_ok(): current user is " + currentUserId);
        UserInfo currentUser = mockUmGetUserInfo(mMockedUserManager, currentUserId,
                UserInfo.FLAG_ADMIN);

        int[] types = new int[] { 1, 2, 3 };
        mockHalGetUserIdentificationAssociation(currentUser, types, new int[] { 10, 20, 30 },
                "D'OH!");

        GetUserIdentificationAssociationResponse response = mCarUserService
                .getUserIdentificationAssociation(types);

        assertThat(response.getValues()).asList().containsExactly(10, 20, 30)
                .inOrder();
        assertThat(response.getErrorMessage()).isEqualTo("D'OH!");
    }

    @Test
    public void testUserMetric_SendEvent() {
        int userId = 99;
        sendUserSwitchingEvent(userId);

        verify(mUserMetrics).onEvent(CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING,
                0, UserHandle.USER_NULL, userId);
    }

    @Test
    public void testUserMetric_FirstUnlock() {
        int userId = 99;
        long timestampMs = 0;
        long duration = 153;
        int halResponseTime = 5;
        mCarUserService.onFirstUserUnlocked(userId, timestampMs, duration, halResponseTime);

        verify(mUserMetrics).logFirstUnlockedUser(userId, timestampMs, duration, halResponseTime);
    }

    @NonNull
    private UserSwitchResult getUserSwitchResult() throws Exception {
        return getResult(mUserSwitchFuture);
    }

    @NonNull
    private <T> T getResult(@NonNull AndroidFuture<T> future) throws Exception {
        try {
            return future.get(mAsyncCallTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("not called in " + mAsyncCallTimeoutMs + "ms", e);
        }
    }

    /**
     * Mock calls that generate a {@code UsersInfo}.
     */
    private void mockExistingUsersAndCurrentUser(@NonNull UserInfo user)
            throws Exception {
        mockExistingUsers();
        mockCurrentUser(user);
    }

    private void mockExistingUsers() {
        when(mMockedUserManager.getUsers()).thenReturn(mExistingUsers);
        for (UserInfo user : mExistingUsers) {
            when(mMockedUserManager.getUserInfo(user.id)).thenReturn(user);
        }
    }

    private void mockCurrentUser(@NonNull UserInfo user) throws Exception {
        when(mMockedIActivityManager.getCurrentUser()).thenReturn(user);
        mockGetCurrentUser(user.id);
    }

    private void mockAmSwitchUser(@NonNull UserInfo user, boolean result) throws Exception {
        when(mMockedIActivityManager.switchUser(user.id)).thenReturn(result);
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

    private void mockHalSwitch(@UserIdInt int currentUserId, @NonNull UserInfo androidTargetUser,
            @Nullable SwitchUserResponse response) {
        mockHalSwitch(currentUserId, HalCallback.STATUS_OK, response, androidTargetUser);
    }

    private BlockingAnswer<Void> mockHalSwitchLateResponse(@UserIdInt int currentUserId,
            @NonNull UserInfo androidTargetUser, @Nullable SwitchUserResponse response) {
        android.hardware.automotive.vehicle.V2_0.UserInfo halTargetUser =
                new android.hardware.automotive.vehicle.V2_0.UserInfo();
        halTargetUser.userId = androidTargetUser.id;
        halTargetUser.flags = UserHalHelper.convertFlags(androidTargetUser);
        UsersInfo usersInfo = newUsersInfo(currentUserId);

        BlockingAnswer<Void> blockingAnswer = BlockingAnswer.forVoidReturn(10_000, (invocation) -> {
            Log.d(TAG, "Answering " + invocation + " with " + response);
            @SuppressWarnings("unchecked")
            HalCallback<SwitchUserResponse> callback = (HalCallback<SwitchUserResponse>) invocation
                    .getArguments()[3];
            callback.onResponse(HalCallback.STATUS_OK, response);
        });
        doAnswer(blockingAnswer).when(mUserHal).switchUser(eq(halTargetUser),
                eq(mAsyncCallTimeoutMs), eq(usersInfo), notNull());
        return blockingAnswer;

    }

    private void mockHalSwitch(@UserIdInt int currentUserId,
            @HalCallback.HalCallbackStatus int callbackStatus,
            @Nullable SwitchUserResponse response, @NonNull UserInfo androidTargetUser) {
        android.hardware.automotive.vehicle.V2_0.UserInfo halTargetUser =
                new android.hardware.automotive.vehicle.V2_0.UserInfo();
        halTargetUser.userId = androidTargetUser.id;
        halTargetUser.flags = UserHalHelper.convertFlags(androidTargetUser);
        UsersInfo usersInfo = newUsersInfo(currentUserId);
        doAnswer((invocation) -> {
            Log.d(TAG, "Answering " + invocation + " with " + response);
            @SuppressWarnings("unchecked")
            HalCallback<SwitchUserResponse> callback =
                    (HalCallback<SwitchUserResponse>) invocation.getArguments()[3];
            callback.onResponse(callbackStatus, response);
            return null;
        }).when(mUserHal).switchUser(eq(halTargetUser), eq(mAsyncCallTimeoutMs), eq(usersInfo),
                notNull());
    }

    private void mockHalGetUserIdentificationAssociation(@NonNull UserInfo user,
            @NonNull int[] types, @NonNull int[] values,  @Nullable String errorMessage) {
        assertWithMessage("mismatch on number of types and values").that(types.length)
                .isEqualTo(values.length);

        UserIdentificationResponse response = new UserIdentificationResponse();
        response.numberAssociation = types.length;
        response.errorMessage = errorMessage;
        for (int i = 0; i < types.length; i++) {
            UserIdentificationAssociation association = new UserIdentificationAssociation();
            association.type = types[i];
            association.value = values[i];
            response.associations.add(association);
        }

        when(mUserHal.getUserAssociation(isUserIdentificationGetRequest(user, types)))
                .thenReturn(response);
    }

    private void mockManageUsersPermission(String permission, boolean granted) {
        int result;
        if (granted) {
            result = android.content.pm.PackageManager.PERMISSION_GRANTED;
        } else {
            result = android.content.pm.PackageManager.PERMISSION_DENIED;
        }
        doReturn(result).when(() -> ActivityManager.checkComponentPermission(eq(permission),
                anyInt(), anyInt(), eq(true)));
    }

    /**
     * Asserts a {@link UsersInfo} that was created based on {@link #mockCurrentUsers(UserInfo)}.
     */
    private void assertDefaultUsersInfo(UsersInfo actual, UserInfo currentUser) {
        // TODO(b/150413515): figure out why this method is not called in other places
        assertThat(actual).isNotNull();
        assertSameUser(actual.currentUser, currentUser);
        assertThat(actual.numberUsers).isEqualTo(mExistingUsers.size());
        for (int i = 0; i < actual.numberUsers; i++) {
            assertSameUser(actual.existingUsers.get(i), mExistingUsers.get(i));
        }

    }

    private void assertSameUser(android.hardware.automotive.vehicle.V2_0.UserInfo halUser,
            UserInfo androidUser) {
        assertThat(halUser.userId).isEqualTo(androidUser.id);
        assertWithMessage("flags mismatch: hal=%s, android=%s",
                UserInfo.flagsToString(androidUser.flags),
                UserHalHelper.userFlagsToString(halUser.flags))
            .that(halUser.flags).isEqualTo(UserHalHelper.convertFlags(androidUser));
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

    private void assertNoPostSwitch() {
        verify(mUserHal, never()).postSwitchResponse(anyInt(), any(), any());
    }

    private void assertPostSwitch(int requestId, int currentId, int targetId) {
        // verify post switch response
        ArgumentCaptor<android.hardware.automotive.vehicle.V2_0.UserInfo> targetUser =
                ArgumentCaptor.forClass(android.hardware.automotive.vehicle.V2_0.UserInfo.class);
        ArgumentCaptor<UsersInfo> usersInfo = ArgumentCaptor.forClass(UsersInfo.class);
        verify(mUserHal).postSwitchResponse(eq(requestId), targetUser.capture(),
                usersInfo.capture());
        assertThat(targetUser.getValue().userId).isEqualTo(targetId);
        assertThat(usersInfo.getValue().currentUser.userId).isEqualTo(currentId);
    }

    // TODO(b/154966308): Refactor to use argument matcher
    private void assertHalSwitch(int currentId, int targetId) {
        ArgumentCaptor<android.hardware.automotive.vehicle.V2_0.UserInfo> targetUser =
                ArgumentCaptor.forClass(android.hardware.automotive.vehicle.V2_0.UserInfo.class);
        ArgumentCaptor<UsersInfo> usersInfo = ArgumentCaptor.forClass(UsersInfo.class);
        verify(mUserHal).switchUser(targetUser.capture(), eq(mAsyncCallTimeoutMs),
                usersInfo.capture(), any());
        assertThat(targetUser.getValue().userId).isEqualTo(targetId);
        assertThat(usersInfo.getValue().currentUser.userId).isEqualTo(currentId);
    }

    // TODO(b/154966308): Refactor to use argument matcher
    private void assertHalSwitch(int currentId1, int targetId1, int currentId2, int targetId2) {
        ArgumentCaptor<android.hardware.automotive.vehicle.V2_0.UserInfo> targetUser =
                ArgumentCaptor.forClass(android.hardware.automotive.vehicle.V2_0.UserInfo.class);
        ArgumentCaptor<UsersInfo> usersInfo = ArgumentCaptor.forClass(UsersInfo.class);
        verify(mUserHal, times(2)).switchUser(targetUser.capture(), eq(mAsyncCallTimeoutMs),
                usersInfo.capture(), any());
        assertThat(targetUser.getAllValues().get(0).userId).isEqualTo(targetId1);
        assertThat(usersInfo.getAllValues().get(0).currentUser.userId).isEqualTo(currentId1);
        assertThat(targetUser.getAllValues().get(1).userId).isEqualTo(targetId2);
        assertThat(usersInfo.getAllValues().get(1).currentUser.userId).isEqualTo(currentId2);
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

    private void sendUserLifecycleEvent(@UserIdInt int userId,
            @UserLifecycleEventType int eventType) {
        mCarUserService.onUserLifecycleEvent(eventType, /* timestampMs= */ 0,
                /* fromUserId= */ UserHandle.USER_NULL, userId);
    }

    private void sendUserUnlockedEvent(@UserIdInt int userId) {
        sendUserLifecycleEvent(userId, CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED);
    }

    private void sendUserSwitchingEvent(@UserIdInt int userId) {
        sendUserLifecycleEvent(userId, CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING);
    }

    @NonNull
    private static UserIdentificationGetRequest isUserIdentificationGetRequest(
            @NonNull UserInfo user, @NonNull int[] types) {
        return argThat(new UserIdentificationGetRequestMatcher(user, types));
    }

    private static class UserIdentificationGetRequestMatcher implements
            ArgumentMatcher<UserIdentificationGetRequest> {

        private static final String MY_TAG =
                UserIdentificationGetRequestMatcher.class.getSimpleName();

        private final @UserIdInt int mUserId;
        private final int mHalFlags;
        private final @NonNull int[] mTypes;

        private UserIdentificationGetRequestMatcher(@NonNull UserInfo user, @NonNull int[] types) {
            mUserId = user.id;
            mHalFlags = UserHalHelper.convertFlags(user);
            mTypes = types;
        }

        @Override
        public boolean matches(UserIdentificationGetRequest argument) {
            if (argument == null) {
                Log.w(MY_TAG, "null argument");
                return false;
            }
            if (argument.userInfo.userId != mUserId) {
                Log.w(MY_TAG, "wrong user id on " + argument + "; expected " + mUserId);
                return false;
            }
            if (argument.userInfo.flags != mHalFlags) {
                Log.w(MY_TAG, "wrong flags on " + argument + "; expected " + mHalFlags);
                return false;
            }
            if (argument.numberAssociationTypes != mTypes.length) {
                Log.w(MY_TAG, "wrong numberAssociationTypes on " + argument + "; expected "
                        + mTypes.length);
                return false;
            }
            if (argument.associationTypes.size() != mTypes.length) {
                Log.w(MY_TAG, "wrong associationTypes size on " + argument + "; expected "
                        + mTypes.length);
                return false;
            }
            for (int i = 0; i < mTypes.length; i++) {
                if (argument.associationTypes.get(i) != mTypes[i]) {
                    Log.w(MY_TAG, "wrong association type on index " + i + " on " + argument
                            + "; expected types: " + Arrays.toString(mTypes));
                    return false;
                }
            }
            Log.d(MY_TAG, "Good News, Everyone! " + argument + " matches " + this);
            return true;
        }

        @Override
        public String toString() {
            return "isUserIdentificationGetRequest(userId=" + mUserId + ", flags="
                    + UserHalHelper.userFlagsToString(mHalFlags) + ", types="
                    + Arrays.toString(mTypes) + ")";
        }
    }
}
