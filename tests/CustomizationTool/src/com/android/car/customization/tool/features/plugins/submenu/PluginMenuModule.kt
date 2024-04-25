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

package com.android.car.customization.tool.features.plugins.submenu

import android.content.Context
import com.android.car.customization.tool.R
import com.android.car.customization.tool.di.RootMenu
import com.android.car.customization.tool.di.UIContext
import com.android.car.customization.tool.domain.menu.MenuItem
import com.android.car.customization.tool.domain.menu.toSubMenu
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import javax.inject.Qualifier

/**
 * Qualifier used to provide elements to the Plugin sub menu.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class PluginsMenu

/**
 * Provides the Menu element for the Plugin sub menu.
 */
@Module
internal class PluginMenuModule {

    @Provides
    @RootMenu
    @IntoSet
    fun providePluginsMenu(
        @PluginsMenu items: Set<@JvmSuppressWildcards MenuItem>,
        @UIContext context: Context,
    ): MenuItem = MenuItem.SubMenuNavigation(
        displayTextRes = R.string.menu_plugin,
        subMenu = items.toSubMenu(context)
    )
}
