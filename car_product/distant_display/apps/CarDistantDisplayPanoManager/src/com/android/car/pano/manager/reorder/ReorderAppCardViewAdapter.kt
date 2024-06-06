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

package com.android.car.pano.manager.reorder

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.recyclerview.widget.RecyclerView
import com.android.car.appcard.ImageAppCard
import com.android.car.appcard.host.AppCardContainer
import com.android.car.pano.manager.AppCardServiceManager
import com.android.car.pano.manager.AppCardType
import com.android.car.pano.manager.R
import com.android.car.pano.manager.holder.ImageAppCardViewHolder
import com.android.car.pano.manager.holder.ProgressAppCardViewHolder
import java.util.Optional

class ReorderAppCardViewAdapter(
  private val context: Context,
  private val inflater: LayoutInflater,
  private val appCardServiceManager: AppCardServiceManager,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(),
  ReorderAppCardTouchHelper.AppCardTouchHelperContract {
  private val selectedBackground = context.resources.getDrawable(
    R.drawable.app_card_selected_bg,
    null
  )
  private val clearedBackground = context.resources.getDrawable(
    R.drawable.app_card_bg,
    null
  )
  private val appCards: MutableList<AppCardContainer> = mutableListOf()

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
        ImageAppCardViewHolder(context, view, selector = null)
      }

      AppCardType.PROGRESS -> {
        val view = inflater.inflate(
          R.layout.progress_buttons_app_card,
          parent,
          attachToRoot
        )
        ProgressAppCardViewHolder(context, view, selector = null)
      }
    }
  }

  override fun getItemViewType(position: Int): Int {
    val appCard = appCards[position].appCard as ImageAppCard
    appCard.image?.let {
      return AppCardType.IMAGE.type
    } ?: run {
      return AppCardType.PROGRESS.type
    }
  }

  override fun getItemCount(): Int = appCards.size

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    val type = AppCardType.fromInt(holder.itemViewType)
    when (type) {
      AppCardType.IMAGE -> {
        (holder as ImageAppCardViewHolder).bind(appCards[position], selected = false)
      }

      AppCardType.PROGRESS -> {
        (holder as ProgressAppCardViewHolder).bind(appCards[position], selected = false)
      }
    }
  }

  override fun setAppCards(
    newList: MutableList<AppCardContainer>,
    from: Optional<Int>,
    to: Optional<Int>
  ) {
    appCards.clear()
    newList.forEach {
      // Ignore [EmptyAppCard] for recyclerview
      if (it.appCard is ImageAppCard) {
        appCards.add(it)
      }
    }
    if (from.isEmpty && to.isEmpty) {
      notifyDataSetChanged()
    } else {
      notifyItemMoved(from.get(), to.get())

      val keyList = mutableListOf<String>()
      appCards.forEach { appCardContainer ->
        keyList.add("${appCardContainer.appId} : ${appCardContainer.appCard.id}")
      }
      appCardServiceManager.sendAppCardOrder(keyList)
    }
  }

  override fun onRowSelected(myViewHolder: RecyclerView.ViewHolder?) {
    myViewHolder?.itemView?.findViewById<RelativeLayout>(R.id.card)?.background = selectedBackground
  }

  override fun onRowClear(myViewHolder: RecyclerView.ViewHolder?) {
    myViewHolder?.itemView?.findViewById<RelativeLayout>(R.id.card)?.background = clearedBackground
  }

  companion object {
    private const val TAG = "ReorderAppCardViewAdapter"
  }
}
