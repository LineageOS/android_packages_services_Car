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

package android.car.userlib;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.sysprop.CarProperties;
import android.util.Log;
import android.util.TimingsTraceLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.UserIcons;

import com.google.android.collect.Sets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Helper class for {@link UserManager}, this is meant to be used by builds that support
 * Multi-user model with headless user 0. User 0 is not associated with a real person, and
 * can not be brought to foreground.
 *
 * <p>This class provides method for user management, including creating, removing, adding
 * and switching users. Methods related to get users will exclude system user by default.
 *
 * @hide
 * @deprecated In the process of being removed.  Use {@link UserManager} APIs directly instead.
 */
@Deprecated
public final class CarUserManagerHelper {
    private static final String TAG = "CarUserManagerHelper";

    private static final int BOOT_USER_NOT_FOUND = -1;

    /**
     * Default set of restrictions for Non-Admin users.
     */
    private static final Set<String> DEFAULT_NON_ADMIN_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_FACTORY_RESET
    );

    /**
     * Additional optional set of restrictions for Non-Admin users. These are the restrictions
     * configurable via Settings.
     */
    private static final Set<String> OPTIONAL_NON_ADMIN_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_OUTGOING_CALLS,
            UserManager.DISALLOW_SMS,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS
    );

    private final Context mContext;
    private final UserManager mUserManager;
    private final ActivityManager mActivityManager;

    /**
     * Initializes with a default name for admin users.
     *
     * @param context Application Context
     */
    public CarUserManagerHelper(Context context) {
        mContext = context.getApplicationContext();
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
    }

    /**
     * Sets the last active user.
     */
    public void setLastActiveUser(@UserIdInt int userId) {
        Settings.Global.putInt(
                mContext.getContentResolver(), Settings.Global.LAST_ACTIVE_USER_ID, userId);
    }

    private int getLastActiveUser() {
        return Settings.Global.getInt(
            mContext.getContentResolver(), Settings.Global.LAST_ACTIVE_USER_ID,
            /* default user id= */ UserHandle.USER_SYSTEM);
    }

    /**
     * Gets the user id for the initial user to boot into. This is only applicable for headless
     * system user model. This method checks for a system property and will only work for system
     * apps.
     *
     * This method checks for the initial user via three mechanisms in this order:
     * <ol>
     *     <li>Check for a boot user override via {@link CarProperties#boot_user_override_id()}</li>
     *     <li>Check for the last active user in the system</li>
     *     <li>Fallback to the smallest user id that is not {@link UserHandle.USER_SYSTEM}</li>
     * </ol>
     *
     * If any step fails to retrieve the stored id or the retrieved id does not exist on device,
     * then it will move onto the next step.
     *
     * @return user id of the initial user to boot into on the device, or
     * {@link UserHandle#USER_NULL} if there is no user available.
     */
    @VisibleForTesting
    int getInitialUser(boolean usesOverrideUserIdProperty) {

        List<Integer> allUsers = userInfoListToUserIdList(getAllUsers());

        if (allUsers.isEmpty()) {
            return UserHandle.USER_NULL;
        }

        if (usesOverrideUserIdProperty) {
            int bootUserOverride = CarProperties.boot_user_override_id()
                    .orElse(BOOT_USER_NOT_FOUND);

            // If an override user is present and a real user, return it
            if (bootUserOverride != BOOT_USER_NOT_FOUND
                    && allUsers.contains(bootUserOverride)) {
                Log.i(TAG, "Boot user id override found for initial user, user id: "
                        + bootUserOverride);
                return bootUserOverride;
            }
        }

        // If the last active user is not the SYSTEM user and is a real user, return it
        int lastActiveUser = getLastActiveUser();
        if (lastActiveUser != UserHandle.USER_SYSTEM
                && allUsers.contains(lastActiveUser)) {
            Log.i(TAG, "Last active user loaded for initial user, user id: "
                    + lastActiveUser);
            return lastActiveUser;
        }

        // If all else fails, return the smallest user id
        int returnId = Collections.min(allUsers);
        Log.i(TAG, "Saved ids were invalid. Returning smallest user id, user id: "
                + returnId);
        return returnId;
    }

    /**
     * Checks whether the device has an initial user that can be switched to.
     */
    public boolean hasInitialUser() {
        List<UserInfo> allUsers = getAllUsers();
        for (int i = 0; i < allUsers.size(); i++) {
            UserInfo user = allUsers.get(i);
            if (user.isManagedProfile()) continue;

            return true;
        }
        return false;
    }

    private static List<Integer> userInfoListToUserIdList(List<UserInfo> allUsers) {
        ArrayList<Integer> list = new ArrayList<>(allUsers.size());
        for (UserInfo userInfo : allUsers) {
            list.add(userInfo.id);
        }
        return list;
    }

    /**
     * Gets all the users that can be brought to the foreground on the system.
     *
     * @return List of {@code UserInfo} for users that associated with a real person.
     */
    private List<UserInfo> getAllUsers() {
        if (UserManager.isHeadlessSystemUserMode()) {
            return getAllUsersExceptSystemUserAndSpecifiedUser(UserHandle.USER_SYSTEM);
        } else {
            return mUserManager.getUsers(/* excludeDying= */ true);
        }
    }

    /**
     * Get all the users except system user and the one with userId passed in.
     *
     * @param userId of the user not to be returned.
     * @return All users other than system user and user with userId.
     */
    private List<UserInfo> getAllUsersExceptSystemUserAndSpecifiedUser(int userId) {
        List<UserInfo> users = mUserManager.getUsers(/* excludeDying= */true);

        for (Iterator<UserInfo> iterator = users.iterator(); iterator.hasNext(); ) {
            UserInfo userInfo = iterator.next();
            if (userInfo.id == userId || userInfo.id == UserHandle.USER_SYSTEM) {
                // Remove user with userId from the list.
                iterator.remove();
            }
        }
        return users;
    }

    // Current process user restriction accessors

    /**
     * Grants admin permissions to the user.
     *
     * @param user User to be upgraded to Admin status.
     */
    @RequiresPermission(allOf = {
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.MANAGE_USERS
    })
    public void grantAdminPermissions(UserInfo user) {
        if (!mUserManager.isAdminUser()) {
            Log.w(TAG, "Only admin users can assign admin permissions.");
            return;
        }

        mUserManager.setUserAdmin(user.id);

        // Remove restrictions imposed on non-admins.
        setDefaultNonAdminRestrictions(user, /* enable= */ false);
        setOptionalNonAdminRestrictions(user, /* enable= */ false);
    }

    /**
     * Creates a new non-admin user on the system.
     *
     * @param userName Name to give to the newly created user.
     * @return Newly created non-admin user, null if failed to create a user.
     */
    @Nullable
    public UserInfo createNewNonAdminUser(String userName) {
        UserInfo user = mUserManager.createUser(userName, 0);
        if (user == null) {
            // Couldn't create user, most likely because there are too many.
            Log.w(TAG, "can't create non-admin user.");
            return null;
        }
        setDefaultNonAdminRestrictions(user, /* enable= */ true);

        assignDefaultIcon(user);
        return user;
    }

    /**
     * Sets the values of default Non-Admin restrictions to the passed in value.
     *
     * @param userInfo User to set restrictions on.
     * @param enable If true, restriction is ON, If false, restriction is OFF.
     */
    public void setDefaultNonAdminRestrictions(UserInfo userInfo, boolean enable) {
        for (String restriction : DEFAULT_NON_ADMIN_RESTRICTIONS) {
            mUserManager.setUserRestriction(restriction, enable, userInfo.getUserHandle());
        }
    }

    /**
     * Sets the values of settings controllable restrictions to the passed in value.
     *
     * @param userInfo User to set restrictions on.
     * @param enable If true, restriction is ON, If false, restriction is OFF.
     */
    private void setOptionalNonAdminRestrictions(UserInfo userInfo, boolean enable) {
        for (String restriction : OPTIONAL_NON_ADMIN_RESTRICTIONS) {
            mUserManager.setUserRestriction(restriction, enable, userInfo.getUserHandle());
        }
    }

    /**
     * Switches (logs in) to another user given user id.
     *
     * @param id User id to switch to.
     * @return {@code true} if user switching succeed.
     */
    public boolean switchToUserId(int id) {
        if (id == UserHandle.USER_SYSTEM && UserManager.isHeadlessSystemUserMode()) {
            // System User doesn't associate with real person, can not be switched to.
            return false;
        }
        if (mUserManager.getUserSwitchability() != UserManager.SWITCHABILITY_STATUS_OK) {
            return false;
        }
        if (id == ActivityManager.getCurrentUser()) {
            return false;
        }
        return mActivityManager.switchUser(id);
    }

    /**
     * Streamlined version of {@code switchUser()} - should only be called on boot / resume.
     */
    public boolean startForegroundUser(@UserIdInt int userId) {
        if (userId == UserHandle.USER_SYSTEM && UserManager.isHeadlessSystemUserMode()) {
            // System User doesn't associate with real person, can not be switched to.
            return false;
        }
        try {
            return ActivityManager.getService().startUserInForegroundWithListener(userId, null);
        } catch (RemoteException e) {
            Log.w(TAG, "failed to start user " + userId, e);
            return false;
        }
    }

    @VisibleForTesting
    void unlockSystemUser() {
        Log.i(TAG, "unlocking system user");
        IActivityManager am = ActivityManager.getService();

        TimingsTraceLog t = new TimingsTraceLog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
        t.traceBegin("UnlockSystemUser");
        try {
            // This is for force changing state into RUNNING_LOCKED. Otherwise unlock does not
            // update the state and USER_SYSTEM unlock happens twice.
            t.traceBegin("am.startUser");
            boolean started = am.startUserInBackground(UserHandle.USER_SYSTEM);
            t.traceEnd();
            if (!started) {
                Log.w(TAG, "could not restart system user in foreground; trying unlock instead");
                t.traceBegin("am.unlockUser");
                boolean unlocked = am.unlockUser(UserHandle.USER_SYSTEM, /* token= */ null,
                        /* secret= */ null, /* listener= */ null);
                t.traceEnd();
                if (!unlocked) {
                    Log.w(TAG, "could not unlock system user neither");
                    return;
                }
            }
        } catch (RemoteException e) {
            // should not happen for local call.
            Log.wtf("RemoteException from AMS", e);
        } finally {
            t.traceEnd();
        }
    }

    /**
     * Switches (logs in) to another user.
     *
     * @param userInfo User to switch to.
     * @return {@code true} if user switching succeed.
     */
    public boolean switchToUser(UserInfo userInfo) {
        return switchToUserId(userInfo.id);
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
}
