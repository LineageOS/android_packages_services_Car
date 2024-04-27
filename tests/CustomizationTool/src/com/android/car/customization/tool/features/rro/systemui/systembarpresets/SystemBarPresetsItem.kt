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

import android.content.om.OverlayManager
import android.os.UserHandle
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.menu.MenuItem
import com.android.car.customization.tool.features.common.isValid

internal fun systemBarPresetsDropDownItem(overlayManager: OverlayManager): MenuItem.DropDown {
    val textRes = R.string.menu_rro_systemui_bars
    val dropDownItems = mutableListOf<MenuItem.DropDown.Item>()
    val barConfigurations = mapOf(
        "Bottom" to "com.android.systemui.rro.bottom",
        "BottomRounded" to "com.android.systemui.rro.bottom.rounded",
        "Right" to "com.android.systemui.rro.right",
        "Left" to "com.android.systemui.rro.left"
    ).mapValues { (_, rroPackage) ->
        overlayManager.getOverlayInfo(
            /*packageName =*/ rroPackage,
            /*userHandle =*/ UserHandle.SYSTEM
        )
    }.filterValues { overlayInfo ->
        overlayInfo?.isValid() ?: false
    }.mapValues { (_, overlayInfo) ->
        // Changes the type of the values from OverlayInfo? to OverlayInfo.
        requireNotNull(overlayInfo)
    }

    barConfigurations.forEach { (title, overlayInfo) ->
        dropDownItems.add(
            MenuItem.DropDown.Item(
                title = title,
                isActive = overlayInfo.isEnabled,
                action = SelectSystemBarPresetAction(
                    itemId = textRes,
                    packageToEnable = overlayInfo.packageName,
                    packagesToDisable = barConfigurations.minus(title).values.map { it.packageName }
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
                action = SelectSystemBarPresetAction(
                    itemId = textRes,
                    // Default needs no package since it's there just to disable the others
                    packageToEnable = null,
                    packagesToDisable = barConfigurations.values.map { it.packageName }
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
