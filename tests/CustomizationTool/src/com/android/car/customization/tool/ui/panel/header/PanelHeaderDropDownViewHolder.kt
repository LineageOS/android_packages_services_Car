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

package com.android.car.customization.tool.ui.panel.header

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.Action
import com.android.car.customization.tool.domain.panel.PanelHeaderItem

internal class PanelHeaderDropDownViewHolder(view: View) : PanelHeaderItemViewHolder(view) {

    private val dropDownText = itemView.requireViewById<TextView>(R.id.panel_header_dropdown_text)
    private val dropDown = itemView.requireViewById<Spinner>(R.id.panel_header_dropdown)

    override fun bindTo(model: PanelHeaderItem, handleAction: (Action) -> Unit) {
        model as PanelHeaderItem.DropDown

        dropDownText.text = model.text
        val adapter = ArrayAdapter(
            dropDown.context,
            R.layout.vh_panel_header_dropdown_item_selected,
            model.items.map { it.text }
        )
        adapter.setDropDownViewResource(R.layout.vh_panel_header_dropdown_item)
        dropDown.adapter = adapter
        dropDown.setSelection(model.items.indexOfFirst { it.isActive })

        dropDown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                handleAction(model.items[position].action)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }
}
