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

package com.android.car.customization.tool.domain

import androidx.annotation.StringRes
import com.android.car.customization.tool.domain.menu.Menu
import com.android.car.customization.tool.domain.menu.MenuAction
import com.android.car.customization.tool.domain.menu.MenuActionReducer
import com.android.car.customization.tool.domain.menu.MenuItem
import com.android.car.customization.tool.domain.menu.ToggleMenuAction
import com.android.car.customization.tool.domain.panel.OpenPanelAction
import com.android.car.customization.tool.domain.panel.Panel
import com.android.car.customization.tool.domain.panel.PanelAction
import com.android.car.customization.tool.domain.panel.PanelActionReducer

internal object FakeAction : Action

internal class FakeMenuAction : MenuAction

internal data class FakeToggleAction(
    @StringRes override val itemId: Int = 0,
    override val newValue: Boolean = false,
) : ToggleMenuAction {

    override fun clone(newValue: Boolean): ToggleMenuAction = copy(newValue = newValue)
}

internal class FakeMenuActionReducer : MenuActionReducer {

    override fun reduce(menu: Menu, action: MenuAction): Menu {
        return Menu(rootNode = rootNode, currentParentNode = secondLevelSubNavigation)
    }
}

// All of the necessary objects to create a simple menu tree. The structure is the following:
//                                     rootNode
//                            /                       \
//                firstLevelSubNavigation              firstLevelPanelLauncher
//                /              |         \
//  secondLevelSwitch1  secondLevelSwitch2  secondLevelSubNavigation
//                                            /
//                                         thirdLevelDropDown
internal val dropDownItemA = MenuItem.DropDown.Item(
    title = "Item1",
    isActive = true,
    action = FakeMenuAction()
)
internal val dropDownItemB = MenuItem.DropDown.Item(
    title = "Item2",
    isActive = false,
    action = FakeMenuAction()
)
internal val thirdLevelDropDown = MenuItem.DropDown(
    displayTextRes = 1,
    isEnabled = true,
    items = listOf(dropDownItemA, dropDownItemB)
)
internal val secondLevelSubNavigation = MenuItem.SubMenuNavigation(
    displayTextRes = 2,
    subMenu = listOf(thirdLevelDropDown)
)
internal val secondLevelSwitch1 = MenuItem.Switch(
    displayTextRes = 3,
    isEnabled = true,
    isChecked = true,
    FakeToggleAction()
)
internal val secondLevelSwitch2 = MenuItem.Switch(
    displayTextRes = 4,
    isEnabled = true,
    isChecked = true,
    FakeToggleAction()
)
internal val firstLevelPanelLauncher = MenuItem.PanelLauncher(
    displayTextRes = 5,
    isEnabled = true,
    action = OpenPanelAction(PanelActionReducer::class)
)
internal val firstLevelSubNavigation = MenuItem.SubMenuNavigation(
    displayTextRes = 6,
    subMenu = listOf(secondLevelSubNavigation, secondLevelSwitch1, secondLevelSwitch2)
)
internal val rootNode = MenuItem.SubMenuNavigation(
    displayTextRes = 0,
    subMenu = listOf(firstLevelPanelLauncher, firstLevelSubNavigation)
)

internal class EmptyPanelReducer : PanelActionReducer {
    override var bundle: Map<String, Any> = emptyMap()
    override fun build(): Panel = Panel(items = emptyList())
    override fun reduce(panel: Panel, action: PanelAction): Panel = Panel(items = emptyList())
}

/**
 * A simple test function to find any node inside the menu tree
 */
internal fun MenuItem.SubMenuNavigation.findNode(
    @StringRes nodeId: Int,
): MenuItem? {
    if (displayTextRes == nodeId) return this

    subMenu.forEach { node ->
        if (node.displayTextRes == nodeId) {
            return node
        } else if (node is MenuItem.SubMenuNavigation) {
            val subNode = node.findNode(nodeId)
            if (subNode?.displayTextRes == nodeId) {
                return subNode
            }
        }
    }
    return null
}
