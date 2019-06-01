/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.google.android.car.bugreport;

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Android System utility class.
 */
final class SystemUtils {
    private static final String TAG = SystemUtils.class.getSimpleName();

    private static final String ENFORCING = "Enforcing";
    private static final String PERMISSIVE = "Permissive";

    private static final String VOLVO_FINGERPRINT_PREFIX = "VolvoCars";

    /**
     * Returns {@code true} if SELinux is {@code Enforcing}. If it can't verify it returns {@code
     * true} because most likely SELinux is not allowing running {@code getenforce}.
     *
     * <p>Returns {@code false} only when SELinux is {@code Permissive}.
     */
    static boolean isSeLinuxEnforcing() {
        StringBuilder result = new StringBuilder();
        Process p;
        try {
            p = Runtime.getRuntime().exec("getenforce");
            p.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        } catch (Exception e) {
            Log.e(TAG, "OS does not support getenforce", e);
            // If getenforce is not available to the device, assume the device is not enforcing
            return false;
        }
        String response = result.toString();
        if (ENFORCING.equals(response)) {
            return true;
        } else if (PERMISSIVE.equals(response)) {
            return false;
        } else {
            Log.e(TAG, "getenforce returned unexpected value, assuming selinux is enforcing!");
            // If getenforce doesn't return anything, most likely SELinux is in enforcing mode.
            return true;
        }
    }

    /** Returns {@code true} if the app is running on Volvo cars. */
    static boolean isVolvo() {
        return Build.FINGERPRINT.startsWith(VOLVO_FINGERPRINT_PREFIX);
    }

    /** Returns {@code true} if the app is running on Android P. */
    static boolean isAndroidP() {
        return Build.VERSION.SDK_INT == Build.VERSION_CODES.P;
    }

    private SystemUtils() {
    }
}
