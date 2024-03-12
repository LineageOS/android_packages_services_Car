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

package com.google.android.car.kitchensink.customizationtool;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

public class CustomizationToolController {

    private static final String PACKAGE = "com.android.car.customization.tool";
    private static final String SERVICE = PACKAGE + '/' + PACKAGE + ".CustomizationToolService";

    private final ContentResolver mContentResolver;

    public CustomizationToolController(Context context) {
        mContentResolver = context.getContentResolver();
    }

    boolean isCustomizationToolActive() {
        String accessibilityServices = Settings.Secure.getString(
                mContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (accessibilityServices == null) {
            return false;
        }
        String serviceStatus = Settings.Secure.getString(
                mContentResolver, Settings.Secure.ACCESSIBILITY_ENABLED);
        return accessibilityServices.contains(SERVICE) && "1".equals(serviceStatus);
    }

    public void toggleCustomizationTool(boolean newState) {
        if (newState) {
            Settings.Secure.putString(
                    mContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, SERVICE);
            Settings.Secure.putString(mContentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED, /* value = */"1");
        } else {
            String newAccessibilityServices = removeServiceFromList();
            Settings.Secure.putString(mContentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newAccessibilityServices);
            Settings.Secure.putString(mContentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED, /* value = */"0");
        }
    }

    private String removeServiceFromList() {
        String currentServices = Settings.Secure.getString(
                mContentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (currentServices == null || currentServices.isEmpty()) {
            return "";
        }
        return currentServices.replace(
                SERVICE, /* replacement = */"").replace(/* target = */"::", /* replacement = */":");
    }
}
