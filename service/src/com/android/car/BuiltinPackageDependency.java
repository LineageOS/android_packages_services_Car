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

import android.content.Context;
import android.content.Intent;

/**
 * Declared all dependency into buildin package, mostly for Activity / class / method names.
 *
 * <p> This is for tracking all dependencies done through java reflection.
 */
public class BuiltinPackageDependency {
    private BuiltinPackageDependency() {};

    /** Package name of builtin, Will be becessary to send Intent. */
    public static final String BUILTIN_CAR_SERVICE_PACKAGE_NAME = "com.android.car";

    /** {@code com.android.car.admin.FactoryResetActivity} class */
    public static final String FACTORY_RESET_ACTIVITY_CLASS =
            "com.android.car.admin.FactoryResetActivity";

    /** {@code com.android.car.am.ContinuousBlankActivity} */
    public static final String BLANK_ACTIVITY_CLASS = "com.android.car.am.ContinuousBlankActivity";

    /** {@code com.android.car.admin.FactoryResetActivity#sendNotification()} */
    public static final String FACTORY_RESET_ACTIVITY_SEND_NOTIFICATION = "sendNotification";

    /** {@code com.android.car.pm.CarSafetyAccessibilityService} */
    public static final String CAR_ACCESSIBILITY_SERVICE_CLASS =
            "com.android.car.pm.CarSafetyAccessibilityService";

    /** {@code com.android.car.PerUserCarService} */
    public static final String PER_USER_CAR_SERVICE_CLASS = "com.android.car.PerUserCarService";

    /** Returns {@code ComponentName} string for builtin package component */
    public static String getComponentName(String className) {
        StringBuilder builder = new StringBuilder();
        builder.append(BUILTIN_CAR_SERVICE_PACKAGE_NAME);
        builder.append('/');
        builder.append(className);
        return builder.toString();
    }

    /** Sets builtin package's class to the passed Intent and returns the Intent. */
    public static Intent addClassNameToIntent(Context context, Intent intent, String className) {
        intent.setClassName(context.getPackageName(), className);
        return intent;
    }
}
