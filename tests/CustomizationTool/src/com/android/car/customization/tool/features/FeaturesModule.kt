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

package com.android.car.customization.tool.features

import com.android.car.customization.tool.features.applications.apprro.AppRroModule
import com.android.car.customization.tool.features.applications.submenu.ApplicationsMenuModule
import com.android.car.customization.tool.features.system.advanced.rrolist.RroListPanelModule
import com.android.car.customization.tool.features.system.advanced.submenu.AdvancedMenuModule
import com.android.car.customization.tool.features.system.cutouts.DisplayCutoutsModule
import com.android.car.customization.tool.features.system.submenu.SystemMenuModule
import com.android.car.customization.tool.features.system.systembarpresets.SystemBarPresetsModule
import com.android.car.customization.tool.features.system.theme.oemtokenstoggle.OemTokensToggleModule
import com.android.car.customization.tool.features.system.theme.plugintoggle.PluginToggleModule
import com.android.car.customization.tool.features.system.theme.submenu.ThemeMenuModule
import com.android.car.customization.tool.features.system.theme.themepresets.ThemePresetsModule
import dagger.Module

/**
 * The container of all the feature modules.
 *
 * Whenever a new feature is added its modules should be added here.
 */
@Module(
    includes = [
        AdvancedMenuModule::class,
        ApplicationsMenuModule::class,
        AppRroModule::class,
        DisplayCutoutsModule::class,
        OemTokensToggleModule::class,
        PluginToggleModule::class,
        RroListPanelModule::class,
        SystemMenuModule::class,
        SystemBarPresetsModule::class,
        ThemeMenuModule::class,
        ThemePresetsModule::class,
    ]
)
class FeaturesModule
