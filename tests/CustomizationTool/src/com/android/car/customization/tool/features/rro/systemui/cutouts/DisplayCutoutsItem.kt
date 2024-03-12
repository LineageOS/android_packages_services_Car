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

package com.android.car.customization.tool.features.rro.systemui.cutouts

import android.content.om.OverlayManager
import android.os.UserHandle
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.menu.MenuItem
import com.android.car.customization.tool.features.common.isValid
import com.android.car.customization.tool.features.rro.systemui.systembarpresets.SelectSystemBarPresetAction

internal fun cutoutsDropDownItem(overlayManager: OverlayManager): MenuItem.DropDown {
    val textRes = R.string.menu_rro_systemui_cutouts

    val dropDownItems = mutableListOf<MenuItem.DropDown.Item>()
    val cutoutsConfiguration = mapOf(
        "Free Form" to "com.android.internal.display.cutout.emulation.free_form",
        "Top and Left" to "com.android.internal.display.cutout.emulation.top_and_left",
        "Top and Right" to "com.android.internal.display.cutout.emulation.top_and_right",
        "Left and Right" to "com.android.internal.display.cutout.emulation.left_and_right",
        "Left and Right Rounded" to
            "com.android.internal.display.cutout.emulation.left_and_right_rounded",
        "Emu01" to "com.android.internal.display.cutout.emulation.emu01",
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

    cutoutsConfiguration.forEach { (title, overlayInfo) ->
        dropDownItems.add(
            MenuItem.DropDown.Item(
                title = title,
                isActive = overlayInfo.isEnabled,
                action = SelectSystemBarPresetAction(
                    itemId = textRes,
                    packageToEnable = overlayInfo.packageName,
                    packagesToDisable = cutoutsConfiguration.minus(title).values.map { it.packageName }
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
                    packagesToDisable = cutoutsConfiguration.values.map { it.packageName }
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
