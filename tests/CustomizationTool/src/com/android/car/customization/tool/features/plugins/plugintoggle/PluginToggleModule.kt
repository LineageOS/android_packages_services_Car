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

import android.content.Context
import com.android.car.customization.tool.R
import com.android.car.customization.tool.di.MenuActionKey
import com.android.car.customization.tool.di.UIContext
import com.android.car.customization.tool.domain.menu.MenuActionReducer
import com.android.car.customization.tool.domain.menu.MenuItem
import com.android.car.customization.tool.features.plugins.submenu.PluginsMenu
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet

/**
 * Toggle Car Ui Lib Plugin.
 *
 * Provides a [MenuItem.Switch] that toggles the Car Ui Lib Plugin. The module also provides the
 * reducer for the item.
 */
/**
 * Dagger Module for the feature: . It provides the [MenuItem] and the
 * [MenuActionReducer] for the feature
 */
@Module
internal class PluginToggleModule {

    @Provides
    @PluginsMenu
    @IntoSet
    fun providePluginToggle(
        pluginManager: PluginManager,
    ): MenuItem {
        val isChecked = pluginManager.isPluginEnabled()
        val displayText = R.string.menu_plugin_global_switch
        return MenuItem.Switch(
            displayTextRes = displayText,
            isChecked = isChecked,
            isEnabled = pluginManager.getPluginPackageName() != null,
            action = ToggleCarUiLibPluginAction(displayText, !isChecked)
        )
    }

    @Provides
    @IntoMap
    @MenuActionKey(ToggleCarUiLibPluginAction::class)
    fun provideTogglePluginReducer(
        @UIContext context: Context,
        pluginManager: PluginManager,
    ): MenuActionReducer = PluginToggleReducer(context, pluginManager)
}
