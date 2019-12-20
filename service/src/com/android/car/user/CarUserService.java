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
import android.car.userlib.CarUserManagerHelper;
import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.location.LocationManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import com.android.car.CarServiceBase;
import com.android.car.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.internal.util.UserIcons;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
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
     * current foreground user bit the current foreground user should not be restarted.
     */
    @GuardedBy("mLockUser")
    private final ArrayList<Integer> mBackgroundUsersToRestart = new ArrayList<>();
    /**
     * Keep the list of background users started here. This is wholly for debugging purpose.
     */
    @GuardedBy("mLockUser")
    private final ArrayList<Integer> mBackgroundUsersRestartedHere = new ArrayList<>();

    private final CopyOnWriteArrayList<UserCallback> mUserCallbacks = new CopyOnWriteArrayList<>();

    /** Interface for callbacks related to user activities. */
    public interface UserCallback {
        /** Gets called when user lock status has been changed. */
        void onUserLockChanged(@UserIdInt int userId, boolean unlocked);
        /** Called when new foreground user started to boot. */
        void onSwitchUser(@UserIdInt int userId);
    }

    private final CopyOnWriteArrayList<PassengerCallback> mPassengerCallbacks =
            new CopyOnWriteArrayList<>();

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

    public CarUserService(
            @NonNull Context context, @NonNull CarUserManagerHelper carUserManagerHelper,
            @NonNull UserManager userManager, @NonNull IActivityManager am, int maxRunningUsers) {
        if (Log.isLoggable(TAG_USER, Log.DEBUG)) {
            Log.d(TAG_USER, "constructed");
        }
        mContext = context;
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
        synchronized (mLockUser) {
            writer.println("User0Unlocked: " + mUser0Unlocked);
            writer.println("MaxRunningUsers: " + mMaxRunningUsers);
            writer.println("BackgroundUsersToRestart: " + mBackgroundUsersToRestart);
            writer.println("BackgroundUsersRestarted: " + mBackgroundUsersRestartedHere);
            List<UserInfo> allDrivers = getAllDrivers();
            int driversSize = allDrivers.size();
            writer.println("NumberOfDrivers: " + driversSize);
            String prefix = "  ";
            for (int i = 0; i < driversSize; i++) {
                int driverId = allDrivers.get(i).id;
                writer.print(prefix + "#" + i + ": id=" + driverId);
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
            writer.println("EnablePassengerSupport: " + mEnablePassengerSupport);
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
        return mCarUserManagerHelper.createNewNonAdminUser(name);
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
     * @see CarUserManager.getAllDrivers
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
     * @see CarUserManager.getPassengers
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

    /** Adds callback to listen to passenger activity events. */
    public void addPassengerCallback(@NonNull PassengerCallback callback) {
        Preconditions.checkNotNull(callback, "callback cannot be null");
        mPassengerCallbacks.add(callback);
    }

    /** Removes previously added callback to listen passenger events. */
    public void removePassengerCallback(@NonNull PassengerCallback callback) {
        Preconditions.checkNotNull(callback, "callback cannot be null");
        mPassengerCallbacks.remove(callback);
    }

    /** Sets the implementation of ZoneUserBindingHelper. */
    public void setZoneUserBindingHelper(@NonNull ZoneUserBindingHelper helper) {
        synchronized (mLockHelper) {
            mZoneUserBindingHelper = helper;
        }
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
        synchronized (mLockUser) {
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
            int r = mAm.stopUser(userId, true, null);
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
     * Called when new foreground user started to boot.
     *
     * @param userId User id of new user.
     */
    public void onSwitchUser(@UserIdInt int userId) {
        if (!isSystemUser(userId) && isPersistentUser(userId)) {
            mCarUserManagerHelper.setLastActiveUser(userId);
        }
        if (mLastPassengerId != UserHandle.USER_NULL) {
            stopPassengerInternal(mLastPassengerId, false);
        }
        if (mEnablePassengerSupport && isPassengerDisplayAvailable()) {
            setupPassengerUser();
            startFirstPassenger(userId);
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
