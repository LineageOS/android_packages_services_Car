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

/**
 * The business logic of a [Panel].
 *
 * It can be seen as a small and specialized state machine that is responsible for creating a
 * specific Panel and process the relevant actions for it.
 */
internal interface PanelActionReducer {

    /**
     * Extra information that can be be passed by the [OpenPanelAction] action.
     */
    var bundle: Map<String, Any>

    /**
     * Creates a brand new instance of a [Panel].
     *
     * The function is also used by [ReloadPanelAction] to create a new instance with the updated
     * values from the system.
     */
    fun build(): Panel

    /**
     * Process a [PanelAction] and returns an updated [Panel].
     */
    fun reduce(panel: Panel, action: PanelAction): Panel
}
