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

import android.car.encryptionrunner.EncryptionRunner
import android.car.encryptionrunner.EncryptionRunnerFactory
import android.car.encryptionrunner.HandshakeException
import android.car.encryptionrunner.HandshakeMessage
import android.car.encryptionrunner.Key
import android.util.Log
import com.android.car.BLEStreamProtos.BLEOperationProto.OperationType
import com.android.car.Utils
import com.android.car.guard
import java.security.SignatureException
import java.util.UUID
import kotlin.concurrent.thread

private const val TAG = "SecureBleChannel"

/**
 * Establishes a secure channel with [EncryptionRunner] over [BleMessageStream] as server side,
 * sends and receives messages securely after the secure channel has been established
 *
 * It only applies to the client that has been associated with the IHU before.
 */
internal class SecureBleChannel(
    private val stream: BleMessageStream,
    private val storage: CarCompanionDeviceStorage,
    private val callback: Callback,
    private var encryptionKey: Key? = null
) {
    private val runner: EncryptionRunner = EncryptionRunnerFactory.newRunner()
    private var deviceId: String? = null
    private var state = HandshakeMessage.HandshakeState.UNKNOWN
    private val isSecureChannelEstablished get() = encryptionKey != null
    private val streamCallback = object : BleMessageStreamCallback {
        override fun onMessageReceived(message: ByteArray, uuid: UUID) {
            if (isSecureChannelEstablished) {
                encryptionKey?.guard {
                    try {
                        val decryptedMessage = it.decryptData(message)
                        notifyCallback { it.onMessageReceived(decryptedMessage) }
                    } catch (e: SignatureException) {
                        Log.e(TAG, "Could not decrypt client credentials.", e)
                        notifyCallback { it.onMessageReceivedError(e) }
                    }
                }
            } else {
                try {
                    processHandshake(message)
                } catch (e: HandshakeException) {
                    Log.e(TAG, "Handshake failed.", e)
                    notifyCallback { it.onEstablishSecureChannelFailure() }
                }
            }
        }

        override fun onMessageReceivedError(uuid: UUID) {
            if (isSecureChannelEstablished) {
                Log.e(TAG, "Error parsing the message from the client for handshake.")
            } else {
                Log.e(TAG, "Error parsing the encrypted message from the client.")
            }
        }

        override fun onWriteMessageError() {
            if (isSecureChannelEstablished) {
                Log.e(TAG, "Error writing encrypted message to the stream.")
            } else {
                Log.e(TAG, "Error writing handshake message to the stream.")
            }
        }
    }

    init {
        // This channel is only for communication between IHU and a client that has been
        // associated before. The first connect happens in association. So it is always a reconnect
        // in this channel.
        runner.setIsReconnect(true)
        stream.callback = streamCallback
    }

    @Throws(HandshakeException::class)
    private fun processHandshake(message: ByteArray) {
        when (state) {
            HandshakeMessage.HandshakeState.UNKNOWN -> {
                if (deviceId != null) {
                    logd("Responding to handshake init request.")
                    val handshakeMessage = runner.respondToInitRequest(message)
                    state = handshakeMessage.handshakeState
                    sendHandshakeMessage(handshakeMessage.nextMessage)
                    return
                }
                deviceId = Utils.bytesToUUID(message)?.toString()?.also { id ->
                    if (hasEncryptionKey(id)) {
                        notifyCallback { it.onDeviceIdReceived(id) }
                        sendUniqueIdToClient()
                    } else {
                        Log.e(TAG, "this client has not been associated before.")
                        notifyCallback { it.onEstablishSecureChannelFailure() }
                    }
                }
            }
            HandshakeMessage.HandshakeState.IN_PROGRESS -> {
                logd("Continuing handshake.")
                val handshakeMessage = runner.continueHandshake(message)
                state = handshakeMessage.handshakeState
                if (state != HandshakeMessage.HandshakeState.RESUMING_SESSION) {
                    Log.e(TAG, "Encounter unexpected handshake state: $state.")
                    notifyCallback { it.onEstablishSecureChannelFailure() }
                }
            }
            HandshakeMessage.HandshakeState.RESUMING_SESSION -> {
                logd("Start reconnection authentication.")
                val id = deviceId ?: run {
                    Log.e(TAG, "Unable to resume session, device id is null.")
                    notifyCallback { it.onEstablishSecureChannelFailure() }
                    return
                }
                val previousKey = storage.getEncryptionKey(id) ?: run {
                    Log.e(TAG, "Unable to resume session, previous key is null.")
                    notifyCallback { it.onEstablishSecureChannelFailure() }
                    return
                }
                val handshakeMessage = runner.authenticateReconnection(message, previousKey)
                state = handshakeMessage.handshakeState
                if (state != HandshakeMessage.HandshakeState.FINISHED) {
                    Log.e(TAG, "Unable to resume session, unexpected next handshake state: $state.")
                    notifyCallback { it.onEstablishSecureChannelFailure() }
                    return
                }
                val newKey = handshakeMessage.key ?: run {
                    Log.e(TAG, "Unable to resume session, new key is null.")
                    notifyCallback { it.onEstablishSecureChannelFailure() }
                    return
                }
                storage.saveEncryptionKey(id, newKey.asBytes())
                encryptionKey = newKey
                sendServerAuthToClient(handshakeMessage.nextMessage)
                notifyCallback { it.onSecureChannelEstablished(newKey) }
            }
            HandshakeMessage.HandshakeState.INVALID, HandshakeMessage.HandshakeState.FINISHED,
            HandshakeMessage.HandshakeState.VERIFICATION_NEEDED -> {
                Log.e(TAG, "Encountered unexpected handshake state: $state. " +
                    "Received message: $message")
                notifyCallback { it.onEstablishSecureChannelFailure() }
            }
            else -> {
                Log.e(TAG, "Encountered unrecognized handshake state: $state. " +
                    "Received message: $message")
                notifyCallback { it.onEstablishSecureChannelFailure() }
            }
        }
    }

    private fun sendUniqueIdToClient() {
        val uniqueId = storage.uniqueId
        if (uniqueId != null) {
            logd("Send car id: $uniqueId")
            stream.writeMessage(Utils.uuidToBytes(uniqueId), OperationType.CLIENT_MESSAGE,
                /* isPayloadEncrypted= */ false)
        } else {
            Log.e(TAG, "Unable to send car id, car id is null.")
            notifyCallback { it.onEstablishSecureChannelFailure() }
        }
    }

    private fun hasEncryptionKey(id: String) = storage.getEncryptionKey(id) != null

    private fun sendHandshakeMessage(message: ByteArray?) {
        if (message != null) {
            logd("Send handshake message: $message.")
            stream.writeMessage(message, OperationType.ENCRYPTION_HANDSHAKE,
                /* isPayloadEncrypted= */ false)
        } else {
            Log.e(TAG, "Unable to send next handshake message, message is null.")
            notifyCallback { it.onEstablishSecureChannelFailure() }
        }
    }

    private fun sendServerAuthToClient(message: ByteArray?) {
        if (message != null) {
            stream.writeMessage(message, OperationType.CLIENT_MESSAGE,
                /* isPayloadEncrypted= */ false)
        } else {
            Log.e(TAG, "Unable to send server authentication message to client, message is null.")
            notifyCallback { it.onEstablishSecureChannelFailure() }
        }
    }

    // This should be called only after the secure channel has been established.
    fun sendEncryptedMessage(message: ByteArray) {
        encryptionKey?.also {
            val encryptedMessage = it.encryptData(message)
            stream.writeMessage(encryptedMessage, OperationType.CLIENT_MESSAGE,
                /* isPayloadEncrypted= */ true)
        } ?: throw IllegalStateException("Secure channel has not been established.")
    }

    private fun logd(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message)
        }
    }

    private fun notifyCallback(notification: (Callback) -> Unit) {
        synchronized(callback) {
            thread(isDaemon = true) { notification(callback) }
        }
    }

    /**
     * Callbacks that will be invoked during establishing secure channel, sending and receiving
     * messages securely.
     */
    interface Callback {
        /**
         * Invoked when secure channel has been established successfully.
         *
         * @param[encryptionKey] The new key generated in handshake.
         */
        fun onSecureChannelEstablished(encryptionKey: Key)

        /**
         * Invoked when an error has been encountered in attempting to establish a secure channel.
         */
        fun onEstablishSecureChannelFailure()

        /**
         * Invoked when a complete message is received securely from the client and decrypted.
         *
         * @param[message] The decrypted message.
         */
        fun onMessageReceived(message: ByteArray)

        /**
         * Invoked when there was an error during a processing or decrypting of a client message.
         *
         * @param[exception] The error.
         */
        fun onMessageReceivedError(exception: Exception)

        /**
         * Invoked when the device id was received from the client.
         *
         * @param[deviceId] The unique device id of client.
         */
        fun onDeviceIdReceived(deviceId: String)
    }
}
