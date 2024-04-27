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

package com.android.car.customization.tool.features.oemtokens.oemtokenstoggle

import android.content.Context
import android.content.om.OverlayManager
import android.os.UserHandle
import androidx.annotation.StringRes
import com.android.car.customization.tool.domain.menu.Menu
import com.android.car.customization.tool.domain.menu.MenuAction
import com.android.car.customization.tool.domain.menu.MenuActionReducer
import com.android.car.customization.tool.domain.menu.ToggleMenuAction
import com.android.car.customization.tool.domain.menu.modifySwitch

internal data class ToggleOemTokensAction(
    @StringRes override val itemId: Int,
    val rroPackage: String,
    override val newValue: Boolean,
) : ToggleMenuAction {

    override fun clone(newValue: Boolean) = copy(newValue = newValue)
}

internal class OemTokensToggleReducer(
    private val context: Context,
    private val overlayManager: OverlayManager,
) : MenuActionReducer {

    override fun reduce(menu: Menu, action: MenuAction): Menu {
        action as ToggleOemTokensAction
        overlayManager.setEnabled(action.rroPackage, action.newValue, UserHandle.CURRENT)

        return menu.modifySwitch(context, action.itemId, action.newValue)
    }
}
