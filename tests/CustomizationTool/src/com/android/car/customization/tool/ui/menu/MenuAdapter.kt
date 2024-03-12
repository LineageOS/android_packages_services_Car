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

package com.android.car.customization.tool.ui.menu

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.Action
import com.android.car.customization.tool.domain.menu.MenuItem

/**
 * Adapter for the Menu RecyclerView
 */
internal class MenuAdapter(
    private val handleAction: (Action) -> Unit,
) : ListAdapter<MenuItem, MenuItemViewHolder>(ItemCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuItemViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (ViewType.from(viewType)) {
            ViewType.SUBMENU_NAVIGATION -> {
                SubMenuNavigationItemViewHolder(
                    inflater.inflate(
                        R.layout.vh_menu_navigation,
                        parent,
                        /*attachToRoot=*/false
                    )
                )
            }

            ViewType.UP_NAVIGATION -> {
                UpNavigationViewHolder(
                    inflater.inflate(
                        R.layout.vh_menu_up_navigation,
                        parent,
                        /*attachToRoot=*/false
                    )
                )
            }

            ViewType.SWITCH -> {
                SwitchItemViewHolder(
                    inflater.inflate(
                        R.layout.vh_menu_switch,
                        parent,
                        /*attachToRoot=*/false
                    )
                )
            }

            ViewType.PANEL_LAUNCHER -> {
                PanelLauncherItemViewHolder(
                    inflater.inflate(
                        R.layout.vh_menu_panel_launcher,
                        parent,
                        /*attachToRoot=*/false
                    )
                )
            }

            ViewType.DROPDOWN -> DropDownItemViewHolder(
                inflater.inflate(
                    R.layout.vh_menu_dropdown,
                    parent,
                    /*attachToRoot=*/false
                )
            )
        }
    }

    override fun onBindViewHolder(viewHolder: MenuItemViewHolder, position: Int) {
        viewHolder.bindTo(getItem(position), handleAction)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is MenuItem.SubMenuNavigation -> ViewType.SUBMENU_NAVIGATION.ordinal
            is MenuItem.UpNavigation -> ViewType.UP_NAVIGATION.ordinal
            is MenuItem.Switch -> ViewType.SWITCH.ordinal
            is MenuItem.PanelLauncher -> ViewType.PANEL_LAUNCHER.ordinal
            is MenuItem.DropDown -> ViewType.DROPDOWN.ordinal
        }
    }

    /**
     * Defines which types of [MenuItem] are supported in this Adapter
     */
    enum class ViewType {
        SUBMENU_NAVIGATION, UP_NAVIGATION, SWITCH, PANEL_LAUNCHER, DROPDOWN;

        companion object {
            fun from(index: Int): ViewType {
                if (index >= values().size) {
                    throw IllegalArgumentException("Type not supported")
                }
                return values()[index]
            }
        }
    }

    class ItemCallback : DiffUtil.ItemCallback<MenuItem>() {

        override fun areItemsTheSame(p0: MenuItem, p1: MenuItem): Boolean {
            return p0.displayTextRes == p1.displayTextRes
        }

        override fun areContentsTheSame(p0: MenuItem, p1: MenuItem): Boolean {
            return p0 == p1
        }
    }
}
