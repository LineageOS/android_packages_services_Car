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

import android.content.om.OverlayManager
import android.os.UserHandle
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.menu.MenuItem
import com.android.car.customization.tool.features.common.isValid

internal data class ThemeRRO(
    val systemPackages: List<String>,
    val userPackages: List<String>,
) {

    fun filterInstalled(overlayManager: OverlayManager) = ThemeRRO(
        systemPackages = systemPackages.filter { rroPackage ->
            overlayManager.getOverlayInfo(rroPackage, UserHandle.SYSTEM)?.isValid() ?: false
        },
        userPackages = userPackages.filter { rroPackage ->
            overlayManager.getOverlayInfo(rroPackage, UserHandle.CURRENT)?.isValid() ?: false
        }
    )

    fun isActive(overlayManager: OverlayManager): Boolean {
        return systemPackages.all { rroPackage ->
            overlayManager.getOverlayInfo(rroPackage, UserHandle.SYSTEM)?.isEnabled ?: false
        } && userPackages.all { rroPackage ->
            overlayManager.getOverlayInfo(rroPackage, UserHandle.CURRENT)?.isEnabled ?: false
        }
    }
}

internal fun rroThemePresetsDropDownItem(overlayManager: OverlayManager): MenuItem.DropDown {
    val textRes = R.string.menu_rro_systemui_theme

    val dropDownItems = mutableListOf<MenuItem.DropDown.Item>()

    val themes = mapOf(
        "Pink" to ThemeRRO(
            systemPackages = getSystemRroPackages(theme = "pink"),
            userPackages = getUserRroPackages(theme = "pink")
        ),
        "Orange" to ThemeRRO(
            systemPackages = getSystemRroPackages(theme = "orange"),
            userPackages = getUserRroPackages(theme = "orange")
        )
    ).mapValues { (_, themeRros) -> themeRros.filterInstalled(overlayManager) }

    themes
        .forEach { (title, theme) ->
            dropDownItems.add(
                MenuItem.DropDown.Item(
                    title = title,
                    isActive = theme.isActive(overlayManager),
                    action = SelectThemePresetAction(
                        itemId = textRes,
                        themeToEnable = theme,
                        themesToDisable = themes.minus(title).values.toList()
                    )
                )
            )
        }

    val isDropDownEnabled = dropDownItems.isNotEmpty()
    if (isDropDownEnabled) {
        dropDownItems.add(
            index = 0,
            MenuItem.DropDown.Item(
                title = "Default",
                isActive = dropDownItems.none { it.isActive },
                action = SelectThemePresetAction(
                    itemId = textRes,
                    // Default needs no package since it's there just to disable the others
                    themeToEnable = null,
                    themesToDisable = themes.values.toList()
                )
            )
        )
    }

    return MenuItem.DropDown(
        displayTextRes = textRes,
        isEnabled = isDropDownEnabled,
        items = dropDownItems
    )
}

private fun getSystemRroPackages(theme: String): List<String> = listOf(
    "com.android.systemui.googlecarui.theme.$theme.rro",
    "android.googlecarui.theme.$theme.rro"
)

private fun getUserRroPackages(theme: String): List<String> = listOf(
    "com.android.car.messenger.googlecarui.theme.$theme.rro",
    "com.android.car.calendar.googlecarui.theme.$theme.rro",
    "com.android.car.media.googlecarui.theme.$theme.rro",
    "com.android.car.radio.googlecarui.theme.$theme.rro",
    "com.android.htmlviewer.googlecarui.theme.$theme.rro",
    "com.android.car.systemupdater.googlecarui.theme.$theme.rro",
    "com.android.car.developeroptions.googlecarui.theme.$theme.rro",
    "com.android.settings.intelligence.googlecarui.theme.$theme.rro",
    "com.android.car.settings.googlecarui.theme.$theme.rro",
    "com.android.car.home.googlecarui.theme.$theme.rro",
    "com.android.car.dialer.googlecarui.theme.$theme.rro",
    "com.android.car.notification.googlecarui.theme.$theme.rro",
    "com.android.car.linkviewer.googlecarui.theme.$theme.rro",
    "com.android.car.themeplayground.googlecarui.theme.$theme.rro",
    "com.android.car.carlauncher.googlecarui.theme.$theme.rro",
    "com.android.managedprovisioning.googlecarui.theme.$theme.rro",
    "com.android.car.rotaryplayground.googlecarui.theme.$theme.rro",
)
