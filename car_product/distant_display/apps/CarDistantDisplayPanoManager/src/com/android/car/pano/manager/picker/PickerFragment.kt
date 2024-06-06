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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.car.pano.manager.AppCardServiceManager
import com.android.car.pano.manager.AppCardViewModel
import com.android.car.pano.manager.R
import com.android.car.pano.manager.reorder.ReorderFragment

class PickerFragment : Fragment(R.layout.picker_fragment) {
  private lateinit var inflater: LayoutInflater
  private lateinit var listView: RecyclerView
  private lateinit var adapter: PickerAppCardViewAdapter
  private val appCardViewModel: AppCardViewModel by activityViewModels()
  private val appCardServiceManager: AppCardServiceManager by activityViewModels()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    view.requireViewById<Button>(R.id.done_button).setOnClickListener {
      activity?.supportFragmentManager?.commit {
        setReorderingAllowed(true)
        val args = null
        add(R.id.fragment_container_view, ReorderFragment::class.java, args)
      }
    }

    listView = view.requireViewById(R.id.all_app_cards)
    inflater = LayoutInflater.from(activity)
    adapter = PickerAppCardViewAdapter(context!!, inflater, appCardServiceManager, appCardViewModel)
    appCardViewModel.touchHelper = null

    val owner = this
    appCardViewModel.unselectedAppCards.observe(owner) {
      appCardViewModel.selectedAppCards.value?.let { selected ->
        adapter.setAppCards(selected, it)
      }
    }
    appCardViewModel.selectedAppCards.observe(owner) {
      appCardViewModel.unselectedAppCards.value?.let { unselected ->
        adapter.setAppCards(it, unselected)
      }
    }

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

    appCardViewModel.refreshAll()
  }

  override fun onStop() {
    super.onStop()

    appCardViewModel.removeUnselectedAppCards()
  }

  companion object {
    private const val TAG = "PickerFragment"
  }
}
