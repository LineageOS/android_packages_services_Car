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

package com.android.car.pano.manager.holder

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.RecyclerView
import com.android.car.appcard.ImageAppCard
import com.android.car.appcard.component.Image
import com.android.car.appcard.host.AppCardContainer
import com.android.car.pano.manager.R
import com.android.car.pano.manager.picker.PickerAppCardViewAdapter

class ImageAppCardViewHolder(
  context: Context,
  private val view: View,
  selector: PickerAppCardViewAdapter.Selector?,
) : RecyclerView.ViewHolder(view) {
  private val packageManager = context.packageManager
  private val headerTextView = view.requireViewById<TextView>(R.id.header_text)
  private val headerIconView = view.requireViewById<ImageView>(R.id.header_icon)
  private val imageView = view.requireViewById<ImageView>(R.id.image)
  private val primaryTextView = view.requireViewById<TextView>(R.id.primary_text)
  private val secondaryTextView = view.requireViewById<TextView>(R.id.secondary_text)
  private val imageTintColor = context.resources.getColor(R.color.app_card_image_tint, null)
  private var isSelected = false
  private val selectedBackground = context.resources.getDrawable(
    R.drawable.app_card_selected_bg,
    null
  )
  private val clearedBackground = context.resources.getDrawable(
    R.drawable.app_card_bg,
    null
  )
  private lateinit var appCardContainer: AppCardContainer

  init {
    selector?.let {
      view.setOnClickListener { _ ->
        if (it.shouldToggleSelect(appCardContainer)) {
          isSelected = !isSelected
          updateSelectedBackground()
        }
      }
    }
  }

  fun bind(container: AppCardContainer, selected: Boolean) {
    isSelected = selected
    appCardContainer = container
    val appCard = appCardContainer.appCard as ImageAppCard

    updateSelectedBackground()

    headerTextView.apply {
      appCard.header?.title?.let {
        text = it
        visibility = View.VISIBLE
      } ?: run {
        visibility = View.INVISIBLE
      }
    }

    headerIconView.apply {
      appCard.header?.logo?.let {
        setImageBitmap(it.imageData)
        colorFilter = if (it.colorFilter == Image.ColorFilter.TINT) {
          PorterDuffColorFilter(imageTintColor, PorterDuff.Mode.SRC_IN)
        } else {
          null
        }
      } ?: packageManager?.getApplicationIcon(appCardContainer.appId.packageName)?.toBitmap()?.let {
        setImageBitmap(it)
      }
    }

    imageView.apply {
      appCard.image?.let {
        setImageBitmap(it.imageData)
        colorFilter = if (it.colorFilter == Image.ColorFilter.TINT) {
          PorterDuffColorFilter(imageTintColor, PorterDuff.Mode.SRC_IN)
        } else {
          null
        }
      }
    }

    primaryTextView.apply {
      appCard.primaryText?.let {
        text = it
        visibility = View.VISIBLE
      } ?: run {
        visibility = View.INVISIBLE
      }
    }

    secondaryTextView.apply {
      appCard.secondaryText?.let {
        text = it
        visibility = View.VISIBLE
      } ?: run {
        visibility = View.INVISIBLE
      }
    }
  }

  private fun updateSelectedBackground() {
    view.findViewById<RelativeLayout>(R.id.card)?.background =
      if (isSelected) selectedBackground else clearedBackground
  }

  companion object {
    private const val TAG = "ImageAppCardViewHolder"
  }
}
