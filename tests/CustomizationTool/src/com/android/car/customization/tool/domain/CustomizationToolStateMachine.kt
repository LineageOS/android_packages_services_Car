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

import com.android.car.customization.tool.domain.menu.MenuAction
import com.android.car.customization.tool.domain.menu.MenuController
import com.android.car.customization.tool.domain.menu.ReloadMenuAction
import com.android.car.customization.tool.domain.panel.PanelAction
import com.android.car.customization.tool.domain.panel.PanelController
import com.android.car.customization.tool.domain.panel.ReloadPanelAction
import javax.inject.Inject

/**
 * Main StateMachine, accepts [Action] and creates new [PageState].
 *
 * This state machine is directly responsible for actions that change the state of the whole tool
 * (like [Action.ToggleToolAction]) and redirects all of the other actions to the [MenuController]
 * and the [PanelController].
 *
 * @property menuController the [MenuController].
 * @property panelController the [PanelController].
 */
internal class CustomizationToolStateMachine @Inject constructor(
    private val menuController: MenuController,
    private val panelController: PanelController,
) : StateMachine<PageState, Action>(allowDuplicates = false) {

    override var currentState: PageState = PageState(
        isOpen = false,
        menu = menuController.buildMenu(),
        panel = null
    )

    override fun reducer(state: PageState, action: Action): PageState = when (action) {
        ToggleUiAction -> state.copy(isOpen = !state.isOpen)

        is ReloadStateAction -> {
            state.copy(
                menu = menuController.handleAction(state.menu, ReloadMenuAction),
                panel = panelController.handleAction(state.panel, ReloadPanelAction)
            )
        }

        is MenuAction -> {
            state.copy(
                menu = menuController.handleAction(state.menu, action)
            )
        }

        is PanelAction -> {
            state.copy(
                panel = panelController.handleAction(state.panel, action)
            )
        }

        else -> throw IllegalArgumentException("Action $action has not been implemented")
    }
}
