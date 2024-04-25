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

package com.android.car.customization.tool.features.rro.systemui.systembarpresets

import android.content.Context
import android.content.om.OverlayManager
import android.os.UserHandle
import androidx.annotation.StringRes
import com.android.car.customization.tool.domain.menu.Menu
import com.android.car.customization.tool.domain.menu.MenuAction
import com.android.car.customization.tool.domain.menu.MenuActionReducer
import com.android.car.customization.tool.domain.menu.modifyDropDown

internal data class SelectSystemBarPresetAction(
    @StringRes val itemId: Int,
    val packageToEnable: String?,
    val packagesToDisable: List<String>,
) : MenuAction

internal class SystemBarPresetsReducer(
    val overlayManager: OverlayManager,
    val context: Context,
) : MenuActionReducer {

    override fun reduce(menu: Menu, action: MenuAction): Menu {
        action as SelectSystemBarPresetAction

        if (action.packageToEnable != null) {
            overlayManager.setEnabled(
                /*packageName =*/ action.packageToEnable,
                /*enable =*/ true,
                /*user =*/ UserHandle.SYSTEM
            )
        }
        togglePersistentRRO(overlayManager, action.packageToEnable != null)

        action.packagesToDisable.forEach { rroPackage ->
            overlayManager.setEnabled(
                /*packageName =*/ rroPackage,
                /*enable =*/ false,
                /*user =*/ UserHandle.SYSTEM
            )
        }

        return menu.modifyDropDown(context, action.itemId, action)
    }

    private fun togglePersistentRRO(overlayManager: OverlayManager, enable: Boolean) {
        val persistentRRO = "com.android.systemui.rro.persistent"
        if (overlayManager.getOverlayInfo(persistentRRO, UserHandle.SYSTEM) != null) {
            overlayManager.setEnabled(
                /*packageName =*/ persistentRRO,
                /*enable =*/ enable,
                /*user =*/ UserHandle.SYSTEM
            )
        }
        if (overlayManager.getOverlayInfo(persistentRRO, UserHandle.CURRENT) != null) {
            overlayManager.setEnabled(
                /*packageName =*/ persistentRRO,
                /*enable =*/ enable,
                /*user =*/ UserHandle.CURRENT
            )
        }
    }
}
