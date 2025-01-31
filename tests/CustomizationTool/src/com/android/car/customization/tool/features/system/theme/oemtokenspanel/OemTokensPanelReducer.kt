/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.car.customization.tool.features.system.theme.oemtokenspanel

import android.annotation.SuppressLint
import android.content.om.OverlayIdentifier
import android.content.om.OverlayInfo
import android.content.om.OverlayManager
import android.content.pm.PackageManager
import android.os.UserHandle
import com.android.car.customization.tool.domain.panel.Panel
import com.android.car.customization.tool.domain.panel.PanelAction
import com.android.car.customization.tool.domain.panel.PanelActionReducer
import com.android.car.customization.tool.domain.panel.PanelItem
import com.android.car.customization.tool.features.common.isValid
import com.android.car.customization.tool.features.common.setEnableExclusive
import com.android.car.customization.tool.features.common.setEnableOverlay

internal data class PanelToggleTokensRroAction(
    val rroIdentifier: OverlayIdentifier,
    val newState: Boolean,
) : PanelAction

internal data class PanelToggleLibraryAction(
    val newState: Boolean
) : PanelAction

@SuppressLint("MissingPermission") // Permission is actually set in the manifest
internal class OemTokensPanelReducer(
    private val packageManager: PackageManager,
    private val overlayManager: OverlayManager,
) : PanelActionReducer {

    override var bundle: Map<String, Any> = emptyMap()

    private lateinit var sharedLibraryOverlays: List<OverlayInfo>
    private lateinit var androidOverlays: List<OverlayInfo>
    private lateinit var pluginOverlays: List<OverlayInfo>

    override fun build(): Panel {
        sharedLibraryOverlays = getOverlaysSharedLibrary()
        androidOverlays = getOverlaysAndroid()
        pluginOverlays = getOverlaysPlugin()

        return createPanel()
    }

    override fun reduce(panel: Panel, action: PanelAction): Panel =
        when (action) {
            is PanelToggleTokensRroAction -> toggleRRO(panel, action)
            is PanelToggleLibraryAction -> toggleLibrary(panel, action)
            else -> throw NotImplementedError("Action $action not implemented for this Panel")
        }

    private fun createPanel(): Panel {
        val items = mutableListOf(
            PanelItem.SectionTitle("Shared library ($LIBRARY_PACKAGE)"),
            getLibraryStatusItem(),
            getLibraryToggleItem()
        )
        items.addAll(
            sharedLibraryOverlays.toRroListSection(
                rroTargetText = "Shared Library"
            )
        )
        items.addAll(
            androidOverlays.toRroListSection(
                rroTargetText = "Android"
            )
        )
        items.addAll(
            pluginOverlays.toRroListSection(
                rroTargetText = "Car Ui Lib Plugin"
            )
        )
        return Panel(items = items)
    }

    private fun toggleRRO(panel: Panel, action: PanelToggleTokensRroAction): Panel {
        overlayEnable(action)

        when {
            sharedLibraryOverlays.any { it.packageName == action.rroIdentifier.packageName } -> {
                sharedLibraryOverlays = getOverlaysSharedLibrary()
            }

            androidOverlays.any { it.packageName == action.rroIdentifier.packageName } -> {
                androidOverlays = getOverlaysAndroid()
            }

            pluginOverlays.any { it.packageName == action.rroIdentifier.packageName } -> {
                pluginOverlays = getOverlaysPlugin()
            }
        }

        return createPanel()
    }

    private fun overlayEnable(action: PanelToggleTokensRroAction) {
        if (action.newState) {
            // Enable exclusively one RRO
            overlayManager.setEnableExclusive(
                action.rroIdentifier,
                sharedLibraryOverlays,
                UserHandle.CURRENT
            )
        } else {
            overlayManager.setEnableOverlay(
                action.rroIdentifier,
                newState = false,
                UserHandle.CURRENT
            )
        }
    }

    private fun toggleLibrary(panel: Panel, action: PanelToggleLibraryAction): Panel {
        packageManager.setApplicationEnabledSetting(
            LIBRARY_PACKAGE,
            if (action.newState) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            /*flags = */0
        )
        return createPanel()
    }

    private fun getLibraryStatusItem(): PanelItem.Status {
        val status =
            packageManager.getSharedLibraries(/*flags = */0)
                .any { libraryInfo -> libraryInfo.packageName == LIBRARY_PACKAGE }
        return PanelItem.Status(
            text = "Shared Library installed",
            errorText = if (!status) "Library not found" else null,
            isPositive = status
        )
    }

    private fun getLibraryToggleItem(): PanelItem.Switch {
        val isInstalled = packageManager.getSharedLibraries(/*flags = */0)
            .any { libraryInfo -> libraryInfo.packageName == LIBRARY_PACKAGE }
        val status = packageManager.getApplicationEnabledSetting(LIBRARY_PACKAGE)
        val isChecked =
            status == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                    status == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        return PanelItem.Switch(
            text = "Shared Library enabled",
            errorText = if (!isInstalled) "Library not found" else null,
            isEnabled = isInstalled,
            isChecked = isChecked,
            action = PanelToggleLibraryAction(!isChecked)
        )
    }

    private fun List<OverlayInfo>.toRroListSection(
        rroTargetText: String
    ): List<PanelItem> {
        val uiOverlayItems =
            map { overlayInfo ->
                PanelItem.Switch(
                    text = overlayInfo.overlayIdentifier.toString(),
                    errorText = if (!overlayInfo.isValid()) {
                        OverlayInfo.stateToString(overlayInfo.state)
                    } else {
                        null
                    },
                    isChecked = overlayInfo.isEnabled,
                    isEnabled = overlayInfo.isMutable && overlayInfo.isValid(),
                    action = PanelToggleTokensRroAction(
                        overlayInfo.overlayIdentifier,
                        !overlayInfo.isEnabled
                    )
                )
            }
                .sortedBy { it.text }
        val sectionTitle = PanelItem.SectionTitle(
            text = "${if (uiOverlayItems.isEmpty()) "No" else ""} RROs targeting $rroTargetText"
        )
        return listOf(sectionTitle)
            .plus(
                uiOverlayItems
            )
    }

    private fun getOverlaysSharedLibrary(): List<OverlayInfo> =
        overlayManager
            .getOverlayInfosForTarget(
                LIBRARY_PACKAGE,
                UserHandle.CURRENT
            )
            .filter {
                it.isValid()
            }

    private fun getOverlaysAndroid(): List<OverlayInfo> =
        overlayManager
            .getOverlayInfosForTarget(
                "android",
                UserHandle.CURRENT
            )
            .filter {
                it.packageName.contains(OVERLAY_PREFIX) && it.isValid()
            }

    private fun getOverlaysPlugin(): List<OverlayInfo> =
        overlayManager
            .getOverlayInfosForTarget(
                "com.chassis.car.ui.plugin",
                UserHandle.CURRENT
            )
            .filter {
                it.packageName.contains(OVERLAY_PREFIX) && it.isValid()
            }

    companion object {
        private const val LIBRARY_PACKAGE = "oem.demo.sharedlib"
        private const val OVERLAY_PREFIX = "oem.brand.model"
    }
}
