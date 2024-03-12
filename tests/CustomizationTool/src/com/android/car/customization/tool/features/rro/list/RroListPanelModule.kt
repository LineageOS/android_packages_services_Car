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

package com.android.car.customization.tool.features.rro.list

import android.content.om.OverlayManager
import android.content.pm.PackageManager
import com.android.car.customization.tool.R
import com.android.car.customization.tool.di.PanelReducerKey
import com.android.car.customization.tool.domain.menu.MenuItem
import com.android.car.customization.tool.domain.panel.OpenPanelAction
import com.android.car.customization.tool.domain.panel.PanelActionReducer
import com.android.car.customization.tool.features.rro.submenu.RroMenu
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet

/**
 * RRO List Panel.
 *
 * Shows a Panel that contains all of the RROs installed in the system and if they are dynamic RROs
 * also allows the user to switch their state.
 * The module provides a [MenuItem.PanelLauncher] to access the panel and the [RroListPanelReducer]
 * for the Panel logic.
 */
@Module
internal class RroListPanelModule {

    @Provides
    @RroMenu
    @IntoSet
    fun provideRroListPanelLauncher(): MenuItem =
        MenuItem.PanelLauncher(
            displayTextRes = R.string.menu_rro_list_control_panel,
            isEnabled = true,
            action = OpenPanelAction(RroListPanelReducer::class)
        )

    @Provides
    @IntoMap
    @PanelReducerKey(RroListPanelReducer::class)
    fun provideRROListPanel(
        packageManager: PackageManager,
        overlayManager: OverlayManager,
    ): PanelActionReducer = RroListPanelReducer(packageManager, overlayManager)
}
