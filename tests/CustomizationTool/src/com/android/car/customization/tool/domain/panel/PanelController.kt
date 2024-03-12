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

package com.android.car.customization.tool.domain.panel

import javax.inject.Inject
import javax.inject.Provider

/**
 * The Controller for all the possible panels in the tool.
 *
 * It receives [PanelAction] from the main state machine and then processes it or redirects
 * it to a specific [PanelActionReducer].
 *
 * @property panelActionReducers a map of all of the [PanelActionReducer] available in the system.
 */
internal class PanelController @Inject constructor(
    private val panelActionReducers: Map<
        Class<out PanelActionReducer>,
        @JvmSuppressWildcards Provider<PanelActionReducer>
        >,
) {

    private var currentPanelActionReducer: PanelActionReducer? = null

    fun handleAction(panel: Panel?, action: PanelAction): Panel? = when (action) {
        is OpenPanelAction -> {
            currentPanelActionReducer = panelActionReducers[action.panelClass.java]?.get()
            currentPanelActionReducer?.bundle = action.bundle
            currentPanelActionReducer?.build()
        }

        ClosePanelAction -> {
            currentPanelActionReducer = null
            null
        }

        ReloadPanelAction -> {
            currentPanelActionReducer?.build()
        }

        else -> {
            // Here there has to be a valid panel and a valid panelReducer, otherwise it should crash.
            requireNotNull(panel)
            currentPanelActionReducer?.reduce(panel, action)
        }
    }
}
