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
import com.android.internal.widget.helper.ItemTouchHelper
import java.util.Collections

/** An [AndroidViewModel] that registers itself as an [AppCardListener] */
class AppCardViewModel(application: Application) : AndroidViewModel(application), AppCardListener,
  AppCardTouchHelper.AppCardMoveHelperContract {
  private lateinit var appCardHost: AppCardHost
  private var appCardContext: AppCardContext? = null
  var touchHelper: AppCardTouchHelper.AppCardTouchHelperContract? = null
  private var inMoveState = false
  private var initOrder: List<String>? = null

  val allAppCards = MutableLiveData<MutableList<AppCardContainer>>().apply {
    value = mutableListOf()
  }

  fun setAppCardHost(host: AppCardHost) {
    appCardHost = host
    // Register view model as [AppCardListener] so that changes to app cards can
    // propagate to all views that use this view model
    appCardHost.registerListener(listener = this)
  }

  fun setInitialList(initialOrder: List<String>) {
    synchronized(lock = allAppCards) {
      initOrder = initialOrder
      initOrder?.forEach { key ->
        // Add [EmptyAppCard] for initial list so that they are updated in place once actual app
        // card is received
        getAppCardIdentifier(key)?.let {
          val emptyAppCard = EmptyAppCard(it.second)
          allAppCards.value?.add(AppCardContainer(it.first, emptyAppCard))
        }
      }
      appCardContext?.let {
        refresh()
      }
    }
  }

  fun setAppCardContext(newAppCardContext: AppCardContext) {
    synchronized(lock = allAppCards) {
      appCardContext = newAppCardContext
      initOrder?.let {
        refresh()
      }
    }
  }

  /**
   * A new [AppCardContainer] has been received, either update an existing view
   * or create a new view representing the [AppCardContainer]
   */
  override fun onAppCardReceived(appCard: AppCardContainer) {
    synchronized(lock = allAppCards) {
      var updated = false
      allAppCards.value?.forEach {
        if (getKey(it) == getKey(appCard)) {
          it.appCard = appCard.appCard
          updated = true
        }
      }
      if (!updated) {
        allAppCards.value?.add(appCard)
      }

      if (!inMoveState) {
        allAppCards.value = allAppCards.value
      }
    }
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
    synchronized(lock = allAppCards) {
      allAppCards.value?.forEach {
        if (getKey(it) == getKey(component)) {
          it.appCard.updateComponent(component.component)
        }
      }

      if (!inMoveState) {
        allAppCards.value = allAppCards.value
      }
    }
  }

  /** A package has been removed from the system, remove all associated views */
  override fun onProviderRemoved(packageName: String, authority: String?) {
    synchronized(lock = allAppCards) {
      var removeIndex = -1
      allAppCards.value?.forEachIndexed { index, it ->
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
      if (removeIndex != -1) {
        allAppCards.value?.removeAt(removeIndex)
      }

      if (!inMoveState) {
        allAppCards.value = allAppCards.value
      }
    }
  }

  /** A package has been added tp the system, refresh [AppCard]s if needed */
  override fun onProviderAdded(packageName: String, authority: String?) {
    Log.d(TAG, "onPackageAdded: $packageName")
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
  fun refresh() {
    appCardHost.refreshCompatibleApplication()

    appCardContext?.let { context ->
      initOrder?.forEach { key ->
        getAppCardIdentifier(key)?.let {
          appCardHost.requestAppCard(context, it.first, it.second)
        }
      }
    }
  }

  /** Remove all [AppCard]s */
  fun onStop() {
    removeAllAppCards()
  }

  /** Remove all [AppCard]s then destroy [AppCardHost] */
  fun onDestroy() {
    appCardHost.destroy()
  }

  private fun removeAllAppCards() {
    synchronized(lock = allAppCards) {
      allAppCards.value?.forEach {
        appCardHost.notifyAppCardRemoved(
          it.appId,
          it.appCard.id
        )
      }

      allAppCards.value?.clear()
      allAppCards.value = mutableListOf()
    }
  }

  override fun onRowMoved(from: Int, to: Int) {
    synchronized(lock = allAppCards) {
      allAppCards.value?.let {
        if (from < to) {
          for (i in from until to) {
            Collections.swap(it, i, i + 1)
          }
        } else {
          for (i in from downTo to + 1) {
            Collections.swap(it, i, i - 1)
          }
        }
        touchHelper?.setAppCards(it, from, to)
      }
    }
  }

  override fun stateChanged(actionState: Int) {
    inMoveState = actionState == ItemTouchHelper.ACTION_STATE_DRAG
  }

  companion object {
    private const val TAG = "AppCardViewModel"
    private const val KEY_DELIMITER = " : "
  }
}
