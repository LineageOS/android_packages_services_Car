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
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;

/** Context for updatable package */
public class UpdatablePackageContext extends ContextWrapper {

    public static final String UPDATABLE_CAR_SERVICE_PACKAGE_NAME = "com.android.car.updatable";

    // This is the package context of the com.android.car.updatable
    private final Context mPackageContext;

    /** Create context for updatable package */
    public static UpdatablePackageContext create(Context baseContext) {
        Context packageContext;
        try {
            PackageInfo info = baseContext.getPackageManager().getPackageInfo(
                    UPDATABLE_CAR_SERVICE_PACKAGE_NAME, 0);
            if (info == null || info.applicationInfo == null || !(info.applicationInfo.isSystemApp()
                    || info.applicationInfo.isUpdatedSystemApp())) {
                throw new IllegalStateException(
                        "Updated car service package is not usable:" + ((info == null)
                                ? "do not exist" : info.applicationInfo));
            }
            // CONTEXT_IGNORE_SECURITY: UID is different but ok as the package is trustable system
            // app
            packageContext = baseContext.createPackageContext(
                    UPDATABLE_CAR_SERVICE_PACKAGE_NAME,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (Exception e) {
            throw new RuntimeException("Cannot load updatable package code", e);
        }

        return new UpdatablePackageContext(baseContext, packageContext);
    }

    private UpdatablePackageContext(Context baseContext, Context packageContext) {
        super(baseContext);
        mPackageContext = packageContext;
    }

    @Override
    public AssetManager getAssets() {
        return mPackageContext.getAssets();
    }

    @Override
    public Resources getResources() {
        return mPackageContext.getResources();
    }

    @Override
    public ClassLoader getClassLoader() {
        // This context cannot load code from builtin any more.
        return mPackageContext.getClassLoader();
    }
}
