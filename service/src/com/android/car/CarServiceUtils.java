/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.util.Log;

/** Utility class */
public class CarServiceUtils {

    private static final String PACKAGE_NOT_FOUND = "Package not found:";

    /**
     * Check if package name passed belongs to UID for the current binder call.
     * @param context
     * @param packageName
     */
    public static void assertPakcageName(Context context, String packageName)
            throws IllegalArgumentException, SecurityException {
        if (packageName == null) {
            throw new IllegalArgumentException("Package name null");
        }
        ApplicationInfo appInfo = null;
        try {
            appInfo = context.getPackageManager().getApplicationInfo(packageName,
                    0);
        } catch (NameNotFoundException e) {
            String msg = PACKAGE_NOT_FOUND + packageName;
            Log.w(CarLog.TAG_SERVICE, msg, e);
            throw new SecurityException(msg, e);
        }
        if (appInfo == null) {
            throw new SecurityException(PACKAGE_NOT_FOUND + packageName);
        }
        int uid = Binder.getCallingUid();
        if (uid != appInfo.uid) {
            throw new SecurityException("Wrong package name:" + packageName +
                    ", The package does not belong to caller's uid:" + uid);
        }
    }
}
