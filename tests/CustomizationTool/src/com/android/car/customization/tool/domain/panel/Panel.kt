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
 * The model of a Panel.
 *
 * @property items the list of [PanelItem] that are shown in a panel.
 */
internal data class Panel(
    val headerItems: List<PanelHeaderItem> = listOf(PanelHeaderItem.CloseButton),
    val items: List<PanelItem>
)

internal sealed class PanelHeaderItem {

    data class SearchBox(
        val searchHint: String,
        val searchAction: (String) -> PanelAction,
    ) : PanelHeaderItem()

    object CloseButton : PanelHeaderItem() {
        val action: PanelAction = ClosePanelAction
    }

    data class DropDown(
        val text: String,
        val items: List<Item>
    ) : PanelHeaderItem() {
        data class Item(
            val text: String,
            val action: PanelAction,
            val isActive: Boolean
        )

        fun selectDropDownItem(action: PanelAction): DropDown = copy(
            items = items.map { item -> item.copy(isActive = item.action == action) }
        )
    }
}

/**
 * The base for all items that can be shown in a Panel.
 *
 * @property isEnabled defines if an item is enabled, users can't interact with disabled items.
 */
internal sealed class PanelItem(open val isEnabled: Boolean) {

    data class SectionTitle(
        val text: String,
    ) : PanelItem(isEnabled = true)

    data class Switch(
        val text: String,
        val errorText: String?,
        val isChecked: Boolean,
        override val isEnabled: Boolean,
        val action: PanelAction,
    ) : PanelItem(isEnabled)
}
