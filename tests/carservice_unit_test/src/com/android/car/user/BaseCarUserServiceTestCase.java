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

import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetUserHandles;
import static android.car.test.mocks.AndroidMockitoHelper.mockUmRemoveUserOrSetEphemeral;
import static android.car.test.mocks.JavaMockitoHelper.getResult;

import static com.android.car.user.MockedUserHandleBuilder.expectAdminUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectGuestUserExists;
import static com.android.car.user.MockedUserHandleBuilder.expectRegularUserExists;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.car.ICarResultReceiver;
import android.car.builtin.app.ActivityManagerHelper;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.ICarUxRestrictionsChangeListener;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.BlockingAnswer;
import android.car.testapi.BlockingUserLifecycleListener;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserLifecycleEvent;
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
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponse;
import android.hardware.automotive.vehicle.V2_0.RemoveUserRequest;
import android.hardware.automotive.vehicle.V2_0.SwitchUserRequest;
import android.hardware.automotive.vehicle.V2_0.SwitchUserResponse;
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
import com.android.internal.R;
import com.android.internal.util.Preconditions;

import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
abstract class BaseCarUserServiceTestCase extends AbstractExtendedMockitoTestCase {

    private static final String TAG = BaseCarUserServiceTestCase.class.getSimpleName();

    protected static final int NO_USER_INFO_FLAGS = 0;
    protected static final int NON_EXISTING_USER = 55; // must not be on mExistingUsers

    protected static final boolean HAS_CALLER_RESTRICTIONS = true;
    protected static final boolean NO_CALLER_RESTRICTIONS = false;

    protected static final int DEFAULT_TIMEOUT_MS = 15000;

    @Mock protected Context mMockContext;
    @Mock protected Context mApplicationContext;
    @Mock protected LocationManager mLocationManager;
    @Mock protected UserHalService mUserHal;
    @Mock protected ActivityManager mMockedActivityManager;
    @Mock protected ActivityManagerHelper mMockedActivityManagerHelper;
    @Mock protected UserManager mMockedUserManager;
    @Mock protected Resources mMockedResources;
    @Mock protected Drawable mMockedDrawable;
    @Mock protected InitialUserSetter mInitialUserSetter;
    @Mock protected UserPreCreator mUserPreCreator;
    @Mock protected ICarResultReceiver mSwitchUserUiReceiver;
    @Mock protected PackageManager mPackageManager;
    @Mock protected CarUxRestrictionsManagerService mCarUxRestrictionService;
    @Mock protected ICarUxRestrictionsChangeListener mCarUxRestrictionsListener;
    @Mock protected ICarServiceHelper mICarServiceHelper;
    @Mock protected Handler mMockedHandler;
    @Mock protected UserHandleHelper mMockedUserHandleHelper;

    protected final BlockingUserLifecycleListener mUserLifecycleListener =
            BlockingUserLifecycleListener.forAnyEvent().build();

    @Captor protected ArgumentCaptor<UsersInfo> mUsersInfoCaptor;

    protected CarUserService mCarUserService;
    protected boolean mUser0TaskExecuted;

    protected final AndroidFuture<UserSwitchResult> mUserSwitchFuture = new AndroidFuture<>();
    protected final AndroidFuture<UserSwitchResult> mUserSwitchFuture2 = new AndroidFuture<>();
    protected final AndroidFuture<UserCreationResult> mUserCreationFuture = new AndroidFuture<>();
    protected final AndroidFuture<UserRemovalResult> mUserRemovalFuture = new AndroidFuture<>();
    protected final AndroidFuture<UserIdentificationAssociationResponse>
            mUserAssociationRespFuture = new AndroidFuture<>();
    protected final int mAsyncCallTimeoutMs = 100;
    protected final InitialUserInfoResponse mGetUserInfoResponse = new InitialUserInfoResponse();
    protected final SwitchUserResponse mSwitchUserResponse = new SwitchUserResponse();

    protected UserHandle mAdminUser;
    protected UserHandle mAnotherAdminUser;
    protected UserHandle mGuestUser;
    protected UserHandle mRegularUser;
    protected UserHandle mAnotherRegularUser;
    protected List<UserHandle> mExistingUsers;

    protected final HandlerThread mHandlerThread = CarServiceUtils.getHandlerThread(
            getClass().getSimpleName());
    protected final Handler mHandler = new Handler(mHandlerThread.getLooper());

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder
                .spyStatic(ActivityManager.class)
                .spyStatic(ActivityManagerHelper.class)
                // TODO(b/156299496): it cannot spy on UserManager, as it would slow down the tests
                // considerably (more than 5 minutes total, instead of just a couple seconds).
                // So, it's mocking UserHelper.isHeadlessSystemUser()
                // (on mockIsHeadlessSystemUser()) instead...
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

    protected ICarUxRestrictionsChangeListener initService() {
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

    protected void verifyListenerOnEventInvoked(int expectedNewUserId, int expectedEventType)
            throws Exception {
        UserLifecycleEvent actualEvent = mUserLifecycleListener.waitForAnyEvent();
        assertThat(actualEvent.getEventType()).isEqualTo(expectedEventType);
        assertThat(actualEvent.getUserId()).isEqualTo(expectedNewUserId);
    }

    protected void verifyLastActiveUserSet(UserHandle user) {
        verify(mInitialUserSetter).setLastActiveUser(user.getIdentifier());
    }

    protected void associateParentChild(UserHandle parent, UserHandle child) {
        when(mMockedUserHandleHelper.getProfileGroupId(child)).thenReturn(parent.getIdentifier());
    }

    protected void assertInvalidArgumentsFailure() throws Exception {
        UserCreationResult result = getUserCreationResult();
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo(UserCreationResult.STATUS_INVALID_REQUEST);
        assertThat(result.getUser()).isNull();
    }

    protected void createUserWithRestrictionsInvalidTypes(@NonNull String type) throws Exception {
        mCarUserService.createUser("name", type, /* flags= */ 0, mAsyncCallTimeoutMs,
                mUserCreationFuture, HAS_CALLER_RESTRICTIONS);
        waitForHandlerThreadToFinish();
        assertInvalidArgumentsFailure();
    }

    protected void createUserWithRestrictionsInvalidTypes(int flags) throws Exception {
        mCarUserService.createUser("name", UserManager.USER_TYPE_FULL_SECONDARY, flags,
                mAsyncCallTimeoutMs, mUserCreationFuture, HAS_CALLER_RESTRICTIONS);
        waitForHandlerThreadToFinish();
        assertInvalidArgumentsFailure();
    }

    protected void mockCreateProfile(int profileOwner, String profileName, UserHandle profile) {
        Context userContext = mock(Context.class);
        UserManager userManager = mock(UserManager.class);
        when(mMockContext.createContextAsUser(UserHandle.of(profileOwner), /* flags= */ 0))
                .thenReturn(userContext);
        when(userContext.getSystemService(UserManager.class)).thenReturn(userManager);
        when(userManager.createProfile(eq(profileName), eq(UserManager.USER_TYPE_PROFILE_MANAGED),
                any())).thenReturn(profile);
    }

    protected void waitForHandlerThreadToFinish() {
        assertThat(mHandler.runWithScissors(() -> {}, DEFAULT_TIMEOUT_MS)).isTrue();
    }

    protected void createUser(@Nullable String name, @NonNull String userType, int flags,
            int timeoutMs, @NonNull AndroidFuture<UserCreationResult> receiver,
            boolean hasCallerRestrictions) {
        mCarUserService.createUser(name, userType, flags, timeoutMs, receiver,
                hasCallerRestrictions);
        waitForHandlerThreadToFinish();
    }

    protected void switchUser(@UserIdInt int userId, int timeoutMs,
            @NonNull AndroidFuture<UserSwitchResult> receiver) {
        mCarUserService.switchUser(userId, timeoutMs, receiver);
        waitForHandlerThreadToFinish();
    }

    protected void removeUser(@UserIdInt int userId,
            @NonNull AndroidFuture<UserRemovalResult> userRemovalFuture) {
        mCarUserService.removeUser(userId, userRemovalFuture);
        waitForHandlerThreadToFinish();
    }

    protected void removeUser(@UserIdInt int userId, boolean hasCallerRestrictions,
            @NonNull AndroidFuture<UserRemovalResult> userRemovalFuture) {
        mCarUserService.removeUser(userId, hasCallerRestrictions, userRemovalFuture);
        waitForHandlerThreadToFinish();
    }

    protected void startUserInBackground(@UserIdInt int userId,
            @NonNull AndroidFuture<UserStartResult> userStartResultFuture) {
        mCarUserService.startUserInBackground(userId, userStartResultFuture);
        waitForHandlerThreadToFinish();
    }

    protected void stopUser(@UserIdInt int userId,
            @NonNull AndroidFuture<UserStopResult> userStopResultFuture) {
        mCarUserService.stopUser(userId, userStopResultFuture);
        waitForHandlerThreadToFinish();
    }

    @NonNull
    protected UserSwitchResult getUserSwitchResult() throws Exception {
        return getResult(mUserSwitchFuture);
    }

    @NonNull
    protected UserSwitchResult getUserSwitchResult2() throws Exception {
        return getResult(mUserSwitchFuture2);
    }

    @NonNull
    protected UserCreationResult getUserCreationResult() throws Exception {
        return getResult(mUserCreationFuture);
    }

    @NonNull
    protected UserRemovalResult getUserRemovalResult() throws Exception {
        return getResult(mUserRemovalFuture);
    }

    @NonNull
    protected UserIdentificationAssociationResponse getUserAssociationRespResult()
            throws Exception {
        return getResult(mUserAssociationRespFuture);
    }

    protected CarUserService newCarUserService(boolean switchGuestUserBeforeGoingSleep) {
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
    protected UserHandle mockCurrentUserForBinderCalls() {
        int currentUserId = ActivityManager.getCurrentUser();
        Log.d(TAG, "testetUserIdentificationAssociation_ok(): current user is " + currentUserId);
        UserHandle currentUser = expectRegularUserExists(mMockedUserHandleHelper, currentUserId);

        return currentUser;
    }

    /**
     * Mock calls that generate a {@code UsersInfo}.
     */
    protected void mockExistingUsersAndCurrentUser(@NonNull UserHandle user)
            throws Exception {
        mockExistingUsers(mExistingUsers);
        mockCurrentUser(user);
    }

    protected void mockExistingUsersAndCurrentUser(@NonNull List<UserHandle> existingUsers,
            @NonNull UserHandle currentUser) throws Exception {
        mockExistingUsers(existingUsers);
        mockCurrentUser(currentUser);
    }

    protected void mockExistingUsers(@NonNull List<UserHandle> existingUsers) {
        mockUmGetUserHandles(mMockedUserManager, /* excludeDying= */ false, existingUsers);
    }

    protected void mockCurrentUser(@NonNull UserHandle user) throws Exception {
        mockGetCurrentUser(user.getIdentifier());
    }

    // TODO(b/210864238): Move the helper methods to AndroidMockitoHelper.
    protected void mockAmStartUserInBackground(@UserIdInt int userId, boolean result)
            throws Exception {
        when(mMockedActivityManagerHelper.startUserInBackground(userId)).thenReturn(result);
    }

    protected void mockRemoveUser(@NonNull UserHandle user) {
        mockRemoveUser(user, UserManager.REMOVE_RESULT_REMOVED);
    }

    protected void mockRemoveUser(@NonNull UserHandle user, @RemoveResult int result) {
        mockRemoveUser(user, /* evenWhenDisallowed= */ false, result);
    }

    protected void mockRemoveUser(@NonNull UserHandle user, boolean evenWhenDisallowed) {
        mockRemoveUser(user, evenWhenDisallowed, UserManager.REMOVE_RESULT_REMOVED);
    }

    protected void mockRemoveUser(@NonNull UserHandle user, boolean evenWhenDisallowed,
            @RemoveResult int result) {
        mockUmRemoveUserOrSetEphemeral(mMockedUserManager, user, evenWhenDisallowed, result,
                (u) -> mCarUserService.onUserRemoved(u));
    }

    protected void mockRemoveUserNoCallback(@NonNull UserHandle user, @RemoveResult int result) {
        mockRemoveUserNoCallback(user, /* evenWhenDisallowed= */ false, result);
    }

    protected void mockRemoveUserNoCallback(@NonNull UserHandle user, boolean evenWhenDisallowed,
            @RemoveResult int result) {
        mockUmRemoveUserOrSetEphemeral(mMockedUserManager, user, evenWhenDisallowed, result,
                /* listener= */ null);
    }

    protected void mockStopUserWithDelayedLocking(@UserIdInt int userId, int result)
            throws Exception {
        when(mMockedActivityManagerHelper.stopUserWithDelayedLocking(userId, true))
                .thenReturn(result);
    }

    protected void mockStopUserWithDelayedLockingThrowsIllegalStateException(@UserIdInt int userId)
            throws Exception {
        when(mMockedActivityManagerHelper.stopUserWithDelayedLocking(userId, true))
                .thenThrow(new IllegalStateException());
    }

    protected void mockHalGetInitialInfo(@UserIdInt int currentUserId,
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

    protected void mockIsHeadlessSystemUser(@UserIdInt int userId, boolean mode) {
        doReturn(mode).when(() -> UserHelperLite.isHeadlessSystemUser(userId));
    }

    protected void mockHalSwitch(@UserIdInt int currentUserId,
            @NonNull UserHandle androidTargetUser, @Nullable SwitchUserResponse response) {
        mockHalSwitch(currentUserId, HalCallback.STATUS_OK, response, androidTargetUser);
    }

    @NonNull
    protected ArgumentCaptor<CreateUserRequest> mockHalCreateUser(
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

    protected void mockHalCreateUserThrowsRuntimeException() {
        doThrow(new RuntimeException("D'OH!"))
                .when(mUserHal).createUser(any(), eq(mAsyncCallTimeoutMs), notNull());
    }

    protected void mockCallerUid(int uid, boolean returnCorrectUid) throws Exception {
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

    protected BlockingAnswer<Void> mockHalSwitchLateResponse(@UserIdInt int currentUserId,
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

    protected void mockHalSwitch(@UserIdInt int currentUserId,
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

    protected void mockHalGetUserIdentificationAssociation(@NonNull UserHandle user,
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

    protected void mockHalSetUserIdentificationAssociationSuccess(@NonNull UserHandle user,
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

    protected void mockHalSetUserIdentificationAssociationFailure(@NonNull String errorMessage) {
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

    protected void mockInteractAcrossUsersPermission(boolean granted) {
        int result = granted ? android.content.pm.PackageManager.PERMISSION_GRANTED
                : android.content.pm.PackageManager.PERMISSION_DENIED;
        doReturn(result).when(() -> ActivityManagerHelper.checkComponentPermission(
                eq(android.Manifest.permission.INTERACT_ACROSS_USERS),
                anyInt(), anyInt(), eq(true)));
        doReturn(result).when(() -> ActivityManagerHelper.checkComponentPermission(
                eq(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL),
                anyInt(), anyInt(), eq(true)));
    }

    protected void mockManageUsersPermission(String permission, boolean granted) {
        int result = granted ? android.content.pm.PackageManager.PERMISSION_GRANTED
                : android.content.pm.PackageManager.PERMISSION_DENIED;
        doReturn(result).when(() -> ActivityManagerHelper.checkComponentPermission(eq(permission),
                anyInt(), anyInt(), eq(true)));
    }

    protected void mockUserHalSupported(boolean result) {
        when(mUserHal.isSupported()).thenReturn(result);
    }

    protected void mockUserHalUserAssociationSupported(boolean result) {
        when(mUserHal.isUserAssociationSupported()).thenReturn(result);
    }

    protected CarUxRestrictions getUxRestrictions(boolean restricted) {
        int restrictions = CarUxRestrictions.UX_RESTRICTIONS_BASELINE;
        if (restricted) {
            restrictions |= CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP;
        }
        return new CarUxRestrictions.Builder(/* reqOpt= */ false, restrictions,
                System.currentTimeMillis()).build();
    }

    protected void updateUxRestrictions(ICarUxRestrictionsChangeListener listener,
            boolean restricted) throws RemoteException {
        CarUxRestrictions restrictions = getUxRestrictions(restricted);
        Log.v(TAG, "updateUxRestrictions(" + restricted + "): sending UX restrictions ("
                + restrictions + ") to " + listener);
        listener.onUxRestrictionsChanged(restrictions);
    }


    protected void mockGetUxRestrictions(boolean restricted) {
        CarUxRestrictions restrictions = getUxRestrictions(restricted);
        Log.v(TAG, "mockUxRestrictions(" + restricted + ") mocking getCurrentUxRestrictions() to "
                + "return " + restrictions);
        when(mCarUxRestrictionService.getCurrentUxRestrictions()).thenReturn(restrictions);
    }

    /**
     * Asserts a {@link UsersInfo} that was created based on {@link #mockCurrentUsers(UserInfo)}.
     */
    protected void assertDefaultUsersInfo(UsersInfo actual, UserHandle currentUser) {
        // TODO(b/150413515): figure out why this method is not called in other places
        assertThat(actual).isNotNull();
        assertSameUser(actual.currentUser, currentUser);
        assertThat(actual.numberUsers).isEqualTo(mExistingUsers.size());
        for (int i = 0; i < actual.numberUsers; i++) {
            assertSameUser(actual.existingUsers.get(i), mExistingUsers.get(i));
        }
    }

    protected void assertSameUser(android.hardware.automotive.vehicle.V2_0.UserInfo halUser,
            UserHandle androidUser) {
        assertThat(halUser.userId).isEqualTo(androidUser.getIdentifier());

        assertWithMessage("flags mismatch: hal=%s, android=%s",
                halUser.flags,
                UserHalHelper.convertFlags(mMockedUserHandleHelper, androidUser))
                .that(halUser.flags).isEqualTo(
                        UserHalHelper.convertFlags(mMockedUserHandleHelper, androidUser));

    }

    protected void verifyUserRemoved(@UserIdInt int userId) {
        verify(mMockedUserManager).removeUser(UserHandle.of(userId));
    }

    protected void verifyNoUserRemoved() {
        verify(mMockedUserManager, never()).removeUserOrSetEphemeral(anyInt(), anyBoolean());
        verify(mMockedUserManager, never()).removeUser(any());
    }

    protected void verifyAnyUserSwitch() throws Exception {
        verify(mMockedActivityManager).switchUser(any());
    }

    protected void verifyNoUserSwitch() throws Exception {
        verify(mMockedActivityManager, never()).switchUser(any());
    }

    @NonNull
    protected UsersInfo newUsersInfo(@UserIdInt int currentUserId) {
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

    protected void assertNoPostSwitch() {
        verify(mUserHal, never()).postSwitchResponse(any());
    }

    protected void assertPostSwitch(int requestId, int currentId, int targetId) {
        verify(mUserHal).postSwitchResponse(isSwitchUserRequest(requestId, currentId, targetId));
    }

    protected void assertHalSwitch(int currentId, int targetId) {
        verify(mUserHal).switchUser(isSwitchUserRequest(0, currentId, targetId),
                eq(mAsyncCallTimeoutMs), any());
    }

    protected void assertHalSwitchAnyUser() {
        verify(mUserHal).switchUser(any(), eq(mAsyncCallTimeoutMs), any());
    }

    protected void assertNoHalUserSwitch() {
        verify(mUserHal, never()).switchUser(any(), anyInt(), any());
    }

    protected void assertNoHalUserCreation() {
        verify(mUserHal, never()).createUser(any(), anyInt(), any());
    }

    protected void assertNoHalUserRemoval() {
        verify(mUserHal, never()).removeUser(any());
    }

    protected void assertHalRemove(@NonNull UserHandle currentUser,
            @NonNull UserHandle removeUser) {
        assertHalRemove(currentUser, removeUser, /* evenWhenDisallowed= */ false);
    }

    protected void assertHalRemove(@NonNull UserHandle currentUser, @NonNull UserHandle removeUser,
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

    protected void assertUserRemovalResultStatus(UserRemovalResult result,
            @UserRemovalResult.Status int expectedStatus) {
        int actualStatus = result.getStatus();
        assertWithMessage("UserRemovalResult status (where %s=%s, %s=%s)",
                expectedStatus, UserRemovalResult.statusToString(expectedStatus),
                actualStatus, UserRemovalResult.statusToString(actualStatus))
                .that(actualStatus).isEqualTo(expectedStatus);
    }

    @NonNull
    protected static SwitchUserRequest isSwitchUserRequest(int requestId,
            @UserIdInt int currentUserId, @UserIdInt int targetUserId) {
        return argThat(new SwitchUserRequestMatcher(requestId, currentUserId, targetUserId));
    }

    protected void sendUserLifecycleEvent(@UserIdInt int fromUserId, @UserIdInt int toUserId,
            @UserLifecycleEventType int eventType) {
        mCarUserService.onUserLifecycleEvent(eventType, fromUserId,
                toUserId);
    }

    protected void sendUserUnlockedEvent(@UserIdInt int userId) {
        sendUserLifecycleEvent(/* fromUser */ 0, userId,
                CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED);
    }

    protected void sendUserSwitchingEvent(@UserIdInt int fromUserId, @UserIdInt int toUserId) {
        sendUserLifecycleEvent(fromUserId, toUserId,
                CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING);
    }

    @NonNull
    protected UserIdentificationGetRequest isUserIdentificationGetRequest(
            @NonNull UserHandle user, @NonNull int[] types) {
        return argThat(new UserIdentificationGetRequestMatcher(user, types));
    }

    protected final class UserIdentificationGetRequestMatcher implements
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

    static final class SwitchUserRequestMatcher
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
