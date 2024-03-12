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

import android.content.Context
import androidx.annotation.StringRes

/**
 * Finds the parent of a given node.
 *
 * @receiver the root node where the search starts.
 * @param node the node whose parent needs to be found.
 * @return the parent of the input [node] or null if the parent has not been found
 */
internal fun MenuItem.SubMenuNavigation.findParentOf(
    node: MenuItem.SubMenuNavigation,
): MenuItem.SubMenuNavigation? {
    if (subMenu.contains(node)) return this

    subMenu.filterIsInstance<MenuItem.SubMenuNavigation>().forEach {
        val newParent = it.findParentOf(node)
        if (newParent != null) {
            return newParent
        }
    }

    return null
}

/**
 * Finds a node in the tree.
 *
 * @receiver the starting Node for the search.
 * @param nodeId the ID of the node to find.
 * @return the node or null if the node has not been found.
 */
internal fun MenuItem.SubMenuNavigation.findSubMenuNavigationNode(
    @StringRes nodeId: Int,
): MenuItem.SubMenuNavigation? {
    if (displayTextRes == nodeId) return this

    subMenu.filterIsInstance<MenuItem.SubMenuNavigation>().forEach {
        val node = it.findSubMenuNavigationNode(nodeId)
        if (node != null) {
            return node
        }
    }
    return null
}

/**
 * Modifies the state of a [MenuItem.Switch] item.
 *
 * @receiver the current [Menu].
 * @param context used for making the exception message more useful.
 * @param itemId the ID of the [MenuItem.Switch] to modify.
 * @param newState the new state the switch should have.
 * @return A new [Menu] where the [MenuItem.Switch] value has been updated.
 * @throws IllegalStateException if the [Menu] tree doesn't contain the element.
 */
internal fun Menu.modifySwitch(
    context: Context,
    @StringRes itemId: Int,
    newState: Boolean,
): Menu = modifyMenu(context, oldMenu = this, itemId) {
    it as MenuItem.Switch

    it.copy(
        isChecked = newState,
        action = it.action.clone(newValue = newState)
    )
}

/**
 * Modifies which [MenuItem.DropDown.Item] is active in a [MenuItem.DropDown].
 *
 * @receiver the current [Menu].
 * @param context used for making the exception message more useful.
 * @param itemId the ID of the [MenuItem.DropDown] to modify.
 * @param action the action that will be used to select the new active item.
 * @return A new [Menu] where the [MenuItem.DropDown] value has been updated.
 * @throws IllegalStateException if the [Menu] tree doesn't contain the element.
 */
internal fun Menu.modifyDropDown(
    context: Context,
    itemId: Int,
    action: MenuAction,
): Menu = modifyMenu(context, oldMenu = this, itemId) {
    it as MenuItem.DropDown

    val newItems = it.items.map { item ->
        if (item.action == action) {
            item.copy(isActive = true)
        } else {
            item.copy(isActive = false)
        }
    }
    it.copy(items = newItems)
}

/**
 * Finds a [MenuItem], applies a lambda and returns a new updated [Menu].
 *
 * @param context used for making the exception message more useful.
 * @param oldMenu the current [Menu].
 * @param itemId the ID of the [MenuItem] to find.
 * @param block the lambda to apply to the [MenuItem].
 * @return A new [Menu] where the [MenuItem] has been updated.
 * @throws IllegalStateException if the [Menu] tree doesn't contain the element.
 */
private fun modifyMenu(
    context: Context,
    oldMenu: Menu,
    @StringRes itemId: Int,
    block: (MenuItem) -> MenuItem,
): Menu {
    val newRoot = modifyNode(oldMenu.rootNode, itemId, block)
    val newParentNode = newRoot.findSubMenuNavigationNode(oldMenu.currentParentNode.displayTextRes)
        ?: throw IllegalStateException(
            "The new menu tree doesn't contain ID: " + context.getString(
                oldMenu.currentParentNode.displayTextRes
            )
        )
    return Menu(newRoot, newParentNode)
}

/**
 * Finds a [MenuItem], applies a lambda and returns a new updated root element.
 *
 * @param root the root node where the search starts.
 * @param itemId the ID of the [MenuItem] to find.
 * @param block the lambda to apply to the [MenuItem].
 * @return A new root [MenuItem.SubMenuNavigation] where the [MenuItem] has been updated.
 * @throws IllegalStateException if the [Menu] tree doesn't contain the element.
 */
private fun modifyNode(
    root: MenuItem.SubMenuNavigation,
    @StringRes itemId: Int,
    block: (MenuItem) -> MenuItem,
): MenuItem.SubMenuNavigation {
    val newSubmenu: List<MenuItem> = if (root.subMenu.any { it.displayTextRes == itemId }) {
        root.subMenu.map { if (it.displayTextRes == itemId) block(it) else it }
    } else {
        root.subMenu.map {
            if (it is MenuItem.SubMenuNavigation) {
                modifyNode(it, itemId, block)
            } else {
                it
            }
        }
    }
    return root.copy(
        subMenu = newSubmenu
    )
}

/**
 * Formats a sub menu correctly starting from a [Set] of [MenuItem].
 *
 * @receiver the [Set] of [MenuItem].
 * @param context used to sort the values of the element, this helps with keeping the UI consistent.
 * @param addBackButton decides if this sub menu will have a "up button" or not.
 * @return A list of [MenuItem] properly formatted as a sub menu
 */
internal fun Set<MenuItem>.toSubMenu(
    context: Context,
    addBackButton: Boolean = true,
): List<MenuItem> {
    val submenu = this.toList().sortedBy { context.getString(it.displayTextRes) }
    return if (addBackButton) submenu.plus(MenuItem.UpNavigation) else submenu
}
