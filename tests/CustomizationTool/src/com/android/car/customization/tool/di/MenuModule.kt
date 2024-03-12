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

package com.android.car.customization.tool.di

import android.content.Context
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.menu.MenuItem
import com.android.car.customization.tool.domain.menu.toSubMenu
import dagger.Module
import dagger.Provides
import javax.inject.Qualifier

/**
 * Qualifier for the root menu, use this to provide elements to the root menu.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class RootMenu

/**
 * The base module for the menu.
 *
 * Provides the root element of the menu.
 *
 */
@Module
internal class MenuModule {

    @Provides
    fun provideRootMenu(
        @RootMenu rootMenu: Set<@JvmSuppressWildcards MenuItem>,
        @UIContext context: Context,
    ): MenuItem.SubMenuNavigation = MenuItem.SubMenuNavigation(
        displayTextRes = R.string.menu_root,
        subMenu = rootMenu.toSubMenu(context, addBackButton = false)
    )
}
