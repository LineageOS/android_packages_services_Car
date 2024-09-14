/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.car.distantdisplay.util;

import android.os.Build;
import android.util.Log;

/**
 * Utility class for Distant display.
 */
public class Logging {

    private static final boolean IS_DEBUGGABLE = Build.IS_ENG || Build.IS_USERDEBUG;

    /**
     * Use this method for logging on debug builds and keep prod builds faster.
     */
    public static void logIfDebuggable(String tag, String message) {
        if (IS_DEBUGGABLE) {
            Log.d(tag, message);
        }
    }
}
