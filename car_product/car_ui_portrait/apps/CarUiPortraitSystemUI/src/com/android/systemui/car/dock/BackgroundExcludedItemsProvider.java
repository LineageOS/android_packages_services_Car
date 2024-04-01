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

package com.android.systemui.car.dock;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.text.TextUtils;
import android.util.ArraySet;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.car.app.CarContext;

import com.android.car.docklib.ExcludedItemsProvider;
import com.android.systemui.R;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link ExcludedItemsProvider}] that excludes packages and components that are shown in the
 * background panel.
 */
public class BackgroundExcludedItemsProvider implements ExcludedItemsProvider {
    // todo(b/280647032): Functionality copied from TaskCategoryManager

    private static final String STUB_GEO_DATA = "geo:0.0,0,0";
    private final Context mContext;
    private final Set<ComponentName> mBackgroundActivities = new HashSet<>();
    private final ApplicationInstallUninstallReceiver mApplicationInstallUninstallReceiver;

    public BackgroundExcludedItemsProvider(Context context) {
        mContext = context;
        mApplicationInstallUninstallReceiver = registerApplicationInstallUninstallReceiver();
        updateBackgroundActivityMap();
    }

    @Override
    public boolean isPackageExcluded(@NonNull String pkg) {
        return false;
    }

    @Override
    public boolean isComponentExcluded(@NonNull ComponentName component) {
        return mBackgroundActivities.stream().anyMatch(cn -> cn.equals(component));
    }

    /** Responsible to unregister all receivers and performs necessary cleanup. */
    public void destroy() {
        mContext.unregisterReceiver(mApplicationInstallUninstallReceiver);
    }

    private void updateBackgroundActivityMap() {
        mBackgroundActivities.clear();
        Intent intent = new Intent(CarContext.ACTION_NAVIGATE, Uri.parse(STUB_GEO_DATA));
        List<ResolveInfo> result = mContext.getPackageManager().queryIntentActivitiesAsUser(
                intent, PackageManager.MATCH_ALL, mContext.getUser());

        for (ResolveInfo info : result) {
            if (info == null || info.activityInfo == null
                    || info.activityInfo.getComponentName() == null) {
                continue;
            }
            mBackgroundActivities.add(info.getComponentInfo().getComponentName());
        }

        mBackgroundActivities.addAll(convertToComponentNames(mContext.getResources()
                .getStringArray(R.array.config_backgroundActivities)));
    }

    private ApplicationInstallUninstallReceiver registerApplicationInstallUninstallReceiver() {
        ApplicationInstallUninstallReceiver
                installUninstallReceiver = new ApplicationInstallUninstallReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(installUninstallReceiver, filter);
        return installUninstallReceiver;
    }

    private static ArraySet<ComponentName> convertToComponentNames(String[] componentStrings) {
        ArraySet<ComponentName> componentNames = new ArraySet<>(componentStrings.length);
        for (int i = componentStrings.length - 1; i >= 0; i--) {
            componentNames.add(ComponentName.unflattenFromString(componentStrings[i]));
        }
        return componentNames;
    }

    private class ApplicationInstallUninstallReceiver extends BroadcastReceiver {
        @MainThread
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getData() == null) {
                return;
            }
            String packageName = intent.getData().getSchemeSpecificPart();
            String action = intent.getAction();
            if (TextUtils.isEmpty(packageName) && TextUtils.isEmpty(action)) {
                // Ignoring empty announcements
                return;
            }
            updateBackgroundActivityMap();
        }
    }
}
