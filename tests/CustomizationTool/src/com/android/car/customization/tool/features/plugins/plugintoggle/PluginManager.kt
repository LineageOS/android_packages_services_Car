/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car.customization.tool.features.plugins.plugintoggle

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
import android.content.pm.PackageManager.MATCH_SYSTEM_ONLY
import android.content.pm.ProviderInfo
import com.android.car.customization.tool.di.UIContext
import javax.inject.Inject

/**
 * Toggles the state of the Car-UI-LIb Plugin in the system.
 */
internal class PluginManager @Inject constructor(
    @UIContext private val context: Context,
) {

    private val providerInfo: ProviderInfo? = context.packageManager.resolveContentProvider(
        AUTHORITY,
        /*flags =*/ MATCH_DISABLED_COMPONENTS or MATCH_SYSTEM_ONLY
    )

    fun isPluginEnabled(): Boolean {
        val packageManager = context.packageManager
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
            providerInfo == null) {
            return false
        }
        val componentName = ComponentName(providerInfo.packageName, providerInfo.name)
        val state = packageManager.getComponentEnabledSetting(componentName)
        return if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            providerInfo.enabled
        } else {
            state == COMPONENT_ENABLED_STATE_ENABLED
        }
    }

    fun getPluginPackageName(): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            return null
        }
        return providerInfo?.packageName
    }

    fun changeCarUiPluginState(newValue: Boolean) {
        if (providerInfo == null) {
            throw Exception("Plugin is disabled or does not exist")
        }
        val componentName = ComponentName(providerInfo.packageName, providerInfo.name)
        val state =
            if (newValue) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED

        context.packageManager.setComponentEnabledSetting(componentName, state, 0)
    }

    companion object {
        private const val AUTHORITY: String = "com.android.car.ui.plugin"
    }
}
