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

import android.view.View
import android.widget.ImageButton
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.Action
import com.android.car.customization.tool.domain.menu.MenuItem

internal class UpNavigationViewHolder(view: View) : MenuItemViewHolder(view) {

    private val navigationUpButton = itemView.requireViewById<ImageButton>(R.id.menu_up_navigation)

    override fun bindTo(model: MenuItem, handleAction: (Action) -> Unit) {
        model as MenuItem.UpNavigation

        navigationUpButton.setOnClickListener {
            handleAction(model.action)
        }
    }
}
