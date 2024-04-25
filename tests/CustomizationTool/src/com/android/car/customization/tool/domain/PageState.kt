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

import com.android.car.customization.tool.domain.menu.Menu
import com.android.car.customization.tool.domain.panel.Panel

/**
 * The state of the tool.
 *
 * @param isOpen if the tool is opened or closed
 * @param menu the current [Menu]
 * @param panel the current [Panel] if present
 */
internal data class PageState(
    val isOpen: Boolean,
    val menu: Menu,
    val panel: Panel?,
)
