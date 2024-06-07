/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.car.pano.manager.picker

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.android.car.appcard.ImageAppCard
import com.android.car.appcard.host.AppCardContainer
import com.android.car.pano.manager.AppCardServiceManager
import com.android.car.pano.manager.AppCardType
import com.android.car.pano.manager.R
import com.android.car.pano.manager.holder.ImageAppCardViewHolder
import com.android.car.pano.manager.holder.ProgressAppCardViewHolder

class PickerAppCardViewAdapter(
  private val context: Context,
  private val inflater: LayoutInflater,
  private val appCardServiceManager: AppCardServiceManager,
  private val selector: Selector,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  private val selectedAppCards: MutableList<AppCardContainer> = mutableListOf()
  private val unselectedAppCards: MutableList<AppCardContainer> = mutableListOf()

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    val type = AppCardType.fromInt(viewType)
    val attachToRoot = false
    return when (type) {
      AppCardType.IMAGE -> {
        val view = inflater.inflate(
          R.layout.image_app_card,
          parent,
          attachToRoot
        )
        ImageAppCardViewHolder(context, view, selector)
      }

      AppCardType.PROGRESS -> {
        val view = inflater.inflate(
          R.layout.progress_buttons_app_card,
          parent,
          attachToRoot
        )
        ProgressAppCardViewHolder(context, view, selector)
      }
    }
  }

  override fun getItemViewType(position: Int): Int {
    val appCard = if (position >= selectedAppCards.size) {
      unselectedAppCards[position - selectedAppCards.size].appCard as ImageAppCard
    } else {
      selectedAppCards[position].appCard as ImageAppCard
    }

    appCard.image?.let {
      return AppCardType.IMAGE.type
    } ?: run {
      return AppCardType.PROGRESS.type
    }
  }

  override fun getItemCount(): Int = unselectedAppCards.size + selectedAppCards.size

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    val type = AppCardType.fromInt(holder.itemViewType)
    var selected = false
    val card = if (position >= selectedAppCards.size) {
      unselectedAppCards[position - selectedAppCards.size]
    } else {
      selected = true
      selectedAppCards[position]
    }

    when (type) {
      AppCardType.IMAGE -> {
        (holder as ImageAppCardViewHolder).bind(card, selected)
      }

      AppCardType.PROGRESS -> {
        (holder as ProgressAppCardViewHolder).bind(card, selected)
      }
    }
  }

  fun setAppCards(
    newSelected: MutableList<AppCardContainer>,
    newUnselected: MutableList<AppCardContainer>,
  ) {
    selectedAppCards.clear()
    unselectedAppCards.clear()
    newSelected.forEach {
      // Ignore [EmptyAppCard] for recyclerview
      if (it.appCard is ImageAppCard) {
        selectedAppCards.add(it)
      }
    }
    newUnselected.forEach {
      // Ignore [EmptyAppCard] for recyclerview
      if (it.appCard is ImageAppCard) {
        unselectedAppCards.add(it)
      }
    }

    notifyDataSetChanged()

    val keyList = mutableListOf<String>()
    selectedAppCards.forEach { appCardContainer ->
      keyList.add("${appCardContainer.appId} : ${appCardContainer.appCard.id}")
    }
    appCardServiceManager.sendAppCardOrder(keyList)
  }

  interface Selector {
    fun shouldToggleSelect(appCardContainer: AppCardContainer): Boolean
  }

  companion object {
    private const val TAG = "PickerAppCardViewAdapter"
  }
}
