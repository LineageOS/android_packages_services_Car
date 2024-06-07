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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.car.pano.manager.AppCardServiceManager
import com.android.car.pano.manager.AppCardViewModel
import com.android.car.pano.manager.R
import com.android.car.pano.manager.picker.PickerFragment
import java.util.Optional

class ReorderFragment : Fragment(R.layout.reorder_fragment) {
  private lateinit var inflater: LayoutInflater
  private lateinit var listView: RecyclerView
  private lateinit var adapter: ReorderAppCardViewAdapter
  private val appCardViewModel: AppCardViewModel by activityViewModels()
  private val appCardServiceManager: AppCardServiceManager by activityViewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    inflater = LayoutInflater.from(context)
    context?.let { adapter = ReorderAppCardViewAdapter(it, inflater, appCardServiceManager) }
    appCardViewModel.touchHelper = adapter
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    listView = view.requireViewById(R.id.selected_app_cards)
    view.requireViewById<ImageButton>(R.id.close_button).setOnClickListener {
      activity?.finish()
    }
    view.requireViewById<Button>(R.id.add_replace_button).setOnClickListener {
      activity?.supportFragmentManager?.commit {
        setReorderingAllowed(true)
        val args = null
        add(R.id.fragment_container_view, PickerFragment::class.java, args)
      }
    }

    val snapHelper = LinearSnapHelper()
    snapHelper.attachToRecyclerView(listView)

    val owner = this
    appCardViewModel.selectedAppCards.observe(owner) {
      adapter.setAppCards(it, from = Optional.empty(), to = Optional.empty())
    }

    val callback = ReorderAppCardTouchHelper(adapter, appCardViewModel)
    val touchHelper = ItemTouchHelper(callback)
    touchHelper.attachToRecyclerView(listView)

    listView.adapter = adapter
    val reverseLayout = false
    listView.layoutManager = LinearLayoutManager(
      context,
      LinearLayoutManager.HORIZONTAL,
      reverseLayout
    )
  }

  override fun onStart() {
    super.onStart()

    appCardViewModel.refreshSelected()
  }

  companion object {
    private const val TAG = "PickerFragment"
  }
}
