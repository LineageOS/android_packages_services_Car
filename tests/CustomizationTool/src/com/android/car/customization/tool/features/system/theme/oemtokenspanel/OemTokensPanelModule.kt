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

package com.android.car.customization.tool.features.system.theme.oemtokenspanel

import android.content.om.OverlayManager
import android.content.pm.PackageManager
import com.android.car.customization.tool.R
import com.android.car.customization.tool.di.PanelReducerKey
import com.android.car.customization.tool.domain.menu.MenuItem
import com.android.car.customization.tool.domain.panel.OpenPanelAction
import com.android.car.customization.tool.domain.panel.PanelActionReducer
import com.android.car.customization.tool.features.system.theme.submenu.SystemThemeMenu
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet

/**
 * OEM Design Tokens Panel.
 *
 * Adds a Panel to the menu that shows the setup status of OEM Design Tokens.
 * The module also provides the reducer for the item.
 */
@Module
internal class OemTokensPanelModule {

    @Provides
    @SystemThemeMenu
    @IntoSet
    fun provideOemTokenPanelLauncher(): MenuItem =
        MenuItem.PanelLauncher(
            displayTextRes = R.string.menu_system_theme_design_tokens_panel,
            isEnabled = true,
            action = OpenPanelAction(OemTokensPanelReducer::class)
        )

    @Provides
    @IntoMap
    @PanelReducerKey(OemTokensPanelReducer::class)
    fun provideRROListPanel(
        packageManager: PackageManager,
        overlayManager: OverlayManager,
    ): PanelActionReducer = OemTokensPanelReducer(packageManager, overlayManager)
}
