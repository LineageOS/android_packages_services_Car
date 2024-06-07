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

package com.android.systemui.car.appcard

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.io.FileDescriptor
import java.io.PrintWriter

/** Service used to communicate between multiple app card hosts */
class AppCardService : Service() {
  private val handler = IncomingHandler(Looper.getMainLooper())
  private val messenger = Messenger(handler)

  override fun onCreate() {
    logIfDebuggable("onCreate")
    super.onCreate()
  }

  override fun onRebind(intent: Intent?) {
    logIfDebuggable("onRebind")
    super.onRebind(intent)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    logIfDebuggable("onStartCommand")
    super.onStartCommand(intent, flags, startId)
    return START_STICKY
  }

  override fun onDestroy() {
    logIfDebuggable("onDestroy")
    super.onDestroy()
  }

  override fun onUnbind(intent: Intent?): Boolean {
    logIfDebuggable("onUnbind")
    return super.onUnbind(intent)
  }

  override fun onBind(intent: Intent?): IBinder? {
    logIfDebuggable("onBind")
    return messenger.binder
  }

  private class IncomingHandler(looper: Looper) : Handler(looper) {
    var contextListeners = mutableMapOf<String, Messenger>()
    var appCardListeners = mutableMapOf<String, Messenger>()
    var latestContext: Any? = null
    var latestOrder: Any? = null

    override fun handleMessage(msg: Message) {
      super.handleMessage(msg)
      val msgType = MessageType.fromInt(msg.what)
      logIfDebuggable("handleMessage: ${msgType.type}")
      when (msgType) {
        MessageType.MSG_REGISTER_CONTEXT_LISTENER -> {
          getHostId(msg)?.let { id ->
            contextListeners[id] = msg.replyTo
            val handler = null
            latestContext?.let {
              val message = Message.obtain(
                handler,
                MessageType.MSG_SEND_CONTEXT_UPDATE.type,
                it
              )
              msg.replyTo.send(message)
            }
          }
        }

        MessageType.MSG_REGISTER_APP_CARD_LISTENER -> {
          getHostId(msg)?.let { id ->
            appCardListeners[id] = msg.replyTo
            val handler = null
            latestOrder?.let {
              val message = Message.obtain(
                handler,
                MessageType.MSG_SEND_APP_CARD_UPDATE.type,
                it
              )
              msg.replyTo.send(message)
            }
          }
        }

        MessageType.MSG_SEND_APP_CARD_UPDATE -> {
          latestOrder = msg.obj
          val handler = null
          val message = Message.obtain(handler, msg.what, msg.obj)
          appCardListeners.values.forEach { it.send(message) }
        }

        MessageType.MSG_SEND_CONTEXT_UPDATE -> {
          latestContext = msg.obj
          val handler = null
          val message = Message.obtain(handler, msg.what, msg.obj)
          contextListeners.values.forEach { it.send(message) }
        }

        MessageType.MSG_UNREGISTER_CONTEXT_LISTENER -> {
          getHostId(msg)?.let {
            contextListeners.remove(it)
          }
        }

        MessageType.MSG_UNREGISTER_APP_CARD_LISTENER -> {
          getHostId(msg)?.let {
            appCardListeners.remove(it)
          }
        }
      }
    }

    private fun getHostId(msg: Message): String? {
      val bundle = msg.obj as Bundle
      return bundle.getString(KEY_APP_CARD_HOST_ID)
    }
  }

  public override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<String?>?) {
    writer.println("AppCardService dump:")
    writer.println("- cached app card context: " + handler.latestContext)
    writer.println("- app card context listeners: " + handler.contextListeners)
    writer.println("- cached app card order: " + handler.latestOrder)
    writer.println("- app card context listeners: " + handler.appCardListeners)
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
    private const val TAG = "AppCardService"
    private const val KEY_APP_CARD_HOST_ID = "appCardHostId"

    private fun logIfDebuggable(msg: String) {
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, msg)
      }
    }
  }
}
