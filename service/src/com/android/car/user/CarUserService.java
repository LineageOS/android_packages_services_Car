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
import android.app.IActivityManager;
import android.car.ICarUserService;
import android.car.settings.CarSettings;
import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import com.android.car.CarServiceBase;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.internal.util.UserIcons;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
    private final Context mContext;
    private final IActivityManager mAm;
    private final UserManager mUserManager;
    private final int mMaxRunningUsers;

    /**
     * Default restrictions for Non-Admin users.
     */
    private static final String[] DEFAULT_NON_ADMIN_RESTRICTIONS = new String[] {
            UserManager.DISALLOW_FACTORY_RESET
    };

    /**
     * Default restrictions for Guest users.
     */
    private static final String[] DEFAULT_GUEST_RESTRICTIONS = new String[] {
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_REMOVE_USER,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_UNINSTALL_APPS
    };

    /**
     * List of restrictions relaxed for Non-Admin users.
     *
     * <p>Each non-admin has sms and outgoing call restrictions applied by the UserManager on
     * creation. We want to enable these permissions by default in the car.
     */
    private static final String[] RELAXED_RESTRICTIONS_FOR_NON_ADMIN = new String[] {
            UserManager.DISALLOW_SMS,
            UserManager.DISALLOW_OUTGOING_CALLS
    };

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private boolean mUser0Unlocked;
    @GuardedBy("mLock")
    private final ArrayList<Runnable> mUser0UnlockTasks = new ArrayList<>();
    /**
     * Background users that will be restarted in garage mode. This list can include the
     * current foreground user bit the current foreground user should not be restarted.
     */
    @GuardedBy("mLock")
    private final ArrayList<Integer> mBackgroundUsersToRestart = new ArrayList<>();
    /**
     * Keep the list of background users started here. This is wholly for debugging purpose.
     */
    @GuardedBy("mLock")
    private final ArrayList<Integer> mBackgroundUsersRestartedHere = new ArrayList<>();

    private final CopyOnWriteArrayList<UserCallback> mUserCallbacks = new CopyOnWriteArrayList<>();

    /** Interface for callbacks related to user activities. */
    public interface UserCallback {
        /** Gets called when user lock status has been changed. */
        void onUserLockChanged(@UserIdInt int userId, boolean unlocked);
        /** Called when new foreground user started to boot. */
        void onSwitchUser(@UserIdInt int userId);
    }

    public CarUserService(@NonNull Context context, @NonNull UserManager userManager,
            @NonNull IActivityManager am, int maxRunningUsers) {
        if (Log.isLoggable(TAG_USER, Log.DEBUG)) {
            Log.d(TAG_USER, "constructed");
        }
        mContext = context;
        mAm = am;
        mMaxRunningUsers = maxRunningUsers;
        mUserManager = userManager;
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
        writer.println("*CarUserService*");
        synchronized (mLock) {
            writer.println("User0Unlocked: " + mUser0Unlocked);
            writer.println("MaxRunningUsers: " + mMaxRunningUsers);
            writer.println("BackgroundUsersToRestart: " + mBackgroundUsersToRestart);
            writer.println("BackgroundUsersRestarted: " + mBackgroundUsersRestartedHere);
            writer.println("NumberOfDrivers: " + getAllDrivers().size());
        }
    }

    /**
     * @see CarUserManager.createDriver
     */
    @Override
    @Nullable
    public UserInfo createDriver(@NonNull String name, boolean admin) {
        checkManageUsersPermission("createDriver");
        Preconditions.checkNotNull(name, "name cannot be null");
        if (admin) {
            return createNewAdminUser(name);
        }
        return createNewNonAdminUser(name);
    }

    /**
     * @see CarUserManager.createPassenger
     */
    @Override
    @Nullable
    public UserInfo createPassenger(@NonNull String name, @UserIdInt int driverId) {
        checkManageUsersPermission("createPassenger");
        Preconditions.checkNotNull(name, "name cannot be null");
        UserInfo driver = mUserManager.getUserInfo(driverId);
        if (driver == null) {
            Log.w(TAG_USER, "the driver is invalid");
            return null;
        }
        if (driver.isGuest()) {
            Log.w(TAG_USER, "a guest driver cannot create a passenger");
            return null;
        }
        UserInfo user = mUserManager.createProfileForUser(name, UserInfo.FLAG_MANAGED_PROFILE,
                driverId);
        if (user == null) {
            // Couldn't create user, most likely because there are too many.
            Log.w(TAG_USER, "can't create a profile for user" + driverId);
            return null;
        }
        // Passenger user should be a non-admin user.
        setDefaultNonAdminRestrictions(user, /* enable= */ true);
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
        if (driverId == getCurrentUserId()) {
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
     * @see CarUserManager.getAllDrivers
     */
    @Override
    @NonNull
    public List<UserInfo> getAllDrivers() {
        checkManageUsersPermission("getAllDrivers");
        return getUsers((user) -> {
            return !isSystemUser(user.id) && user.isEnabled() && !user.isManagedProfile()
                    && !user.isEphemeral();
        });
    }

    /**
     * @see CarUserManager.getPassengers
     * @return
     */
    @Override
    @NonNull
    public List<UserInfo> getPassengers(@UserIdInt int driverId) {
        checkManageUsersPermission("getPassengers");
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
        // TODO(b/139190199): this method will be implemented when dynamic profile group is enabled.
        return false;
    }

    /**
     * @see CarUserManager.stopPassenger
     */
    @Override
    public boolean stopPassenger(@UserIdInt int passengerId) {
        checkManageUsersPermission("stopPassenger");
        // TODO(b/139190199): this method will be implemented when dynamic profile group is enabled.
        return false;
    }

    /** Returns whether the given user is a system user. */
    private static boolean isSystemUser(@UserIdInt int userId) {
        return userId == UserHandle.USER_SYSTEM;
    }

    /** Returns whether the user running the current process has a restriction. */
    private boolean isCurrentProcessUserHasRestriction(String restriction) {
        return mUserManager.hasUserRestriction(restriction);
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
        initDefaultGuestRestrictions();
        Settings.Global.putInt(mContext.getContentResolver(),
                CarSettings.Global.DEFAULT_USER_RESTRICTIONS_SET, 1);
    }

    /**
     * Sets default guest restrictions that will be applied every time a Guest user is created.
     *
     * <p> Restrictions are written to disk and persistent across boots.
     */
    private void initDefaultGuestRestrictions() {
        Bundle defaultGuestRestrictions = new Bundle();
        for (String restriction : DEFAULT_GUEST_RESTRICTIONS) {
            defaultGuestRestrictions.putBoolean(restriction, true);
        }
        mUserManager.setDefaultGuestRestrictions(defaultGuestRestrictions);
    }

    private boolean isPersistentUser(@UserIdInt int userId) {
        return !mUserManager.getUserInfo(userId).isEphemeral();
    }

    /** Adds callback to listen to user activity events. */
    public void addUserCallback(@NonNull UserCallback callback) {
        Preconditions.checkNotNull(callback, "callback cannot be null");
        mUserCallbacks.add(callback);
    }

    /** Removes previously added callback to listen user events. */
    public void removeUserCallback(@NonNull UserCallback callback) {
        Preconditions.checkNotNull(callback, "callback cannot be null");
        mUserCallbacks.remove(callback);
    }

    /**
     * Sets user lock/unlocking status. This is coming from system server through ICar binder call.
     *
     * @param userId User id whoes lock status is changed.
     * @param unlocked Unlocked (={@code true}) or locked (={@code false}).
     */
    public void setUserLockStatus(@UserIdInt int userId, boolean unlocked) {
        for (UserCallback callback : mUserCallbacks) {
            callback.onUserLockChanged(userId, unlocked);
        }
        if (!unlocked) { // nothing else to do when it is locked back.
            return;
        }
        ArrayList<Runnable> tasks = null;
        synchronized (mLock) {
            if (userId == UserHandle.USER_SYSTEM) {
                if (!mUser0Unlocked) { // user 0, unlocked, do this only once
                    updateDefaultUserRestriction();
                    tasks = new ArrayList<>(mUser0UnlockTasks);
                    mUser0UnlockTasks.clear();
                    mUser0Unlocked = unlocked;
                }
            } else { // none user0
                Integer user = userId;
                if (isPersistentUser(userId)) {
                    // current foreground user should stay in top priority.
                    if (userId == getCurrentUserId()) {
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
    }

    /**
     * Starts all background users that were active in system.
     *
     * @return list of background users started successfully.
     */
    @NonNull
    public ArrayList<Integer> startAllBackgroundUsers() {
        ArrayList<Integer> users;
        synchronized (mLock) {
            users = new ArrayList<>(mBackgroundUsersToRestart);
            mBackgroundUsersRestartedHere.clear();
            mBackgroundUsersRestartedHere.addAll(mBackgroundUsersToRestart);
        }
        ArrayList<Integer> startedUsers = new ArrayList<>();
        for (Integer user : users) {
            if (user == getCurrentUserId()) {
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
        synchronized (mLock) {
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
        if (userId == getCurrentUserId()) {
            Log.i(TAG_USER, "stopBackgroundUser, already a FG user:" + userId);
            return false;
        }
        try {
            int r = mAm.stopUser(userId, true, null);
            if (r == ActivityManager.USER_OP_SUCCESS) {
                synchronized (mLock) {
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
     * Called when new foreground user started to boot.
     *
     * @param userId User id of new user.
     */
    public void onSwitchUser(@UserIdInt int userId) {
        if (!isSystemUser(userId) && isPersistentUser(userId)) {
            setLastActiveUser(userId);
        }
        for (UserCallback callback : mUserCallbacks) {
            callback.onSwitchUser(userId);
        }
    }

    /**
     * Runs the given runnable when user 0 is unlocked. If user 0 is already unlocked, it is
     * run inside this call.
     *
     * @param r Runnable to run.
     */
    public void runOnUser0Unlock(@NonNull Runnable r) {
        Preconditions.checkNotNull(r, "runnable cannot be null");
        boolean runNow = false;
        synchronized (mLock) {
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
        synchronized (mLock) {
            backgroundUsersToRestart = new ArrayList<>(mBackgroundUsersToRestart);
        }
        return backgroundUsersToRestart;
    }

    private void setSystemUserRestrictions() {
        // Disable adding accounts for system user.
        UserHandle systemUserHandle = UserHandle.of(UserHandle.USER_SYSTEM);
        mUserManager.setUserRestriction(
                UserManager.DISALLOW_MODIFY_ACCOUNTS, /* value= */ true, systemUserHandle);

        // Disable Location service for system user.
        LocationManager locationManager =
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        locationManager.setLocationEnabledForUser(/* enabled= */ false, systemUserHandle);
    }

    /**
     * Creates a new user on the system, the created user would be granted admin role.
     *
     * @param name Name to be given to the newly created user.
     * @return Newly created admin user, {@code null} if it fails to create a user.
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
     * Creates a new non-admin user on the system.
     *
     * @param name Name to be given to the newly created user.
     * @return Newly created non-admin user, {@code null} if failed to create a user.
     */
    @Nullable
    private UserInfo createNewNonAdminUser(String name) {
        UserInfo user = mUserManager.createUser(name, /* flags= */ 0);
        if (user == null) {
            // Couldn't create user, most likely because there are too many.
            Log.w(TAG_USER, "can't create non-admin user.");
            return null;
        }
        setDefaultNonAdminRestrictions(user, /* enable= */ true);

        // Remove restrictions which are allowed for non-admin car users.
        for (String restriction : RELAXED_RESTRICTIONS_FOR_NON_ADMIN) {
            mUserManager.setUserRestriction(restriction, /* enable= */ false, user.getUserHandle());
        }

        assignDefaultIcon(user);
        return user;
    }

    private Bitmap getUserDefaultIcon(UserInfo userInfo) {
        return UserIcons.convertToBitmap(
                UserIcons.getDefaultUserIcon(mContext.getResources(), userInfo.id, false));
    }

    private Bitmap getGuestDefaultIcon() {
        return UserIcons.convertToBitmap(UserIcons.getDefaultUserIcon(mContext.getResources(),
                UserHandle.USER_NULL, false));
    }

    /** Assigns a default icon to a user according to the user's id. */
    private void assignDefaultIcon(UserInfo userInfo) {
        Bitmap bitmap = userInfo.isGuest()
                ? getGuestDefaultIcon() : getUserDefaultIcon(userInfo);
        mUserManager.setUserIcon(userInfo.id, bitmap);
    }

    private void setDefaultNonAdminRestrictions(UserInfo userInfo, boolean enable) {
        for (String restriction : DEFAULT_NON_ADMIN_RESTRICTIONS) {
            mUserManager.setUserRestriction(restriction, enable, userInfo.getUserHandle());
        }
    }

    /** Gets the current user on the device. */
    @VisibleForTesting
    @UserIdInt
    int getCurrentUserId() {
        UserInfo user = getCurrentUser();
        return user != null ? user.id : UserHandle.USER_NULL;
    }

    @Nullable
    private UserInfo getCurrentUser() {
        UserInfo user = null;
        try {
            user = mAm.getCurrentUser();
        } catch (RemoteException e) {
            // ignore
            Log.w(TAG_USER, "error while getting current user", e);
        }
        return user;
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
     * Sets last active user.
     *
     * @param userId Last active user id.
     */
    @VisibleForTesting
    void setLastActiveUser(@UserIdInt int userId) {
        Settings.Global.putInt(
                mContext.getContentResolver(), Settings.Global.LAST_ACTIVE_USER_ID, userId);
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
        int callingUid = Binder.getCallingUid();
        if (!hasPermissionGranted(android.Manifest.permission.MANAGE_USERS, callingUid)) {
            throw new SecurityException(
                    "You need MANAGE_USERS permission to: " + message);
        }
    }

    private static boolean hasPermissionGranted(String permission, int uid) {
        return ActivityManager.checkComponentPermission(
                permission, uid, /* owningUid = */-1, /* exported = */ true)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
