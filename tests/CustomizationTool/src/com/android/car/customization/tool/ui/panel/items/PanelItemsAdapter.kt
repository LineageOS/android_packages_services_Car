/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.car.customization.tool.ui.panel.items

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.Action
import com.android.car.customization.tool.domain.panel.PanelItem

/**
 * Adapter for the Panel RecyclerView.
 */
internal class PanelItemsAdapter(
    private val handleAction: (Action) -> Unit,
) : ListAdapter<PanelItem, PanelItemViewHolder>(ItemCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PanelItemViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (ViewType.from(viewType)) {
            ViewType.SECTION_TITLE -> {
                PanelSectionTitleItemViewHolder(
                    inflater.inflate(
                        R.layout.vh_panel_section_title,
                        parent,
                        /*attachToRoot=*/false
                    )
                )
            }

            ViewType.SWITCH -> {
                PanelSwitchViewHolder(
                    inflater.inflate(
                        R.layout.vh_panel_switch,
                        parent,
                        /*attachToRoot=*/false
                    )
                )
            }
        }
    }

    override fun onBindViewHolder(viewHolder: PanelItemViewHolder, position: Int) {
        viewHolder.bindTo(getItem(position), handleAction)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is PanelItem.SectionTitle -> ViewType.SECTION_TITLE.ordinal
            is PanelItem.Switch -> ViewType.SWITCH.ordinal
        }
    }

    /**
     * Defines which types of panel items are supported by this Adapter.
     */
    enum class ViewType {
        SECTION_TITLE, SWITCH;

        companion object {
            fun from(index: Int): ViewType {
                if (index >= values().size) {
                    throw IllegalArgumentException("Type not supported")
                }
                return values()[index]
            }
        }
    }

    class ItemCallback : DiffUtil.ItemCallback<PanelItem>() {

        override fun areItemsTheSame(p0: PanelItem, p1: PanelItem): Boolean {
            return p0::class == p1::class
        }

        override fun areContentsTheSame(p0: PanelItem, p1: PanelItem): Boolean {
            return p0 == p1
        }
    }
}
