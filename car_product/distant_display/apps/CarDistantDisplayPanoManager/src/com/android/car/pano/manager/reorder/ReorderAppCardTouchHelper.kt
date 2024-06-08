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

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.car.appcard.host.AppCardContainer
import java.util.Optional

class ReorderAppCardTouchHelper(
  private val touchHelper: AppCardTouchHelperContract,
  private val moveHelper: AppCardMoveHelperContract,
) : ItemTouchHelper.Callback() {
  private var prevTo = -1

  override fun getMovementFlags(list: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
    val dragFlag = ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    val swipeFlags = 0
    return makeMovementFlags(dragFlag, swipeFlags)
  }

  override fun onMove(
    list: RecyclerView,
    viewHolder: RecyclerView.ViewHolder,
    target: RecyclerView.ViewHolder
  ): Boolean {
    var from = viewHolder.bindingAdapterPosition
    val to = target.bindingAdapterPosition
    // When moving items from start to end, when in an interim state sometimes $from is -1, hence,
    // we should change $from to previous $to for correct behavior
    if (from < to && from == RecyclerView.NO_POSITION) {
      from = prevTo
    }
    if (from == to) {
      return false
    }
    moveHelper.onRowMoved(from, to)
    prevTo = to
    return true
  }

  override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
    moveHelper.stateChanged(actionState)
    if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
      touchHelper.onRowSelected(viewHolder)
    }
    super.onSelectedChanged(viewHolder, actionState)
  }

  override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
    super.clearView(recyclerView, viewHolder)
    touchHelper.onRowClear(viewHolder)
  }

  override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
    // no-op
  }

  override fun isLongPressDragEnabled(): Boolean {
    return true
  }

  override fun isItemViewSwipeEnabled(): Boolean {
    return false
  }

  interface AppCardMoveHelperContract {
    fun onRowMoved(from: Int, to: Int)
    fun stateChanged(actionState: Int)
  }

  interface AppCardTouchHelperContract {
    fun onRowSelected(myViewHolder: RecyclerView.ViewHolder?)
    fun onRowClear(myViewHolder: RecyclerView.ViewHolder?)
    fun setAppCards(newList: MutableList<AppCardContainer>, from: Optional<Int>, to: Optional<Int>)
  }
}
