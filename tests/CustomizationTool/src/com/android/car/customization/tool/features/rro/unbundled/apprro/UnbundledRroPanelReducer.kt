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

package com.android.car.customization.tool.features.rro.unbundled.apprro

import android.content.om.OverlayInfo
import android.content.om.OverlayManager
import android.os.UserHandle
import com.android.car.customization.tool.domain.panel.Panel
import com.android.car.customization.tool.domain.panel.PanelAction
import com.android.car.customization.tool.domain.panel.PanelActionReducer
import com.android.car.customization.tool.domain.panel.PanelItem
import com.android.car.customization.tool.features.common.isValid

data class UnbundledAppsRroToggleAction(
    val rroPackage: String,
    val newState: Boolean,
) : PanelAction

internal class UnbundledRroPanelReducer(
    private val overlayManager: OverlayManager,
) : PanelActionReducer {

    override lateinit var bundle: Map<String, Any>

    override fun build(): Panel {
        val appPackage: String = (bundle[BUNDLE_UNBUNDLED_PACKAGE]
            ?: throw IllegalArgumentException(
                "The unbundled package has not been added to the action"
            )) as String

        val items = listOf(PanelItem.SectionTitle(appPackage))
            .plus(
                overlayManager.getOverlayInfosForTarget(
                    appPackage,
                    UserHandle.CURRENT
                ).filter {
                    it.isValid()
                }.map { overlayInfo ->
                    PanelItem.Switch(
                        text = overlayInfo.packageName,
                        errorText = if (!overlayInfo.isValid()) {
                            OverlayInfo.stateToString(overlayInfo.state)
                        } else {
                            null
                        },
                        isChecked = overlayInfo.isEnabled,
                        isEnabled = overlayInfo.isMutable && overlayInfo.isValid(),
                        action = UnbundledAppsRroToggleAction(
                            overlayInfo.packageName,
                            !overlayInfo.isEnabled
                        )
                    )
                }
            )

        return Panel(items = items)
    }

    override fun reduce(
        panel: Panel,
        action: PanelAction,
    ): Panel = when (action) {
        is UnbundledAppsRroToggleAction -> toggleRRO(panel, action)
        else -> throw NotImplementedError("Action $action not implemented for this Panel")
    }

    private fun toggleRRO(
        panel: Panel,
        action: UnbundledAppsRroToggleAction,
    ): Panel {
        overlayManager.setEnabled(action.rroPackage, action.newState, UserHandle.CURRENT)

        return Panel(
            items = panel.items.map { item ->
                if (item is PanelItem.Switch && item.text == action.rroPackage) {
                    item.copy(
                        isChecked = action.newState,
                        action = action.copy(newState = !action.newState)
                    )
                } else {
                    item
                }
            }
        )
    }

    companion object {
        const val BUNDLE_UNBUNDLED_PACKAGE = "unbundled_package"
    }
}
