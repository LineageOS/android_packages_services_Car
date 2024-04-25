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

import com.android.car.customization.tool.features.oemtokens.oemtokenstoggle.OemTokensToggleModule
import com.android.car.customization.tool.features.oemtokens.submenu.OemDesignTokensMenuModule
import com.android.car.customization.tool.features.plugins.plugintoggle.PluginToggleModule
import com.android.car.customization.tool.features.plugins.submenu.PluginMenuModule
import com.android.car.customization.tool.features.rro.list.RroListPanelModule
import com.android.car.customization.tool.features.rro.submenu.RroMenuModule
import com.android.car.customization.tool.features.rro.systemui.cutouts.DisplayCutoutsModule
import com.android.car.customization.tool.features.rro.systemui.submenu.SystemUiMenuModule
import com.android.car.customization.tool.features.rro.systemui.systembarpresets.SystemBarPresetsModule
import com.android.car.customization.tool.features.rro.systemui.themepresets.ThemePresetsModule
import com.android.car.customization.tool.features.rro.unbundled.apprro.UnbundledRroModule
import com.android.car.customization.tool.features.rro.unbundled.submenu.UnbundledMenuModule
import dagger.Module

/**
 * The container of all the feature modules.
 *
 * Whenever a new feature is added its modules should be added here.
 */
@Module(
    includes = [
        PluginMenuModule::class,
        PluginToggleModule::class,
        OemDesignTokensMenuModule::class,
        OemTokensToggleModule::class,
        RroMenuModule::class,
        RroListPanelModule::class,
        UnbundledMenuModule::class,
        UnbundledRroModule::class,
        SystemUiMenuModule::class,
        ThemePresetsModule::class,
        SystemBarPresetsModule::class,
        DisplayCutoutsModule::class,
    ]
)
class FeaturesModule
