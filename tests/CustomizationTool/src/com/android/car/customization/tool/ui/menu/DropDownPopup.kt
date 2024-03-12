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

import android.content.res.Resources
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.Action
import com.android.car.customization.tool.domain.menu.MenuItem

/**
 * Opens a [PopupWindow] with a list of [MenuItem.DropDown.Item].
 */
internal fun showDropDownPopupWindow(
    itemView: View,
    items: List<MenuItem.DropDown.Item>,
    handleAction: (Action) -> Unit,
) {
    val dropDownPopup = LayoutInflater.from(itemView.context).inflate(
        R.layout.vh_menu_dropdown_popup,
        /*root=*/null
    )

    val popupWindow = PopupWindow(
        /*contentView=*/ dropDownPopup,
        /*width=*/ ViewGroup.LayoutParams.WRAP_CONTENT,
        /*height=*/ ViewGroup.LayoutParams.WRAP_CONTENT,
        /*focusable=*/ true
    )

    dropDownPopup.requireViewById<RecyclerView>(R.id.dropdown_popup_list).run {
        layoutManager = LinearLayoutManager(itemView.context)
        adapter = DropDownAdapter(items, handleAction, popupWindow)
        setHasFixedSize(true)
    }

    // Calculate the coordinates based on the button's location
    val location = IntArray(2)
    itemView.getLocationInWindow(location)
    val margin = (5 / Resources.getSystem().displayMetrics.density).toInt()
    val x = location[0]
    val y = location[1] + itemView.height + margin

    popupWindow.showAtLocation(itemView, Gravity.NO_GRAVITY, x, y)
}

/**
 * Adapter for the item list inside a [MenuItem.DropDown].
 */
private class DropDownAdapter(
    private val items: List<MenuItem.DropDown.Item>,
    private val handleAction: (Action) -> Unit,
    private val popupWindow: PopupWindow,
) : RecyclerView.Adapter<DropDownAdapter.ViewHolder>() {
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val titleTextView = itemView.requireViewById<TextView>(R.id.dropdown_title)

        fun bindTo(
            model: MenuItem.DropDown.Item,
            handleAction: (Action) -> Unit,
            popupWindow: PopupWindow,
        ) {
            titleTextView.text = model.title
            itemView.isSelected = model.isActive

            if (!model.isActive) {
                itemView.isSelected = false
                itemView.setOnClickListener {
                    handleAction(model.action)
                    popupWindow.dismiss()
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(
            R.layout.vh_menu_dropdown_popup_item,
            parent,
            /*attachToRoot=*/false
        )
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bindTo(items[position], handleAction, popupWindow)
    }

    override fun getItemCount() = items.size
}
