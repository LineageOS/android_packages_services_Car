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

import static com.android.car.CarLog.TAG_USER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManager.StackInfo;
import android.app.IActivityManager;
import android.car.CarOccupantZoneManager;
import android.car.CarOccupantZoneManager.OccupantTypeEnum;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.ICarUserService;
import android.car.settings.CarSettings;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserLifecycleEvent;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.car.userlib.CarUserManagerHelper;
import android.car.userlib.HalCallback;
import android.car.userlib.UserHalHelper;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponse;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponseAction;
import android.hardware.automotive.vehicle.V2_0.SwitchUserStatus;
import android.hardware.automotive.vehicle.V2_0.UsersInfo;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.sysprop.CarProperties;
import android.util.Log;
import android.util.SparseArray;
import android.util.TimingsTraceLog;

import com.android.car.CarServiceBase;
import com.android.car.R;
import com.android.car.hal.UserHalService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.Preconditions;
import com.android.internal.util.UserIcons;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * User service for cars. Manages users at boot time. Including:
 *
 * <ol>
 *   <li> Creates a user used as driver.
 *   <li> Creates a user used as passenger.
 *   <li> Creates a secondary admin user on first run.
 *   <li> Switch drivers.
 * <ol/>
 */
public final class CarUserService extends ICarUserService.Stub implements CarServiceBase {

    /** {@code int} extra used to represent a user id in a {@link IResultReceiver} response. */
    public static final String BUNDLE_USER_ID = "user.id";
    /** {@code int} extra used to represent user flags in a {@link IResultReceiver} response. */
    public static final String BUNDLE_USER_FLAGS = "user.flags";
    /** {@code String} extra used to represent a user name in a {@link IResultReceiver} response. */
    public static final String BUNDLE_USER_NAME = "user.name";
    /** {@code int} extra used to represent the info action {@link IResultReceiver} response. */
    public static final String BUNDLE_INITIAL_INFO_ACTION = "initial_info.action";

    private final Context mContext;
    private final CarUserManagerHelper mCarUserManagerHelper;
    private final IActivityManager mAm;
    private final UserManager mUserManager;
    private final int mMaxRunningUsers;
    private final boolean mEnablePassengerSupport;

    private final Object mLockUser = new Object();
    @GuardedBy("mLockUser")
    private boolean mUser0Unlocked;
    @GuardedBy("mLockUser")
    private final ArrayList<Runnable> mUser0UnlockTasks = new ArrayList<>();
    // Only one passenger is supported.
    @GuardedBy("mLockUser")
    private @UserIdInt int mLastPassengerId;
    /**
     * Background users that will be restarted in garage mode. This list can include the
     * current foreground user but the current foreground user should not be restarted.
     */
    @GuardedBy("mLockUser")
    private final ArrayList<Integer> mBackgroundUsersToRestart = new ArrayList<>();
    /**
     * Keep the list of background users started here. This is wholly for debugging purpose.
     */
    @GuardedBy("mLockUser")
    private final ArrayList<Integer> mBackgroundUsersRestartedHere = new ArrayList<>();

    private final UserHalService mHal;

    /**
     * List of listeners to be notified on new user activities events.
     */
    private final CopyOnWriteArrayList<UserLifecycleListener>
            mUserLifecycleListeners = new CopyOnWriteArrayList<>();

    /**
     * List of lifecycle listeners by uid.
     */
    @GuardedBy("mLockUser")
    private final SparseArray<IResultReceiver> mLifecycleListeners = new SparseArray<>();

    private final int mHalTimeoutMs = CarProperties.user_hal_timeout().orElse(5_000);

    private final CopyOnWriteArrayList<PassengerCallback> mPassengerCallbacks =
            new CopyOnWriteArrayList<>();

    @Nullable
    @GuardedBy("mLockUser")
    private UserInfo mInitialUser;

    /** Interface for callbaks related to passenger activities. */
    public interface PassengerCallback {
        /** Called when passenger is started at a certain zone. */
        void onPassengerStarted(@UserIdInt int passengerId, int zoneId);
        /** Called when passenger is stopped. */
        void onPassengerStopped(@UserIdInt int passengerId);
    }

    /** Interface for delegating zone-related implementation to CarOccupantZoneService. */
    public interface ZoneUserBindingHelper {
        /** Gets occupant zones corresponding to the occupant type. */
        @NonNull
        List<OccupantZoneInfo> getOccupantZones(@OccupantTypeEnum int occupantType);
        /** Assigns the user to the occupant zone. */
        boolean assignUserToOccupantZone(@UserIdInt int userId, int zoneId);
        /** Makes the occupant zone unoccupied. */
        boolean unassignUserFromOccupantZone(@UserIdInt int userId);
        /** Returns whether there is a passenger display. */
        boolean isPassengerDisplayAvailable();
    }

    private final Object mLockHelper = new Object();
    @GuardedBy("mLockHelper")
    private ZoneUserBindingHelper mZoneUserBindingHelper;

    public CarUserService(@NonNull Context context, @NonNull UserHalService hal,
            @NonNull CarUserManagerHelper carUserManagerHelper,
            @NonNull UserManager userManager, @NonNull IActivityManager am, int maxRunningUsers) {
        if (Log.isLoggable(TAG_USER, Log.DEBUG)) {
            Log.d(TAG_USER, "constructed");
        }
        mContext = context;
        mHal = hal;
        mCarUserManagerHelper = carUserManagerHelper;
        mAm = am;
        mMaxRunningUsers = maxRunningUsers;
        mUserManager = userManager;
        mLastPassengerId = UserHandle.USER_NULL;
        mEnablePassengerSupport = context.getResources().getBoolean(R.bool.enablePassengerSupport);
    }

    @Override
    public void init() {
        if (Log.isLoggable(TAG_USER, Log.DEBUG)) {
            Log.d(TAG_USER, "init");
        }
    }

    @Override
    public void release() {
        if (Log.isLoggable(TAG_USER, Log.DEBUG)) {
            Log.d(TAG_USER, "release");
        }
    }

    @Override
    public void dump(@NonNull PrintWriter writer) {
        checkAtLeastOnePermission("dump()", android.Manifest.permission.DUMP);
        writer.println("*CarUserService*");
        String indent = "  ";
        synchronized (mLockUser) {
            int numberListeners = mLifecycleListeners.size();
            if (numberListeners == 0) {
                writer.println("No lifecycle listeners");
            } else {
                writer.printf("%d lifecycle listeners\n", numberListeners);
                for (int i = 0; i < numberListeners; i++) {
                    int uid = mLifecycleListeners.keyAt(i);
                    IResultReceiver listener = mLifecycleListeners.valueAt(i);
                    writer.printf("%suid: %d Listener %s\n", indent, uid, listener);
                }
            }
            writer.println("User0Unlocked: " + mUser0Unlocked);
            writer.println("MaxRunningUsers: " + mMaxRunningUsers);
            writer.println("BackgroundUsersToRestart: " + mBackgroundUsersToRestart);
            writer.println("BackgroundUsersRestarted: " + mBackgroundUsersRestartedHere);
            List<UserInfo> allDrivers = getAllDrivers();
            int driversSize = allDrivers.size();
            writer.println("NumberOfDrivers: " + driversSize);
            for (int i = 0; i < driversSize; i++) {
                int driverId = allDrivers.get(i).id;
                writer.print(indent + "#" + i + ": id=" + driverId);
                List<UserInfo> passengers = getPassengers(driverId);
                int passengersSize = passengers.size();
                writer.print(" NumberPassengers: " + passengersSize);
                if (passengersSize > 0) {
                    writer.print(" [");
                    for (int j = 0; j < passengersSize; j++) {
                        writer.print(passengers.get(j).id);
                        if (j < passengersSize - 1) {
                            writer.print(" ");
                        }
                    }
                    writer.print("]");
                }
                writer.println();
            }
            writer.printf("EnablePassengerSupport: %s\n", mEnablePassengerSupport);
            writer.printf("User HAL timeout: %dms\n",  mHalTimeoutMs);
            writer.printf("Initial user: %s\n", mInitialUser);
            writer.println("Relevant overlayable properties");
            Resources res = mContext.getResources();
            writer.printf("%sowner_name=%s\n", indent,
                    res.getString(com.android.internal.R.string.owner_name));
            writer.printf("%sdefault_guest_name=%s\n", indent,
                    res.getString(R.string.default_guest_name));
        }
    }

    /**
     * Creates a driver who is a regular user and is allowed to login to the driving occupant zone.
     *
     * @param name The name of the driver to be created.
     * @param admin Whether the created driver will be an admin.
     * @return {@link UserInfo} object of the created driver, or {@code null} if the driver could
     *         not be created.
     */
    @Override
    @Nullable
    public UserInfo createDriver(@NonNull String name, boolean admin) {
        checkManageUsersPermission("createDriver");
        Objects.requireNonNull(name, "name cannot be null");
        if (admin) {
            return createNewAdminUser(name);
        }
        return mCarUserManagerHelper.createNewNonAdminUser(name);
    }

    /**
     * Creates a passenger who is a profile of the given driver.
     *
     * @param name The name of the passenger to be created.
     * @param driverId User id of the driver under whom a passenger is created.
     * @return {@link UserInfo} object of the created passenger, or {@code null} if the passenger
     *         could not be created.
     */
    @Override
    @Nullable
    public UserInfo createPassenger(@NonNull String name, @UserIdInt int driverId) {
        checkManageUsersPermission("createPassenger");
        Objects.requireNonNull(name, "name cannot be null");
        UserInfo driver = mUserManager.getUserInfo(driverId);
        if (driver == null) {
            Log.w(TAG_USER, "the driver is invalid");
            return null;
        }
        if (driver.isGuest()) {
            Log.w(TAG_USER, "a guest driver cannot create a passenger");
            return null;
        }
        UserInfo user = mUserManager.createProfileForUser(name,
                UserManager.USER_TYPE_PROFILE_MANAGED, /* flags */ 0, driverId);
        if (user == null) {
            // Couldn't create user, most likely because there are too many.
            Log.w(TAG_USER, "can't create a profile for user" + driverId);
            return null;
        }
        // Passenger user should be a non-admin user.
        mCarUserManagerHelper.setDefaultNonAdminRestrictions(user, /* enable= */ true);
        assignDefaultIcon(user);
        return user;
    }

    /**
     * @see CarUserManager.switchDriver
     */
    @Override
    public boolean switchDriver(@UserIdInt int driverId) {
        checkManageUsersPermission("switchDriver");
        if (driverId == UserHandle.USER_SYSTEM && UserManager.isHeadlessSystemUserMode()) {
            // System user doesn't associate with real person, can not be switched to.
            Log.w(TAG_USER, "switching to system user in headless system user mode is not allowed");
            return false;
        }
        int userSwitchable = mUserManager.getUserSwitchability();
        if (userSwitchable != UserManager.SWITCHABILITY_STATUS_OK) {
            Log.w(TAG_USER, "current process is not allowed to switch user");
            return false;
        }
        if (driverId == ActivityManager.getCurrentUser()) {
            // The current user is already the given user.
            return true;
        }
        try {
            return mAm.switchUser(driverId);
        } catch (RemoteException e) {
            // ignore
            Log.w(TAG_USER, "error while switching user", e);
        }
        return false;
    }

    /**
     * Returns all drivers who can occupy the driving zone. Guest users are included in the list.
     *
     * @return the list of {@link UserInfo} who can be a driver on the device.
     */
    @Override
    @NonNull
    public List<UserInfo> getAllDrivers() {
        checkManageUsersOrDumpPermission("getAllDrivers");
        return getUsers((user) -> {
            return !isSystemUser(user.id) && user.isEnabled() && !user.isManagedProfile()
                    && !user.isEphemeral();
        });
    }

    /**
     * Returns all passengers under the given driver.
     *
     * @param driverId User id of a driver.
     * @return the list of {@link UserInfo} who is a passenger under the given driver.
     */
    @Override
    @NonNull
    public List<UserInfo> getPassengers(@UserIdInt int driverId) {
        checkManageUsersOrDumpPermission("getPassengers");
        return getUsers((user) -> {
            return !isSystemUser(user.id) && user.isEnabled() && user.isManagedProfile()
                    && user.profileGroupId == driverId;
        });
    }

    /**
     * @see CarUserManager.startPassenger
     */
    @Override
    public boolean startPassenger(@UserIdInt int passengerId, int zoneId) {
        checkManageUsersPermission("startPassenger");
        synchronized (mLockUser) {
            try {
                if (!mAm.startUserInBackgroundWithListener(passengerId, null)) {
                    Log.w(TAG_USER, "could not start passenger");
                    return false;
                }
            } catch (RemoteException e) {
                // ignore
                Log.w(TAG_USER, "error while starting passenger", e);
                return false;
            }
            if (!assignUserToOccupantZone(passengerId, zoneId)) {
                Log.w(TAG_USER, "could not assign passenger to zone");
                return false;
            }
            mLastPassengerId = passengerId;
        }
        for (PassengerCallback callback : mPassengerCallbacks) {
            callback.onPassengerStarted(passengerId, zoneId);
        }
        return true;
    }

    /**
     * @see CarUserManager.stopPassenger
     */
    @Override
    public boolean stopPassenger(@UserIdInt int passengerId) {
        checkManageUsersPermission("stopPassenger");
        return stopPassengerInternal(passengerId, true);
    }

    private boolean stopPassengerInternal(@UserIdInt int passengerId, boolean checkCurrentDriver) {
        synchronized (mLockUser) {
            UserInfo passenger = mUserManager.getUserInfo(passengerId);
            if (passenger == null) {
                Log.w(TAG_USER, "passenger " + passengerId + " doesn't exist");
                return false;
            }
            if (mLastPassengerId != passengerId) {
                Log.w(TAG_USER, "passenger " + passengerId + " hasn't been started");
                return true;
            }
            if (checkCurrentDriver) {
                int currentUser = ActivityManager.getCurrentUser();
                if (passenger.profileGroupId != currentUser) {
                    Log.w(TAG_USER, "passenger " + passengerId
                            + " is not a profile of the current user");
                    return false;
                }
            }
            // Passenger is a profile, so cannot be stopped through activity manager.
            // Instead, activities started by the passenger are stopped and the passenger is
            // unassigned from the zone.
            stopAllTasks(passengerId);
            if (!unassignUserFromOccupantZone(passengerId)) {
                Log.w(TAG_USER, "could not unassign user from occupant zone");
                return false;
            }
            mLastPassengerId = UserHandle.USER_NULL;
        }
        for (PassengerCallback callback : mPassengerCallbacks) {
            callback.onPassengerStopped(passengerId);
        }
        return true;
    }

    private void stopAllTasks(@UserIdInt int userId) {
        try {
            for (StackInfo info : mAm.getAllStackInfos()) {
                for (int i = 0; i < info.taskIds.length; i++) {
                    if (info.taskUserIds[i] == userId) {
                        int taskId = info.taskIds[i];
                        if (!mAm.removeTask(taskId)) {
                            Log.w(TAG_USER, "could not remove task " + taskId);
                        }
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG_USER, "could not get stack info", e);
        }
    }

    @Override
    public void setLifecycleListenerForUid(IResultReceiver listener) {
        int uid = Binder.getCallingUid();
        checkInteractAcrossUsersPermission("setLifecycleListenerForUid" + uid);

        try {
            listener.asBinder().linkToDeath(() -> onListenerDeath(uid), 0);
        } catch (RemoteException e) {
            Log.wtf(TAG_USER, "Cannot listen to death of " + uid);
        }
        synchronized (mLockUser) {
            mLifecycleListeners.append(uid, listener);
        }
    }

    private void onListenerDeath(int uid) {
        Log.i(TAG_USER, "Removing listeners for uid " + uid + " on binder death");
        synchronized (mLockUser) {
            removeLifecycleListenerLocked(uid);
        }
    }

    @Override
    public void resetLifecycleListenerForUid() {
        int uid = Binder.getCallingUid();
        checkInteractAcrossUsersPermission("resetLifecycleListenerForUid-" + uid);

        synchronized (mLockUser) {
            removeLifecycleListenerLocked(uid);
        }
    }

    private void removeLifecycleListenerLocked(int uid) {
        mLifecycleListeners.remove(uid);
    }

    @Override
    public void getInitialUserInfo(int requestType, int timeoutMs,
            @NonNull IResultReceiver receiver) {
        Objects.requireNonNull(receiver, "receiver cannot be null");
        checkManageUsersPermission("getInitialInfo");
        UsersInfo usersInfo = getUsersInfo();
        mHal.getInitialUserInfo(requestType, timeoutMs, usersInfo, (status, resp) -> {
            try {
                Bundle resultData = null;
                if (resp != null) {
                    switch (resp.action) {
                        case InitialUserInfoResponseAction.SWITCH:
                            resultData = new Bundle();
                            resultData.putInt(BUNDLE_INITIAL_INFO_ACTION, resp.action);
                            resultData.putInt(BUNDLE_USER_ID, resp.userToSwitchOrCreate.userId);
                            break;
                        case InitialUserInfoResponseAction.CREATE:
                            resultData = new Bundle();
                            resultData.putInt(BUNDLE_INITIAL_INFO_ACTION, resp.action);
                            resultData.putInt(BUNDLE_USER_FLAGS, resp.userToSwitchOrCreate.flags);
                            resultData.putString(BUNDLE_USER_NAME, resp.userNameToCreate);
                            break;
                        case InitialUserInfoResponseAction.DEFAULT:
                            resultData = new Bundle();
                            resultData.putInt(BUNDLE_INITIAL_INFO_ACTION, resp.action);
                            break;
                        default:
                            // That's ok, it will be the same as DEFAULT...
                            Log.w(TAG_USER, "invalid response action on " + resp);
                    }
                }
                receiver.send(status, resultData);
            } catch (RemoteException e) {
                Log.w(TAG_USER, "Could not send result back to receiver", e);
            }
        });
    }

    /**
     * Gets the initial foreground user after the device boots or resumes from suspension.
     *
     * <p>When the OEM supports the User HAL, the initial user won't be available until the HAL
     * returns the initial value to {@code CarService} - if HAL takes too long or times out, this
     * method returns {@code null}.
     *
     * <p>If the HAL eventually times out, {@code CarService} will fallback to its default behavior
     * (like switching to the last active user), and this method will return the result of such
     * operation.
     *
     * <p>Notice that if {@code CarService} crashes, subsequent calls to this method will return
     * {@code null}.
     *
     * @hide
     */
    @Nullable
    public UserInfo getInitialUser() {
        checkInteractAcrossUsersPermission("getInitialUser");
        synchronized (mLockUser) {
            return mInitialUser;
        }
    }

    // TODO(b/150413515): temporary method called by ICarImpl.setInitialUser(int userId), as for
    // some reason passing the whole UserInfo through a raw binder transaction  is not working.
    /**
     * Sets the initial foreground user after the device boots or resumes from suspension.
     */
    public void setInitialUser(@UserIdInt int userId) {
        UserInfo initialUser = userId == UserHandle.USER_NULL ? null
                : mUserManager.getUserInfo(userId);
        setInitialUser(initialUser);
    }

    /**
     * Sets the initial foreground user after the device boots or resumes from suspension.
     */
    public void setInitialUser(@Nullable UserInfo user) {
        Log.i(TAG_USER, "setInitialUser: " + user);
        synchronized (mLockUser) {
            mInitialUser = user;
        }
        if (user == null) {
            // This mean InitialUserSetter failed and could not fallback, so the initial user was
            // not switched (and most likely is SYSTEM_USER).
            // TODO(b/153104378): should we set it to ActivityManager.getCurrentUser() instead?
            Log.wtf(TAG_USER, "Initial user set to null");
        }
    }

    /**
     * Calls the User HAL to get the initial user info.
     *
     * @param requestType type as defined by {@code InitialUserInfoRequestType}.
     * @param callback callback to receive the results.
     */
    public void getInitialUserInfo(int requestType,
            HalCallback<InitialUserInfoResponse> callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        checkManageUsersPermission("getInitialUserInfo");
        UsersInfo usersInfo = getUsersInfo();
        mHal.getInitialUserInfo(requestType, mHalTimeoutMs, usersInfo, callback);
    }

    /**
     * Calls the User HAL to switch user.
     *
     * @param targetUserId - target user Id
     * @param timeoutMs - timeout for HAL to wait
     * @param receiver - receiver for the results
     */
    @Override
    public void switchUser(@UserIdInt int targetUserId, int timeoutMs,
            @NonNull IResultReceiver receiver) {
        checkManageUsersPermission("switchUser");
        Objects.requireNonNull(receiver);
        UserInfo targetUser = mUserManager.getUserInfo(targetUserId);
        Preconditions.checkArgument(targetUser != null, "Invalid target user Id");
        UsersInfo usersInfo = getUsersInfo();
        android.hardware.automotive.vehicle.V2_0.UserInfo halUser =
                new android.hardware.automotive.vehicle.V2_0.UserInfo();
        halUser.userId = targetUser.id;
        halUser.flags = UserHalHelper.convertFlags(targetUser);
        mHal.switchUser(halUser, timeoutMs, usersInfo, (status, resp) -> {
            Bundle resultData = null;
            resultData = new Bundle();
            int resultStatus = CarUserManager.USER_SWITCH_STATUS_HAL_INTERNAL_FAILURE;
            if (resp != null) {
                resultData.putInt(CarUserManager.BUNDLE_USER_SWITCH_STATUS, resp.status);
                resultData.putInt(CarUserManager.BUNDLE_USER_SWITCH_MSG_TYPE, resp.messageType);
                if (resp.errorMessage != null) {
                    resultData.putString(CarUserManager.BUNDLE_USER_SWITCH_ERROR_MSG,
                            resp.errorMessage);
                }
                switch (resp.status) {
                    case SwitchUserStatus.SUCCESS:
                        boolean result;
                        try {
                            result = mAm.switchUser(targetUserId);
                            // TODO(b/150409110): post user switch OK/FAIL to Hal using
                            // ANDROID_POST_SWITCH
                            if (result) {
                                resultStatus = CarUserManager.USER_SWITCH_STATUS_SUCCESSFUL;
                            } else {
                                resultStatus = CarUserManager.USER_SWITCH_STATUS_ANDROID_FAILURE;
                            }
                        } catch (RemoteException e) {
                            // ignore
                            Log.w(TAG_USER,
                                    "error while switching user " + targetUser.toFullString(), e);
                        }
                        break;
                    case SwitchUserStatus.FAILURE:
                        // HAL failed to switch user
                        resultStatus = CarUserManager.USER_SWITCH_STATUS_HAL_FAILURE;
                        break;
                }
            }
            try {
                receiver.send(resultStatus, resultData);
            } catch (RemoteException e) {
                // ignore
                Log.w(TAG_USER, "error while sending results", e);
            }

        });
    }

    /**
     * Checks if the User HAL is supported.
     */
    public boolean isUserHalSupported() {
        return mHal.isSupported();
    }

    // TODO(b/144120654): use helper to generate UsersInfo
    private UsersInfo getUsersInfo() {
        UserInfo currentUser;
        try {
            currentUser = mAm.getCurrentUser();
        } catch (RemoteException e) {
            // shouldn't happen
            throw new IllegalStateException("Could not get current user: ", e);
        }
        List<UserInfo> existingUsers = mUserManager.getUsers();
        int size = existingUsers.size();

        UsersInfo usersInfo = new UsersInfo();
        usersInfo.numberUsers = size;
        usersInfo.currentUser.userId = currentUser.id;
        usersInfo.currentUser.flags = UserHalHelper.convertFlags(currentUser);

        for (int i = 0; i < size; i++) {
            UserInfo androidUser = existingUsers.get(i);
            android.hardware.automotive.vehicle.V2_0.UserInfo halUser =
                    new android.hardware.automotive.vehicle.V2_0.UserInfo();
            halUser.userId = androidUser.id;
            halUser.flags = UserHalHelper.convertFlags(androidUser);
            usersInfo.existingUsers.add(halUser);
        }

        return usersInfo;
    }

    /** Returns whether the given user is a system user. */
    private static boolean isSystemUser(@UserIdInt int userId) {
        return userId == UserHandle.USER_SYSTEM;
    }

    private void updateDefaultUserRestriction() {
        // We want to set restrictions on system and guest users only once. These are persisted
        // onto disk, so it's sufficient to do it once + we minimize the number of disk writes.
        if (Settings.Global.getInt(mContext.getContentResolver(),
                CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, /* default= */ 0) != 0) {
            return;
        }
        // Only apply the system user restrictions if the system user is headless.
        if (UserManager.isHeadlessSystemUserMode()) {
            setSystemUserRestrictions();
        }
        Settings.Global.putInt(mContext.getContentResolver(),
                CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, 1);
    }

    private boolean isPersistentUser(@UserIdInt int userId) {
        return !mUserManager.getUserInfo(userId).isEphemeral();
    }

    /**
     * Adds a new {@link UserLifecycleListener} to listen to user activity events.
     */
    public void addUserLifecycleListener(@NonNull UserLifecycleListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        mUserLifecycleListeners.add(listener);
    }

    /**
     * Removes previously added {@link UserLifecycleListener}.
     */
    public void removeUserLifecycleListener(@NonNull UserLifecycleListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        mUserLifecycleListeners.remove(listener);
    }

    /** Adds callback to listen to passenger activity events. */
    public void addPassengerCallback(@NonNull PassengerCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        mPassengerCallbacks.add(callback);
    }

    /** Removes previously added callback to listen passenger events. */
    public void removePassengerCallback(@NonNull PassengerCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        mPassengerCallbacks.remove(callback);
    }

    /** Sets the implementation of ZoneUserBindingHelper. */
    public void setZoneUserBindingHelper(@NonNull ZoneUserBindingHelper helper) {
        synchronized (mLockHelper) {
            mZoneUserBindingHelper = helper;
        }
    }

    private void unlockUser(@UserIdInt int userId) {
        TimingsTraceLog t = new TimingsTraceLog(TAG_USER, Trace.TRACE_TAG_SYSTEM_SERVER);
        notifyUserLifecycleListeners(
                new UserLifecycleEvent(CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKING, userId));
        t.traceBegin("UnlockTasks-" + userId);
        ArrayList<Runnable> tasks = null;
        synchronized (mLockUser) {
            if (userId == UserHandle.USER_SYSTEM) {
                if (!mUser0Unlocked) { // user 0, unlocked, do this only once
                    updateDefaultUserRestriction();
                    tasks = new ArrayList<>(mUser0UnlockTasks);
                    mUser0UnlockTasks.clear();
                    mUser0Unlocked = true;
                }
            } else { // none user0
                Integer user = userId;
                if (isPersistentUser(userId)) {
                    // current foreground user should stay in top priority.
                    if (userId == ActivityManager.getCurrentUser()) {
                        mBackgroundUsersToRestart.remove(user);
                        mBackgroundUsersToRestart.add(0, user);
                    }
                    // -1 for user 0
                    if (mBackgroundUsersToRestart.size() > (mMaxRunningUsers - 1)) {
                        int userToDrop = mBackgroundUsersToRestart.get(
                                mBackgroundUsersToRestart.size() - 1);
                        Log.i(TAG_USER, "New user unlocked:" + userId
                                + ", dropping least recently user from restart list:" + userToDrop);
                        // Drop the least recently used user.
                        mBackgroundUsersToRestart.remove(mBackgroundUsersToRestart.size() - 1);
                    }
                }
            }
        }
        if (tasks != null && tasks.size() > 0) {
            Log.d(TAG_USER, "User0 unlocked, run queued tasks:" + tasks.size());
            for (Runnable r : tasks) {
                r.run();
            }
        }
        t.traceEnd();
    }

    /**
     * Starts all background users that were active in system.
     *
     * @return list of background users started successfully.
     */
    @NonNull
    public ArrayList<Integer> startAllBackgroundUsers() {
        ArrayList<Integer> users;
        synchronized (mLockUser) {
            users = new ArrayList<>(mBackgroundUsersToRestart);
            mBackgroundUsersRestartedHere.clear();
            mBackgroundUsersRestartedHere.addAll(mBackgroundUsersToRestart);
        }
        ArrayList<Integer> startedUsers = new ArrayList<>();
        for (Integer user : users) {
            if (user == ActivityManager.getCurrentUser()) {
                continue;
            }
            try {
                if (mAm.startUserInBackground(user)) {
                    if (mUserManager.isUserUnlockingOrUnlocked(user)) {
                        // already unlocked / unlocking. No need to unlock.
                        startedUsers.add(user);
                    } else if (mAm.unlockUser(user, null, null, null)) {
                        startedUsers.add(user);
                    } else { // started but cannot unlock
                        Log.w(TAG_USER, "Background user started but cannot be unlocked:" + user);
                        if (mUserManager.isUserRunning(user)) {
                            // add to started list so that it can be stopped later.
                            startedUsers.add(user);
                        }
                    }
                }
            } catch (RemoteException e) {
                // ignore
                Log.w(TAG_USER, "error while starting user in background", e);
            }
        }
        // Keep only users that were re-started in mBackgroundUsersRestartedHere
        synchronized (mLockUser) {
            ArrayList<Integer> usersToRemove = new ArrayList<>();
            for (Integer user : mBackgroundUsersToRestart) {
                if (!startedUsers.contains(user)) {
                    usersToRemove.add(user);
                }
            }
            mBackgroundUsersRestartedHere.removeAll(usersToRemove);
        }
        return startedUsers;
    }

    /**
     * Stops all background users that were active in system.
     *
     * @return whether stopping succeeds.
     */
    public boolean stopBackgroundUser(@UserIdInt int userId) {
        if (userId == UserHandle.USER_SYSTEM) {
            return false;
        }
        if (userId == ActivityManager.getCurrentUser()) {
            Log.i(TAG_USER, "stopBackgroundUser, already a FG user:" + userId);
            return false;
        }
        try {
            int r = mAm.stopUserWithDelayedLocking(userId, true, null);
            if (r == ActivityManager.USER_OP_SUCCESS) {
                synchronized (mLockUser) {
                    Integer user = userId;
                    mBackgroundUsersRestartedHere.remove(user);
                }
            } else if (r == ActivityManager.USER_OP_IS_CURRENT) {
                return false;
            } else {
                Log.i(TAG_USER, "stopBackgroundUser failed, user:" + userId + " err:" + r);
                return false;
            }
        } catch (RemoteException e) {
            // ignore
            Log.w(TAG_USER, "error while stopping user", e);
        }
        return true;
    }

    /**
     * Notifies all registered {@link UserLifecycleListener} with the event passed as argument.
     */
    public void onUserLifecycleEvent(UserLifecycleEvent event) {
        int userId = event.getUserId();
        if (event.getEventType() == CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING) {
            onSwitchUser(userId);
        } else if (event.getEventType() == CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKING) {
            unlockUser(userId);
        }

        // TODO(b/144120654): right now just the app listeners are running in the background so the
        // CTS tests pass (as otherwise they might fail if a car service callback takes too long),
        // but once we refactor the car service callback into lifecycle listeners, we should use a
        // proper thread management (like a Threadpool / executor);

        // Notify all user listeners
        notifyUserLifecycleListeners(event);

        // Notify all app listeners
        notifyAppLifecycleListeners(event);
    }

    private void notifyAppLifecycleListeners(UserLifecycleEvent event) {
        int listenersSize = mLifecycleListeners.size();
        if (listenersSize == 0) {
            Log.i(TAG_USER, "No app listener to be notified");
            return;
        }
        new Thread(() -> {
            // Must use a different TimingsTraceLog because it's another thread
            TimingsTraceLog t = new TimingsTraceLog(TAG_USER, Trace.TRACE_TAG_SYSTEM_SERVER);
            Log.i(TAG_USER, "Notifying " + listenersSize + " app listeners");
            int userId = event.getUserId();
            for (int i = 0; i < listenersSize; i++) {
                int uid = mLifecycleListeners.keyAt(i);
                IResultReceiver listener = mLifecycleListeners.valueAt(i);
                t.traceBegin("notify-" + event.getEventType() + "-app-listener-" + uid);
                Bundle data = new Bundle();
                data.putInt(CarUserManager.BUNDLE_PARAM_ACTION, event.getEventType());
                // TODO(b/144120654): should pass currentId from CarServiceHelperService so it
                // can set BUNDLE_PARAM_PREVIOUS_USER_ID (and unit test it)
                if (Log.isLoggable(TAG_USER, Log.DEBUG)) {
                    Log.d(TAG_USER, "Notifying listener for uid " + uid);
                }
                try {
                    listener.send(userId, data);
                } catch (RemoteException e) {
                    Log.e(TAG_USER, "Error calling lifecycle listener", e);
                } finally {
                    t.traceEnd();
                }
            }
        }, "SwitchUser-" + event.getUserId() + "-Listeners").start();
    }

    private void notifyUserLifecycleListeners(UserLifecycleEvent event) {
        TimingsTraceLog t = new TimingsTraceLog(TAG_USER, Trace.TRACE_TAG_SYSTEM_SERVER);
        if (mUserLifecycleListeners.isEmpty()) {
            Log.i(TAG_USER, "Not notifying internal UserLifecycleListeners");
            return;
        }
        t.traceBegin("notifyInternalUserLifecycleListeners");
        for (UserLifecycleListener listener : mUserLifecycleListeners) {
            t.traceBegin("notify-" + event.getEventType() + "-listener-" + listener);
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                Log.e(TAG_USER,
                        "Exception raised when invoking onEvent for " + listener, e);
            }
            t.traceEnd();
        }
        t.traceEnd();
    }

    private void onSwitchUser(@UserIdInt int userId) {
        Log.i(TAG_USER, "onSwitchUser() callback for user " + userId);
        TimingsTraceLog t = new TimingsTraceLog(TAG_USER, Trace.TRACE_TAG_SYSTEM_SERVER);
        t.traceBegin("onSwitchUser-" + userId);

        if (!isSystemUser(userId)) {
            mCarUserManagerHelper.setLastActiveUser(userId);
        }
        if (mLastPassengerId != UserHandle.USER_NULL) {
            stopPassengerInternal(mLastPassengerId, false);
        }
        if (mEnablePassengerSupport && isPassengerDisplayAvailable()) {
            setupPassengerUser();
            startFirstPassenger(userId);
        }
    }

    /**
     * Runs the given runnable when user 0 is unlocked. If user 0 is already unlocked, it is
     * run inside this call.
     *
     * @param r Runnable to run.
     */
    public void runOnUser0Unlock(@NonNull Runnable r) {
        Objects.requireNonNull(r, "runnable cannot be null");
        boolean runNow = false;
        synchronized (mLockUser) {
            if (mUser0Unlocked) {
                runNow = true;
            } else {
                mUser0UnlockTasks.add(r);
            }
        }
        if (runNow) {
            r.run();
        }
    }

    @VisibleForTesting
    @NonNull
    ArrayList<Integer> getBackgroundUsersToRestart() {
        ArrayList<Integer> backgroundUsersToRestart = null;
        synchronized (mLockUser) {
            backgroundUsersToRestart = new ArrayList<>(mBackgroundUsersToRestart);
        }
        return backgroundUsersToRestart;
    }

    private void setSystemUserRestrictions() {
        // Disable Location service for system user.
        LocationManager locationManager =
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        locationManager.setLocationEnabledForUser(
                /* enabled= */ false, UserHandle.of(UserHandle.USER_SYSTEM));
    }

    /**
     * Creates a new user on the system, the created user would be granted admin role.
     *
     * @param name Name to be given to the newly created user.
     * @return newly created admin user, {@code null} if it fails to create a user.
     */
    @Nullable
    private UserInfo createNewAdminUser(String name) {
        if (!(mUserManager.isAdminUser() || mUserManager.isSystemUser())) {
            // Only admins or system user can create other privileged users.
            Log.e(TAG_USER, "Only admin users and system user can create other admins.");
            return null;
        }

        UserInfo user = mUserManager.createUser(name, UserInfo.FLAG_ADMIN);
        if (user == null) {
            // Couldn't create user, most likely because there are too many.
            Log.w(TAG_USER, "can't create admin user.");
            return null;
        }
        assignDefaultIcon(user);

        return user;
    }

    /**
     * Assigns a default icon to a user according to the user's id.
     *
     * @param userInfo User whose avatar is set to default icon.
     * @return Bitmap of the user icon.
     */
    private Bitmap assignDefaultIcon(UserInfo userInfo) {
        int idForIcon = userInfo.isGuest() ? UserHandle.USER_NULL : userInfo.id;
        Bitmap bitmap = UserIcons.convertToBitmap(
                UserIcons.getDefaultUserIcon(mContext.getResources(), idForIcon, false));
        mUserManager.setUserIcon(userInfo.id, bitmap);
        return bitmap;
    }

    private interface UserFilter {
        boolean isEligibleUser(UserInfo user);
    }

    /** Returns all users who are matched by the given filter. */
    private List<UserInfo> getUsers(UserFilter filter) {
        List<UserInfo> users = mUserManager.getUsers(/* excludeDying= */ true);

        for (Iterator<UserInfo> iterator = users.iterator(); iterator.hasNext(); ) {
            UserInfo user = iterator.next();
            if (!filter.isEligibleUser(user)) {
                iterator.remove();
            }
        }
        return users;
    }

    /**
     * Enforces that apps which have the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS}
     * can make certain calls to the CarUserManager.
     *
     * @param message used as message if SecurityException is thrown.
     * @throws SecurityException if the caller is not system or root.
     */
    private static void checkManageUsersPermission(String message) {
        checkAtLeastOnePermission(message, android.Manifest.permission.MANAGE_USERS);
    }

    private static void checkManageUsersOrDumpPermission(String message) {
        checkAtLeastOnePermission(message,
                android.Manifest.permission.MANAGE_USERS,
                android.Manifest.permission.DUMP);
    }

    private void checkInteractAcrossUsersPermission(String message) {
        checkAtLeastOnePermission(message, android.Manifest.permission.INTERACT_ACROSS_USERS,
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    private static void checkAtLeastOnePermission(String message, String...permissions) {
        int callingUid = Binder.getCallingUid();
        if (!hasAtLeastOnePermissionGranted(callingUid, permissions)) {
            throw new SecurityException("You need one of " + Arrays.toString(permissions)
                    + " to: " + message);
        }
    }

    private static boolean hasAtLeastOnePermissionGranted(int uid, String... permissions) {
        for (String permission : permissions) {
            if (ActivityManager.checkComponentPermission(permission, uid, /* owningUid = */-1,
                    /* exported = */ true)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private int getNumberOfManagedProfiles(@UserIdInt int userId) {
        List<UserInfo> users = mUserManager.getUsers(/* excludeDying= */true);
        // Count all users that are managed profiles of the given user.
        int managedProfilesCount = 0;
        for (UserInfo user : users) {
            if (user.isManagedProfile() && user.profileGroupId == userId) {
                managedProfilesCount++;
            }
        }
        return managedProfilesCount;
    }

    /**
     * Starts the first passenger of the given driver and assigns the passenger to the front
     * passenger zone.
     *
     * @param driverId User id of the driver.
     * @return whether it succeeds.
     */
    private boolean startFirstPassenger(@UserIdInt int driverId) {
        int zoneId = getAvailablePassengerZone();
        if (zoneId == OccupantZoneInfo.INVALID_ZONE_ID) {
            Log.w(TAG_USER, "passenger occupant zone is not found");
            return false;
        }
        List<UserInfo> passengers = getPassengers(driverId);
        if (passengers.size() < 1) {
            Log.w(TAG_USER, "passenger is not found");
            return false;
        }
        // Only one passenger is supported. If there are two or more passengers, the first passenger
        // is chosen.
        int passengerId = passengers.get(0).id;
        if (!startPassenger(passengerId, zoneId)) {
            Log.w(TAG_USER, "cannot start passenger " + passengerId);
            return false;
        }
        return true;
    }

    private int getAvailablePassengerZone() {
        int[] occupantTypes = new int[] {CarOccupantZoneManager.OCCUPANT_TYPE_FRONT_PASSENGER,
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER};
        for (int occupantType : occupantTypes) {
            int zoneId = getZoneId(occupantType);
            if (zoneId != OccupantZoneInfo.INVALID_ZONE_ID) {
                return zoneId;
            }
        }
        return OccupantZoneInfo.INVALID_ZONE_ID;
    }

    /**
     * Creates a new passenger user when there is no passenger user.
     */
    private void setupPassengerUser() {
        int currentUser = ActivityManager.getCurrentUser();
        int profileCount = getNumberOfManagedProfiles(currentUser);
        if (profileCount > 0) {
            Log.w(TAG_USER, "max profile of user" + currentUser
                    + " is exceeded: current profile count is " + profileCount);
            return;
        }
        // TODO(b/140311342): Use resource string for the default passenger name.
        UserInfo passenger = createPassenger("Passenger", currentUser);
        if (passenger == null) {
            // Couldn't create user, most likely because there are too many.
            Log.w(TAG_USER, "cannot create a passenger user");
            return;
        }
    }

    @NonNull
    private List<OccupantZoneInfo> getOccupantZones(@OccupantTypeEnum int occupantType) {
        ZoneUserBindingHelper helper = null;
        synchronized (mLockHelper) {
            if (mZoneUserBindingHelper == null) {
                Log.w(TAG_USER, "implementation is not delegated");
                return new ArrayList<OccupantZoneInfo>();
            }
            helper = mZoneUserBindingHelper;
        }
        return helper.getOccupantZones(occupantType);
    }

    private boolean assignUserToOccupantZone(@UserIdInt int userId, int zoneId) {
        ZoneUserBindingHelper helper = null;
        synchronized (mLockHelper) {
            if (mZoneUserBindingHelper == null) {
                Log.w(TAG_USER, "implementation is not delegated");
                return false;
            }
            helper = mZoneUserBindingHelper;
        }
        return helper.assignUserToOccupantZone(userId, zoneId);
    }

    private boolean unassignUserFromOccupantZone(@UserIdInt int userId) {
        ZoneUserBindingHelper helper = null;
        synchronized (mLockHelper) {
            if (mZoneUserBindingHelper == null) {
                Log.w(TAG_USER, "implementation is not delegated");
                return false;
            }
            helper = mZoneUserBindingHelper;
        }
        return helper.unassignUserFromOccupantZone(userId);
    }

    private boolean isPassengerDisplayAvailable() {
        ZoneUserBindingHelper helper = null;
        synchronized (mLockHelper) {
            if (mZoneUserBindingHelper == null) {
                Log.w(TAG_USER, "implementation is not delegated");
                return false;
            }
            helper = mZoneUserBindingHelper;
        }
        return helper.isPassengerDisplayAvailable();
    }

    /**
     * Gets the zone id of the given occupant type. If there are two or more zones, the first found
     * zone is returned.
     *
     * @param occupantType The type of an occupant.
     * @return The zone id of the given occupant type. {@link OccupantZoneInfo.INVALID_ZONE_ID},
     *         if not found.
     */
    private int getZoneId(@OccupantTypeEnum int occupantType) {
        List<OccupantZoneInfo> zoneInfos = getOccupantZones(occupantType);
        return (zoneInfos.size() > 0) ? zoneInfos.get(0).zoneId : OccupantZoneInfo.INVALID_ZONE_ID;
    }
}
