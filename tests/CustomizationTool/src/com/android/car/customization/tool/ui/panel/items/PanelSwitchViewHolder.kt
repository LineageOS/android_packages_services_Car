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

package com.android.car.customization.tool.ui.panel.items

import android.view.View
import android.widget.Switch
import android.widget.TextView
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.Action
import com.android.car.customization.tool.domain.panel.PanelItem

internal class PanelSwitchViewHolder(view: View) : PanelItemViewHolder(view) {

    private val textView = itemView.requireViewById<TextView>(R.id.panel_switch_text)
    private val errorTextView = itemView.requireViewById<TextView>(R.id.panel_switch_error)
    private val switch = itemView.requireViewById<Switch>(R.id.panel_switch_toggle)

    override fun bindTo(model: PanelItem, handleAction: (Action) -> Unit) {
        model as PanelItem.Switch

        textView.text = model.text
        if (model.errorText.isNullOrEmpty()) {
            errorTextView.visibility = View.GONE
        } else {
            errorTextView.text = model.errorText
            errorTextView.visibility = View.VISIBLE
        }
        switch.isChecked = model.isChecked
        switch.isEnabled = model.isEnabled
        switch.setOnClickListener {
            handleAction(model.action)
        }
    }
}
