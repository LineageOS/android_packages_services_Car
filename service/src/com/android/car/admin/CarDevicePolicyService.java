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
import android.annotation.UserIdInt;
import android.car.admin.ICarDevicePolicyService;
import android.car.user.UserRemovalResult;
import android.util.Log;

import com.android.car.CarServiceBase;
import com.android.car.user.CarUserService;

import java.io.PrintWriter;

/**
 * Service for device policy related features.
 */
public final class CarDevicePolicyService extends ICarDevicePolicyService.Stub
        implements CarServiceBase {

    private static final String TAG = CarDevicePolicyService.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final CarUserService mCarUserService;

    public CarDevicePolicyService(@NonNull CarUserService carUserService) {
        mCarUserService = carUserService;
    }

    @Override
    public void init() {
        if (DEBUG) Log.d(TAG, "init()");
    }

    @Override
    public void release() {
        if (DEBUG) Log.d(TAG, "release()");
    }

    @Override
    public UserRemovalResult removeUser(@UserIdInt int userId) {
        return mCarUserService.removeUser(userId, /* hasCallerRestrictions= */ true);
    }

    @Override
    public void dump(@NonNull PrintWriter writer) {
        checkHasDumpPermissionGranted("dump()");

        writer.println("*CarDevicePolicyService*");
    }
}
