/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.car.builtin.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.internal.util.UserIcons;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper for User related operations.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class UserManagerHelper {
    private UserManagerHelper() {
        throw new UnsupportedOperationException();
    }

    /** user id for invalid user */
    public static final @UserIdInt int USER_NULL = UserHandle.USER_NULL;

    /** A user id constant to indicate the "system" user of the device */
    public static final @UserIdInt int USER_SYSTEM = UserHandle.USER_SYSTEM;

    // TODO(b/197181121): Move it after making systemAPI
    /**
     * Type for Guest user
     *
     * @deprecated Move it after making systemAPI
     */
    @Deprecated
    public static final String USER_TYPE_FULL_GUEST = UserManager.USER_TYPE_FULL_GUEST;

    // Flags copied from UserInfo.
    public static final int FLAG_PRIMARY = UserInfo.FLAG_PRIMARY;
    public static final int FLAG_ADMIN = UserInfo.FLAG_ADMIN;
    public static final int FLAG_GUEST = UserInfo.FLAG_GUEST;
    public static final int FLAG_RESTRICTED = UserInfo.FLAG_RESTRICTED;
    public static final int FLAG_INITIALIZED = UserInfo.FLAG_INITIALIZED;
    public static final int FLAG_MANAGED_PROFILE = UserInfo.FLAG_MANAGED_PROFILE;
    public static final int FLAG_DISABLED = UserInfo.FLAG_DISABLED;
    public static final int FLAG_QUIET_MODE = UserInfo.FLAG_QUIET_MODE;
    public static final int FLAG_EPHEMERAL = UserInfo.FLAG_EPHEMERAL;
    public static final int FLAG_DEMO = UserInfo.FLAG_DEMO;
    public static final int FLAG_FULL = UserInfo.FLAG_FULL;
    public static final int FLAG_SYSTEM = UserInfo.FLAG_SYSTEM;
    public static final int FLAG_PROFILE = UserInfo.FLAG_PROFILE;

    /** Assign default Icon for a given user. */
    public static Bitmap assignDefaultIconForUser(@NonNull Context context,
            @NonNull UserHandle user) {
        UserManager userManager = context.getSystemService(UserManager.class);
        UserInfo userInfo = userManager.getUserInfo(user.getIdentifier());
        if (userInfo == null) {
            return null;
        }
        int idForIcon = userInfo.isGuest() ? UserHandle.USER_NULL : user.getIdentifier();
        Bitmap bitmap = UserIcons.convertToBitmap(
                UserIcons.getDefaultUserIcon(context.getResources(), idForIcon, false));
        userManager.setUserIcon(user.getIdentifier(), bitmap);
        return bitmap;
    }

    /**
     * Sets the value of a specific restriction on a specific user
     */
    public static void setUserRestriction(@NonNull UserManager userManager,
            @NonNull String restriction, boolean enable, @NonNull UserHandle user) {
        userManager.setUserRestriction(restriction, enable, user);
    }

    /** Assigns admin privileges to the user */
    public static void setUserAdmin(@NonNull UserManager userManager, @NonNull UserHandle user) {
        userManager.setUserAdmin(user.getIdentifier());
    }

    /**
     * Would be removed after making getUserHandle a system API with parameters.
     *
     * @deprecated Would be removed
     */
    @Deprecated
    @NonNull
    public static List<UserHandle> getUserHandles(@NonNull UserManager userManager,
            boolean excludePartial, boolean excludeDying) {
        return getUserHandles(userManager, excludePartial, excludeDying,
                /* excludePreCreated= */ true);
    }

    /**
     * Returns all users based on the boolean flags.
     */
    @NonNull
    public static List<UserHandle> getUserHandles(@NonNull UserManager userManager,
            boolean excludePartial, boolean excludeDying, boolean excludePreCreated) {
        List<UserInfo> users = userManager.getUsers(excludePartial, excludeDying,
                excludePreCreated);

        List<UserHandle> result = new ArrayList<>(users.size());
        for (UserInfo user : users) {
            result.add(user.getUserHandle());
        }
        return result;
    }

    /**
     * Would be removed after making isUserEphemeral a system API
     *
     * @deprecated Would be removed
     */
    @Deprecated
    public static boolean isEphemeralUser(@NonNull UserManager userManager,
            @NonNull UserHandle user) {
        return userManager.isUserEphemeral(user.getIdentifier());
    }

    /**
     * Would be removed after understanding the requirement of the call.
     *
     * @deprecated Would be removed
     */
    @Deprecated
    public static boolean isEnabledUser(@NonNull UserManager userManager,
            @NonNull UserHandle user) {
        return userManager.getUserInfo(user.getIdentifier()).isEnabled();
    }

    /**
     * Would be removed after more research in existing API.
     *
     * @deprecated Would be removed
     */
    @Deprecated
    public static boolean isAdminUser(@NonNull UserManager userManager,
            @NonNull UserHandle user) {
        return userManager.getUserInfo(user.getIdentifier()).isAdmin();
    }

    /**
     * Would be removed after more research in existing API.
     *
     * @deprecated Would be removed
     */
    @Deprecated
    public static boolean isGuestUser(@NonNull UserManager userManager,
            @NonNull UserHandle user) {
        return userManager.getUserInfo(user.getIdentifier()).isGuest();
    }

    /**
     * Checks if a user is precreated.
     */
    public static boolean isPreCreatedUser(@NonNull UserManager userManager,
            @NonNull UserHandle user) {
        return userManager.getUserInfo(user.getIdentifier()).preCreated;
    }

    /**
     * @deprecated Would be removed after more research in existing API
     */
    @Deprecated
    public static boolean isInitializedUser(@NonNull UserManager userManager,
            @NonNull UserHandle user) {
        return userManager.getUserInfo(user.getIdentifier()).isInitialized();
    }

    /**
     * Would be removed after more research in existing API.
     *
     * @deprecated Would be removed
     */
    @Deprecated
    public static boolean isProfileUser(@NonNull UserManager userManager,
            @NonNull UserHandle user) {
        return userManager.getUserInfo(user.getIdentifier()).isProfile();
    }

    /**
     * It may be replaced by isSameProfileGroup. Need to check.
     *
     * @deprecated Would be removed
     */
    @Deprecated
    public static int getProfileGroupId(@NonNull UserManager userManager,
            @NonNull UserHandle user) {
        return userManager.getUserInfo(user.getIdentifier()).profileGroupId;
    }

    /**
     * Gets DefaultUserType given userInfo flags.
     */
    public static String getDefaultUserTypeForUserInfoFlags(int userInfoFlag) {
        return UserInfo.getDefaultUserType(userInfoFlag);
    }

    /**
     * Precreates user based on user type
     */
    @Nullable
    public static UserHandle preCreateUser(@NonNull UserManager userManager, @NonNull String type) {
        UserInfo userInfo = userManager.preCreateUser(type);
        return userInfo == null ? null : userInfo.getUserHandle();
    }

    /**
     * Gets the default name for a user.
     */
    @NonNull
    public static String getDefaultUserName(@NonNull Context context) {
        return context.getResources().getString(com.android.internal.R.string.owner_name);
    }

    /**
     * Gets the maximum number of users that can be running at any given time.
     */
    public static int getMaxRunningUsers(@NonNull Context context) {
        return context.getResources()
                .getInteger(com.android.internal.R.integer.config_multiuserMaxRunningUsers);
    }
}
