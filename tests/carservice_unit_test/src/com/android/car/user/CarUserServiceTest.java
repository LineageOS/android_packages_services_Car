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

import static android.car.test.mocks.AndroidMockitoHelper.mockAmSwitchUser;
import static android.car.test.mocks.JavaMockitoHelper.getResult;

import static com.android.car.user.MockedUserHandleBuilder.expectEphemeralUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectGuestUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectRegularUserExists;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

import android.app.ActivityManager;
import android.car.builtin.os.UserManagerHelper;
import android.car.drivingstate.ICarUxRestrictionsChangeListener;
import android.car.settings.CarSettings;
import android.car.test.mocks.BlockingAnswer;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserLifecycleEvent;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.car.user.UserCreationResult;
import android.car.user.UserIdentificationAssociationResponse;
import android.car.user.UserRemovalResult;
import android.car.user.UserStartResult;
import android.car.user.UserStopResult;
import android.car.user.UserSwitchResult;
import android.car.util.concurrent.AndroidFuture;
import android.content.Context;
import android.hardware.automotive.vehicle.V2_0.CreateUserRequest;
import android.hardware.automotive.vehicle.V2_0.CreateUserStatus;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponseAction;
import android.hardware.automotive.vehicle.V2_0.SwitchUserResponse;
import android.hardware.automotive.vehicle.V2_0.SwitchUserStatus;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.car.hal.HalCallback;
import com.android.car.internal.util.DebugUtils;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for the {@link CarUserService}.
 */
public final class CarUserServiceTest extends BaseCarUserServiceTestCase {

    private static final String TAG = CarUserServiceTest.class.getSimpleName();

    @Test
    public void testInitAndRelease() {
        // init()
        ICarUxRestrictionsChangeListener listener = initService();
        assertThat(listener).isNotNull();

        // release()
        mCarUserService.release();
        verify(mCarUxRestrictionService).unregisterUxRestrictionsChangeListener(listener);
    }

    @Test
    public void testSetInitialUser() throws Exception {
        UserHandle user = UserHandle.of(101);

        mCarUserService.setInitialUser(user);

        assertThat(mCarUserService.getInitialUser()).isEqualTo(user);
    }

    @Test
    @ExpectWtf
    public void testSetInitialUser_nullUser() throws Exception {
        mCarUserService.setInitialUser(null);

        mockInteractAcrossUsersPermission(true);
        assertThat(mCarUserService.getInitialUser()).isNull();
    }

    @Test
    public void testSendInitialUserToSystemServer() throws Exception {
        UserHandle user = UserHandle.of(101);
        mCarUserService.setCarServiceHelper(mICarServiceHelper);

        mCarUserService.setInitialUser(user);

        verify(mICarServiceHelper).sendInitialUser(user);
    }

    @Test
    public void testsetInitialUserFromSystemServer() throws Exception {
        UserHandle user = UserHandle.of(101);

        mCarUserService.setInitialUserFromSystemServer(user);

        assertThat(mCarUserService.getInitialUser()).isEqualTo(user);
    }

    @Test
    public void testsetInitialUserFromSystemServer_nullUser() throws Exception {
        mCarUserService.setInitialUserFromSystemServer(null);

        assertThat(mCarUserService.getInitialUser()).isNull();
    }

    @Test
    public void testSetICarServiceHelper_withUxRestrictions() throws Exception {
        mockGetUxRestrictions(/* restricted= */ true);
        ICarUxRestrictionsChangeListener listener = initService();

        mCarUserService.setCarServiceHelper(mICarServiceHelper);
        verify(mICarServiceHelper).setSafetyMode(false);

        updateUxRestrictions(listener, /* restricted= */ false);
        verify(mICarServiceHelper).setSafetyMode(true);
    }

    @Test
    public void testSetICarServiceHelper_withoutUxRestrictions() throws Exception {
        mockGetUxRestrictions(/* restricted= */ false);
        ICarUxRestrictionsChangeListener listener = initService();

        mCarUserService.setCarServiceHelper(mICarServiceHelper);
        verify(mICarServiceHelper).setSafetyMode(true);

        updateUxRestrictions(listener, /* restricted= */ true);
        verify(mICarServiceHelper).setSafetyMode(false);
    }

    @Test
    public void testAddUserLifecycleListener_checkNullParameter() {
        assertThrows(NullPointerException.class,
                () -> mCarUserService.addUserLifecycleListener(null));
    }

    @Test
    public void testRemoveUser_binderMethod() {
        CarUserService spy = spy(mCarUserService);

        spy.removeUser(42, mUserRemovalFuture);

        verify(spy).removeUser(42, NO_CALLER_RESTRICTIONS, mUserRemovalFuture);
    }

    @Test
    public void testRemoveUserLifecycleListener_checkNullParameter() {
        assertThrows(NullPointerException.class,
                () -> mCarUserService.removeUserLifecycleListener(null));
    }

    @Test
    public void testOnUserLifecycleEvent_legacyUserSwitch_halCalled() throws Exception {
        // Arrange
        mockExistingUsers(mExistingUsers);

        // Act
        sendUserSwitchingEvent(mAdminUser.getIdentifier(), mRegularUser.getIdentifier());

        // Verify
        verify(mUserHal).legacyUserSwitch(any());
    }

    @Test
    public void testOnUserLifecycleEvent_legacyUserSwitch_halnotSupported() throws Exception {
        // Arrange
        mockExistingUsers(mExistingUsers);
        mockUserHalSupported(false);

        // Act
        sendUserSwitchingEvent(mAdminUser.getIdentifier(), mRegularUser.getIdentifier());

        // Verify
        verify(mUserHal, never()).legacyUserSwitch(any());
    }

    @Test
    public void testOnUserLifecycleEvent_notifyListener() throws Exception {
        // Arrange
        mCarUserService.addUserLifecycleListener(mUserLifecycleListener);
        mockExistingUsers(mExistingUsers);

        // Act
        sendUserSwitchingEvent(mAdminUser.getIdentifier(), mRegularUser.getIdentifier());

        // Verify
        verifyListenerOnEventInvoked(mRegularUser.getIdentifier(),
                CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING);
    }

    @Test
    public void testOnUserLifecycleEvent_ensureAllListenersAreNotified() throws Exception {
        // Arrange: add two listeners, one to fail on onEvent
        // Adding the failure listener first.
        UserLifecycleListener failureListener = mock(UserLifecycleListener.class);
        doThrow(new RuntimeException("Failed onEvent invocation")).when(
                failureListener).onEvent(any(UserLifecycleEvent.class));
        mCarUserService.addUserLifecycleListener(failureListener);
        mockExistingUsers(mExistingUsers);

        // Adding the non-failure listener later.
        mCarUserService.addUserLifecycleListener(mUserLifecycleListener);

        // Act
        sendUserSwitchingEvent(mAdminUser.getIdentifier(), mRegularUser.getIdentifier());

        // Verify
        verifyListenerOnEventInvoked(mRegularUser.getIdentifier(),
                CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING);
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
        verify(mLocationManager).setAdasGnssLocationEnabled(false);
    }

    /**
     * Test that the {@link CarUserService} updates last active user on user switch in non-headless
     * system user mode.
     */
    @Test
    public void testLastActiveUserUpdatedOnUserSwitch_nonHeadlessSystemUser() throws Exception {
        mockIsHeadlessSystemUser(mRegularUser.getIdentifier(), false);
        mockExistingUsers(mExistingUsers);

        sendUserSwitchingEvent(mAdminUser.getIdentifier(), mRegularUser.getIdentifier());

        verifyLastActiveUserSet(mRegularUser);
    }

    /**
     * Test that the {@link CarUserService} sets default guest restrictions on first boot.
     */
    @Test
    public void testInitializeGuestRestrictions_IfNotAlreadySet() {
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
        assertThat(getSettingsInt(CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET)).isEqualTo(1);
    }

    @Test
    public void testRunOnUser0UnlockImmediate() {
        mUser0TaskExecuted = false;
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
        mCarUserService.runOnUser0Unlock(() -> {
            mUser0TaskExecuted = true;
        });
        assertThat(mUser0TaskExecuted).isTrue();
    }

    @Test
    public void testRunOnUser0UnlockLater() {
        mUser0TaskExecuted = false;
        mCarUserService.runOnUser0Unlock(() -> {
            mUser0TaskExecuted = true;
        });
        assertThat(mUser0TaskExecuted).isFalse();
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
        assertThat(mUser0TaskExecuted).isTrue();
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

        UserHandle user1Handle = expectRegularUserExists(mMockedUserHandleHelper, user1);
        UserHandle user2Handle = expectRegularUserExists(mMockedUserHandleHelper, user2);
        UserHandle user3Handle = expectRegularUserExists(mMockedUserHandleHelper, user3);
        UserHandle user4GuestHandle = expectGuestUserExists(mMockedUserHandleHelper, user4Guest,
                /* isEphemeral= */ true);
        UserHandle user5Handle = expectRegularUserExists(mMockedUserHandleHelper, user5);

        mockGetCurrentUser(user1);
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
        // user 0 should never go to that list.
        assertThat(mCarUserService.getBackgroundUsersToRestart()).isEmpty();

        sendUserUnlockedEvent(user1);
        assertThat(mCarUserService.getBackgroundUsersToRestart()).containsExactly(user1);

        // user 2 background, ignore in restart list
        sendUserUnlockedEvent(user2);
        assertThat(mCarUserService.getBackgroundUsersToRestart()).containsExactly(user1);

        mockGetCurrentUser(user3);
        sendUserUnlockedEvent(user3);
        assertThat(mCarUserService.getBackgroundUsersToRestart()).containsExactly(user1, user3);

        mockGetCurrentUser(user4Guest);
        sendUserUnlockedEvent(user4Guest);
        assertThat(mCarUserService.getBackgroundUsersToRestart()).containsExactly(user1, user3);

        mockGetCurrentUser(user5);
        sendUserUnlockedEvent(user5);
        assertThat(mCarUserService.getBackgroundUsersToRestart()).containsExactly(user3, user5);
    }

    /**
     * Test is lengthy as it is testing LRU logic.
     */
    @Test
    public void testBackgroundUsersStartStopKeepBackgroundUserList() throws Exception {
        int user1 = 101;
        int user2 = 102;
        int user3 = 103;

        UserHandle user1Handle = UserHandle.of(user1);
        UserHandle user2Handle = UserHandle.of(user2);
        UserHandle user3Handle = UserHandle.of(user3);

        mockGetCurrentUser(user1);
        sendUserUnlockedEvent(UserHandle.USER_SYSTEM);
        sendUserUnlockedEvent(user1);
        mockGetCurrentUser(user2);
        sendUserUnlockedEvent(user2);
        sendUserUnlockedEvent(user1);
        mockGetCurrentUser(user3);
        sendUserUnlockedEvent(user3);
        mockStopUserWithDelayedLocking(user3, ActivityManager.USER_OP_IS_CURRENT);

        assertThat(mCarUserService.getBackgroundUsersToRestart()).containsExactly(user2, user3);

        when(mMockedActivityManagerHelper.startUserInBackground(user2)).thenReturn(true);
        when(mMockedActivityManagerHelper.unlockUser(user2)).thenReturn(true);
        assertThat(mCarUserService.startAllBackgroundUsersInGarageMode()).containsExactly(user2);
        sendUserUnlockedEvent(user2);
        assertThat(mCarUserService.getBackgroundUsersToRestart()).containsExactly(user2, user3);

        // should not stop the current fg user
        assertThat(mCarUserService.stopBackgroundUserInGagageMode(user3)).isFalse();
        assertThat(mCarUserService.stopBackgroundUserInGagageMode(user2)).isTrue();
        assertThat(mCarUserService.getBackgroundUsersToRestart()).containsExactly(user2, user3);
        assertThat(mCarUserService.getBackgroundUsersToRestart()).containsExactly(user2, user3);
    }

    @Test
    public void testStopUser_success() throws Exception {
        int userId = 101;
        mockStopUserWithDelayedLocking(userId, ActivityManager.USER_OP_SUCCESS);

        AndroidFuture<UserStopResult> userStopResult = new AndroidFuture<>();
        stopUser(userId, userStopResult);

        assertThat(getResult(userStopResult).getStatus())
                .isEqualTo(UserStopResult.STATUS_SUCCESSFUL);
        assertThat(getResult(userStopResult).isSuccess()).isTrue();
    }

    @Test
    public void testStopUser_permissionDenied() throws Exception {
        int userId = 101;
        mockManageUsersPermission(android.Manifest.permission.MANAGE_USERS, false);
        mockManageUsersPermission(android.Manifest.permission.CREATE_USERS, false);

        AndroidFuture<UserStopResult> userStopResult = new AndroidFuture<>();
        assertThrows(SecurityException.class, () -> stopUser(userId, userStopResult));
    }

    @Test
    public void testStopUser_fail() throws Exception {
        int userId = 101;
        AndroidFuture<UserStopResult> userStopResult = new AndroidFuture<>();
        CarUserService carUserServiceLocal = new CarUserService(
                mMockContext,
                mUserHal,
                mMockedUserManager,
                mMockedUserHandleHelper,
                mMockedActivityManager,
                mMockedActivityManagerHelper,
                /* maxRunningUsers= */ 3,
                mInitialUserSetter,
                mUserPreCreator,
                mCarUxRestrictionService,
                mMockedHandler);
        mockStopUserWithDelayedLockingThrowsIllegalStateException(userId);

        carUserServiceLocal.stopUser(userId, userStopResult);

        ArgumentCaptor<Runnable> runnableCaptor =
                ArgumentCaptor.forClass(Runnable.class);
        verify(mMockedHandler).post(runnableCaptor.capture());
        Runnable runnable = runnableCaptor.getValue();
        expectThrows(IllegalStateException.class, ()-> runnable.run());
    }

    @Test
    public void testStopUser_userDoesNotExist() throws Exception {
        int userId = 101;
        mockStopUserWithDelayedLocking(userId, ActivityManager.USER_OP_UNKNOWN_USER);

        AndroidFuture<UserStopResult> userStopResult = new AndroidFuture<>();
        stopUser(userId, userStopResult);

        assertThat(getResult(userStopResult).getStatus())
                .isEqualTo(UserStopResult.STATUS_USER_DOES_NOT_EXIST);
        assertThat(getResult(userStopResult).isSuccess()).isFalse();
    }

    @Test
    public void testStopUser_systemUser() throws Exception {
        mockStopUserWithDelayedLocking(
                UserHandle.USER_SYSTEM, ActivityManager.USER_OP_ERROR_IS_SYSTEM);

        AndroidFuture<UserStopResult> userStopResult = new AndroidFuture<>();
        stopUser(UserHandle.USER_SYSTEM, userStopResult);

        assertThat(getResult(userStopResult).getStatus())
                .isEqualTo(UserStopResult.STATUS_FAILURE_SYSTEM_USER);
        assertThat(getResult(userStopResult).isSuccess()).isFalse();
    }

    @Test
    public void testStopUser_currentUser() throws Exception {
        int userId = 101;
        mockStopUserWithDelayedLocking(userId, ActivityManager.USER_OP_IS_CURRENT);

        AndroidFuture<UserStopResult> userStopResult = new AndroidFuture<>();
        stopUser(userId, userStopResult);

        assertThat(getResult(userStopResult).getStatus())
                .isEqualTo(UserStopResult.STATUS_FAILURE_CURRENT_USER);
        assertThat(getResult(userStopResult).isSuccess()).isFalse();
    }

    @Test
    public void testStopBackgroundUserForSystemUser() throws Exception {
        mockStopUserWithDelayedLocking(
                UserHandle.USER_SYSTEM, ActivityManager.USER_OP_ERROR_IS_SYSTEM);

        assertThat(mCarUserService.stopBackgroundUserInGagageMode(UserHandle.USER_SYSTEM))
                .isFalse();
    }

    @Test
    public void testStopBackgroundUserForFgUser() throws Exception {
        int userId = 101;
        mockStopUserWithDelayedLocking(userId, ActivityManager.USER_OP_IS_CURRENT);

        assertThat(mCarUserService.stopBackgroundUserInGagageMode(userId)).isFalse();
    }

    @Test
    public void testRemoveUser_currentUser_successSetEphemeral() throws Exception {
        UserHandle currentUser = mRegularUser;
        mockExistingUsersAndCurrentUser(mExistingUsers, currentUser);
        UserHandle removeUser = mRegularUser;
        mockRemoveUserNoCallback(removeUser, UserManager.REMOVE_RESULT_DEFERRED);

        removeUser(removeUser.getIdentifier(), mUserRemovalFuture);

        assertUserRemovalResultStatus(getUserRemovalResult(),
                UserRemovalResult.STATUS_SUCCESSFUL_SET_EPHEMERAL);
        assertNoHalUserRemoval();
    }

    @Test
    public void testRemoveUser_alreadyBeingRemoved_success() throws Exception {
        UserHandle currentUser = mRegularUser;
        mockExistingUsersAndCurrentUser(mExistingUsers, currentUser);
        UserHandle removeUser = mRegularUser;
        mockRemoveUser(removeUser, UserManager.REMOVE_RESULT_ALREADY_BEING_REMOVED);

        removeUser(removeUser.getIdentifier(), mUserRemovalFuture);

        assertUserRemovalResultStatus(getUserRemovalResult(), UserRemovalResult.STATUS_SUCCESSFUL);
        assertHalRemove(currentUser, removeUser);
    }

    @Test
    public void testRemoveUser_currentLastAdmin_successSetEphemeral() throws Exception {
        UserHandle currentUser = mAdminUser;
        List<UserHandle> existingUsers = Arrays.asList(mAdminUser, mRegularUser);
        mockExistingUsersAndCurrentUser(existingUsers, currentUser);
        UserHandle removeUser = mAdminUser;
        mockRemoveUserNoCallback(removeUser, UserManager.REMOVE_RESULT_DEFERRED);

        removeUser(mAdminUser.getIdentifier(), NO_CALLER_RESTRICTIONS, mUserRemovalFuture);

        assertUserRemovalResultStatus(getUserRemovalResult(),
                UserRemovalResult.STATUS_SUCCESSFUL_LAST_ADMIN_SET_EPHEMERAL);
        assertNoHalUserRemoval();
    }

    @Test
    public void testRemoveUser_userNotExist() throws Exception {
        removeUser(15, NO_CALLER_RESTRICTIONS, mUserRemovalFuture);

        assertUserRemovalResultStatus(getUserRemovalResult(),
                UserRemovalResult.STATUS_USER_DOES_NOT_EXIST);
    }

    @Test
    public void testRemoveUser_lastAdminUser_success() throws Exception {
        UserHandle currentUser = mRegularUser;
        UserHandle removeUser = mAdminUser;
        List<UserHandle> existingUsers = Arrays.asList(mAdminUser, mRegularUser);

        mockExistingUsersAndCurrentUser(existingUsers, currentUser);
        mockRemoveUser(removeUser);

        removeUser(mAdminUser.getIdentifier(), NO_CALLER_RESTRICTIONS, mUserRemovalFuture);

        assertUserRemovalResultStatus(getUserRemovalResult(),
                UserRemovalResult.STATUS_SUCCESSFUL_LAST_ADMIN_REMOVED);
        assertHalRemove(currentUser, removeUser);
    }

    @Test
    public void testRemoveUser_notLastAdminUser_success() throws Exception {
        UserHandle currentUser = mRegularUser;
        // Give admin rights to current user.
        // currentUser.flags = currentUser.flags | FLAG_ADMIN;
        mockExistingUsersAndCurrentUser(mExistingUsers, currentUser);
        UserHandle removeUser = mAdminUser;
        mockRemoveUser(removeUser);

        removeUser(removeUser.getIdentifier(), NO_CALLER_RESTRICTIONS, mUserRemovalFuture);

        assertUserRemovalResultStatus(getUserRemovalResult(), UserRemovalResult.STATUS_SUCCESSFUL);
        assertHalRemove(currentUser, removeUser);
    }

    @Test
    public void testRemoveUser_success() throws Exception {
        UserHandle currentUser = mAdminUser;
        mockExistingUsersAndCurrentUser(mExistingUsers, currentUser);
        UserHandle removeUser = mRegularUser;
        mockRemoveUser(removeUser);

        removeUser(removeUser.getIdentifier(), NO_CALLER_RESTRICTIONS, mUserRemovalFuture);
        UserRemovalResult result = getUserRemovalResult();

        assertUserRemovalResultStatus(result, UserRemovalResult.STATUS_SUCCESSFUL);
        assertHalRemove(currentUser, removeUser);
    }

    @Test
    public void testRemoveUser_halNotSupported() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        UserHandle removeUser = mRegularUser;
        mockUserHalSupported(false);
        mockRemoveUser(removeUser);

        removeUser(removeUser.getIdentifier(), NO_CALLER_RESTRICTIONS, mUserRemovalFuture);

        assertUserRemovalResultStatus(getUserRemovalResult(), UserRemovalResult.STATUS_SUCCESSFUL);
        verify(mUserHal, never()).removeUser(any());
    }

    @Test
    public void testRemoveUser_androidFailure() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int targetUserId = mRegularUser.getIdentifier();
        mockRemoveUser(mRegularUser, UserManager.REMOVE_RESULT_ERROR);

        removeUser(targetUserId, NO_CALLER_RESTRICTIONS, mUserRemovalFuture);

        assertUserRemovalResultStatus(getUserRemovalResult(),
                UserRemovalResult.STATUS_ANDROID_FAILURE);
    }

    @Test
    public void testRemoveUserWithRestriction_nonAdminRemovingAdmin() throws Exception {
        UserHandle currentUser = mRegularUser;
        UserHandle removeUser = mAdminUser;
        mockGetCallingUserHandle(currentUser.getIdentifier());
        mockExistingUsersAndCurrentUser(mExistingUsers, currentUser);
        mockRemoveUser(removeUser, /* evenWhenDisallowed= */ true);

        assertThrows(SecurityException.class,
                () -> removeUser(removeUser.getIdentifier(), HAS_CALLER_RESTRICTIONS,
                        mUserRemovalFuture));
    }

    @Test
    public void testRemoveUserWithRestriction_nonAdminRemovingNonAdmin() throws Exception {
        UserHandle currentUser = mRegularUser;
        UserHandle removeUser = mAnotherRegularUser;
        mockGetCallingUserHandle(currentUser.getIdentifier());
        mockExistingUsersAndCurrentUser(mExistingUsers, currentUser);
        mockRemoveUser(removeUser, /* evenWhenDisallowed= */ true);

        assertThrows(SecurityException.class,
                () -> removeUser(removeUser.getIdentifier(), HAS_CALLER_RESTRICTIONS,
                        mUserRemovalFuture));
    }

    @Test
    public void testRemoveUserWithRestriction_nonAdminRemovingItself() throws Exception {
        UserHandle currentUser = mRegularUser;
        UserHandle removeUser = mRegularUser;
        mockGetCallingUserHandle(currentUser.getIdentifier());
        mockExistingUsersAndCurrentUser(mExistingUsers, currentUser);
        mockRemoveUserNoCallback(removeUser, /* evenWhenDisallowed= */ true,
                UserManager.REMOVE_RESULT_DEFERRED);

        removeUser(removeUser.getIdentifier(), HAS_CALLER_RESTRICTIONS, mUserRemovalFuture);

        assertUserRemovalResultStatus(getUserRemovalResult(),
                UserRemovalResult.STATUS_SUCCESSFUL_SET_EPHEMERAL);
        assertNoHalUserRemoval();
    }

    @Test
    public void testRemoveUserWithRestriction_adminRemovingAdmin() throws Exception {
        UserHandle currentUser = mAdminUser;
        UserHandle removeUser = mAnotherAdminUser;
        mockGetCallingUserHandle(currentUser.getIdentifier());
        mockExistingUsersAndCurrentUser(mExistingUsers, currentUser);
        mockRemoveUser(removeUser, /* evenWhenDisallowed= */ true);

        removeUser(removeUser.getIdentifier(), HAS_CALLER_RESTRICTIONS, mUserRemovalFuture);

        assertUserRemovalResultStatus(getUserRemovalResult(), UserRemovalResult.STATUS_SUCCESSFUL);
        assertHalRemove(currentUser, removeUser, /* evenWhenDisallowed= */ true);
    }

    @Test
    public void testRemoveUserWithRestriction_adminRemovingNonAdmin() throws Exception {
        UserHandle currentUser = mAdminUser;
        UserHandle removeUser = mRegularUser;
        mockGetCallingUserHandle(currentUser.getIdentifier());
        mockExistingUsersAndCurrentUser(mExistingUsers, currentUser);
        mockRemoveUser(removeUser, /* evenWhenDisallowed= */ true);

        removeUser(removeUser.getIdentifier(), HAS_CALLER_RESTRICTIONS, mUserRemovalFuture);

        assertUserRemovalResultStatus(getUserRemovalResult(), UserRemovalResult.STATUS_SUCCESSFUL);
        assertHalRemove(currentUser, removeUser, /* evenWhenDisallowed= */ true);
    }

    @Test
    public void testRemoveUserWithRestriction_adminRemovingItself() throws Exception {
        UserHandle currentUser = mAdminUser;
        UserHandle removeUser = mAdminUser;
        mockGetCallingUserHandle(currentUser.getIdentifier());
        mockExistingUsersAndCurrentUser(mExistingUsers, currentUser);
        mockRemoveUserNoCallback(removeUser, /* evenWhenDisallowed= */ true,
                UserManager.REMOVE_RESULT_DEFERRED);

        removeUser(removeUser.getIdentifier(),
                HAS_CALLER_RESTRICTIONS, mUserRemovalFuture);

        assertUserRemovalResultStatus(getUserRemovalResult(),
                UserRemovalResult.STATUS_SUCCESSFUL_SET_EPHEMERAL);
        assertNoHalUserRemoval();
    }

    @Test
    public void testSwitchUser_nullReceiver() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);

        assertThrows(NullPointerException.class,
                () -> switchUser(mAdminUser.getIdentifier(), mAsyncCallTimeoutMs, null));
    }

    @Test
    public void testSwitchUser_nonExistingTarget() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mCarUserService
                .switchUser(NON_EXISTING_USER, mAsyncCallTimeoutMs, mUserSwitchFuture));
    }

    @Test
    public void testSwitchUser_noUserSwitchability() throws Exception {
        UserHandle currentUser = mAdminUser;
        mockExistingUsersAndCurrentUser(mExistingUsers, currentUser);
        doReturn(UserManager.SWITCHABILITY_STATUS_SYSTEM_USER_LOCKED).when(mMockedUserManager)
                .getUserSwitchability();

        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_NOT_SWITCHABLE);
    }

    @Test
    public void testSwitchUser_targetSameAsCurrentUser() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);

        switchUser(mAdminUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_OK_USER_ALREADY_IN_FOREGROUND);

        verifyNoUserSwitch();
    }

    @Test
    public void testSwitchUser_halNotSupported_success() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mockUserHalSupported(false);
        mockAmSwitchUser(mMockedActivityManager, mRegularUser, true);

        switchUser(mRegularUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);
        verify(mUserHal, never()).switchUser(any(), anyInt(), any());

        // update current user due to successful user switch
        mockCurrentUser(mRegularUser);
        sendUserUnlockedEvent(mRegularUser.getIdentifier());
        assertNoHalUserSwitch();
        assertNoPostSwitch();
    }

    @Test
    public void testSwitchUser_halNotSupported_failure() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mockUserHalSupported(false);

        switchUser(mRegularUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_ANDROID_FAILURE);
        assertNoHalUserSwitch();
    }

    @Test
    public void testSwitchUser_HalSuccessAndroidSuccess() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mGuestUser, true);

        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        assertThat(getUserSwitchResult().getStatus()).isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);

        // update current user due to successful user switch
        mockCurrentUser(mGuestUser);
        sendUserUnlockedEvent(mGuestUser.getIdentifier());
        assertPostSwitch(requestId, mGuestUser.getIdentifier(), mGuestUser.getIdentifier());
    }

    @Test
    public void testSwitchUser_HalSuccessAndroidFailure() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mGuestUser, false);

        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_ANDROID_FAILURE);
        assertPostSwitch(requestId, mAdminUser.getIdentifier(), mGuestUser.getIdentifier());
    }

    @Test
    public void testSwitchUser_HalFailure() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mSwitchUserResponse.status = SwitchUserStatus.FAILURE;
        mSwitchUserResponse.errorMessage = "Error Message";
        mockHalSwitch(mAdminUser.getIdentifier(), mGuestUser, mSwitchUserResponse);

        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        UserSwitchResult result = getUserSwitchResult();
        assertThat(result.getStatus()).isEqualTo(UserSwitchResult.STATUS_HAL_FAILURE);
        assertThat(result.getErrorMessage()).isEqualTo(mSwitchUserResponse.errorMessage);
        verifyNoUserSwitch();
    }

    @Test
    public void testSwitchUser_error_badCallbackStatus() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mockHalSwitch(mAdminUser.getIdentifier(), HalCallback.STATUS_WRONG_HAL_RESPONSE,
                mSwitchUserResponse, mGuestUser);

        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_HAL_INTERNAL_FAILURE);
        verifyNoUserSwitch();
    }

    @Test
    public void testSwitchUser_failUxRestrictedOnInit() throws Exception {
        mockGetUxRestrictions(/*restricted= */ true);
        mockExistingUsersAndCurrentUser(mAdminUser);

        initService();
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_UX_RESTRICTION_FAILURE);
        assertNoHalUserSwitch();
        verifyNoUserSwitch();
    }

    @Test
    public void testSwitchUser_failUxRestrictionsChanged() throws Exception {
        mockGetUxRestrictions(/*restricted= */ false); // not restricted when CarService init()s
        mockExistingUsersAndCurrentUser(mAdminUser);
        mSwitchUserResponse.requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mockHalSwitch(mAdminUser.getIdentifier(), mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mGuestUser, true);

        // Should be ok first time...
        ICarUxRestrictionsChangeListener listener = initService();
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);
        assertThat(getUserSwitchResult().getStatus()).isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);

        // ...but then fail after the state changed
        mockCurrentUser(mGuestUser);
        updateUxRestrictions(listener, /* restricted= */ true); // changed state
        switchUser(mAdminUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture2);
        assertThat(getUserSwitchResult2().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_UX_RESTRICTION_FAILURE);

        // Verify only initial call succeeded (if second was also called the mocks, verify() would
        // fail because it was called more than once()
        assertHalSwitchAnyUser();
        verifyAnyUserSwitch();
    }

    @Test
    public void testSwitchUser_multipleCallsDifferentUser_beforeFirstUserUnlocked()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mGuestUser, true);
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        int newRequestId = 43;
        SwitchUserResponse switchUserResponse = new SwitchUserResponse();
        switchUserResponse.status = SwitchUserStatus.SUCCESS;
        switchUserResponse.requestId = newRequestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mRegularUser, switchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mRegularUser, true);
        switchUser(mRegularUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture2);

        assertThat(getUserSwitchResult().getStatus()).isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);
        assertThat(getUserSwitchResult2().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);
        assertNoPostSwitch();
        assertHalSwitch(mAdminUser.getIdentifier(), mGuestUser.getIdentifier());
        assertHalSwitch(mAdminUser.getIdentifier(), mRegularUser.getIdentifier());
    }

    @Test
    public void testSwitchUser_multipleCallsDifferentUser_beforeFirstUserUnlock_abandonFirstCall()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mGuestUser, true);
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        int newRequestId = 43;
        SwitchUserResponse switchUserResponse = new SwitchUserResponse();
        switchUserResponse.status = SwitchUserStatus.SUCCESS;
        switchUserResponse.requestId = newRequestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mRegularUser, switchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mRegularUser, true);
        switchUser(mRegularUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture2);
        mockCurrentUser(mRegularUser);
        sendUserUnlockedEvent(mRegularUser.getIdentifier());

        assertThat(getUserSwitchResult2().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);
        assertPostSwitch(newRequestId, mRegularUser.getIdentifier(), mRegularUser.getIdentifier());
        assertHalSwitch(mAdminUser.getIdentifier(), mGuestUser.getIdentifier());
        assertHalSwitch(mAdminUser.getIdentifier(), mRegularUser.getIdentifier());
    }

    @Test
    public void testSwitchUser_multipleCallsDifferentUser_beforeHALResponded()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        int newRequestId = 43;
        SwitchUserResponse switchUserResponse = new SwitchUserResponse();
        switchUserResponse.status = SwitchUserStatus.SUCCESS;
        switchUserResponse.requestId = newRequestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mRegularUser, switchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mRegularUser, true);
        switchUser(mRegularUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture2);

        assertThat(getUserSwitchResult2().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);
        assertNoPostSwitch();
        assertHalSwitch(mAdminUser.getIdentifier(), mGuestUser.getIdentifier());
        assertHalSwitch(mAdminUser.getIdentifier(), mRegularUser.getIdentifier());
    }

    @Test
    public void testSwitchUser_multipleCallsDifferentUser_beforeHALResponded_abandonFirstCall()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        int newRequestId = 43;
        SwitchUserResponse switchUserResponse = new SwitchUserResponse();
        switchUserResponse.status = SwitchUserStatus.SUCCESS;
        switchUserResponse.requestId = newRequestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mRegularUser, switchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mRegularUser, true);
        switchUser(mRegularUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture2);

        mockCurrentUser(mRegularUser);
        sendUserUnlockedEvent(mRegularUser.getIdentifier());

        assertThat(getUserSwitchResult2().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);
        assertPostSwitch(newRequestId, mRegularUser.getIdentifier(), mRegularUser.getIdentifier());
        assertHalSwitch(mAdminUser.getIdentifier(), mGuestUser.getIdentifier());
        assertHalSwitch(mAdminUser.getIdentifier(), mRegularUser.getIdentifier());
    }

    @Test
    public void testSwitchUser_multipleCallsDifferentUser_HALRespondedLate_abandonFirstCall()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        BlockingAnswer<Void> blockingAnswer = mockHalSwitchLateResponse(mAdminUser.getIdentifier(),
                mGuestUser, mSwitchUserResponse);
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        int newRequestId = 43;
        SwitchUserResponse switchUserResponse = new SwitchUserResponse();
        switchUserResponse.status = SwitchUserStatus.SUCCESS;
        switchUserResponse.requestId = newRequestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mRegularUser, switchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mRegularUser, true);
        switchUser(mRegularUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture2);

        mockCurrentUser(mRegularUser);
        sendUserUnlockedEvent(mRegularUser.getIdentifier());
        blockingAnswer.unblock();

        UserSwitchResult result = getUserSwitchResult();
        assertThat(result.getStatus())
                .isEqualTo(UserSwitchResult.STATUS_TARGET_USER_ABANDONED_DUE_TO_A_NEW_REQUEST);
        assertThat(getUserSwitchResult2().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);
        assertPostSwitch(newRequestId, mRegularUser.getIdentifier(), mRegularUser.getIdentifier());
        assertHalSwitch(mAdminUser.getIdentifier(), mGuestUser.getIdentifier());
        assertHalSwitch(mAdminUser.getIdentifier(), mRegularUser.getIdentifier());
    }

    @Test
    public void testSwitchUser_multipleCallsSameUser_beforeHALResponded() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;

        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture2);

        assertThat(getUserSwitchResult2().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_TARGET_USER_ALREADY_BEING_SWITCHED_TO);
        assertNoPostSwitch();
        assertHalSwitch(mAdminUser.getIdentifier(), mGuestUser.getIdentifier());
    }

    @Test
    public void testSwitchUser_multipleCallsSameUser_beforeFirstUserUnlocked() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mGuestUser, true);

        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);
        // calling another user switch before unlock
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture2);

        assertThat(getUserSwitchResult().getStatus()).isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);
        assertThat(getUserSwitchResult2().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_TARGET_USER_ALREADY_BEING_SWITCHED_TO);
        assertNoPostSwitch();
        assertHalSwitch(mAdminUser.getIdentifier(), mGuestUser.getIdentifier());
    }

    @Test
    public void testSwitchUser_multipleCallsSameUser_beforeFirstUserUnlocked_noAffectOnFirstCall()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mGuestUser, true);

        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);
        int newRequestId = 43;
        mSwitchUserResponse.requestId = newRequestId;

        // calling another user switch before unlock
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture2);
        mockCurrentUser(mGuestUser);
        sendUserUnlockedEvent(mGuestUser.getIdentifier());

        assertThat(getUserSwitchResult().getStatus()).isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);
        assertThat(getUserSwitchResult2().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_TARGET_USER_ALREADY_BEING_SWITCHED_TO);
        assertPostSwitch(requestId, mGuestUser.getIdentifier(), mGuestUser.getIdentifier());
        assertHalSwitch(mAdminUser.getIdentifier(), mGuestUser.getIdentifier());
    }

    @Test
    public void testSwitchUser_InvalidPermission() throws Exception {
        mockManageUsersPermission(android.Manifest.permission.MANAGE_USERS, false);
        assertThrows(SecurityException.class, () -> mCarUserService
                .switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture));
    }

    @Test
    public void testLegacyUserSwitch_ok() throws Exception {
        mockExistingUsers(mExistingUsers);
        int targetUserId = mRegularUser.getIdentifier();
        int sourceUserId = mAdminUser.getIdentifier();

        mockCallerUid(Binder.getCallingUid(), true);
        mCarUserService.setUserSwitchUiCallback(mSwitchUserUiReceiver);

        sendUserSwitchingEvent(sourceUserId, targetUserId);

        verify(mUserHal).legacyUserSwitch(
                isSwitchUserRequest(/* requestId= */ 0, sourceUserId, targetUserId));
        verify(mSwitchUserUiReceiver).send(targetUserId, null);
    }

    @Test
    public void testLegacyUserSwitch_notCalledAfterNormalSwitch() throws Exception {
        // Arrange - emulate normal switch
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mGuestUser, true);
        int targetUserId = mGuestUser.getIdentifier();
        mockCallerUid(Binder.getCallingUid(), true);
        mCarUserService.setUserSwitchUiCallback(mSwitchUserUiReceiver);
        switchUser(targetUserId, mAsyncCallTimeoutMs, mUserSwitchFuture);

        // Act - trigger legacy switch
        sendUserSwitchingEvent(mAdminUser.getIdentifier(), targetUserId);

        // Assert
        verify(mUserHal, never()).legacyUserSwitch(any());
        verify(mSwitchUserUiReceiver).send(targetUserId, null);
    }

    @Test
    public void testSetSwitchUserUI_receiverSetAndCalled() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int callerId = Binder.getCallingUid();
        mockCallerUid(callerId, true);
        int requestId = 42;
        mCarUserService.setUserSwitchUiCallback(mSwitchUserUiReceiver);

        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mMockedActivityManager, mGuestUser, true);

        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        // update current user due to successful user switch
        verify(mSwitchUserUiReceiver).send(mGuestUser.getIdentifier(), null);
    }

    @Test
    public void testSetSwitchUserUI_nonCarSysUiCaller() throws Exception {
        int callerId = Binder.getCallingUid();
        mockCallerUid(callerId, false);

        assertThrows(SecurityException.class,
                () -> mCarUserService.setUserSwitchUiCallback(mSwitchUserUiReceiver));
    }

    @Test
    public void testSwitchUser_OEMRequest_success() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mockAmSwitchUser(mMockedActivityManager, mRegularUser, true);
        int requestId = -1;

        mCarUserService.switchAndroidUserFromHal(requestId, mRegularUser.getIdentifier());
        mockCurrentUser(mRegularUser);
        sendUserUnlockedEvent(mRegularUser.getIdentifier());

        assertPostSwitch(requestId, mRegularUser.getIdentifier(), mRegularUser.getIdentifier());
    }

    @Test
    public void testSwitchUser_OEMRequest_failure() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mockAmSwitchUser(mMockedActivityManager, mRegularUser, false);
        int requestId = -1;

        mCarUserService.switchAndroidUserFromHal(requestId, mRegularUser.getIdentifier());

        assertPostSwitch(requestId, mAdminUser.getIdentifier(), mRegularUser.getIdentifier());
    }

    @Test
    public void testCreateUser_nullType() throws Exception {
        assertThrows(NullPointerException.class, () -> mCarUserService
                .createUser("dude", null, 108, mAsyncCallTimeoutMs, mUserCreationFuture,
                        NO_CALLER_RESTRICTIONS));
    }

    @Test
    public void testCreateUser_nullReceiver() throws Exception {
        assertThrows(NullPointerException.class, () -> mCarUserService
                .createUser("dude", "TypeONegative", 108, mAsyncCallTimeoutMs, null,
                        NO_CALLER_RESTRICTIONS));
    }

    @Test
    public void testCreateUser_umCreateReturnsNull() throws Exception {
        // No need to mock um.createUser() to return null

        createUser("dude", "TypeONegative", 108, mAsyncCallTimeoutMs,
                mUserCreationFuture, NO_CALLER_RESTRICTIONS);

        UserCreationResult result = getUserCreationResult();
        assertThat(result.getStatus()).isEqualTo(UserCreationResult.STATUS_ANDROID_FAILURE);
        assertThat(result.getUser()).isNull();
        assertThat(result.getErrorMessage()).isNull();
        assertNoHalUserCreation();
        verifyNoUserRemoved();
        assertNoHalUserRemoval();
    }

    @Test
    public void testCreateUser_umCreateThrowsException() throws Exception {
        mockUmCreateUser(mMockedUserManager, "dude", "TypeONegative", 108,
                new RuntimeException("D'OH!"));

        createUser("dude", "TypeONegative", 108, mAsyncCallTimeoutMs,
                mUserCreationFuture, NO_CALLER_RESTRICTIONS);

        UserCreationResult result = getUserCreationResult();
        assertThat(result.getStatus()).isEqualTo(UserCreationResult.STATUS_ANDROID_FAILURE);
        assertThat(result.getUser()).isNull();
        assertThat(result.getErrorMessage()).isNull();
        assertNoHalUserCreation();
        verifyNoUserRemoved();
        assertNoHalUserRemoval();
    }

    @Test
    public void testCreateUser_internalHalFailure() throws Exception {
        UserHandle newUser = UserHandle.of(42);
        mockUmCreateUser(mMockedUserManager, "dude", "TypeONegative", 108, newUser);
        mockHalCreateUser(HalCallback.STATUS_INVALID, /* not_used_status= */ -1);
        mockRemoveUser(newUser);

        createUser("dude", "TypeONegative", 108, mAsyncCallTimeoutMs,
                mUserCreationFuture, NO_CALLER_RESTRICTIONS);

        UserCreationResult result = getUserCreationResult();
        assertThat(result.getStatus()).isEqualTo(UserCreationResult.STATUS_HAL_INTERNAL_FAILURE);
        assertThat(result.getUser()).isNull();
        assertThat(result.getErrorMessage()).isNull();
        verifyUserRemoved(newUser.getIdentifier());
        assertNoHalUserRemoval();
    }

    @Test
    public void testCreateUser_halFailure() throws Exception {
        UserHandle newUser = UserHandle.of(42);
        mockUmCreateUser(mMockedUserManager, "dude", "TypeONegative", 108, newUser);
        mockHalCreateUser(HalCallback.STATUS_OK, CreateUserStatus.FAILURE);
        mockRemoveUser(newUser);

        createUser("dude", "TypeONegative", 108, mAsyncCallTimeoutMs,
                mUserCreationFuture, NO_CALLER_RESTRICTIONS);

        UserCreationResult result = getUserCreationResult();
        assertThat(result.getStatus()).isEqualTo(UserCreationResult.STATUS_HAL_FAILURE);
        assertThat(result.getUser()).isNull();
        assertThat(result.getErrorMessage()).isNull();

        verifyUserRemoved(newUser.getIdentifier());
        assertNoHalUserRemoval();
    }

    @Test
    public void testCreateUser_halServiceThrowsRuntimeException() throws Exception {
        UserHandle newUser = UserHandle.of(42);
        mockUmCreateUser(mMockedUserManager, "dude", "TypeONegative", 108, newUser);
        mockHalCreateUserThrowsRuntimeException();
        mockRemoveUser(newUser);

        createUser("dude", "TypeONegative", 108, mAsyncCallTimeoutMs,
                mUserCreationFuture, NO_CALLER_RESTRICTIONS);

        UserCreationResult result = getUserCreationResult();
        assertThat(result.getStatus()).isEqualTo(UserCreationResult.STATUS_HAL_INTERNAL_FAILURE);
        assertThat(result.getUser()).isNull();
        assertThat(result.getErrorMessage()).isNull();

        verifyUserRemoved(newUser.getIdentifier());
        assertNoHalUserRemoval();
    }

    @Test
    public void testCreateUser_halNotSupported_success() throws Exception {
        mockUserHalSupported(false);
        mockExistingUsersAndCurrentUser(mAdminUser);
        int userId = mRegularUser.getIdentifier();
        mockUmCreateUser(mMockedUserManager, "dude", UserManager.USER_TYPE_FULL_SECONDARY,
                UserManagerHelper.FLAG_EPHEMERAL, UserHandle.of(userId));

        createUser("dude", UserManager.USER_TYPE_FULL_SECONDARY,
                UserManagerHelper.FLAG_EPHEMERAL, mAsyncCallTimeoutMs, mUserCreationFuture,
                NO_CALLER_RESTRICTIONS);

        UserCreationResult result = getUserCreationResult();
        assertThat(result.getStatus()).isEqualTo(UserCreationResult.STATUS_SUCCESSFUL);
        assertNoHalUserCreation();
        assertNoHalUserRemoval();
    }

    @Test
    public void testCreateUser_success() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int userId = 300;
        UserHandle user = expectEphemeralUserExists(mMockedUserHandleHelper, userId);

        mockUmCreateUser(mMockedUserManager, "dude", UserManager.USER_TYPE_FULL_SECONDARY,
                UserManagerHelper.FLAG_EPHEMERAL, UserHandle.of(userId));
        ArgumentCaptor<CreateUserRequest> requestCaptor =
                mockHalCreateUser(HalCallback.STATUS_OK, CreateUserStatus.SUCCESS);

        createUser("dude", UserManager.USER_TYPE_FULL_SECONDARY,
                UserManagerHelper.FLAG_EPHEMERAL, mAsyncCallTimeoutMs, mUserCreationFuture,
                NO_CALLER_RESTRICTIONS);

        // Assert request
        CreateUserRequest request = requestCaptor.getValue();
        Log.d(TAG, "createUser() request: " + request);
        assertThat(request.newUserName).isEqualTo("dude");
        assertThat(request.newUserInfo.userId).isEqualTo(userId);
        assertThat(request.newUserInfo.flags).isEqualTo(UserFlags.EPHEMERAL);
        assertDefaultUsersInfo(request.usersInfo, mAdminUser);

        UserCreationResult result = getUserCreationResult();
        assertThat(result.getStatus()).isEqualTo(UserCreationResult.STATUS_SUCCESSFUL);
        assertThat(result.getErrorMessage()).isNull();
        UserHandle newUser = result.getUser();
        assertThat(newUser).isNotNull();
        assertThat(newUser.getIdentifier()).isEqualTo(userId);

        verify(mMockedUserManager, never()).createGuest(any(Context.class), anyString());
        verifyNoUserRemoved();
        assertNoHalUserRemoval();
    }

    @Test
    public void testCreateUser_guest_success() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int userId = mGuestUser.getIdentifier();
        mockUmCreateGuest(mMockedUserManager, "guest", userId);
        ArgumentCaptor<CreateUserRequest> requestCaptor =
                mockHalCreateUser(HalCallback.STATUS_OK, CreateUserStatus.SUCCESS);

        createUser("guest", UserManager.USER_TYPE_FULL_GUEST,
                0, mAsyncCallTimeoutMs, mUserCreationFuture, NO_CALLER_RESTRICTIONS);

        // Assert request
        CreateUserRequest request = requestCaptor.getValue();
        Log.d(TAG, "createUser() request: " + request);
        assertThat(request.newUserName).isEqualTo("guest");
        assertThat(request.newUserInfo.userId).isEqualTo(userId);
        assertThat(request.newUserInfo.flags).isEqualTo(UserFlags.GUEST);
        assertDefaultUsersInfo(request.usersInfo, mAdminUser);

        UserCreationResult result = getUserCreationResult();
        assertThat(result.getStatus()).isEqualTo(UserCreationResult.STATUS_SUCCESSFUL);
        assertThat(result.getErrorMessage()).isNull();
        UserHandle newUser = result.getUser();
        assertThat(newUser).isNotNull();
        assertThat(newUser.getIdentifier()).isEqualTo(userId);

        verify(mMockedUserManager, never()).createUser(anyString(), anyString(), anyInt());
        verifyNoUserRemoved();
        assertNoHalUserRemoval();
    }

    @Test
    public void testCreateUser_guest_failsWithNonZeroFlags() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);

        createUser("guest", UserManager.USER_TYPE_FULL_GUEST,
                UserManagerHelper.FLAG_EPHEMERAL, mAsyncCallTimeoutMs, mUserCreationFuture,
                NO_CALLER_RESTRICTIONS);

        assertInvalidArgumentsFailure();
    }


    @Test
    public void testCreateUser_success_nullName() throws Exception {
        String nullName = null;
        mockExistingUsersAndCurrentUser(mAdminUser);
        int userId = 300;
        UserHandle expectedeUser = expectEphemeralUserExists(mMockedUserHandleHelper, userId);

        mockUmCreateUser(mMockedUserManager, nullName, UserManager.USER_TYPE_FULL_SECONDARY,
                UserManagerHelper.FLAG_EPHEMERAL, expectedeUser);
        ArgumentCaptor<CreateUserRequest> requestCaptor =
                mockHalCreateUser(HalCallback.STATUS_OK, CreateUserStatus.SUCCESS);

        createUser(nullName, UserManager.USER_TYPE_FULL_SECONDARY,
                UserManagerHelper.FLAG_EPHEMERAL, mAsyncCallTimeoutMs, mUserCreationFuture,
                NO_CALLER_RESTRICTIONS);

        // Assert request
        CreateUserRequest request = requestCaptor.getValue();
        Log.d(TAG, "createUser() request: " + request);
        assertThat(request.newUserName).isEmpty();
        assertThat(request.newUserInfo.userId).isEqualTo(userId);
        assertThat(request.newUserInfo.flags).isEqualTo(UserFlags.EPHEMERAL);
        assertDefaultUsersInfo(request.usersInfo, mAdminUser);

        UserCreationResult result = getUserCreationResult();
        assertThat(result.getStatus()).isEqualTo(UserCreationResult.STATUS_SUCCESSFUL);
        assertThat(result.getErrorMessage()).isNull();

        UserHandle newUser = result.getUser();
        assertThat(newUser).isNotNull();
        assertThat(newUser.getIdentifier()).isEqualTo(userId);

        verifyNoUserRemoved();
        verify(mUserHal, never()).removeUser(any());
    }

    @Test
    public void testCreateUser_binderMethod() {
        CarUserService spy = spy(mCarUserService);
        AndroidFuture<UserCreationResult> receiver = new AndroidFuture<>();
        int flags = 42;
        int timeoutMs = 108;

        spy.createUser("name", "type", flags, timeoutMs, receiver);

        verify(spy).createUser("name", "type", flags, timeoutMs, receiver,
                NO_CALLER_RESTRICTIONS);
    }

    @Test
    public void testCreateUserWithRestrictions_nonAdminCreatingAdmin() throws Exception {
        UserHandle currentUser = mRegularUser;
        mockExistingUsersAndCurrentUser(currentUser);
        mockGetCallingUserHandle(currentUser.getIdentifier());

        createUser("name", UserManager.USER_TYPE_FULL_SECONDARY,
                UserManagerHelper.FLAG_ADMIN, mAsyncCallTimeoutMs,
                mUserCreationFuture, HAS_CALLER_RESTRICTIONS);
        assertInvalidArgumentsFailure();
    }


    @Test
    public void testCreateUserWithRestrictions_invalidTypes() throws Exception {
        createUserWithRestrictionsInvalidTypes(UserManager.USER_TYPE_FULL_DEMO);
        createUserWithRestrictionsInvalidTypes(UserManager.USER_TYPE_FULL_RESTRICTED);
        createUserWithRestrictionsInvalidTypes(UserManager.USER_TYPE_FULL_SYSTEM);
        createUserWithRestrictionsInvalidTypes(UserManager.USER_TYPE_PROFILE_MANAGED);
        createUserWithRestrictionsInvalidTypes(UserManager.USER_TYPE_SYSTEM_HEADLESS);
    }

    @Test
    public void testCreateUserWithRestrictions_invalidFlags() throws Exception {
        createUserWithRestrictionsInvalidTypes(UserManagerHelper.FLAG_DEMO);
        createUserWithRestrictionsInvalidTypes(UserManagerHelper.FLAG_DISABLED);
        createUserWithRestrictionsInvalidTypes(UserManagerHelper.FLAG_EPHEMERAL);
        createUserWithRestrictionsInvalidTypes(UserManagerHelper.FLAG_FULL);
        createUserWithRestrictionsInvalidTypes(UserManagerHelper.FLAG_INITIALIZED);
        createUserWithRestrictionsInvalidTypes(UserManagerHelper.FLAG_MANAGED_PROFILE);
        createUserWithRestrictionsInvalidTypes(UserManagerHelper.FLAG_PRIMARY);
        createUserWithRestrictionsInvalidTypes(UserManagerHelper.FLAG_QUIET_MODE);
        createUserWithRestrictionsInvalidTypes(UserManagerHelper.FLAG_RESTRICTED);
        createUserWithRestrictionsInvalidTypes(UserManagerHelper.FLAG_SYSTEM);
    }

    @Test
    @ExpectWtf
    public void testCreateUserEvenWhenDisallowed_noHelper() throws Exception {
        UserHandle userHandle = mCarUserService.createUserEvenWhenDisallowed("name",
                UserManager.USER_TYPE_FULL_SECONDARY, UserManagerHelper.FLAG_ADMIN);
        waitForHandlerThreadToFinish();

        assertThat(userHandle).isNull();
    }

    @Test
    public void testCreateUserEvenWhenDisallowed_remoteException() throws Exception {
        mCarUserService.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.createUserEvenWhenDisallowed(any(), any(), anyInt()))
                .thenThrow(new RemoteException("D'OH!"));

        UserHandle userHandle = mCarUserService.createUserEvenWhenDisallowed("name",
                UserManager.USER_TYPE_FULL_SECONDARY, UserManagerHelper.FLAG_ADMIN);
        waitForHandlerThreadToFinish();

        assertThat(userHandle).isNull();
    }

    @Test
    public void testCreateUserEvenWhenDisallowed_success() throws Exception {
        UserHandle user = UserHandle.of(100);
        mCarUserService.setCarServiceHelper(mICarServiceHelper);
        when(mICarServiceHelper.createUserEvenWhenDisallowed("name",
                UserManager.USER_TYPE_FULL_SECONDARY, UserManagerHelper.FLAG_ADMIN))
                        .thenReturn(user);

        UserHandle actualUser = mCarUserService.createUserEvenWhenDisallowed("name",
                UserManager.USER_TYPE_FULL_SECONDARY, UserManagerHelper.FLAG_ADMIN);
        waitForHandlerThreadToFinish();

        assertThat(actualUser).isNotNull();
        assertThat(actualUser.getIdentifier()).isEqualTo(100);
    }

    @Test
    public void testStartUserInBackground_success() throws Exception {
        mockCurrentUser(mRegularUser);
        UserHandle newUser = expectRegularUserExists(mMockedUserHandleHelper, 101);
        mockAmStartUserInBackground(newUser.getIdentifier(), true);

        AndroidFuture<UserStartResult> userStartResult = new AndroidFuture<>();
        startUserInBackground(newUser.getIdentifier(), userStartResult);

        assertThat(getResult(userStartResult).getStatus())
                .isEqualTo(UserStartResult.STATUS_SUCCESSFUL);
        assertThat(getResult(userStartResult).isSuccess()).isTrue();
    }

    @Test
    public void testStartUserInBackground_permissionDenied() throws Exception {
        int userId = 101;
        mockManageUsersPermission(android.Manifest.permission.MANAGE_USERS, false);
        mockManageUsersPermission(android.Manifest.permission.CREATE_USERS, false);

        AndroidFuture<UserStartResult> userStartResult = new AndroidFuture<>();
        assertThrows(SecurityException.class,
                () -> startUserInBackground(userId, userStartResult));
    }

    @Test
    public void testStartUserInBackground_fail() throws Exception {
        int userId = 101;
        UserHandle user = expectRegularUserExists(mMockedUserHandleHelper, userId);
        mockCurrentUser(mRegularUser);
        mockAmStartUserInBackground(userId, false);

        AndroidFuture<UserStartResult> userStartResult = new AndroidFuture<>();
        startUserInBackground(userId, userStartResult);

        assertThat(getResult(userStartResult).getStatus())
                .isEqualTo(UserStartResult.STATUS_ANDROID_FAILURE);
        assertThat(getResult(userStartResult).isSuccess()).isFalse();
    }

    @Test
    public void testStartUserInBackground_currentUser() throws Exception {
        UserHandle newUser = expectRegularUserExists(mMockedUserHandleHelper, 101);
        mockGetCurrentUser(newUser.getIdentifier());
        mockAmStartUserInBackground(newUser.getIdentifier(), true);

        AndroidFuture<UserStartResult> userStartResult = new AndroidFuture<>();
        startUserInBackground(newUser.getIdentifier(), userStartResult);

        assertThat(getResult(userStartResult).getStatus())
                .isEqualTo(UserStartResult.STATUS_SUCCESSFUL_USER_IS_CURRENT_USER);
        assertThat(getResult(userStartResult).isSuccess()).isTrue();
    }

    @Test
    public void testStartUserInBackground_userDoesNotExist() throws Exception {
        int userId = 101;
        mockCurrentUser(mRegularUser);
        mockAmStartUserInBackground(userId, true);

        AndroidFuture<UserStartResult> userStartResult = new AndroidFuture<>();
        startUserInBackground(userId, userStartResult);

        assertThat(getResult(userStartResult).getStatus())
                .isEqualTo(UserStartResult.STATUS_USER_DOES_NOT_EXIST);
        assertThat(getResult(userStartResult).isSuccess()).isFalse();
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
        mockCurrentUserForBinderCalls();

        UserIdentificationAssociationResponse response = mCarUserService
                .getUserIdentificationAssociation(new int[] { 108 });

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getValues()).isNull();
        assertThat(response.getErrorMessage()).isNull();
    }

    @Test
    public void testGetUserIdentificationAssociation_halNotSupported() throws Exception {
        mockUserHalUserAssociationSupported(false);

        UserIdentificationAssociationResponse response = mCarUserService
                .getUserIdentificationAssociation(new int[] { });

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getValues()).isNull();
        assertThat(response.getErrorMessage()).isEqualTo(CarUserService.VEHICLE_HAL_NOT_SUPPORTED);
        verify(mUserHal, never()).getUserAssociation(any());
    }

    @Test
    public void testGetUserIdentificationAssociation_ok() throws Exception {
        UserHandle currentUser = mockCurrentUserForBinderCalls();

        int[] types = new int[] { 1, 2, 3 };
        int[] values = new int[] { 10, 20, 30 };
        mockHalGetUserIdentificationAssociation(currentUser, types, values, "D'OH!");

        UserIdentificationAssociationResponse response = mCarUserService
                .getUserIdentificationAssociation(types);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getValues()).asList().containsAtLeast(10, 20, 30).inOrder();
        assertThat(response.getErrorMessage()).isEqualTo("D'OH!");
    }

    @Test
    public void testSetUserIdentificationAssociation_nullTypes() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mCarUserService
                .setUserIdentificationAssociation(mAsyncCallTimeoutMs,
                        null, new int[] {42}, mUserAssociationRespFuture));
    }

    @Test
    public void testSetUserIdentificationAssociation_emptyTypes() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mCarUserService
                .setUserIdentificationAssociation(mAsyncCallTimeoutMs,
                        new int[0], new int[] {42}, mUserAssociationRespFuture));
    }

    @Test
    public void testSetUserIdentificationAssociation_nullValues() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mCarUserService
                .setUserIdentificationAssociation(mAsyncCallTimeoutMs,
                        new int[] {42}, null, mUserAssociationRespFuture));
    }
    @Test
    public void testSetUserIdentificationAssociation_sizeMismatch() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mCarUserService
                .setUserIdentificationAssociation(mAsyncCallTimeoutMs,
                        new int[] {1}, new int[] {2, 2}, mUserAssociationRespFuture));
    }

    @Test
    public void testSetUserIdentificationAssociation_nullFuture() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mCarUserService
                .setUserIdentificationAssociation(mAsyncCallTimeoutMs,
                        new int[] {42}, new int[] {42}, null));
    }

    @Test
    public void testSetUserIdentificationAssociation_noPermission() throws Exception {
        mockManageUsersPermission(android.Manifest.permission.MANAGE_USERS, false);
        assertThrows(SecurityException.class, () -> mCarUserService
                .setUserIdentificationAssociation(mAsyncCallTimeoutMs,
                        new int[] {42}, new int[] {42}, mUserAssociationRespFuture));
    }

    @Test
    public void testSetUserIdentificationAssociation_noCurrentUser() throws Exception {
        // Should fail because we're not mocking UserManager.getUserInfo() to set the flag
        assertThrows(IllegalArgumentException.class, () -> mCarUserService
                .setUserIdentificationAssociation(mAsyncCallTimeoutMs,
                        new int[] {42}, new int[] {42}, mUserAssociationRespFuture));
    }

    @Test
    public void testSetUserIdentificationAssociation_halNotSupported() throws Exception {
        int[] types = new int[] { 1, 2, 3 };
        int[] values = new int[] { 10, 20, 30 };
        mockUserHalUserAssociationSupported(false);

        mCarUserService.setUserIdentificationAssociation(mAsyncCallTimeoutMs, types, values,
                mUserAssociationRespFuture);
        UserIdentificationAssociationResponse response = getUserAssociationRespResult();

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getValues()).isNull();
        assertThat(response.getErrorMessage()).isEqualTo(CarUserService.VEHICLE_HAL_NOT_SUPPORTED);
        verify(mUserHal, never()).setUserAssociation(anyInt(), any(), any());
    }

    @Test
    public void testSetUserIdentificationAssociation_halFailedWithErrorMessage() throws Exception {
        mockCurrentUserForBinderCalls();
        mockHalSetUserIdentificationAssociationFailure("D'OH!");
        int[] types = new int[] { 1, 2, 3 };
        int[] values = new int[] { 10, 20, 30 };
        mCarUserService.setUserIdentificationAssociation(mAsyncCallTimeoutMs, types, values,
                mUserAssociationRespFuture);

        UserIdentificationAssociationResponse response = getUserAssociationRespResult();

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getValues()).isNull();
        assertThat(response.getErrorMessage()).isEqualTo("D'OH!");

    }

    @Test
    public void testSetUserIdentificationAssociation_halFailedWithoutErrorMessage()
            throws Exception {
        mockCurrentUserForBinderCalls();
        mockHalSetUserIdentificationAssociationFailure(/* errorMessage= */ null);
        int[] types = new int[] { 1, 2, 3 };
        int[] values = new int[] { 10, 20, 30 };
        mCarUserService.setUserIdentificationAssociation(mAsyncCallTimeoutMs, types, values,
                mUserAssociationRespFuture);

        UserIdentificationAssociationResponse response = getUserAssociationRespResult();

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getValues()).isNull();
        assertThat(response.getErrorMessage()).isNull();
    }

    @Test
    public void testSetUserIdentificationAssociation_ok() throws Exception {
        UserHandle currentUser = mockCurrentUserForBinderCalls();

        int[] types = new int[] { 1, 2, 3 };
        int[] values = new int[] { 10, 20, 30 };
        mockHalSetUserIdentificationAssociationSuccess(currentUser, types, values, "D'OH!");

        mCarUserService.setUserIdentificationAssociation(mAsyncCallTimeoutMs, types, values,
                mUserAssociationRespFuture);

        UserIdentificationAssociationResponse response = getUserAssociationRespResult();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getValues()).asList().containsAtLeast(10, 20, 30).inOrder();
        assertThat(response.getErrorMessage()).isEqualTo("D'OH!");
    }

    @Test
    public void testInitBootUser_halNotSupported() {
        mockUserHalSupported(false);

        mCarUserService.initBootUser();
        waitForHandlerThreadToFinish();

        verify(mInitialUserSetter).set(argThat((info) -> {
            return info.type == InitialUserSetter.TYPE_DEFAULT_BEHAVIOR
                    && info.userLocales == null;
        }));
    }

    @Test
    public void testInitBootUser_halNullResponse() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mockHalGetInitialInfo(mAdminUser.getIdentifier(), null);

        mCarUserService.initBootUser();
        waitForHandlerThreadToFinish();

        verify(mInitialUserSetter).set(argThat((info) -> {
            return info.type == InitialUserSetter.TYPE_DEFAULT_BEHAVIOR;
        }));
    }

    @Test
    public void testInitBootUser_halDefaultResponse() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mGetUserInfoResponse.action = InitialUserInfoResponseAction.DEFAULT;
        mGetUserInfoResponse.userLocales = "LOL";
        mockHalGetInitialInfo(mAdminUser.getIdentifier(), mGetUserInfoResponse);

        mCarUserService.initBootUser();
        waitForHandlerThreadToFinish();

        verify(mInitialUserSetter).set(argThat((info) -> {
            return info.type == InitialUserSetter.TYPE_DEFAULT_BEHAVIOR
                    && info.userLocales.equals("LOL");
        }));
    }

    @Test
    public void testInitBootUser_halSwitchResponse() throws Exception {
        int switchUserId = mGuestUser.getIdentifier();
        mockExistingUsersAndCurrentUser(mAdminUser);
        mGetUserInfoResponse.action = InitialUserInfoResponseAction.SWITCH;
        mGetUserInfoResponse.userToSwitchOrCreate.userId = switchUserId;
        mockHalGetInitialInfo(mAdminUser.getIdentifier(), mGetUserInfoResponse);

        mCarUserService.initBootUser();
        waitForHandlerThreadToFinish();

        verify(mInitialUserSetter).set(argThat((info) -> {
            return info.type == InitialUserSetter.TYPE_SWITCH
                    && info.switchUserId == switchUserId;
        }));
    }

    @Test
    public void testInitBootUser_halCreateResponse() throws Exception {
        int newUserFlags = 42;
        String newUserName = "TheDude";
        mockExistingUsersAndCurrentUser(mAdminUser);
        mGetUserInfoResponse.action = InitialUserInfoResponseAction.CREATE;
        mGetUserInfoResponse.userToSwitchOrCreate.flags = newUserFlags;
        mGetUserInfoResponse.userNameToCreate = newUserName;
        mockHalGetInitialInfo(mAdminUser.getIdentifier(), mGetUserInfoResponse);

        mCarUserService.initBootUser();
        waitForHandlerThreadToFinish();

        verify(mInitialUserSetter).set(argThat((info) -> {
            return info.type == InitialUserSetter.TYPE_CREATE
                    && info.newUserFlags == newUserFlags
                    && info.newUserName == newUserName;
        }));
    }

    @Test
    public void testUpdatePreCreatedUser_success() throws Exception {
        mCarUserService.updatePreCreatedUsers();
        waitForHandlerThreadToFinish();

        verify(mUserPreCreator).managePreCreatedUsers();
    }

    @Test
    public void testOnSuspend_replace() throws Exception {
        mockExistingUsersAndCurrentUser(mGuestUser);
        when(mInitialUserSetter.canReplaceGuestUser(any())).thenReturn(true);

        CarUserService service = newCarUserService(/* switchGuestUserBeforeGoingSleep= */ true);
        service.onSuspend();
        waitForHandlerThreadToFinish();

        verify(mInitialUserSetter).set(argThat((info) -> {
            return info.type == InitialUserSetter.TYPE_REPLACE_GUEST;
        }));
        verify(mUserPreCreator).managePreCreatedUsers();
    }

    @Test
    public void testOnSuspend_notReplace() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);

        CarUserService service = newCarUserService(/* switchGuestUserBeforeGoingSleep= */ true);
        service.onSuspend();
        waitForHandlerThreadToFinish();

        verify(mInitialUserSetter, never()).set(any());
        verify(mUserPreCreator).managePreCreatedUsers();
    }

    @Test
    public void testOnResume_halNullResponse_replaceTrue() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mockHalGetInitialInfo(mAdminUser.getIdentifier(), null);

        mCarUserService.onResume();
        waitForHandlerThreadToFinish();

        verify(mInitialUserSetter).set(argThat((info) -> {
            return info.type == InitialUserSetter.TYPE_DEFAULT_BEHAVIOR
                    && info.replaceGuest;
        }));
    }

    @Test
    public void testOnResume_halDefaultResponse_replaceGuest()
            throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mGetUserInfoResponse.action = InitialUserInfoResponseAction.DEFAULT;
        mGetUserInfoResponse.userLocales = "LOL";
        mockHalGetInitialInfo(mAdminUser.getIdentifier(), mGetUserInfoResponse);

        mCarUserService.onResume();
        waitForHandlerThreadToFinish();

        verify(mInitialUserSetter).set(argThat((info) -> {
            return info.type == InitialUserSetter.TYPE_DEFAULT_BEHAVIOR && info.replaceGuest
                    && info.userLocales.equals("LOL");
        }));
    }

    @Test
    public void testOnResume_halSwitchResponse_replaceGuest()
            throws Exception {
        int switchUserId = mGuestUser.getIdentifier();
        mockExistingUsersAndCurrentUser(mAdminUser);
        mGetUserInfoResponse.action = InitialUserInfoResponseAction.SWITCH;
        mGetUserInfoResponse.userToSwitchOrCreate.userId = switchUserId;
        mockHalGetInitialInfo(mAdminUser.getIdentifier(), mGetUserInfoResponse);

        mCarUserService.onResume();
        waitForHandlerThreadToFinish();

        verify(mInitialUserSetter).set(argThat((info) -> {
            return info.type == InitialUserSetter.TYPE_SWITCH && info.replaceGuest
                    && info.switchUserId == switchUserId;
        }));
    }

    @Test
    public void testOnResume_halDisabled()
            throws Exception {
        mockUserHalSupported(false);

        mCarUserService.onResume();
        waitForHandlerThreadToFinish();

        verify(mInitialUserSetter).set(argThat((info) -> {
            return info.type == InitialUserSetter.TYPE_DEFAULT_BEHAVIOR && info.replaceGuest;
        }));
    }

    @Test
    public void testInitialUserInfoRequestType_FirstBoot() throws Exception {
        when(mInitialUserSetter.hasInitialUser()).thenReturn(false);
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.isDeviceUpgrading()).thenReturn(true);

        assertThat(mCarUserService.getInitialUserInfoRequestType())
                .isEqualTo(InitialUserInfoRequestType.FIRST_BOOT);
    }

    @Test
    public void testInitialUserInfoRequestType_FirstBootAfterOTA() throws Exception {
        when(mInitialUserSetter.hasInitialUser()).thenReturn(true);
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.isDeviceUpgrading()).thenReturn(true);

        assertThat(mCarUserService.getInitialUserInfoRequestType())
                .isEqualTo(InitialUserInfoRequestType.FIRST_BOOT_AFTER_OTA);
    }

    @Test
    public void testInitialUserInfoRequestType_ColdBoot() throws Exception {
        when(mInitialUserSetter.hasInitialUser()).thenReturn(true);
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.isDeviceUpgrading()).thenReturn(false);

        assertThat(mCarUserService.getInitialUserInfoRequestType())
                .isEqualTo(InitialUserInfoRequestType.COLD_BOOT);
    }

    @Test
    public void testUserOpFlags() {
        userOpFlagTesT(CarUserService.USER_OP_SUCCESS, ActivityManager.USER_OP_SUCCESS);
        userOpFlagTesT(CarUserService.USER_OP_UNKNOWN_USER, ActivityManager.USER_OP_UNKNOWN_USER);
        userOpFlagTesT(CarUserService.USER_OP_IS_CURRENT, ActivityManager.USER_OP_IS_CURRENT);
        userOpFlagTesT(CarUserService.USER_OP_ERROR_IS_SYSTEM,
                ActivityManager.USER_OP_ERROR_IS_SYSTEM);
        userOpFlagTesT(CarUserService.USER_OP_ERROR_RELATED_USERS_CANNOT_STOP,
                ActivityManager.USER_OP_ERROR_RELATED_USERS_CANNOT_STOP);
    }

    protected void userOpFlagTesT(int carConstant, int amConstant) {
        assertWithMessage("Constant %s",
                DebugUtils.constantToString(CarUserService.class, "USER_OP_", carConstant))
                .that(carConstant).isEqualTo(amConstant);
    }
}
