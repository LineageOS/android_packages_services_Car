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

/**
 * The base for expanding functionality of the tool.
 *
 * Implementing this interface allows to define a function that will be executed when a specific
 * action is triggered.
 */
internal interface MenuActionReducer {

    /**
     * Updates the menu based on the action received.
     *
     * @param menu the current Menu.
     * @param action the action triggered.
     *
     * @return A new menu, updated with the effect of the [action].
     */
    fun reduce(menu: Menu, action: MenuAction): Menu
}
