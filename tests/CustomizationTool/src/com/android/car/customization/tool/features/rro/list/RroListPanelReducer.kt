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

package com.android.car.customization.tool.features.rro.list

import android.content.om.OverlayInfo
import android.content.om.OverlayManager
import android.content.pm.PackageManager
import android.os.UserHandle
import com.android.car.customization.tool.domain.panel.Panel
import com.android.car.customization.tool.domain.panel.PanelAction
import com.android.car.customization.tool.domain.panel.PanelActionReducer
import com.android.car.customization.tool.domain.panel.PanelHeaderItem
import com.android.car.customization.tool.domain.panel.PanelItem
import com.android.car.customization.tool.features.common.isValid

internal data class PanelToggleRroAction(
    val rroPackage: String,
    val newState: Boolean,
) : PanelAction

internal data class PanelSearchTargetPackageAction(
    val packageToSearch: String
) : PanelAction

internal data class PanelSearchRroPackageAction(
    val packageToSearch: String
) : PanelAction

internal data class PanelSelectUserHandleAction(
    val userHandle: UserHandle
) : PanelAction

internal class RroListPanelReducer(
    private val packageManager: PackageManager,
    private val overlayManager: OverlayManager,
) : PanelActionReducer {

    override lateinit var bundle: Map<String, Any>

    private lateinit var header: List<PanelHeaderItem>

    private var currentTargetPackageFilter: String = ""
    private var currentRroPackageFilter: String = ""
    private var currentUserHandle: UserHandle = UserHandle.CURRENT

    data class RroInfo(
        val packageName: String,
        val targetPackage: String,
        val isChecked: Boolean,
        val isEnabled: Boolean,
        val errorText: String?
    )

    private var rroState: List<Pair<String, List<RroInfo>>> = listOf()

    override fun build(): Panel {
        if (!this::header.isInitialized) {
            header = listOf(
                PanelHeaderItem.CloseButton,
                PanelHeaderItem.SearchBox(
                    searchHint = "Target Package",
                    searchAction = { text -> PanelSearchTargetPackageAction(text) },
                ),
                PanelHeaderItem.SearchBox(
                    searchHint = "RRO Package",
                    searchAction = { text -> PanelSearchRroPackageAction(text) },
                ),
                PanelHeaderItem.DropDown(
                    text = "UserHandle:",
                    items = listOf(
                        PanelHeaderItem.DropDown.Item(
                            text = "CURRENT",
                            action = PanelSelectUserHandleAction(UserHandle.CURRENT),
                            isActive = true
                        ),
                        PanelHeaderItem.DropDown.Item(
                            text = "SYSTEM",
                            action = PanelSelectUserHandleAction(UserHandle.SYSTEM),
                            isActive = false
                        ),
                    )
                )
            )
        }

        rroState = getOverlayInfoFromSystem()
        return Panel(headerItems = header, items = createUiItems())
    }

    override fun reduce(panel: Panel, action: PanelAction): Panel = when (action) {
        is PanelToggleRroAction -> toggleRro(action)
        is PanelSearchTargetPackageAction -> filterByTargetPackage(action)
        is PanelSearchRroPackageAction -> filterByRroPackage(action)
        is PanelSelectUserHandleAction -> selectUserHandle(action)
        else -> throw NotImplementedError("Action $action not implemented for this Panel")
    }

    private fun toggleRro(action: PanelToggleRroAction): Panel {
        overlayManager.setEnabled(action.rroPackage, action.newState, currentUserHandle)
        rroState = rroState.map { (targetPackage, overlays) ->
            Pair(
                targetPackage,
                overlays.map { rroInfo ->
                    if (rroInfo.packageName == action.rroPackage) {
                        rroInfo.copy(isChecked = action.newState)
                    } else {
                        rroInfo
                    }
                }
            )
        }

        return Panel(headerItems = header, items = createUiItems())
    }

    private fun filterByTargetPackage(action: PanelSearchTargetPackageAction): Panel {
        currentTargetPackageFilter = action.packageToSearch
        return Panel(headerItems = header, items = createUiItems())
    }

    private fun filterByRroPackage(action: PanelSearchRroPackageAction): Panel {
        currentRroPackageFilter = action.packageToSearch
        return Panel(headerItems = header, items = createUiItems())
    }

    private fun selectUserHandle(action: PanelSelectUserHandleAction): Panel {
        currentUserHandle = action.userHandle
        header = header.map { headerItem ->
            if (headerItem is PanelHeaderItem.DropDown) {
                headerItem.selectDropDownItem(action)
            } else {
                headerItem
            }
        }

        rroState = getOverlayInfoFromSystem()
        return Panel(header, createUiItems())
    }

    private fun getOverlayInfoFromSystem(): List<Pair<String, List<RroInfo>>> {
        return packageManager.getInstalledPackages(/*flags=*/0).map { packageInfo ->
            Pair(
                packageInfo.packageName,
                overlayManager.getOverlayInfosForTarget(
                    packageInfo.packageName,
                    currentUserHandle
                ).map { overlayInfo ->
                    RroInfo(
                        packageName = overlayInfo.packageName,
                        targetPackage = overlayInfo.targetPackageName,
                        isChecked = overlayInfo.isEnabled,
                        isEnabled = overlayInfo.isMutable && overlayInfo.isValid(),
                        errorText = if (!overlayInfo.isValid()) {
                            OverlayInfo.stateToString(overlayInfo.state)
                        } else {
                            null
                        }
                    )
                }
            )
        }
    }

    private fun createUiItems(): List<PanelItem> = rroState
        // Filtering all packages that don't have overlays
        .filter { (_, overlays) -> overlays.isNotEmpty() }
        .filter { (targetPackage, _) -> targetPackage.contains(currentTargetPackageFilter) }
        .map { (targetPackage, overlays) ->
            Pair(
                targetPackage,
                overlays.filter { rroInfo ->
                    rroInfo.packageName.contains(currentRroPackageFilter)
                }
            )
        }
        // Removing packages that had all of the overlays filtered out
        .filter { (_, overlays) -> overlays.isNotEmpty() }
        .sortedBy { (packageName, _) -> packageName }
        .flatMap { (packageName, overlays) ->
            listOf(PanelItem.SectionTitle(packageName)).plus(
                overlays.map { rroInfo ->
                    PanelItem.Switch(
                        text = rroInfo.packageName,
                        errorText = rroInfo.errorText,
                        isChecked = rroInfo.isChecked,
                        isEnabled = rroInfo.isEnabled,
                        PanelToggleRroAction(rroInfo.packageName, !rroInfo.isChecked)
                    )
                }
            )
        }
}
