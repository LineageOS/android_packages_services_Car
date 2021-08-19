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

package com.android.car.admin;

import static com.android.car.PermissionHelper.checkHasDumpPermissionGranted;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.car.admin.CarDevicePolicyManager;
import android.car.admin.ICarDevicePolicyService;
import android.car.builtin.util.Slog;
import android.car.user.UserCreationResult;
import android.car.user.UserRemovalResult;
import android.car.user.UserStartResult;
import android.car.user.UserStopResult;
import android.car.util.concurrent.AndroidFuture;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserManager;

import com.android.car.CarLog;
import com.android.car.CarServiceBase;
import com.android.car.internal.common.UserHelperLite;
import com.android.car.internal.os.CarSystemProperties;
import com.android.car.user.CarUserService;
import com.android.car.util.IndentingPrintWriter;

/**
 * Service for device policy related features.
 */
public final class CarDevicePolicyService extends ICarDevicePolicyService.Stub
        implements CarServiceBase {

    private static final String TAG = CarLog.tagFor(CarDevicePolicyService.class);
    static final boolean DEBUG = false;

    private static final int HAL_TIMEOUT_MS = CarSystemProperties.getUserHalTimeout().orElse(5_000);

    private final CarUserService mCarUserService;
    private final Context mContext;

    public CarDevicePolicyService(@NonNull Context context,
            @NonNull CarUserService carUserService) {
        mCarUserService = carUserService;
        mContext = context;
    }

    @Override
    public void init() {
        if (DEBUG) Slog.d(TAG, "init()");
    }

    @Override
    public void release() {
        if (DEBUG) Slog.d(TAG, "release()");
    }

    @Override
    public void removeUser(@UserIdInt int userId, AndroidFuture<UserRemovalResult> receiver) {
        mCarUserService.removeUser(userId, /* hasCallerRestrictions= */ true, receiver);
    }

    @Override
    public void createUser(@Nullable String name,
            @CarDevicePolicyManager.UserType int type, AndroidFuture<UserCreationResult> receiver) {
        int userInfoFlags = 0;
        String userType = UserManager.USER_TYPE_FULL_SECONDARY;
        switch(type) {
            case CarDevicePolicyManager.USER_TYPE_REGULAR:
                break;
            case CarDevicePolicyManager.USER_TYPE_ADMIN:
                userInfoFlags = UserInfo.FLAG_ADMIN;
                break;
            case CarDevicePolicyManager.USER_TYPE_GUEST:
                userType = UserManager.USER_TYPE_FULL_GUEST;
                break;
            default:
                if (DEBUG) {
                    Slog.d(TAG, "createUser(): invalid userType (" + userType + ") / flags ("
                            + userInfoFlags + ") combination");
                }
                receiver.complete(
                        new UserCreationResult(UserCreationResult.STATUS_INVALID_REQUEST));
                return;
        }

        if (DEBUG) {
            Slog.d(TAG, "calling createUser(" + UserHelperLite.safeName(name) + "," + userType
                    + ", " + userInfoFlags + ", " + HAL_TIMEOUT_MS + ")");
        }

        mCarUserService.createUser(name, userType, userInfoFlags, HAL_TIMEOUT_MS, receiver);
    }

    @Override
    public void startUserInBackground(@UserIdInt int userId,
            AndroidFuture<UserStartResult> receiver) {
        mCarUserService.startUserInBackground(userId, receiver);
    }

    @Override
    public void stopUser(@UserIdInt int userId, AndroidFuture<UserStopResult> receiver) {
        mCarUserService.stopUser(userId, receiver);
    }

    @Override
    public void dump(@NonNull IndentingPrintWriter writer) {
        checkHasDumpPermissionGranted(mContext, "dump()");

        writer.println("*CarDevicePolicyService*");

        writer.printf("HAL_TIMEOUT_MS: %d\n", HAL_TIMEOUT_MS);
    }
}
