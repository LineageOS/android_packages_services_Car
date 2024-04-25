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

package com.android.car.customization.tool.domain.menu

import androidx.annotation.StringRes
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.panel.OpenPanelAction

/**
 * The tool's visual menu.
 *
 * The menu has a tree structure and is composed of different [MenuItem] nodes.
 * Only nodes of type [MenuItem.SubMenuNavigation] can have children and allow the user to navigate
 * to sub menus.
 *
 * @property rootNode The root element of the menu.
 * @property currentParentNode The parent of the current menu shown to the user. For example if
 * [currentParentNode] is the same as [rootNode] then the first layer of sub menu is shown (e.g. the
 * menu containing the items: RRO, Plugin, OEM Design Tokens).
 */
internal data class Menu(
    val rootNode: MenuItem.SubMenuNavigation,
    val currentParentNode: MenuItem.SubMenuNavigation,
)

/**
 * The base for all visual items available in Menu.
 *
 * @property displayTextRes the string resource (and ID) of a MenuItem.
 * @property isEnabled defines if an item is enabled, users can't interact with disabled items.
 */
internal sealed class MenuItem(
    @StringRes open val displayTextRes: Int,
    open val isEnabled: Boolean,
) {

    /**
     * The menu item that allows to navigate up the menu.
     *
     * Apart from the main level (RRO, Plugins...) all the other sub menus will contain
     * a UpNavigation item.
     */
    object UpNavigation : MenuItem(R.string.menu_back, isEnabled = true) {
        val action: MenuAction = NavigateUpAction
    }

    /**
     * The menu item that allows to navigate down the menu tree.
     *
     * @property displayTextRes the string resource (and ID) of the item.
     * @property subMenu the list of child nodes of this item.
     */
    data class SubMenuNavigation(
        @StringRes override val displayTextRes: Int,
        val subMenu: List<MenuItem>,
    ) : MenuItem(displayTextRes, isEnabled = true) {

        val action: MenuAction = OpenSubMenuAction(displayTextRes)
    }

    /**
     * The menu item that represents a switch on the menu.
     *
     * @property displayTextRes the string resource (and ID) of the item.
     * @property isEnabled defines if an item is enabled, users can't interact with disabled items.
     * @property isChecked defines if the switch is checked or not.
     * @property action [ToggleMenuAction] that will be triggered when the user taps on the Switch
     */
    data class Switch(
        @StringRes override val displayTextRes: Int,
        override val isEnabled: Boolean,
        val isChecked: Boolean,
        val action: ToggleMenuAction,
    ) : MenuItem(displayTextRes, isEnabled)

    /**
     * The menu item that represents a DropDown item on the menu.
     *
     * Tapping on the DropDown shows the options defined in the [items] property.
     *
     * @property displayTextRes the string resource (and ID) of the item.
     * @property isEnabled defines if an item is enabled, users can't interact with disabled items.
     * @property items contains a list of DropDown.Item that will be shown when the user taps on the
     * DropDown.
     */
    data class DropDown(
        @StringRes override val displayTextRes: Int,
        override val isEnabled: Boolean,
        val items: List<Item>,
    ) : MenuItem(displayTextRes, isEnabled) {

        /**
         * An item inside a DropDown popup list.
         *
         * @property title: the text on the item.
         * @property isActive a boolean that define which is the active option from the list. Only one
         * item should be active at any time.
         * @property action the action that is triggered when the user taps on the item.
         */
        data class Item(
            val title: String,
            val isActive: Boolean = false,
            val action: MenuAction,
        )
    }

    /**
     * The menu item that has the responsibility of launching a [Panel]
     *
     * @property displayTextRes the string resource (and ID) of the item.
     * @property isEnabled defines if an item is enabled, users can't interact with disabled items.
     * @property action [OpenPanelAction] that opens a specific panel.
     */
    data class PanelLauncher(
        @StringRes override val displayTextRes: Int,
        override val isEnabled: Boolean = true,
        val action: OpenPanelAction,
    ) : MenuItem(displayTextRes, isEnabled)
}
