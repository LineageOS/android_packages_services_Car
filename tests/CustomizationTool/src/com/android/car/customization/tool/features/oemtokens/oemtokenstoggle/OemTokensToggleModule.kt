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
import android.content.om.OverlayInfo
import android.content.om.OverlayManager
import android.content.pm.PackageManager
import android.os.UserHandle
import com.android.car.customization.tool.R
import com.android.car.customization.tool.di.MenuActionKey
import com.android.car.customization.tool.di.UIContext
import com.android.car.customization.tool.domain.menu.MenuActionReducer
import com.android.car.customization.tool.domain.menu.MenuItem
import com.android.car.customization.tool.features.common.isValid
import com.android.car.customization.tool.features.oemtokens.submenu.OemDesignTokensMenu
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet

/**
 * OEM Design Tokens toggle.
 *
 * Adds a [MenuItem.Switch] to the menu that toggles the RRO related to OEM Design Tokens.
 * The module also provides the reducer for the item.
 */
@Module
internal class OemTokensToggleModule {

    @Provides
    @OemDesignTokensMenu
    @IntoSet
    fun provideOemTokenSwitch(
        packageManager: PackageManager,
        overlayManager: OverlayManager,
    ): MenuItem {
        val displayText = R.string.menu_oem_tokens_toggle
        val sharedLib: String? = getTokenSharedLibPackageName(packageManager)
        var isEnabled = false
        var isChecked = false
        var rroPackage = ""
        if (sharedLib != null) {
            val overlayInfo: List<OverlayInfo> =
                overlayManager.getOverlayInfosForTarget(sharedLib, UserHandle.CURRENT)
            if (overlayInfo.isNotEmpty() && overlayInfo[0].isValid()) {
                isEnabled = true
                isChecked = overlayInfo[0].isEnabled
                rroPackage = overlayInfo[0].packageName
            }
        }

        return MenuItem.Switch(
            displayTextRes = displayText,
            isEnabled = isEnabled,
            isChecked = isChecked,
            action = ToggleOemTokensAction(displayText, rroPackage, !isChecked)
        )
    }

    @Provides
    @IntoMap
    @MenuActionKey(ToggleOemTokensAction::class)
    fun provideOemTokensToggleActionReducer(
        @UIContext context: Context,
        overlayManager: OverlayManager,
    ): MenuActionReducer = OemTokensToggleReducer(context, overlayManager)
}

private fun getTokenSharedLibPackageName(packageManager: PackageManager): String? {
    return packageManager.getSharedLibraries(/*flags=*/0)
        .find { info -> info.name == "com.android.oem.tokens" }
        ?.declaringPackage
        ?.packageName
}
