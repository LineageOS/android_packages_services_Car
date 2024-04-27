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

/**
 * The base of all of the actions available in the tool.
 *
 * As a naming convention, all actions should include a verb in the name and end with
 * the "Action" suffix.
 */
internal interface Action

/**
 * Toggles the UI state of the tool from closed to open and vice-versa.
 *
 * When the tool is "closed" only the entry point button is visible to the user.
 */
internal object ToggleUiAction : Action

/**
 * The action that is triggered when a refresh of the State is necessary.
 *
 * Since there are no reactive listeners on system properties available (like RRO state)
 * this actions polls an update from the system.
 */
internal object ReloadStateAction : Action
