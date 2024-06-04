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

import com.android.car.appcard.AppCard
import com.android.car.appcard.component.Component

/** Empty [AppCard] that is only used as a placeholder for initial list of app cards */
class EmptyAppCard(id: String) : AppCard(id) {
  override fun toByteArray() = ByteArray(0)

  override fun updateComponent(component: Component) {
    // no-op
  }

  override fun verifyUniquenessOfComponentIds() = false
}
