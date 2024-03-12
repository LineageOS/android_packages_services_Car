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

import com.android.car.customization.tool.domain.Action
import kotlin.reflect.KClass

/**
 * The base for all actions that trigger change in a panel.
 */
internal interface PanelAction : Action

/**
 * This action triggers an update of the whole [Panel].
 *
 * It's used to keep the [Panel] up to date with the system.
 */
object ReloadPanelAction : PanelAction

/**
 * The action responsible for closing a [Panel].
 */
internal object ClosePanelAction : PanelAction

/**
 * The action responsible for opening a [Panel].
 *
 * @property panelClass the class of the [Panel] that needs to be opened.
 * @property bundle a map where extra information can be specified.
 */
internal data class OpenPanelAction(
    val panelClass: KClass<out PanelActionReducer>,
    val bundle: Map<String, Any> = emptyMap(),
) : PanelAction
