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

package com.android.car.customization.tool.ui.panel.header

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.Action
import com.android.car.customization.tool.domain.panel.PanelHeaderItem

/**
 * Adapter for the Panel RecyclerView.
 */
internal class PanelHeaderAdapter(
    private val handleAction: (Action) -> Unit,
) : ListAdapter<PanelHeaderItem, PanelHeaderItemViewHolder>(ItemCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PanelHeaderItemViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (ViewType.from(viewType)) {
            ViewType.HEADER_CLOSE -> {
                PanelHeaderCloseViewHolder(
                    inflater.inflate(
                        R.layout.vh_panel_header_close,
                        parent,
                        /*attachToRoot=*/false
                    )
                )
            }

            ViewType.HEADER_SEARCH -> {
                PanelHeaderSearchBoxViewHolder(
                    inflater.inflate(
                        R.layout.vh_panel_header_search,
                        parent,
                        /*attachToRoot=*/false
                    )
                )
            }

            ViewType.HEADER_DROPDOWN -> PanelHeaderDropDownViewHolder(
                inflater.inflate(
                    R.layout.vh_panel_header_dropdown,
                    parent,
                    /*attachToRoot=*/false
                )
            )
        }
    }

    override fun onBindViewHolder(viewHolder: PanelHeaderItemViewHolder, position: Int) {
        viewHolder.bindTo(getItem(position), handleAction)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is PanelHeaderItem.SearchBox -> ViewType.HEADER_SEARCH.ordinal
            is PanelHeaderItem.CloseButton -> ViewType.HEADER_CLOSE.ordinal
            is PanelHeaderItem.DropDown -> ViewType.HEADER_DROPDOWN.ordinal
        }
    }

    /**
     * Defines which types of panel items are supported by this Adapter.
     */
    enum class ViewType {
        HEADER_SEARCH, HEADER_CLOSE, HEADER_DROPDOWN;

        companion object {
            fun from(index: Int): ViewType {
                if (index >= values().size) {
                    throw IllegalArgumentException("Type not supported")
                }
                return values()[index]
            }
        }
    }

    class ItemCallback : DiffUtil.ItemCallback<PanelHeaderItem>() {

        override fun areItemsTheSame(p0: PanelHeaderItem, p1: PanelHeaderItem): Boolean {
            return p0::class == p1::class
        }

        override fun areContentsTheSame(p0: PanelHeaderItem, p1: PanelHeaderItem): Boolean {
            return p0 == p1
        }
    }
}
