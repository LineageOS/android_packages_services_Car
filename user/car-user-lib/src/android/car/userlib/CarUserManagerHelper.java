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
import android.annotation.SystemApi;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.sysprop.CarProperties;
import android.util.Log;

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
    public static final Set<String> OPTIONAL_NON_ADMIN_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_OUTGOING_CALLS,
            UserManager.DISALLOW_SMS,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS
    );

    private final Context mContext;
    private final UserManager mUserManager;
    private final ActivityManager mActivityManager;
    private final TestableFrameworkWrapper mTestableFrameworkWrapper;

    /**
     * Initializes with a default name for admin users.
     *
     * @param context Application Context
     */
    public CarUserManagerHelper(Context context) {
        this(context, new TestableFrameworkWrapper());
    }

    @VisibleForTesting
    CarUserManagerHelper(Context context, TestableFrameworkWrapper testableFrameworkWrapper) {
        mContext = context.getApplicationContext();
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mTestableFrameworkWrapper = testableFrameworkWrapper;
    }

    /**
     * Set last active user.
     *
     * @param userId last active user id.
     */
    public void setLastActiveUser(int userId) {
        Settings.Global.putInt(
                mContext.getContentResolver(), Settings.Global.LAST_ACTIVE_USER_ID, userId);
    }

    /**
     * Get user id for the last active user.
     *
     * @return user id of the last active user.
     */
    public int getLastActiveUser() {
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
     * @return user id of the initial user to boot into on the device.
     */
    @SystemApi
    public int getInitialUser() {
        List<Integer> allUsers = userInfoListToUserIdList(getAllPersistentUsers());

        int bootUserOverride = mTestableFrameworkWrapper.getBootUserOverrideId(BOOT_USER_NOT_FOUND);

        // If an override user is present and a real user, return it
        if (bootUserOverride != BOOT_USER_NOT_FOUND
                && allUsers.contains(bootUserOverride)) {
            Log.i(TAG, "Boot user id override found for initial user, user id: "
                    + bootUserOverride);
            return bootUserOverride;
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

    private List<Integer> userInfoListToUserIdList(List<UserInfo> allUsers) {
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
     * Gets all the users that are non-ephemeral and can be brought to the foreground on the system.
     *
     * @return List of {@code UserInfo} for non-ephemeral users that associated with a real person.
     */
    private List<UserInfo> getAllPersistentUsers() {
        List<UserInfo> users = getAllUsers();
        for (Iterator<UserInfo> iterator = users.iterator(); iterator.hasNext(); ) {
            UserInfo userInfo = iterator.next();
            if (userInfo.isEphemeral()) {
                // Remove user that is ephemeral.
                iterator.remove();
            }
        }
        return users;
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
