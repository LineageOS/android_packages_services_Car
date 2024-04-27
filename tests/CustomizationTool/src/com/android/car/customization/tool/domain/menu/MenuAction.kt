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
import com.android.car.customization.tool.domain.Action

/**
 * Base for all the menu actions.
 *
 * Menu Actions are actions that trigger a change in the menu.
 */
internal interface MenuAction : Action

/**
 * This action triggers an update of the whole menu.
 *
 * It's used to keep the menu up to date with the system.
 */
object ReloadMenuAction : MenuAction

/**
 * Navigate up the menu tree.
 */
object NavigateUpAction : MenuAction

/**
 * Open a sub menu and navigate down the menu tree.
 *
 * @property newParentId the id of the new parent that will be used in [Menu.currentParentNode].
 */
data class OpenSubMenuAction(
    @StringRes val newParentId: Int,
) : MenuAction

/**
 * A generic action for [MenuItem.Switch] menu elements.
 */
internal interface ToggleMenuAction : MenuAction {

    @get:StringRes
    val itemId: Int

    /**
     * The new value of a [MenuItem.Switch] after the action is completed.
     */
    val newValue: Boolean

    /**
     * A helper function to make sure the action can be copied, changing only the [newValue] property.
     */
    fun clone(newValue: Boolean): ToggleMenuAction
}
