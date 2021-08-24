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
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.internal.util.UserIcons;

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

    /** Type for Guest user */
    // TODO(b/197181121): Move it after making systemAPI
    public static final String USER_TYPE_FULL_GUEST = UserManager.USER_TYPE_FULL_GUEST;

    /** Assign default Icon for a given user. */
    public static Bitmap assignDefaultIcon(@NonNull Context context, @NonNull UserHandle user) {
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
}
