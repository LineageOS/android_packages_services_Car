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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.android.car.appcard.AppCardContext

/** Used to communicate with AppCardService */
class AppCardServiceManager(application: Application) : AndroidViewModel(application) {
  private val applicationContext = application
  private val handler = IncomingHandler(Looper.getMainLooper())
  private var messenger = Messenger(handler)
  private var serviceMessenger: Messenger? = null
  private var sentKeyList = listOf<String>()
  private val serviceConnection: ServiceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      serviceMessenger = Messenger(service)
      askServiceToSendContextUpdates()
      askServiceToSendOrderUpdates()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      serviceMessenger = null
    }

    override fun onBindingDied(name: ComponentName?) {
      bind()
    }
  }

  init {
    bind()
  }

  override fun onCleared() {
    super.onCleared()
    unregisterOrderListener()
    unregisterContextListener()
    unbind()
  }

  fun bind() {
    val launchIntent = Intent()
    launchIntent.action = SERVICE_ACTION
    launchIntent.setPackage(SERVICE_PKG)
    applicationContext.bindService(launchIntent, serviceConnection, Context.BIND_AUTO_CREATE)
  }

  fun unbind() {
    applicationContext.unbindService(serviceConnection)
  }

  fun registerContextListener(listener: AppCardContextListener) {
    handler.appCardContextListener = listener
    handler.latestAppCardContext?.let {
      listener.update(it)
    }
    askServiceToSendContextUpdates()
  }

  fun registerOrderListener(listener: AppCardOrderListener) {
    handler.appCardOrderListener = listener
    handler.latestAppCardOrder?.let {
      listener.update(it)
      // We only want to receive app card order once
      handler.appCardOrderListener = null
    }
    askServiceToSendOrderUpdates()
  }

  fun sendAppCardOrder(appCards: List<String>) {
    if (appCards == sentKeyList) {
      return
    }
    serviceMessenger?.let {
      val bundle = Bundle()
      bundle.putStringArrayList(KEY_APP_CARD_ORDER, ArrayList(appCards))
      val h = null
      val msg = Message.obtain(h, MessageType.MSG_SEND_APP_CARD_UPDATE.type, bundle)
      it.send(msg)
      sentKeyList = appCards
    }
  }

  private fun askServiceToSendContextUpdates() {
    serviceMessenger?.send(getMessageWithHostId(MessageType.MSG_REGISTER_CONTEXT_LISTENER))
  }

  private fun askServiceToSendOrderUpdates() {
    serviceMessenger?.send(getMessageWithHostId(MessageType.MSG_REGISTER_APP_CARD_LISTENER))
  }

  private fun unregisterOrderListener() {
    serviceMessenger?.send(getMessageWithHostId(MessageType.MSG_UNREGISTER_APP_CARD_LISTENER))
  }

  private fun unregisterContextListener() {
    serviceMessenger?.send(getMessageWithHostId(MessageType.MSG_UNREGISTER_CONTEXT_LISTENER))
  }

  private fun getMessageWithHostId(type: MessageType): Message {
    val h = null
    val msg = Message.obtain(h, type.type)
    val bundle = Bundle()
    bundle.putString(KEY_APP_CARD_HOST_ID, applicationContext.packageName)
    msg.obj = bundle
    msg.replyTo = messenger
    return msg
  }

  private class IncomingHandler(looper: Looper) : Handler(looper) {
    var appCardContextListener: AppCardContextListener? = null
    var appCardOrderListener: AppCardOrderListener? = null
    var latestAppCardContext: AppCardContext? = null
    var latestAppCardOrder: List<String>? = null

    override fun handleMessage(msg: Message) {
      super.handleMessage(msg)
      val msgType = MessageType.fromInt(msg.what)
      logIfDebuggable("handleMessage: ${msgType.type}")
      when (msgType) {
        MessageType.MSG_SEND_CONTEXT_UPDATE -> {
          AppCardContext.fromBundle(msg.obj as Bundle)?.let {
            latestAppCardContext = it
            appCardContextListener?.update(it)
          }
        }

        MessageType.MSG_SEND_APP_CARD_UPDATE -> {
          val bundle = msg.obj as Bundle
          val order = bundle.getStringArrayList(KEY_APP_CARD_ORDER)
          order?.let {
            latestAppCardOrder = it
            appCardOrderListener?.update(it)
            // We only want to receive app card order once
            appCardOrderListener = null
          }
        }

        else -> return
      }
    }
  }

  interface AppCardContextListener {
    fun update(appCardContext: AppCardContext)
  }

  interface AppCardOrderListener {
    fun update(order: List<String>)
  }

  internal enum class MessageType(val type: Int) {
    MSG_REGISTER_CONTEXT_LISTENER(0),
    MSG_REGISTER_APP_CARD_LISTENER(1),
    MSG_SEND_APP_CARD_UPDATE(2),
    MSG_SEND_CONTEXT_UPDATE(3),
    MSG_UNREGISTER_CONTEXT_LISTENER(4),
    MSG_UNREGISTER_APP_CARD_LISTENER(5);

    companion object {
      fun fromInt(value: Int) = MessageType.entries.first { it.type == value }
    }
  }

  companion object {
    private const val KEY_APP_CARD_ORDER = "appCardOrder"
    private const val KEY_APP_CARD_HOST_ID = "appCardHostId"
    private const val TAG = "AppCardServiceManager"
    private const val SERVICE_ACTION = "com.android.systemui.car.appcard.AppCardService"
    private const val SERVICE_PKG = "com.android.systemui"

    private fun logIfDebuggable(msg: String) {
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, msg)
      }
    }
  }
}
