/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.trust

import android.content.Context
import java.util.UUID

/** Manager of devices connected to the car. */
class ConnectedDeviceManager(context: Context) {

    /** Returns [List<ConnectedDevice>] of devices currently connected. */
    fun getConnectedDevices(): List<ConnectedDevice> {
        return mutableListOf()
    }

    /**
     * Register a [callback] for manager triggered connection events for only the
     * currently active user's devices.
     */
    fun registerActiveUserConnectionCallback(callback: ConnectionCallback) {
    }

    /** Unregister a connection [callback] from manager. */
    fun unregisterConnectionCallback(callback: ConnectionCallback) {
    }

    /** Connect to a device for the active user if available. */
    fun connectToActiveUserDevice() {
    }

    /**
     * Disconnect from a [device]. If there are any active subscriptions this action will fail and
     * return `false`. Returns `true` if successful.
     */
    fun disconnectFromDevice(device: ConnectedDevice): Boolean {
        return true
    }

    /** Register [callback] for specific [device] and [recipientId] device events. */
    fun registerDeviceCallback(
        device: ConnectedDevice,
        recipientId: UUID,
        callback: DeviceCallback
    ) {
    }

    /** Unregister device [callback] for specific [device] and [recipientId]. */
    fun unregisterDeviceCallback(
        device: ConnectedDevice,
        recipientId: UUID,
        callback: DeviceCallback
    ) {
    }

    /**
     * Send encrypted [message] to [recipientId] on [device]. Throws
     * [IllegalStateException] if secure channel has not been established.
     */
    @Throws(IllegalStateException::class)
    fun sendMessageSecurely(
        device: ConnectedDevice,
        recipientId: UUID,
        message: ByteArray
    ) {
    }

    /** Send unencrypted [message] to [recipientId] on [device]. */
    fun sendMessageUnsecurely(
        device: ConnectedDevice,
        recipientId: UUID,
        message: ByteArray
    ) {
    }

    /** Callback for triggered connection events from [ConnectedDeviceManager]. */
    interface ConnectionCallback {
        /** Triggered when a new [device] has connected. */
        fun onDeviceConnected(device: ConnectedDevice)

        /** Triggered when a [device] has disconnected. */
        fun onDeviceDisconnected(device: ConnectedDevice)
    }

    /** Triggered device events for a connected device from [ConnectedDeviceManager]. */
    interface DeviceCallback {
        /**
         * Triggered when secure channel has been established on [device]. Encrypted messaging now
         * available.
         */
        fun onSecureChannelEstablished(device: ConnectedDevice)

        /** Triggered when a new [message] is received from [device]. */
        fun onMessageReceived(device: ConnectedDevice, message: ByteArray)

        /** Triggered when an [error] has occurred for a [device]. */
        fun onDeviceError(device: ConnectedDevice, error: DeviceError)
    }

    /** Possible device errors. */
    enum class DeviceError {
        // Unable to establish a secure connection because of key mis-match.
        INVALID_SECURITY_KEY
    }
}
