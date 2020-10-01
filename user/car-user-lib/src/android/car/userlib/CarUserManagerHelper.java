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
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserManager;
import android.util.Log;

import com.google.android.collect.Sets;

import java.util.Set;

/**
 * Helper class for {@link UserManager}, this is meant to be used by builds that support
 * Multi-user model with headless user 0. User 0 is not associated with a real person, and
 * can not be brought to foreground.
 *
 * <p>This class provides method for user management, including creating, removing, adding
 * and switching users. Methods related to get users will exclude system user by default.
 *
 * <p><b>Note: </b>this class is in the process of being removed.  Use {@link UserManager} APIs
 * directly or {@link android.car.user.CarUserManager.CarUserManager} instead.
 *
 * @hide
 */
public final class CarUserManagerHelper {
    private static final String TAG = "CarUserManagerHelper";

    private static final boolean DEBUG = false;

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
        mContext = context;
        mUserManager = UserManager.get(mContext);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
    }

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
     * Sets the values of default Non-Admin restrictions to the passed in value.
     *
     * @param userInfo User to set restrictions on.
     * @param enable If true, restriction is ON, If false, restriction is OFF.
     */
    private void setDefaultNonAdminRestrictions(UserInfo userInfo, boolean enable) {
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
}
