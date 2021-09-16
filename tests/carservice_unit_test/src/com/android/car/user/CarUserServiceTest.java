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

import static android.car.test.mocks.AndroidMockitoHelper.mockUmCreateGuest;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmCreateUser;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetUserHandles;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmRemoveUserOrSetEphemeral;
import static android.car.test.mocks.JavaMockitoHelper.getResult;

import static com.android.car.user.MockedUserHandleBuilder.expectAdminUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectDisabledUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectEphemeralUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectGuestUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectManagedProfileExists;
import static com.android.car.user.MockedUserHandleBuilder.expectRegularUserExists;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.car.CarOccupantZoneManager.OccupantTypeEnum;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.ICarResultReceiver;
import android.car.builtin.app.ActivityManagerHelper;
import android.car.builtin.os.UserManagerHelper;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.ICarUxRestrictionsChangeListener;
import android.car.settings.CarSettings;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.BlockingAnswer;
import android.car.testapi.BlockingUserLifecycleListener;
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
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.hardware.automotive.vehicle.V2_0.CreateUserRequest;
import android.hardware.automotive.vehicle.V2_0.CreateUserResponse;
import android.hardware.automotive.vehicle.V2_0.CreateUserStatus;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponse;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponseAction;
import android.hardware.automotive.vehicle.V2_0.RemoveUserRequest;
import android.hardware.automotive.vehicle.V2_0.SwitchUserRequest;
import android.hardware.automotive.vehicle.V2_0.SwitchUserResponse;
import android.hardware.automotive.vehicle.V2_0.SwitchUserStatus;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociation;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationGetRequest;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationResponse;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationSetRequest;
import android.hardware.automotive.vehicle.V2_0.UsersInfo;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManager.RemoveResult;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;

import androidx.test.InstrumentationRegistry;

import com.android.car.CarServiceUtils;
import com.android.car.CarUxRestrictionsManagerService;
import com.android.car.hal.HalCallback;
import com.android.car.hal.HalCallback.HalCallbackStatus;
import com.android.car.hal.UserHalHelper;
import com.android.car.hal.UserHalService;
import com.android.car.internal.ICarServiceHelper;
import com.android.car.internal.common.CommonConstants.UserLifecycleEventType;
import com.android.car.internal.common.UserHelperLite;
import com.android.car.internal.os.CarSystemProperties;
import com.android.car.internal.user.UserHelper;
import com.android.car.internal.util.DebugUtils;
import com.android.car.user.ExperimentalCarUserService.ZoneUserBindingHelper;
import com.android.internal.R;
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
import java.util.Optional;
import java.util.Set;

/**
 * This class contains unit tests for the {@link CarUserService}.
 *
 * The following mocks are used:
 * <ol>
 * <li> {@link Context} provides system services and resources.
 * <li> {@link ActivityManager} provides current user and other calls.
 * <li> {@link UserManager} provides user creation and user info.
 * <li> {@link Resources} provides user icon.
 * <li> {@link Drawable} provides bitmap of user icon.
 * <ol/>
 */
public final class CarUserServiceTest extends AbstractExtendedMockitoTestCase {

    private static final String TAG = CarUserServiceTest.class.getSimpleName();
    private static final int NO_USER_INFO_FLAGS = 0;

    private static final int NON_EXISTING_USER = 55; // must not be on mExistingUsers

    private static final boolean HAS_CALLER_RESTRICTIONS = true;
    private static final boolean NO_CALLER_RESTRICTIONS = false;

    private static final int DEFAULT_TIMEOUT_MS = 15000;

    @Mock private Context mMockContext;
    @Mock private Context mApplicationContext;
    @Mock private LocationManager mLocationManager;
    @Mock private UserHalService mUserHal;
    @Mock private ActivityManager mMockedActivityManager;
    @Mock private ActivityManagerHelper mMockedActivityManagerHelper;
    @Mock private UserManager mMockedUserManager;
    @Mock private Resources mMockedResources;
    @Mock private Drawable mMockedDrawable;
    @Mock private InitialUserSetter mInitialUserSetter;
    @Mock private UserPreCreator mUserPreCreator;
    @Mock private ICarResultReceiver mSwitchUserUiReceiver;
    @Mock private PackageManager mPackageManager;
    @Mock private CarUxRestrictionsManagerService mCarUxRestrictionService;
    @Mock private ICarUxRestrictionsChangeListener mCarUxRestrictionsListener;
    @Mock private ICarServiceHelper mICarServiceHelper;
    @Mock private Handler mMockedHandler;
    @Mock private UserHandleHelper mMockedUserHandleHelper;

    private final BlockingUserLifecycleListener mUserLifecycleListener =
            BlockingUserLifecycleListener.forAnyEvent().build();

    @Captor private ArgumentCaptor<UsersInfo> mUsersInfoCaptor;

    private CarUserService mCarUserService;
    private ExperimentalCarUserService mExperimentalCarUserService;
    private boolean mUser0TaskExecuted;

    private final AndroidFuture<UserSwitchResult> mUserSwitchFuture = new AndroidFuture<>();
    private final AndroidFuture<UserSwitchResult> mUserSwitchFuture2 = new AndroidFuture<>();
    private final AndroidFuture<UserCreationResult> mUserCreationFuture = new AndroidFuture<>();
    private final AndroidFuture<UserRemovalResult> mUserRemovalFuture = new AndroidFuture<>();
    private final AndroidFuture<UserIdentificationAssociationResponse> mUserAssociationRespFuture =
            new AndroidFuture<>();
    private final int mAsyncCallTimeoutMs = 100;
    private final InitialUserInfoResponse mGetUserInfoResponse = new InitialUserInfoResponse();
    private final SwitchUserResponse mSwitchUserResponse = new SwitchUserResponse();

    private UserHandle mAdminUser;
    private UserHandle mAnotherAdminUser;
    private UserHandle mGuestUser;
    private UserHandle mRegularUser;
    private UserHandle mAnotherRegularUser;
    private List<UserHandle> mExistingUsers;

    private final HandlerThread mHandlerThread = CarServiceUtils.getHandlerThread(
            getClass().getSimpleName());
    private final Handler mHandler = new Handler(mHandlerThread.getLooper());

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder
            .spyStatic(ActivityManager.class)
            // TODO(b/156299496): it cannot spy on UserManager, as it would slow down the tests
            // considerably (more than 5 minutes total, instead of just a couple seconds). So, it's
            // mocking UserHelper.isHeadlessSystemUser() (on mockIsHeadlessSystemUser()) instead...
            .spyStatic(UserHelper.class)
            .spyStatic(UserHelperLite.class)
            .spyStatic(CarSystemProperties.class)
            .spyStatic(Binder.class);
    }

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUpMocks() {
        doReturn(mApplicationContext).when(mMockContext).getApplicationContext();
        doReturn(mMockContext).when(mMockContext).createContextAsUser(any(), anyInt());
        doReturn(mLocationManager).when(mMockContext).getSystemService(Context.LOCATION_SERVICE);
        doReturn(InstrumentationRegistry.getTargetContext().getContentResolver())
                .when(mMockContext).getContentResolver();
        doReturn(false).when(mMockedUserManager).isUserUnlockingOrUnlocked(any());
        doReturn(mMockedResources).when(mMockContext).getResources();
        doReturn(mMockedDrawable).when(mMockedResources)
                .getDrawable(eq(R.drawable.ic_account_circle), eq(null));
        doReturn(mMockedDrawable).when(mMockedDrawable).mutate();
        doReturn(1).when(mMockedDrawable).getIntrinsicWidth();
        doReturn(1).when(mMockedDrawable).getIntrinsicHeight();
        mockUserHalSupported(true);
        mockUserHalUserAssociationSupported(true);
        doReturn(Optional.of(mAsyncCallTimeoutMs)).when(
                () -> CarSystemProperties.getUserHalTimeout());

        mCarUserService = newCarUserService(/* switchGuestUserBeforeGoingSleep= */ false);
        mExperimentalCarUserService =
                new ExperimentalCarUserService(mMockContext, mCarUserService, mMockedUserManager,
                        mMockedActivityManagerHelper, mMockedUserHandleHelper);

        // TODO(b/172262561): refactor this call, which is not assigning the service to anything
        // (but without it some tests fail due to NPE).
        new FakeCarOccupantZoneService(mExperimentalCarUserService);
    }

    @Before
    public void setUpUsers() {
        mAdminUser = expectAdminUserExists(mMockedUserHandleHelper, 100);
        mAnotherAdminUser = expectAdminUserExists(mMockedUserHandleHelper, 108);
        mGuestUser = expectGuestUserExists(mMockedUserHandleHelper, 111, /* isEphemeral= */ false);
        mRegularUser = expectRegularUserExists(mMockedUserHandleHelper, 222);
        mAnotherRegularUser = expectRegularUserExists(mMockedUserHandleHelper, 333);
        mExistingUsers = Arrays
                .asList(mAdminUser, mAnotherAdminUser, mGuestUser, mRegularUser,
                        mAnotherRegularUser);
    }

    private ICarUxRestrictionsChangeListener initService() {
        ArgumentCaptor<ICarUxRestrictionsChangeListener> listenerCaptor =
                ArgumentCaptor.forClass(ICarUxRestrictionsChangeListener.class);
        doNothing().when(mCarUxRestrictionService).registerUxRestrictionsChangeListener(
                listenerCaptor.capture(), eq(Display.DEFAULT_DISPLAY));

        mCarUserService.init();

        ICarUxRestrictionsChangeListener listener = listenerCaptor.getValue();
        assertWithMessage("init() didn't register ICarUxRestrictionsChangeListener")
                .that(listener).isNotNull();

        return listener;
    }

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

    private void verifyListenerOnEventInvoked(int expectedNewUserId, int expectedEventType)
            throws Exception {
        UserLifecycleEvent actualEvent = mUserLifecycleListener.waitForAnyEvent();
        assertThat(actualEvent.getEventType()).isEqualTo(expectedEventType);
        assertThat(actualEvent.getUserId()).isEqualTo(expectedNewUserId);
    }

    private void verifyLastActiveUserSet(UserHandle user) {
        verify(mInitialUserSetter).setLastActiveUser(user.getIdentifier());
    }

    private void verifyLastActiveUserNotSet() {
        verify(mInitialUserSetter, never()).setLastActiveUser(any());
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
    public void testCreateAdminDriver_IfCurrentUserIsAdminUser() throws Exception {
        when(mMockedUserManager.isSystemUser()).thenReturn(true);
        mockUmCreateUser(mMockedUserManager, "testUser", UserManager.USER_TYPE_FULL_SECONDARY,
                UserManagerHelper.FLAG_ADMIN, UserHandle.of(10));
        mockHalCreateUser(HalCallback.STATUS_OK, CreateUserStatus.SUCCESS);

        AndroidFuture<UserCreationResult> future =
                mExperimentalCarUserService.createDriver("testUser", true);
        waitForHandlerThreadToFinish();

        assertThat(getResult(future).getUser().getIdentifier()).isEqualTo(10);
    }

    @Test
    public void testCreateAdminDriver_IfCurrentUserIsNotSystemUser() throws Exception {
        when(mMockedUserManager.isSystemUser()).thenReturn(false);
        AndroidFuture<UserCreationResult> future =
                mExperimentalCarUserService.createDriver("testUser", true);
        waitForHandlerThreadToFinish();
        assertThat(getResult(future).getStatus())
                .isEqualTo(UserCreationResult.STATUS_INVALID_REQUEST);
    }

    @Test
    public void testCreateNonAdminDriver() throws Exception {
        mockUmCreateUser(mMockedUserManager, "testUser", UserManager.USER_TYPE_FULL_SECONDARY,
                NO_USER_INFO_FLAGS, UserHandle.of(10));
        mockHalCreateUser(HalCallback.STATUS_OK, CreateUserStatus.SUCCESS);

        AndroidFuture<UserCreationResult> future =
                mExperimentalCarUserService.createDriver("testUser", false);
        waitForHandlerThreadToFinish();

        UserHandle userHandle = getResult(future).getUser();
        assertThat(userHandle.getIdentifier()).isEqualTo(10);
    }

    @Test
    // TODO(b/196179969): remove UserInfo usages
    public void testCreatePassenger() {
        // assignDefaultIconForUser is not used for testing
        doReturn(null).when(() -> UserHelper.assignDefaultIcon(any(), any()));
        doNothing()
                .when(() -> UserHelper.setDefaultNonAdminRestrictions(any(), any(), anyBoolean()));
        int driverId = 90;
        int passengerId = 99;
        String userName = "testUser";
        UserHandle passenger = expectManagedProfileExists(mMockedUserHandleHelper, passengerId);
        doReturn(new UserInfo(passengerId, "", 0)).when(mMockedUserManager).createProfileForUser(
                eq(userName), eq(UserManager.USER_TYPE_PROFILE_MANAGED), eq(0), eq(driverId));
        UserHandle driver = expectRegularUserExists(mMockedUserHandleHelper, driverId);
        assertThat(mExperimentalCarUserService.createPassenger(userName, driverId))
                .isEqualTo(passenger);
    }

    @Test
    public void testCreatePassenger_IfMaximumProfileAlreadyCreated() {
        UserHandle driver = expectManagedProfileExists(mMockedUserHandleHelper, 90);
        String userName = "testUser";
        doReturn(null).when(mMockedUserManager).createProfileForUser(eq(userName),
                eq(UserManager.USER_TYPE_PROFILE_MANAGED), anyInt(), anyInt());
        assertThat(mExperimentalCarUserService.createPassenger(userName, driver.getIdentifier()))
                .isNull();
    }

    @Test
    public void testCreatePassenger_IfDriverIsGuest() {
        int driverId = 90;
        UserHandle driver = expectGuestUserExists(mMockedUserHandleHelper, driverId,
                /* isEphemeral= */ false);
        String userName = "testUser";
        assertThat(mExperimentalCarUserService.createPassenger(userName, driverId)).isNull();
    }

    @Test
    public void testSwitchDriver() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mRegularUser, mSwitchUserResponse);
        mockAmSwitchUser(mRegularUser, true);
        when(mMockedUserManager.hasUserRestriction(UserManager.DISALLOW_USER_SWITCH))
                .thenReturn(false);
        mExperimentalCarUserService.switchDriver(mRegularUser.getIdentifier(), mUserSwitchFuture);
        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);
    }

    @Test
    public void testSwitchDriver_failUxRestrictions() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mockGetUxRestrictions(/* restricted= */ true);
        initService();

        mExperimentalCarUserService.switchDriver(mRegularUser.getIdentifier(), mUserSwitchFuture);

        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_UX_RESTRICTION_FAILURE);
        verifyNoUserSwitch();
        assertNoHalUserSwitch();
    }

    @Test
    public void testSwitchDriver_IfUserSwitchIsNotAllowed() throws Exception {
        when(mMockedUserManager.getUserSwitchability())
                .thenReturn(UserManager.SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED);
        mExperimentalCarUserService.switchDriver(mRegularUser.getIdentifier(), mUserSwitchFuture);
        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_INVALID_REQUEST);
    }

    @Test
    public void testSwitchDriver_IfSwitchedToCurrentUser() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        when(mMockedUserManager.hasUserRestriction(UserManager.DISALLOW_USER_SWITCH))
                .thenReturn(false);
        mExperimentalCarUserService.switchDriver(mAdminUser.getIdentifier(), mUserSwitchFuture);
        assertThat(getUserSwitchResult().getStatus())
                .isEqualTo(UserSwitchResult.STATUS_OK_USER_ALREADY_IN_FOREGROUND);
    }

    @Test
    public void testStartPassenger() throws RemoteException {
        int passenger1Id = 91;
        int passenger2Id = 92;
        int passenger3Id = 93;
        int zone1Id = 1;
        int zone2Id = 2;
        doReturn(true).when(mMockedActivityManagerHelper)
                .startUserInBackground(anyInt());
        assertThat(mExperimentalCarUserService.startPassenger(passenger1Id, zone1Id)).isTrue();
        assertThat(mExperimentalCarUserService.startPassenger(passenger2Id, zone2Id)).isTrue();
        assertThat(mExperimentalCarUserService.startPassenger(passenger3Id, zone2Id)).isFalse();
    }

    @Test
    public void testStopPassenger() throws RemoteException {
        int user1Id = 11;
        int passenger1Id = 91;
        int passenger2Id = 92;
        int zoneId = 1;
        UserHandle user1 = expectRegularUserExists(mMockedUserHandleHelper, user1Id);
        UserHandle passenger1 = expectRegularUserExists(mMockedUserHandleHelper, passenger1Id);

        associateParentChild(user1, passenger1);
        mockGetCurrentUser(user1Id);
        doReturn(true).when(mMockedActivityManagerHelper)
                .startUserInBackground(anyInt());
        assertThat(mExperimentalCarUserService.startPassenger(passenger1Id, zoneId)).isTrue();
        assertThat(mExperimentalCarUserService.stopPassenger(passenger1Id)).isTrue();
        // Test of stopping an already stopped passenger.
        assertThat(mExperimentalCarUserService.stopPassenger(passenger1Id)).isTrue();
        // Test of stopping a non-existing passenger.
        assertThat(mExperimentalCarUserService.stopPassenger(passenger2Id)).isFalse();
    }

    private void associateParentChild(UserHandle parent, UserHandle child) {
        when(mMockedUserHandleHelper.getProfileGroupId(child)).thenReturn(parent.getIdentifier());
    }

    private List<UserHandle> prepareUserList() {
        List<UserHandle> users = new ArrayList<>(Arrays.asList(
                expectAdminUserExists(mMockedUserHandleHelper, 10),
                expectRegularUserExists(mMockedUserHandleHelper, 11),
                expectManagedProfileExists(mMockedUserHandleHelper, 12),
                expectRegularUserExists(mMockedUserHandleHelper, 13),
                expectGuestUserExists(mMockedUserHandleHelper, 14, /* isEphemeral= */ false),
                expectEphemeralUserExists(mMockedUserHandleHelper, 15),
                expectDisabledUserExists(mMockedUserHandleHelper, 16),
                expectManagedProfileExists(mMockedUserHandleHelper, 17),
                expectManagedProfileExists(mMockedUserHandleHelper, 18),
                expectRegularUserExists(mMockedUserHandleHelper, 19)));

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
        mockExistingUsers(prepareUserList());
        mockIsHeadlessSystemUser(19, true);
        for (UserHandle user : mExperimentalCarUserService.getAllDrivers()) {
            assertThat(expected).contains(user.getIdentifier());
            expected.remove(user.getIdentifier());
        }
        assertThat(expected).isEmpty();
    }

    @Test
    public void testGetAllPassengers() {
        SparseArray<HashSet<Integer>> testCases = new SparseArray<HashSet<Integer>>() {
            {
                put(0, new HashSet<Integer>());
                put(10, new HashSet<Integer>(Arrays.asList(12)));
                put(11, new HashSet<Integer>());
                put(13, new HashSet<Integer>(Arrays.asList(17)));
            }
        };
        mockIsHeadlessSystemUser(18, true);
        for (int i = 0; i < testCases.size(); i++) {
            mockExistingUsers(prepareUserList());
            List<UserHandle> passengers = mExperimentalCarUserService
                    .getPassengers(testCases.keyAt(i));
            HashSet<Integer> expected = testCases.valueAt(i);
            for (UserHandle user : passengers) {
                assertThat(expected).contains(user.getIdentifier());
                expected.remove(user.getIdentifier());
            }
            assertThat(expected).isEmpty();
        }
    }

    @Test
    public void testRemoveUser_currentUser_successSetEphemeral() throws Exception {
        UserHandle currentUser = mRegularUser;
        mockExistingUsersAndCurrentUser(mExistingUsers, currentUser);
        UserHandle removeUser = mRegularUser;
        mockRemoveUserNoCallback(removeUser, UserManager.REMOVE_RESULT_SET_EPHEMERAL);

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
        mockRemoveUserNoCallback(removeUser, UserManager.REMOVE_RESULT_SET_EPHEMERAL);

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
                UserManager.REMOVE_RESULT_SET_EPHEMERAL);

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
                UserManager.REMOVE_RESULT_SET_EPHEMERAL);

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
        mockAmSwitchUser(mRegularUser, true);

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
        mockAmSwitchUser(mGuestUser, true);

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
        mockAmSwitchUser(mGuestUser, false);

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
        mockAmSwitchUser(mGuestUser, true);

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
        mockAmSwitchUser(mGuestUser, true);
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        int newRequestId = 43;
        SwitchUserResponse switchUserResponse = new SwitchUserResponse();
        switchUserResponse.status = SwitchUserStatus.SUCCESS;
        switchUserResponse.requestId = newRequestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mRegularUser, switchUserResponse);
        mockAmSwitchUser(mRegularUser, true);
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
        mockAmSwitchUser(mGuestUser, true);
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        // calling another user switch before unlock
        int newRequestId = 43;
        SwitchUserResponse switchUserResponse = new SwitchUserResponse();
        switchUserResponse.status = SwitchUserStatus.SUCCESS;
        switchUserResponse.requestId = newRequestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mRegularUser, switchUserResponse);
        mockAmSwitchUser(mRegularUser, true);
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
        mockAmSwitchUser(mRegularUser, true);
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
        mockAmSwitchUser(mRegularUser, true);
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
        mockAmSwitchUser(mRegularUser, true);
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
        mockAmSwitchUser(mGuestUser, true);

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
        mockAmSwitchUser(mGuestUser, true);

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

        sendUserSwitchingEvent(mAdminUser.getIdentifier(), mRegularUser.getIdentifier());

        verify(mUserHal).legacyUserSwitch(
                isSwitchUserRequest(0, mAdminUser.getIdentifier(), mRegularUser.getIdentifier()));
    }

    @Test
    public void testLegacyUserSwitch_notCalledAfterNormalSwitch() throws Exception {
        // Arrange - emulate normal switch
        mockExistingUsersAndCurrentUser(mAdminUser);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mGuestUser, true);
        switchUser(mGuestUser.getIdentifier(), mAsyncCallTimeoutMs, mUserSwitchFuture);

        // Act - trigger legacy switch
        sendUserSwitchingEvent(mAdminUser.getIdentifier(), mGuestUser.getIdentifier());

        // Assert
        verify(mUserHal, never()).legacyUserSwitch(any());
    }

    @Test
    public void testSetSwitchUserUI_receiverSetAndCalled() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        int callerId = Binder.getCallingUid();
        mockCallerUid(callerId, true);
        int requestId = 42;
        mSwitchUserResponse.status = SwitchUserStatus.SUCCESS;
        mSwitchUserResponse.requestId = requestId;
        mockHalSwitch(mAdminUser.getIdentifier(), mGuestUser, mSwitchUserResponse);
        mockAmSwitchUser(mGuestUser, true);

        mCarUserService.setUserSwitchUiCallback(mSwitchUserUiReceiver);
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
        mockAmSwitchUser(mRegularUser, true);
        int requestId = -1;

        mCarUserService.switchAndroidUserFromHal(requestId, mRegularUser.getIdentifier());
        mockCurrentUser(mRegularUser);
        sendUserUnlockedEvent(mRegularUser.getIdentifier());

        assertPostSwitch(requestId, mRegularUser.getIdentifier(), mRegularUser.getIdentifier());
    }

    @Test
    public void testSwitchUser_OEMRequest_failure() throws Exception {
        mockExistingUsersAndCurrentUser(mAdminUser);
        mockAmSwitchUser(mRegularUser, false);
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

    private void assertInvalidArgumentsFailure() throws Exception {
        UserCreationResult result = getUserCreationResult();
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo(UserCreationResult.STATUS_INVALID_REQUEST);
        assertThat(result.getUser()).isNull();
    }

    @Test
    public void testCreateUserWithRestrictions_invalidTypes() throws Exception {
        createUserWithRestrictionsInvalidTypes(UserManager.USER_TYPE_FULL_DEMO);
        createUserWithRestrictionsInvalidTypes(UserManager.USER_TYPE_FULL_RESTRICTED);
        createUserWithRestrictionsInvalidTypes(UserManager.USER_TYPE_FULL_SYSTEM);
        createUserWithRestrictionsInvalidTypes(UserManager.USER_TYPE_PROFILE_MANAGED);
        createUserWithRestrictionsInvalidTypes(UserManager.USER_TYPE_SYSTEM_HEADLESS);
    }


    private void createUserWithRestrictionsInvalidTypes(@NonNull String type) throws Exception {
        mCarUserService.createUser("name", type, /* flags= */ 0, mAsyncCallTimeoutMs,
                mUserCreationFuture, HAS_CALLER_RESTRICTIONS);
        waitForHandlerThreadToFinish();
        assertInvalidArgumentsFailure();
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

    private void createUserWithRestrictionsInvalidTypes(int flags) throws Exception {
        mCarUserService.createUser("name", UserManager.USER_TYPE_FULL_SECONDARY, flags,
                mAsyncCallTimeoutMs, mUserCreationFuture, HAS_CALLER_RESTRICTIONS);
        waitForHandlerThreadToFinish();
        assertInvalidArgumentsFailure();
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
    @ExpectWtf
    public void testSetInitialUser_nullUser() throws Exception {
        mCarUserService.setInitialUser(null);

        mockInteractAcrossUsersPermission(true);
        assertThat(mCarUserService.getInitialUser()).isNull();
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

    private void userOpFlagTesT(int carConstant, int amConstant) {
        assertWithMessage("Constant %s",
                DebugUtils.constantToString(CarUserService.class, "USER_OP_", carConstant))
                        .that(carConstant).isEqualTo(amConstant);
    }

    private void waitForHandlerThreadToFinish() {
        assertThat(mHandler.runWithScissors(() -> {}, DEFAULT_TIMEOUT_MS)).isTrue();
    }

    private void createUser(@Nullable String name, @NonNull String userType,
            int flags,
            int timeoutMs, @NonNull AndroidFuture<UserCreationResult> receiver,
            boolean hasCallerRestrictions) {
        mCarUserService.createUser(name, userType, flags, timeoutMs, receiver,
                hasCallerRestrictions);
        waitForHandlerThreadToFinish();
    }

    private void switchUser(@UserIdInt int userId, int timeoutMs,
            @NonNull AndroidFuture<UserSwitchResult> receiver) {
        mCarUserService.switchUser(userId, timeoutMs, receiver);
        waitForHandlerThreadToFinish();
    }

    private void removeUser(@UserIdInt int userId,
            @NonNull AndroidFuture<UserRemovalResult> userRemovalFuture) {
        mCarUserService.removeUser(userId, userRemovalFuture);
        waitForHandlerThreadToFinish();
    }

    private void removeUser(@UserIdInt int userId, boolean hasCallerRestrictions,
            @NonNull AndroidFuture<UserRemovalResult> userRemovalFuture) {
        mCarUserService.removeUser(userId, hasCallerRestrictions, userRemovalFuture);
        waitForHandlerThreadToFinish();
    }

    private void startUserInBackground(@UserIdInt int userId,
            @NonNull AndroidFuture<UserStartResult> userStartResultFuture) {
        mCarUserService.startUserInBackground(userId, userStartResultFuture);
        waitForHandlerThreadToFinish();
    }

    private void stopUser(@UserIdInt int userId,
            @NonNull AndroidFuture<UserStopResult> userStopResultFuture) {
        mCarUserService.stopUser(userId, userStopResultFuture);
        waitForHandlerThreadToFinish();
    }

    @NonNull
    private UserSwitchResult getUserSwitchResult() throws Exception {
        return getResult(mUserSwitchFuture);
    }

    @NonNull
    private UserSwitchResult getUserSwitchResult2() throws Exception {
        return getResult(mUserSwitchFuture2);
    }

    @NonNull
    private UserCreationResult getUserCreationResult() throws Exception {
        return getResult(mUserCreationFuture);
    }

    @NonNull
    private UserRemovalResult getUserRemovalResult() throws Exception {
        return getResult(mUserRemovalFuture);
    }

    @NonNull
    private UserIdentificationAssociationResponse getUserAssociationRespResult()
            throws Exception {
        return getResult(mUserAssociationRespFuture);
    }

    private CarUserService newCarUserService(boolean switchGuestUserBeforeGoingSleep) {
        when(mMockedResources
                .getBoolean(com.android.car.R.bool.config_switchGuestUserBeforeGoingSleep))
                        .thenReturn(switchGuestUserBeforeGoingSleep);

        return new CarUserService(
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
                mHandler);
    }

    /**
     * This method must be called for cases where the service infers the user id of the caller
     * using Binder - it's not worth the effort of mocking such (native) calls.
     */
    @NonNull
    private UserHandle mockCurrentUserForBinderCalls() {
        int currentUserId = ActivityManager.getCurrentUser();
        Log.d(TAG, "testetUserIdentificationAssociation_ok(): current user is " + currentUserId);
        UserHandle currentUser = expectRegularUserExists(mMockedUserHandleHelper, currentUserId);

        return currentUser;
    }

    /**
     * Mock calls that generate a {@code UsersInfo}.
     */
    private void mockExistingUsersAndCurrentUser(@NonNull UserHandle user)
            throws Exception {
        mockExistingUsers(mExistingUsers);
        mockCurrentUser(user);
    }

    private void mockExistingUsersAndCurrentUser(@NonNull List<UserHandle> existingUsers,
            @NonNull UserHandle currentUser) throws Exception {
        mockExistingUsers(existingUsers);
        mockCurrentUser(currentUser);
    }

    private void mockExistingUsers(@NonNull List<UserHandle> existingUsers) {
        mockUmGetUserHandles(mMockedUserManager, /* excludeDying= */ false, existingUsers);
    }

    private void mockCurrentUser(@NonNull UserHandle user) throws Exception {
        mockGetCurrentUser(user.getIdentifier());
    }

    private void mockAmStartUserInBackground(@UserIdInt int userId, boolean result)
            throws Exception {
        when(mMockedActivityManagerHelper.startUserInBackground(userId)).thenReturn(result);
    }

    private void mockAmSwitchUser(@NonNull UserHandle user, boolean result) throws Exception {
        when(mMockedActivityManager.switchUser(user)).thenReturn(result);
    }

    private void mockRemoveUser(@NonNull UserHandle user) {
        mockRemoveUser(user, UserManager.REMOVE_RESULT_REMOVED);
    }

    private void mockRemoveUser(@NonNull UserHandle user, @RemoveResult int result) {
        mockRemoveUser(user, /* evenWhenDisallowed= */ false, result);
    }

    private void mockRemoveUser(@NonNull UserHandle user, boolean evenWhenDisallowed) {
        mockRemoveUser(user, evenWhenDisallowed, UserManager.REMOVE_RESULT_REMOVED);
    }

    private void mockRemoveUser(@NonNull UserHandle user, boolean evenWhenDisallowed,
            @RemoveResult int result) {
        mockUmRemoveUserOrSetEphemeral(mMockedUserManager, user, evenWhenDisallowed, result,
                (u) -> mCarUserService.onUserRemoved(u));
    }

    private void mockRemoveUserNoCallback(@NonNull UserHandle user, @RemoveResult int result) {
        mockRemoveUserNoCallback(user, /* evenWhenDisallowed= */ false, result);
    }

    private void mockRemoveUserNoCallback(@NonNull UserHandle user, boolean evenWhenDisallowed,
            @RemoveResult int result) {
        mockUmRemoveUserOrSetEphemeral(mMockedUserManager, user, evenWhenDisallowed, result,
                /* listener= */ null);
    }

    private void mockStopUserWithDelayedLocking(@UserIdInt int userId, int result)
            throws Exception {
        when(mMockedActivityManagerHelper.stopUserWithDelayedLocking(userId, true))
                .thenReturn(result);
    }

    private void mockStopUserWithDelayedLockingThrowsIllegalStateException(@UserIdInt int userId)
            throws Exception {
        when(mMockedActivityManagerHelper.stopUserWithDelayedLocking(userId, true))
                .thenThrow(new IllegalStateException());
    }

    private void mockHalGetInitialInfo(@UserIdInt int currentUserId,
            @NonNull InitialUserInfoResponse response) {
        UsersInfo usersInfo = newUsersInfo(currentUserId);
        doAnswer((invocation) -> {
            Log.d(TAG, "Answering " + invocation + " with " + response);
            @SuppressWarnings("unchecked")
            HalCallback<InitialUserInfoResponse> callback =
                    (HalCallback<InitialUserInfoResponse>) invocation.getArguments()[3];
            callback.onResponse(HalCallback.STATUS_OK, response);
            return null;
        }).when(mUserHal).getInitialUserInfo(anyInt(), eq(mAsyncCallTimeoutMs),
                eq(usersInfo), notNull());
    }

    private void mockIsHeadlessSystemUser(@UserIdInt int userId, boolean mode) {
        doReturn(mode).when(() -> UserHelperLite.isHeadlessSystemUser(userId));
    }

    private void mockHalSwitch(@UserIdInt int currentUserId, @NonNull UserHandle androidTargetUser,
            @Nullable SwitchUserResponse response) {
        mockHalSwitch(currentUserId, HalCallback.STATUS_OK, response, androidTargetUser);
    }

    @NonNull
    private ArgumentCaptor<CreateUserRequest> mockHalCreateUser(
            @HalCallbackStatus int callbackStatus, int responseStatus) {
        CreateUserResponse response = new CreateUserResponse();
        response.status = responseStatus;
        ArgumentCaptor<CreateUserRequest> captor = ArgumentCaptor.forClass(CreateUserRequest.class);
        doAnswer((invocation) -> {
            Log.d(TAG, "Answering " + invocation + " with " + response);
            @SuppressWarnings("unchecked")
            HalCallback<CreateUserResponse> callback =
                    (HalCallback<CreateUserResponse>) invocation.getArguments()[2];
            callback.onResponse(callbackStatus, response);
            return null;
        }).when(mUserHal).createUser(captor.capture(), eq(mAsyncCallTimeoutMs), notNull());
        return captor;
    }

    private void mockHalCreateUserThrowsRuntimeException() {
        doThrow(new RuntimeException("D'OH!"))
                .when(mUserHal).createUser(any(), eq(mAsyncCallTimeoutMs), notNull());
    }

    private void mockCallerUid(int uid, boolean returnCorrectUid) throws Exception {
        String packageName = "packageName";
        String className = "className";
        when(mMockedResources.getString(anyInt())).thenReturn(packageName + "/" + className);
        when(mMockContext.createContextAsUser(any(), anyInt())).thenReturn(mMockContext);
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);

        if (returnCorrectUid) {
            when(mPackageManager.getPackageUid(any(), anyInt())).thenReturn(uid);
        } else {
            when(mPackageManager.getPackageUid(any(), anyInt())).thenReturn(uid + 1);
        }
    }

    private BlockingAnswer<Void> mockHalSwitchLateResponse(@UserIdInt int currentUserId,
            @NonNull UserHandle androidTargetUser, @Nullable SwitchUserResponse response) {
        android.hardware.automotive.vehicle.V2_0.UserInfo halTargetUser =
                new android.hardware.automotive.vehicle.V2_0.UserInfo();
        halTargetUser.userId = androidTargetUser.getIdentifier();
        halTargetUser.flags = UserHalHelper.convertFlags(mMockedUserHandleHelper,
                androidTargetUser);
        UsersInfo usersInfo = newUsersInfo(currentUserId);
        SwitchUserRequest request = new SwitchUserRequest();
        request.targetUser = halTargetUser;
        request.usersInfo = usersInfo;

        BlockingAnswer<Void> blockingAnswer = BlockingAnswer.forVoidReturn(10_000, (invocation) -> {
            Log.d(TAG, "Answering " + invocation + " with " + response);
            @SuppressWarnings("unchecked")
            HalCallback<SwitchUserResponse> callback = (HalCallback<SwitchUserResponse>) invocation
                    .getArguments()[2];
            callback.onResponse(HalCallback.STATUS_OK, response);
        });
        doAnswer(blockingAnswer).when(mUserHal).switchUser(eq(request), eq(mAsyncCallTimeoutMs),
                notNull());
        return blockingAnswer;
    }

    private void mockHalSwitch(@UserIdInt int currentUserId,
            @HalCallback.HalCallbackStatus int callbackStatus,
            @Nullable SwitchUserResponse response, @NonNull UserHandle androidTargetUser) {
        android.hardware.automotive.vehicle.V2_0.UserInfo halTargetUser =
                new android.hardware.automotive.vehicle.V2_0.UserInfo();
        halTargetUser.userId = androidTargetUser.getIdentifier();
        halTargetUser.flags = UserHalHelper.convertFlags(mMockedUserHandleHelper,
                androidTargetUser);
        UsersInfo usersInfo = newUsersInfo(currentUserId);
        SwitchUserRequest request = new SwitchUserRequest();
        request.targetUser = halTargetUser;
        request.usersInfo = usersInfo;

        doAnswer((invocation) -> {
            Log.d(TAG, "Answering " + invocation + " with " + response);
            @SuppressWarnings("unchecked")
            HalCallback<SwitchUserResponse> callback =
                    (HalCallback<SwitchUserResponse>) invocation.getArguments()[2];
            callback.onResponse(callbackStatus, response);
            return null;
        }).when(mUserHal).switchUser(eq(request), eq(mAsyncCallTimeoutMs), notNull());
    }

    private void mockHalGetUserIdentificationAssociation(@NonNull UserHandle user,
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

    private void mockHalSetUserIdentificationAssociationSuccess(@NonNull UserHandle user,
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

        doAnswer((invocation) -> {
            Log.d(TAG, "Answering " + invocation + " with " + response);
            @SuppressWarnings("unchecked")
            UserIdentificationSetRequest request =
                    (UserIdentificationSetRequest) invocation.getArguments()[1];
            assertWithMessage("Wrong user on %s", request)
                    .that(request.userInfo.userId)
                    .isEqualTo(user.getIdentifier());
            assertWithMessage("Wrong flags on %s", request)
                    .that(request.userInfo.flags)
                    .isEqualTo(UserHalHelper.convertFlags(mMockedUserHandleHelper, user));
            @SuppressWarnings("unchecked")
            HalCallback<UserIdentificationResponse> callback =
                    (HalCallback<UserIdentificationResponse>) invocation.getArguments()[2];
            callback.onResponse(HalCallback.STATUS_OK, response);
            return null;
        }).when(mUserHal).setUserAssociation(eq(mAsyncCallTimeoutMs), notNull(), notNull());
    }

    private void mockHalSetUserIdentificationAssociationFailure(@NonNull String errorMessage) {
        UserIdentificationResponse response = new UserIdentificationResponse();
        response.errorMessage = errorMessage;
        doAnswer((invocation) -> {
            Log.d(TAG, "Answering " + invocation + " with " + response);
            @SuppressWarnings("unchecked")
            HalCallback<UserIdentificationResponse> callback =
                    (HalCallback<UserIdentificationResponse>) invocation.getArguments()[2];
            callback.onResponse(HalCallback.STATUS_WRONG_HAL_RESPONSE, response);
            return null;
        }).when(mUserHal).setUserAssociation(eq(mAsyncCallTimeoutMs), notNull(), notNull());
    }

    private void mockInteractAcrossUsersPermission(boolean granted) {
        int result = granted ? android.content.pm.PackageManager.PERMISSION_GRANTED
                : android.content.pm.PackageManager.PERMISSION_DENIED;

        doReturn(result).when(() -> ActivityManager.checkComponentPermission(
                eq(android.Manifest.permission.INTERACT_ACROSS_USERS),
                anyInt(), anyInt(), eq(true)));
        doReturn(result).when(() -> ActivityManager.checkComponentPermission(
                eq(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL),
                anyInt(), anyInt(), eq(true)));
    }

    private void mockManageUsersPermission(String permission, boolean granted) {
        int result = granted ? android.content.pm.PackageManager.PERMISSION_GRANTED
                : android.content.pm.PackageManager.PERMISSION_DENIED;

        doReturn(result).when(() -> ActivityManager.checkComponentPermission(eq(permission),
                anyInt(), anyInt(), eq(true)));
    }

    private void mockUserHalSupported(boolean result) {
        when(mUserHal.isSupported()).thenReturn(result);
    }

    private void mockUserHalUserAssociationSupported(boolean result) {
        when(mUserHal.isUserAssociationSupported()).thenReturn(result);
    }

    private CarUxRestrictions getUxRestrictions(boolean restricted) {
        int restrictions = CarUxRestrictions.UX_RESTRICTIONS_BASELINE;
        if (restricted) {
            restrictions |= CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP;
        }
        return new CarUxRestrictions.Builder(/* reqOpt= */ false, restrictions,
                System.currentTimeMillis()).build();
    }

    private void updateUxRestrictions(ICarUxRestrictionsChangeListener listener, boolean restricted)
            throws RemoteException {
        CarUxRestrictions restrictions = getUxRestrictions(restricted);
        Log.v(TAG, "updateUxRestrictions(" + restricted + "): sending UX restrictions ("
                + restrictions + ") to " + listener);
        listener.onUxRestrictionsChanged(restrictions);
    }


    private void mockGetUxRestrictions(boolean restricted) {
        CarUxRestrictions restrictions = getUxRestrictions(restricted);
        Log.v(TAG, "mockUxRestrictions(" + restricted + ") mocking getCurrentUxRestrictions() to "
                + "return " + restrictions);
        when(mCarUxRestrictionService.getCurrentUxRestrictions()).thenReturn(restrictions);
    }

    /**
     * Asserts a {@link UsersInfo} that was created based on {@link #mockCurrentUsers(UserInfo)}.
     */
    private void assertDefaultUsersInfo(UsersInfo actual, UserHandle currentUser) {
        // TODO(b/150413515): figure out why this method is not called in other places
        assertThat(actual).isNotNull();
        assertSameUser(actual.currentUser, currentUser);
        assertThat(actual.numberUsers).isEqualTo(mExistingUsers.size());
        for (int i = 0; i < actual.numberUsers; i++) {
            assertSameUser(actual.existingUsers.get(i), mExistingUsers.get(i));
        }
    }

    private void assertSameUser(android.hardware.automotive.vehicle.V2_0.UserInfo halUser,
            UserHandle androidUser) {
        assertThat(halUser.userId).isEqualTo(androidUser.getIdentifier());

        assertWithMessage("flags mismatch: hal=%s, android=%s",
                halUser.flags,
                UserHalHelper.convertFlags(mMockedUserHandleHelper, androidUser))
                        .that(halUser.flags).isEqualTo(
                                UserHalHelper.convertFlags(mMockedUserHandleHelper, androidUser));

    }

    private void verifyUserRemoved(@UserIdInt int userId) {
        verify(mMockedUserManager).removeUser(UserHandle.of(userId));
    }

    private void verifyNoUserRemoved() {
        verify(mMockedUserManager, never()).removeUserOrSetEphemeral(anyInt(), anyBoolean());
        verify(mMockedUserManager, never()).removeUser(any());
    }

    private void verifyAnyUserSwitch() throws Exception {
        verify(mMockedActivityManager).switchUser(any());
    }

    private void verifyNoUserSwitch() throws Exception {
        verify(mMockedActivityManager, never()).switchUser(any());
    }

    @NonNull
    private UsersInfo newUsersInfo(@UserIdInt int currentUserId) {
        UsersInfo infos = new UsersInfo();
        infos.numberUsers = mExistingUsers.size();
        boolean foundCurrentUser = false;
        for (UserHandle handle : mExistingUsers) {
            android.hardware.automotive.vehicle.V2_0.UserInfo existingUser =
                    new android.hardware.automotive.vehicle.V2_0.UserInfo();
            int flags = UserFlags.NONE;
            if (handle.getIdentifier() == UserHandle.USER_SYSTEM) {
                flags |= UserFlags.SYSTEM;
            }
            if (mMockedUserHandleHelper.isAdminUser(handle)) {
                flags |= UserFlags.ADMIN;
            }
            if (mMockedUserHandleHelper.isGuestUser(handle)) {
                flags |= UserFlags.GUEST;
            }
            if (mMockedUserHandleHelper.isEphemeralUser(handle)) {
                flags |= UserFlags.EPHEMERAL;
            }
            existingUser.userId = handle.getIdentifier();
            existingUser.flags = flags;
            if (handle.getIdentifier() == currentUserId) {
                foundCurrentUser = true;
                infos.currentUser.userId = handle.getIdentifier();
                infos.currentUser.flags = flags;
            }
            infos.existingUsers.add(existingUser);
        }
        Preconditions.checkArgument(foundCurrentUser,
                "no user with id " + currentUserId + " on " + mExistingUsers);
        return infos;
    }

    private void assertNoPostSwitch() {
        verify(mUserHal, never()).postSwitchResponse(any());
    }

    private void assertPostSwitch(int requestId, int currentId, int targetId) {
        verify(mUserHal).postSwitchResponse(isSwitchUserRequest(requestId, currentId, targetId));
    }

    private void assertHalSwitch(int currentId, int targetId) {
        verify(mUserHal).switchUser(isSwitchUserRequest(0, currentId, targetId),
                eq(mAsyncCallTimeoutMs), any());
    }

    private void assertHalSwitchAnyUser() {
        verify(mUserHal).switchUser(any(), eq(mAsyncCallTimeoutMs), any());
    }

    private void assertNoHalUserSwitch() {
        verify(mUserHal, never()).switchUser(any(), anyInt(), any());
    }

    private void assertNoHalUserCreation() {
        verify(mUserHal, never()).createUser(any(), anyInt(), any());
    }

    private void assertNoHalUserRemoval() {
        verify(mUserHal, never()).removeUser(any());
    }

    private void assertHalRemove(@NonNull UserHandle currentUser, @NonNull UserHandle removeUser) {
        assertHalRemove(currentUser, removeUser, /* evenWhenDisallowed= */ false);
    }

    private void assertHalRemove(@NonNull UserHandle currentUser, @NonNull UserHandle removeUser,
            boolean evenWhenDisallowed) {
        verify(mMockedUserManager).removeUserOrSetEphemeral(removeUser.getIdentifier(),
                evenWhenDisallowed);
        ArgumentCaptor<RemoveUserRequest> requestCaptor =
                ArgumentCaptor.forClass(RemoveUserRequest.class);
        verify(mUserHal).removeUser(requestCaptor.capture());
        RemoveUserRequest request = requestCaptor.getValue();
        assertThat(request.removedUserInfo.userId).isEqualTo(removeUser.getIdentifier());
        assertThat(request.removedUserInfo.flags)
                .isEqualTo(UserHalHelper.convertFlags(mMockedUserHandleHelper, removeUser));
        assertThat(request.usersInfo.currentUser.userId).isEqualTo(currentUser.getIdentifier());
    }

    private void assertUserRemovalResultStatus(UserRemovalResult result,
            @UserRemovalResult.Status int expectedStatus) {
        int actualStatus = result.getStatus();
        assertWithMessage("UserRemovalResult status (where %s=%s, %s=%s)",
                        expectedStatus, UserRemovalResult.statusToString(expectedStatus),
                        actualStatus, UserRemovalResult.statusToString(actualStatus))
                .that(actualStatus).isEqualTo(expectedStatus);
    }

    @NonNull
    private static SwitchUserRequest isSwitchUserRequest(int requestId,
            @UserIdInt int currentUserId, @UserIdInt int targetUserId) {
        return argThat(new SwitchUserRequestMatcher(requestId, currentUserId, targetUserId));
    }

    static final class FakeCarOccupantZoneService {
        private final SparseArray<Integer> mZoneUserMap = new SparseArray<Integer>();
        private final ZoneUserBindingHelper mZoneUserBindigHelper =
                new ZoneUserBindingHelper() {
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

        FakeCarOccupantZoneService(ExperimentalCarUserService experimentalCarUserService) {
            experimentalCarUserService.setZoneUserBindingHelper(mZoneUserBindigHelper);
        }
    }

    private void sendUserLifecycleEvent(@UserIdInt int fromUserId, @UserIdInt int toUserId,
            @UserLifecycleEventType int eventType) {
        mCarUserService.onUserLifecycleEvent(eventType, fromUserId,
                toUserId);
    }

    private void sendUserUnlockedEvent(@UserIdInt int userId) {
        sendUserLifecycleEvent(/* fromUser */ 0, userId,
                CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED);
    }

    private void sendUserSwitchingEvent(@UserIdInt int fromUserId, @UserIdInt int toUserId) {
        sendUserLifecycleEvent(fromUserId, toUserId,
                CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING);
    }

    @NonNull
    private UserIdentificationGetRequest isUserIdentificationGetRequest(
            @NonNull UserHandle user, @NonNull int[] types) {
        return argThat(new UserIdentificationGetRequestMatcher(user, types));
    }

    private final class UserIdentificationGetRequestMatcher implements
            ArgumentMatcher<UserIdentificationGetRequest> {

        private final @UserIdInt int mUserId;
        private final int mHalFlags;
        private final @NonNull int[] mTypes;

        private UserIdentificationGetRequestMatcher(@NonNull UserHandle user,
                @NonNull int[] types) {
            mUserId = user.getIdentifier();
            mHalFlags = UserHalHelper.convertFlags(mMockedUserHandleHelper, user);
            mTypes = types;
        }

        @Override
        public boolean matches(UserIdentificationGetRequest argument) {
            if (argument == null) {
                Log.w(TAG, "null argument");
                return false;
            }
            if (argument.userInfo.userId != mUserId) {
                Log.w(TAG, "wrong user id on " + argument + "; expected " + mUserId);
                return false;
            }
            if (argument.userInfo.flags != mHalFlags) {
                Log.w(TAG, "wrong flags on " + argument + "; expected " + mHalFlags);
                return false;
            }
            if (argument.numberAssociationTypes != mTypes.length) {
                Log.w(TAG, "wrong numberAssociationTypes on " + argument + "; expected "
                        + mTypes.length);
                return false;
            }
            if (argument.associationTypes.size() != mTypes.length) {
                Log.w(TAG, "wrong associationTypes size on " + argument + "; expected "
                        + mTypes.length);
                return false;
            }
            for (int i = 0; i < mTypes.length; i++) {
                if (argument.associationTypes.get(i) != mTypes[i]) {
                    Log.w(TAG, "wrong association type on index " + i + " on " + argument
                            + "; expected types: " + Arrays.toString(mTypes));
                    return false;
                }
            }
            Log.d(TAG, "Good News, Everyone! " + argument + " matches " + this);
            return true;
        }

        @Override
        public String toString() {
            return "isUserIdentificationGetRequest(userId=" + mUserId + ", flags="
                    + UserHalHelper.userFlagsToString(mHalFlags) + ", types="
                    + Arrays.toString(mTypes) + ")";
        }
    }

    private static final class SwitchUserRequestMatcher
            implements ArgumentMatcher<SwitchUserRequest> {
        private static final String MY_TAG = UsersInfo.class.getSimpleName();

        private final int mRequestId;
        private final @UserIdInt int mCurrentUserId;
        private final @UserIdInt int mTargetUserId;


        private SwitchUserRequestMatcher(int requestId, @UserIdInt int currentUserId,
                @UserIdInt int targetUserId) {
            mCurrentUserId = currentUserId;
            mTargetUserId = targetUserId;
            mRequestId = requestId;
        }

        @Override
        public boolean matches(SwitchUserRequest argument) {
            if (argument == null) {
                Log.w(MY_TAG, "null argument");
                return false;
            }
            if (argument.usersInfo.currentUser.userId != mCurrentUserId) {
                Log.w(MY_TAG,
                        "wrong current user id on " + argument + "; expected " + mCurrentUserId);
                return false;
            }

            if (argument.targetUser.userId != mTargetUserId) {
                Log.w(MY_TAG,
                        "wrong target user id on " + argument + "; expected " + mTargetUserId);
                return false;
            }

            if (argument.requestId != mRequestId) {
                Log.w(MY_TAG,
                        "wrong request Id on " + argument + "; expected " + mTargetUserId);
                return false;
            }

            Log.d(MY_TAG, "Good News, Everyone! " + argument + " matches " + this);
            return true;
        }
    }
}
