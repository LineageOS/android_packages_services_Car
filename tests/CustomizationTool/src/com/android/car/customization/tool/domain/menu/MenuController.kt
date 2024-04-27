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
import com.android.car.customization.tool.di.UIContext
import com.android.car.customization.tool.domain.Action
import java.util.LinkedList
import java.util.Queue
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The Controller for the Menu.
 *
 * The main responsibility of this class is to provide a new Menu at the start of the service and to
 * update the Menu based on the actions that are sent by the [CustomizationToolStateMachine]
 *
 * @param rootProvider a Dagger [Provider] that contains the root node for the Menu.
 * @param context Android [Context], mainly used for making exceptions messages more useful.
 * @param menuActionReducers used to extend the actions supported by the [MenuController].
 */
@Singleton
internal class MenuController @Inject constructor(
    private val rootProvider: Provider<MenuItem.SubMenuNavigation>,
    @UIContext private val context: Context,
    private val menuActionReducers: Map<Class<out Action>, @JvmSuppressWildcards MenuActionReducer>,
) {

    fun buildMenu(): Menu {
        val root = rootProvider.get()
        assertUniqueIds(root)
        return Menu(rootNode = root, currentParentNode = root)
    }

    fun handleAction(menu: Menu, action: MenuAction): Menu = when (action) {
        ReloadMenuAction -> reloadMenu(menu)
        NavigateUpAction -> navigateUp(menu)
        is OpenSubMenuAction -> navigateToSubMenu(menu, action)
        else -> menuActionReducers[action::class.java]?.reduce(
            menu,
            action
        )
            ?: throw IllegalArgumentException("Action not implemented for this MenuController $action")
    }

    private fun assertUniqueIds(
        root: MenuItem.SubMenuNavigation,
    ) {
        val existingIds = mutableSetOf<Int>()
        val nodesToVisit: Queue<MenuItem> = LinkedList()
        nodesToVisit.add(root)

        while (nodesToVisit.isNotEmpty()) {
            val node = requireNotNull(nodesToVisit.poll())
            if (existingIds.contains(node.displayTextRes)) {
                throw IllegalStateException(
                    "The menu tree contains duplicate IDs: ${
                        context.resources.getResourceName(node.displayTextRes)
                    }"
                )
            }
            if (node is MenuItem.SubMenuNavigation) {
                node.subMenu.forEach {
                    if (it !is MenuItem.UpNavigation) nodesToVisit.add(it)
                }
            }
            existingIds.add(node.displayTextRes)
        }
    }

    /**
     * The function executed when the [MenuAction.NavigateUpAction] action is triggered.
     *
     * @param oldMenu the current [Menu].
     * @return A new [Menu] with an updated [Menu.currentParentNode] value.
     * @throws IllegalStateException in case the new parent node is not found.
     */
    private fun navigateUp(oldMenu: Menu): Menu {
        val newParent = oldMenu.rootNode.findParentOf(oldMenu.currentParentNode)
            ?: throw IllegalStateException(
                "No parent has been found from the node: ${oldMenu.currentParentNode}"
            )
        return oldMenu.copy(currentParentNode = newParent)
    }

    /**
     * The function executed when the [MenuAction.OpenSubMenuAction] action is triggered. It changes the
     * [Menu] activating a new sub menu.
     *
     * @param oldMenu the current [Menu]
     * @param action A [MenuAction.OpenSubMenuAction] action that contains the information of the new sub
     * menu to show
     */
    private fun navigateToSubMenu(
        oldMenu: Menu,
        action: OpenSubMenuAction,
    ): Menu {
        val newParent =
            oldMenu.currentParentNode.subMenu.find { it.displayTextRes == action.newParentId }
                ?: throw IllegalArgumentException(
                    "The new menu is not a direct child of the current menu: " +
                        "current parent: ${oldMenu.currentParentNode}, " +
                        "new parent id: ${context.getString(action.newParentId)}"
                )

        return oldMenu.copy(currentParentNode = newParent as MenuItem.SubMenuNavigation)
    }

    /**
     * The function executed when the [MenuAction.ReloadMenuAction] action is triggered. It creates a
     * whole new [Menu], updating all of the [MenuItem] and in doing so makes sure that the
     * [Menu] always reflects the actual state of the system
     *
     * @param oldMenu the current [Menu]
     * @return a new [Menu] with updated nodes
     */
    private fun reloadMenu(oldMenu: Menu): Menu {
        val newRoot = rootProvider.get()
        val newParentNode = newRoot.findSubMenuNavigationNode(oldMenu.currentParentNode.displayTextRes)
            ?: throw IllegalStateException(
                "The new menu tree doesn't contain ID: ${
                    context.getString(oldMenu.currentParentNode.displayTextRes)
                }"
            )
        return Menu(rootNode = newRoot, currentParentNode = newParentNode)
    }
}
