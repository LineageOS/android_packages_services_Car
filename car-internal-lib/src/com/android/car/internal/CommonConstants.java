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

package com.android.car.internal;

/**
 * Provides common constants for CarService, CarServiceHelperService and other packages.
 */
public final class CommonConstants {

    private CommonConstants() {
        throw new UnsupportedOperationException("contains only static constants");
    }

    // CarUserManagerConstants
    public static final int USER_LIFECYCLE_EVENT_TYPE_STARTING = 1;
    public static final int USER_LIFECYCLE_EVENT_TYPE_SWITCHING = 2;
    public static final int USER_LIFECYCLE_EVENT_TYPE_UNLOCKING = 3;
    public static final int USER_LIFECYCLE_EVENT_TYPE_UNLOCKED = 4;
    public static final int USER_LIFECYCLE_EVENT_TYPE_STOPPING = 5;
    public static final int USER_LIFECYCLE_EVENT_TYPE_STOPPED = 6;

    public static final String BUNDLE_PARAM_ACTION = "action";
    public static final String BUNDLE_PARAM_PREVIOUS_USER_ID = "previous_user";

    // CarUserServiceConstants
    public static final String BUNDLE_USER_ID = "user.id";
    public static final String BUNDLE_USER_FLAGS = "user.flags";
    public static final String BUNDLE_USER_NAME = "user.name";
    public static final String BUNDLE_USER_LOCALES = "user.locales";
    public static final String BUNDLE_INITIAL_INFO_ACTION = "initial_info.action";

    // CarService Constants
    public static final String CAR_SERVICE_INTERFACE = "android.car.ICar";
}
