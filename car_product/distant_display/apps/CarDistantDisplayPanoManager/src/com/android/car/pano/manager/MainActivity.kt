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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.android.car.appcard.AppCardContext
import com.android.car.pano.manager.reorder.ReorderFragment

class MainActivity : AppCompatActivity() {
  private val appCardViewModel: AppCardViewModel by viewModels()
  private val appCardServiceManager: AppCardServiceManager by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    appCardServiceManager.registerContextListener(object :
      AppCardServiceManager.AppCardContextListener {
      override fun update(appCardContext: AppCardContext) {
        appCardViewModel.setAppCardContext(appCardContext)
      }
    })
    appCardServiceManager.registerOrderListener(object :
      AppCardServiceManager.AppCardOrderListener {
      override fun update(order: List<String>) {
        appCardViewModel.setInitialList(order)
      }
    })

    supportFragmentManager.commit {
      setReorderingAllowed(true)
      add(R.id.fragment_container_view, ReorderFragment::class.java, savedInstanceState)
    }
  }

  override fun onStart() {
    super.onStart()

    appCardViewModel.refreshAll()
  }

  companion object {
    private const val TAG = "MainActivity"
  }
}
