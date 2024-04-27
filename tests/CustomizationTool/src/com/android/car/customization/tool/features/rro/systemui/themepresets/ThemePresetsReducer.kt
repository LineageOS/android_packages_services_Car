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

package com.android.car.customization.tool.features.rro.systemui.themepresets

import android.content.Context
import android.content.om.OverlayManager
import android.os.UserHandle
import androidx.annotation.StringRes
import com.android.car.customization.tool.di.UIContext
import com.android.car.customization.tool.domain.menu.Menu
import com.android.car.customization.tool.domain.menu.MenuAction
import com.android.car.customization.tool.domain.menu.MenuActionReducer
import com.android.car.customization.tool.domain.menu.modifyDropDown

internal data class SelectThemePresetAction(
    @StringRes val itemId: Int,
    val themeToEnable: ThemeRRO?,
    val themesToDisable: List<ThemeRRO>,
) : MenuAction

internal class ThemePresetsReducer(
    @UIContext private val context: Context,
    private val overlayManager: OverlayManager,
) : MenuActionReducer {

    override fun reduce(menu: Menu, action: MenuAction): Menu {
        action as SelectThemePresetAction

        if (action.themeToEnable != null) {
            toggleRROs(action.themeToEnable.systemPackages, enable = true, UserHandle.SYSTEM)
            toggleRROs(action.themeToEnable.userPackages, enable = true, UserHandle.CURRENT)
        }

        action.themesToDisable.forEach { theme ->
            toggleRROs(theme.systemPackages, enable = false, UserHandle.SYSTEM)
            toggleRROs(theme.userPackages, enable = false, UserHandle.CURRENT)
        }

        return menu.modifyDropDown(context, action.itemId, action)
    }

    private fun toggleRROs(rroPackageList: List<String>, enable: Boolean, user: UserHandle) {
        rroPackageList.forEach { rroPackage ->
            overlayManager.setEnabled(
                /*packageName =*/ rroPackage,
                /*enable =*/ enable,
                /*user =*/ user
            )
        }
    }
}
