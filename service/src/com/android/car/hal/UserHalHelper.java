/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.car.hal;

import android.annotation.NonNull;
import android.content.pm.UserInfo;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.os.UserHandle;

import com.android.car.hal.UserHalService.HalCallback;
import com.android.car.hal.UserHalService.HalCallback.HalCallbackStatus;

/**
 * Provides utility methods for User HAL related functionalities.
 */
public final class UserHalHelper {

    /**
     * Gets user-friendly representation of the status.
     */
    public static String halCallbackStatusToString(@HalCallbackStatus int status) {
        switch (status) {
            case HalCallback.STATUS_OK:
                return "OK";
            case HalCallback.STATUS_HAL_SET_TIMEOUT:
                return "HAL_SET_TIMEOUT";
            case HalCallback.STATUS_HAL_RESPONSE_TIMEOUT:
                return "HAL_RESPONSE_TIMEOUT";
            case HalCallback.STATUS_WRONG_HAL_RESPONSE:
                return "WRONG_HAL_RESPONSE";
            case HalCallback.STATUS_CONCURRENT_OPERATION:
                return "CONCURRENT_OPERATION";
            default:
                return "UNKNOWN-" + status;
        }
    }

    /**
     * Converts a string to a {@link InitialUserInfoRequestType}.
     *
     * @return valid type or numeric value if passed "as is"
     *
     * @throws IllegalArgumentException if type is not valid neither a number
     */
    public static int parseInitialUserInfoRequestType(@NonNull String type) {
        // TODO(b/146207078): add unit test
        switch(type) {
            case "FIRST_BOOT":
                return InitialUserInfoRequestType.FIRST_BOOT;
            case "FIRST_BOOT_AFTER_OTA":
                return InitialUserInfoRequestType.FIRST_BOOT_AFTER_OTA;
            case "COLD_BOOT":
                return InitialUserInfoRequestType.COLD_BOOT;
            case "RESUME":
                return InitialUserInfoRequestType.RESUME;
            default:
                try {
                    return Integer.parseInt(type);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid type: " + type);
                }
        }
    }

    /**
     * Converts Android user flags to HALs.
     */
    public static int convertFlags(@NonNull UserInfo user) {
        // TODO(b/146207078): add unit test
        int flags = UserFlags.NONE;
        if (user.id == UserHandle.USER_SYSTEM) {
            flags |= UserFlags.SYSTEM;
        }
        if (user.isAdmin()) {
            flags |= UserFlags.ADMIN;
        }
        if (user.isGuest()) {
            flags |= UserFlags.GUEST;
        }
        if (user.isEphemeral()) {
            flags |= UserFlags.EPHEMERAL;
        }

        return flags;
    }

    private UserHalHelper() {
        throw new UnsupportedOperationException("contains only static methods");
    }
}
