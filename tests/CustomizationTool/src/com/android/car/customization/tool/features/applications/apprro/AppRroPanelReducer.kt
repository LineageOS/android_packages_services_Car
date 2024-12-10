/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.car.customization.tool.features.applications.apprro

import android.content.om.OverlayIdentifier
import android.content.om.OverlayInfo
import android.content.om.OverlayManager
import android.os.UserHandle
import com.android.car.customization.tool.domain.panel.Panel
import com.android.car.customization.tool.domain.panel.PanelAction
import com.android.car.customization.tool.domain.panel.PanelActionReducer
import com.android.car.customization.tool.domain.panel.PanelHeaderItem
import com.android.car.customization.tool.domain.panel.PanelItem
import com.android.car.customization.tool.features.common.isValid
import com.android.car.customization.tool.features.common.setEnableOverlay

data class AppRroToggleAction(
    val rroIdentifier: OverlayIdentifier,
    val newState: Boolean,
) : PanelAction

internal class AppRroPanelReducer(
    private val overlayManager: OverlayManager,
) : PanelActionReducer {

    override lateinit var bundle: Map<String, Any>
    private lateinit var header: List<PanelHeaderItem>

    override fun build(): Panel {
        if (!this::header.isInitialized) {
            val title = bundle[BUNDLE_PANEL_TITLE]
            require(title is String) { "The app name has not been added to the action" }
            header = listOf(
                PanelHeaderItem.CloseButton,
                PanelHeaderItem.Title(
                    text = title,
                )
            )
        }

        val appPackage = bundle[BUNDLE_APP_PACKAGE]
        require(appPackage is String) { "The app package has not been added to the action" }

        val items =
            overlayManager
                .getOverlayInfosForTarget(
                    appPackage,
                    UserHandle.CURRENT
                ).filter {
                    it.isValid()
                }.map { overlayInfo ->
                    PanelItem.Switch(
                        text = overlayInfo
                            .overlayIdentifier
                            .toString()
                            .removePrefix("$appPackage."),
                        errorText = if (!overlayInfo.isValid()) {
                            OverlayInfo.stateToString(overlayInfo.state)
                        } else {
                            null
                        },
                        isChecked = overlayInfo.isEnabled,
                        isEnabled = overlayInfo.isMutable && overlayInfo.isValid(),
                        action = AppRroToggleAction(
                            overlayInfo.overlayIdentifier,
                            !overlayInfo.isEnabled
                        )
                    )
                }

        return Panel(headerItems = header, items = items)
    }

    override fun reduce(
        panel: Panel,
        action: PanelAction,
    ): Panel = if (action is AppRroToggleAction) {
        toggleRRO(panel, action)
    } else {
        throw NotImplementedError("Action $action not implemented for this Panel")
    }

    private fun toggleRRO(
        panel: Panel,
        action: AppRroToggleAction,
    ): Panel {

        overlayManager.setEnableOverlay(action.rroIdentifier, action.newState, UserHandle.CURRENT)

        return Panel(
            items = panel.items.map { item ->
                if (item is PanelItem.Switch && item.text == action.rroIdentifier.toString()) {
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
        const val BUNDLE_APP_PACKAGE = "app_package"
        const val BUNDLE_PANEL_TITLE = "app_name"
    }
}
