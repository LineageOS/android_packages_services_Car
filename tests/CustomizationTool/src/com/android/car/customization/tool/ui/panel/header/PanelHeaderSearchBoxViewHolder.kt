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
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.Action
import com.android.car.customization.tool.domain.panel.PanelHeaderItem

internal class PanelHeaderSearchBoxViewHolder(view: View) : PanelHeaderItemViewHolder(view) {

    private val searchEditText = itemView.requireViewById<EditText>(R.id.panel_header_search)
    private var runnable: Runnable = Runnable { }

    override fun bindTo(model: PanelHeaderItem, handleAction: (Action) -> Unit) {
        model as PanelHeaderItem.SearchBox
        searchEditText.hint = model.searchHint
        searchEditText.doAfterTextChanged { text ->
            // Adding a manual "debounce" mechanism until Flow is added to the project
            searchEditText.handler.removeCallbacks(runnable)
            runnable = Runnable { handleAction(model.searchAction(text.toString())) }
            searchEditText.handler.postDelayed(runnable, 300L)
        }
    }
}
