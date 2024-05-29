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

package com.android.car.pano.manager

import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.car.appcard.host.AppCardHost

class MainActivity : AppCompatActivity() {
  private lateinit var listView: RecyclerView
  private lateinit var appCardViewModel: AppCardViewModel
  private lateinit var adapter: AppCardViewAdapter
  private lateinit var host: AppCardHost

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    listView = requireViewById(R.id.selected_app_cards)
    requireViewById<ImageButton>(R.id.close_button).setOnClickListener {
      finish()
    }
    val snapHelper = LinearSnapHelper()
    snapHelper.attachToRecyclerView(listView)
    val owner = this
    appCardViewModel = ViewModelProvider(owner)[AppCardViewModel::class.java]
    host = AppCardHost(
      applicationContext,
      applicationContext.resources.getInteger(R.integer.app_card_update_rate_ms),
      applicationContext.resources.getInteger(R.integer.app_card_fast_update_rate_ms),
      ContextCompat.getMainExecutor(applicationContext)
    )
    appCardViewModel.setAppCardHost(host)
    adapter = AppCardViewAdapter(applicationContext)
    appCardViewModel.touchHelper = adapter
    appCardViewModel.allAppCards.observe(owner) {
      adapter.setAppCards(it, from = -1, to = -1)
    }
    val callback = AppCardTouchHelper(adapter, appCardViewModel)
    val touchHelper = ItemTouchHelper(callback)
    touchHelper.attachToRecyclerView(listView)
  }

  override fun onDestroy() {
    super.onDestroy()

    appCardViewModel.onDestroy()
  }

  override fun onStart() {
    super.onStart()

    appCardViewModel.refresh()
  }

  override fun onStop() {
    super.onStop()

    appCardViewModel.onStop()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    listView.adapter = adapter
    val context = this
    val reverseLayout = false
    listView.layoutManager = LinearLayoutManager(
      context,
      LinearLayoutManager.HORIZONTAL,
      reverseLayout
    )
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    listView.adapter = null
  }
}
