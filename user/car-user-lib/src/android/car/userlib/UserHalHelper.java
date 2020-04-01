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
package android.car.userlib;

import android.annotation.NonNull;
import android.car.userlib.HalCallback.HalCallbackStatus;
import android.content.pm.UserInfo;
import android.content.pm.UserInfo.UserInfoFlag;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.hardware.automotive.vehicle.V2_0.UsersInfo;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.DebugUtils;

import com.android.internal.util.Preconditions;

import java.util.Objects;

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
        Preconditions.checkArgument(user != null, "user cannot be null");
        Preconditions.checkArgument(user != null, "user cannot be null");

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

    /**
     * Checks if a HAL flag contains {@link UserFlags#SYSTEM}.
     */
    public static boolean isSystem(int flags) {
        return (flags & UserFlags.SYSTEM) != 0;
    }

    /**
     * Checks if a HAL flag contains {@link UserFlags#GUEST}.
     */
    public static boolean isGuest(int flags) {
        return (flags & UserFlags.GUEST) != 0;
    }

    /**
     * Checks if a HAL flag contains {@link UserFlags#EPHEMERAL}.
     */
    public static boolean isEphemeral(int flags) {
        return (flags & UserFlags.EPHEMERAL) != 0;
    }

    /**
     * Checks if a HAL flag contains {@link UserFlags#ADMIN}.
     */
    public static boolean isAdmin(int flags) {
        return (flags & UserFlags.ADMIN) != 0;
    }

    /**
     * Converts HAL flags to Android's.
     */
    @UserInfoFlag
    public static int toUserInfoFlags(int halFlags) {
        int flags = 0;
        if (isEphemeral(halFlags)) {
            flags |= UserInfo.FLAG_EPHEMERAL;
        }
        if (isAdmin(halFlags)) {
            flags |= UserInfo.FLAG_ADMIN;
        }
        return flags;
    }

    /**
     * Gets a user-friendly representation of the user flags.
     */
    @NonNull
    public static String userFlagsToString(int flags) {
        return DebugUtils.flagsToString(UserFlags.class, "", flags);
    }

    /**
     * Creates VehiclePropValue from request.
     */
    @NonNull
    public static VehiclePropValue createPropRequest(int requestId, int requestType,
                int requestProp) {
        VehiclePropValue propRequest = new VehiclePropValue();
        propRequest.prop = requestProp;
        propRequest.timestamp = SystemClock.elapsedRealtime();
        propRequest.value.int32Values.add(requestId);
        propRequest.value.int32Values.add(requestType);

        return propRequest;
    }

    /**
     * Adds users information to prop value.
     */
    public static void addUsersInfo(@NonNull VehiclePropValue propRequest,
                @NonNull UsersInfo usersInfo) {
        Objects.requireNonNull(usersInfo.currentUser, "Current user cannot be null");

        propRequest.value.int32Values.add(usersInfo.currentUser.userId);
        propRequest.value.int32Values.add(usersInfo.currentUser.flags);

        Preconditions.checkArgument(usersInfo.numberUsers == usersInfo.existingUsers.size(),
                "Number of existing users info does not match numberUsers");

        propRequest.value.int32Values.add(usersInfo.numberUsers);
        for (int i = 0; i < usersInfo.numberUsers; i++) {
            android.hardware.automotive.vehicle.V2_0.UserInfo userInfo =
                    usersInfo.existingUsers.get(i);
            propRequest.value.int32Values.add(userInfo.userId);
            propRequest.value.int32Values.add(userInfo.flags);
        }
    }

    private UserHalHelper() {
        throw new UnsupportedOperationException("contains only static methods");
    }
}
