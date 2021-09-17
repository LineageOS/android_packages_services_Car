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

package android.car.builtin.os;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.text.TextUtils;

/**
 * Helper for package-related operations.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class PackageManagerHelper {

    private PackageManagerHelper() {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets the name of the {@code SystemUI} package.
     * @param context
     * @return
     */
    @NonNull
    public static String getSystemUiPackageName(@NonNull Context context) {
        // TODO(157082995): This information can be taken from
        // PackageManageInternalImpl.getSystemUiServiceComponent()
        String flattenName = context.getResources()
                .getString(com.android.internal.R.string.config_systemUIServiceComponent);
        if (TextUtils.isEmpty(flattenName)) {
            throw new IllegalStateException("No "
                    + "com.android.internal.R.string.config_systemUIServiceComponent resource");
        }
        try {
            ComponentName componentName = ComponentName.unflattenFromString(flattenName);
            return componentName.getPackageName();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid component name defined by "
                    + "com.android.internal.R.string.config_systemUIServiceComponent resource: "
                    + flattenName);
        }
    }
}
