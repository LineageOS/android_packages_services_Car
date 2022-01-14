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

package com.android.car;

import android.content.Intent;

/** Proxy service for CarServciceImpl */
public class CarService extends ServiceProxy {
    /**
     * Represents a minor version change of car service builtin for the same
     * {@link android.os.Build.VERSION#SDK_INT}.
     *
     * <p>It will reset to {@code 0} whenever {@link android.os.Build.VERSION#SDK_INT} is updated
     * and will increase by {@code 1} if car service builtin is changed with the same
     * {@link android.os.Build.VERSION#SDK_INT}. Updatable car service may check this version to
     * have different behavior access minor revision.
     *
     * <p> ADDED FOR FUTURE COMPATIBILITY. DO NOT REMOVE THIS.
     */
    public static final int VERSION_MINOR_INT = 0;

    public CarService() {
        super(UpdatablePackageDependency.CAR_SERVICE_IMPL_CLASS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // keep it alive.
        return START_STICKY;
    }
}
