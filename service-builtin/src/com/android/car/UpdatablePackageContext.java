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

import android.car.builtin.content.pm.PackageManagerHelper;
import android.car.builtin.util.Slogf;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.om.OverlayManager;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;

/** Context for updatable package */
public class UpdatablePackageContext extends ContextWrapper {

    public static final String UPDATABLE_CAR_SERVICE_PACKAGE_NAME = "com.android.car.updatable";

    private static final String TAG = UpdatablePackageContext.class.getSimpleName();

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

            // Enable correct RRO package
            enableRROForCarServiceUpdatable(baseContext);

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

    private static void enableRROForCarServiceUpdatable(Context baseContext) {
        String packageName = SystemProperties.get(
                PackageManagerHelper.PROPERTY_CAR_SERVICE_OVERLAY_PACKAGE_NAME,
                /* default= */ null);
        if (TextUtils.isEmpty(packageName)) {
            // read only property not defined. No need to dynamically overlay resources.
            Slogf.i(TAG, " %s is not set. No need to dynamically overlay resources.",
                    PackageManagerHelper.PROPERTY_CAR_SERVICE_OVERLAY_PACKAGE_NAME);
            return;
        }

        // check package
        try {
            PackageInfo info = baseContext.getPackageManager().getPackageInfo(packageName, 0);
            if (info == null || info.applicationInfo == null
                    || !(PackageManagerHelper.isSystemApp(info.applicationInfo)
                            || PackageManagerHelper.isUpdatedSystemApp(info.applicationInfo)
                            || PackageManagerHelper.isOemApp(info.applicationInfo)
                            || PackageManagerHelper.isOdmApp(info.applicationInfo)
                            || PackageManagerHelper.isVendorApp(info.applicationInfo)
                            || PackageManagerHelper.isProductApp(info.applicationInfo)
                            || PackageManagerHelper.isSystemExtApp(info.applicationInfo))) {
                Slogf.i(TAG, "%s is not usable: %s", packageName, ((info == null)
                                ? "package do not exist" : info.applicationInfo));
                return;
            }
        } catch (Exception e) {
            Slogf.w(TAG, e, "couldn't find package: %s", packageName);
            return;
        }

        // package is valid. Enable RRO. This class is called for each user, so need to enable RRO
        // for system and current user separately.
        UserHandle user = baseContext.getUser();
        try {
            OverlayManager manager = baseContext.getSystemService(OverlayManager.class);
            manager.setEnabled(packageName, true, user);
            Slogf.i(TAG, "RRO package %s is enabled for User %s", packageName, user);
        } catch (Exception e) {
            Slogf.w(TAG, e, "RRO package %s is NOT enabled for User %s", packageName, user);
        }
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
