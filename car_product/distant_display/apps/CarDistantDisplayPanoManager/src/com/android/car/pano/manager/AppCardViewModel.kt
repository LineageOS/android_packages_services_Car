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

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.android.car.appcard.AppCard
import com.android.car.appcard.AppCardContentProvider
import com.android.car.appcard.AppCardContext
import com.android.car.appcard.host.AppCardComponentContainer
import com.android.car.appcard.host.AppCardContainer
import com.android.car.appcard.host.AppCardHost
import com.android.car.appcard.host.AppCardListener
import com.android.car.appcard.host.ApplicationIdentifier
import com.android.car.pano.manager.picker.PickerAppCardViewAdapter
import com.android.car.pano.manager.reorder.ReorderAppCardTouchHelper
import com.android.internal.widget.helper.ItemTouchHelper
import java.util.Collections
import java.util.Optional

/** An [AndroidViewModel] that registers itself as an [AppCardListener] */
class AppCardViewModel(application: Application) : AndroidViewModel(application), AppCardListener,
  ReorderAppCardTouchHelper.AppCardMoveHelperContract, PickerAppCardViewAdapter.Selector {
  private var appCardHost: AppCardHost
  private var appCardContext: AppCardContext? = null
  var touchHelper: ReorderAppCardTouchHelper.AppCardTouchHelperContract? = null
  private var inMoveState = false

  val selectedAppCards = MutableLiveData<MutableList<AppCardContainer>>().apply {
    value = mutableListOf()
  }
  val unselectedAppCards = MutableLiveData<MutableList<AppCardContainer>>().apply {
    value = mutableListOf()
  }
  private val limitToast =
    Toast.makeText(application, MAX_TOAST_TEXT, Toast.LENGTH_SHORT)

  init {
    appCardHost = AppCardHost(
      application,
      application.resources.getInteger(R.integer.app_card_update_rate_ms),
      application.resources.getInteger(R.integer.app_card_fast_update_rate_ms),
      ContextCompat.getMainExecutor(application)
    )

    // Register view model as [AppCardListener] so that changes to app cards can
    // propagate to all views that use this view model
    appCardHost.registerListener(listener = this)
  }

  fun setInitialList(initialOrder: List<String>) {
    synchronized(lock = selectedAppCards) {
      initialOrder.forEach { key ->
        // Add [EmptyAppCard] for initial list so that they are updated in place once actual app
        // card is received
        getAppCardIdentifier(key)?.let {
          val emptyAppCard = EmptyAppCard(it.second)
          selectedAppCards.value?.add(AppCardContainer(it.first, emptyAppCard))
        }
      }
      appCardContext?.let {
        refreshSelected()
      }
    }
  }

  fun setAppCardContext(newAppCardContext: AppCardContext) {
    synchronized(lock = selectedAppCards) {
      appCardContext = newAppCardContext
      refreshSelected()
    }
  }

  override fun onCleared() {
    super.onCleared()

    removeSelectedAppCards()
    removeUnselectedAppCards()
    appCardHost.destroy()
  }

  /**
   * A new [AppCardContainer] has been received, either update an existing view
   * or create a new view representing the [AppCardContainer]
   */
  override fun onAppCardReceived(appCard: AppCardContainer) {
    synchronized(lock = selectedAppCards) {
      Log.d(TAG, "onAppCardReceived: ${appCard.appCard.id}")
      if (!onAppCardReceivedInternal(selectedAppCards, appCard, shouldAdd = false)) {
        onAppCardReceivedInternal(unselectedAppCards, appCard, shouldAdd = true)
      }
    }
  }

  private fun onAppCardReceivedInternal(
    list: MutableLiveData<MutableList<AppCardContainer>>,
    appCard: AppCardContainer,
    shouldAdd: Boolean,
  ): Boolean {
    var updated = false
    list.value?.forEach {
      if (getKey(it) == getKey(appCard)) {
        it.appCard = appCard.appCard
        updated = true
      }
    }
    if (!updated && shouldAdd) {
      list.value?.add(appCard)
    }

    if (!inMoveState && (updated || shouldAdd)) {
      list.value = list.value
    }

    return updated || shouldAdd
  }

  private fun getKey(appCard: AppCardContainer) = "${appCard.appId} : ${appCard.appCard.id}"

  private fun getKey(component: AppCardComponentContainer) =
    "${component.appId} : ${component.appCardId}"

  private fun getAppCardIdentifier(key: String): Pair<ApplicationIdentifier, String>? {
    val split = key.split(KEY_DELIMITER)
    if (split.size != 3) {
      return null
    }
    return Pair(ApplicationIdentifier(split[1], split[0]), split[2])
  }

  /** A new [AppCardComponentContainer] has been received, update an existing view */
  override fun onComponentReceived(component: AppCardComponentContainer) {
    synchronized(lock = selectedAppCards) {
      if (!onComponentReceivedInternal(selectedAppCards, component)) {
        onComponentReceivedInternal(unselectedAppCards, component)
      }
    }
  }

  private fun onComponentReceivedInternal(
    list: MutableLiveData<MutableList<AppCardContainer>>,
    component: AppCardComponentContainer,
  ): Boolean {
    var updated = false
    list.value?.forEach {
      if (getKey(it) == getKey(component)) {
        updated = true
        it.appCard.updateComponent(component.component)
      }
    }

    if (!inMoveState && updated) {
      list.value = list.value
    }

    return updated
  }

  /** A package has been removed from the system, remove all associated views */
  override fun onProviderRemoved(packageName: String, authority: String?) {
    synchronized(lock = selectedAppCards) {
      onProviderRemovedInternal(selectedAppCards, packageName, authority)
      onProviderRemovedInternal(unselectedAppCards, packageName, authority)
    }
  }

  private fun onProviderRemovedInternal(
    list: MutableLiveData<MutableList<AppCardContainer>>,
    packageName: String,
    authority: String?,
  ) {
    var removeIndex: Int? = null
    list.value?.forEachIndexed { index, it ->
      val identifier = it.appId
      val isSamePackage = identifier.containsPackage(packageName)

      var isSameAuthority = true
      authority?.let {
        isSameAuthority = identifier.containsAuthority(authority)
      }

      if (isSamePackage && isSameAuthority) {
        removeIndex = index
        return@forEachIndexed
      }
    }
    removeIndex?.let {
      list.value?.removeAt(it)
      if (!inMoveState) {
        list.value = list.value
      }
    }
  }

  /** A package has been added tp the system, refresh [AppCard]s if needed */
  override fun onProviderAdded(packageName: String, authority: String?) {
    Log.d(TAG, "onPackageAdded: $packageName")
    refreshAll()
  }

  /**
   * An error has occurred when communicating with an [AppCardContentProvider]
   * Handle error
   */
  override fun onPackageCommunicationError(
    identifier: ApplicationIdentifier,
    throwable: Throwable,
  ) {
    Log.d(TAG, "onPackageCommunicationError: $identifier $throwable")
  }

  /**
   * Ask [AppCardHost] to connect to new [AppCardContentProvider] and then retrieve new [AppCard]s
   */
  fun refreshSelected() {
    appCardHost.refreshCompatibleApplication()

    appCardContext?.let { context ->
      selectedAppCards.value?.forEach {
        appCardHost.requestAppCard(context, it.appId, it.appCard.id)
      }
    }
  }

  fun refreshAll() {
    appCardHost.refreshCompatibleApplication()

    appCardContext?.let {
      appCardHost.getAllAppCards(it)
    }
  }

  fun removeUnselectedAppCards() {
    synchronized(lock = selectedAppCards) {
      unselectedAppCards.value?.forEach {
        appCardHost.notifyAppCardRemoved(
          it.appId,
          it.appCard.id
        )
      }

      unselectedAppCards.value?.clear()
      unselectedAppCards.value = mutableListOf()
    }
  }

  fun removeSelectedAppCards() {
    synchronized(lock = selectedAppCards) {
      selectedAppCards.value?.forEach {
        appCardHost.notifyAppCardRemoved(
          it.appId,
          it.appCard.id
        )
      }

      selectedAppCards.value?.clear()
      selectedAppCards.value = mutableListOf()
    }
  }

  override fun onRowMoved(from: Int, to: Int) {
    synchronized(lock = selectedAppCards) {
      selectedAppCards.value?.let {
        Collections.swap(it, from, to)
        touchHelper?.setAppCards(it, Optional.of(from), Optional.of(to))
      }
    }
  }

  override fun stateChanged(actionState: Int) {
    inMoveState = actionState == ItemTouchHelper.ACTION_STATE_DRAG
  }

  override fun shouldToggleSelect(appCardContainer: AppCardContainer): Boolean {
    synchronized(lock = selectedAppCards) {
      val key = getKey(appCardContainer)
      var selectedIndex = Optional.empty<Int>()

      selectedAppCards.value?.forEachIndexed { index, container ->
        if (key == getKey(container)) {
          selectedIndex = Optional.of(index)
          return@forEachIndexed
        }
      }

      if (selectedIndex.isEmpty) {
        unselectedAppCards.value?.forEachIndexed { index, container ->
          if (key == getKey(container)) {
            selectedIndex = Optional.of(index)
            return@forEachIndexed
          }
        }

        if (selectedIndex.isPresent) {
          selectedAppCards.value?.size?.let { size ->
            if (size < MAX_SELECTED_CARDS) {
              unselectedAppCards.value?.removeAt(selectedIndex.get())?.let {
                selectedAppCards.value?.add(it)
                return true
              }
            } else {
              limitToast.show()
            }
          }
        }
      } else {
        selectedAppCards.value?.removeAt(selectedIndex.get())?.let {
          unselectedAppCards.value?.add(0, it)

          return true
        }
      }
      return false
    }
  }

  companion object {
    private const val TAG = "AppCardViewModel"
    private const val KEY_DELIMITER = " : "
    private const val MAX_SELECTED_CARDS = 3
    private const val MAX_TOAST_TEXT = "Maximum app card limit reached"
  }
}
