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

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.util.Log
import com.android.car.BLEStreamProtos.BLEMessageProto.BLEMessage
import com.android.car.BLEStreamProtos.BLEOperationProto.OperationType
import com.android.car.protobuf.InvalidProtocolBufferException
import com.android.internal.annotations.VisibleForTesting
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

private const val TAG = "BleMessageStreamV1"

@VisibleForTesting
const val BLE_MESSAGE_RETRY_LIMIT = 5

private val BLE_MESSAGE_RETRY_DELAY_MS = TimeUnit.SECONDS.toMillis(2)

/**
 * Version 1 of the message stream.
 */
internal class BleMessageStreamV1(
    private val handler: Handler,
    private val bleManager: BleManager,
    override val device: BluetoothDevice,
    override val writeCharacteristic: BluetoothGattCharacteristic,
    override val readCharacteristic: BluetoothGattCharacteristic
) : BleMessageStream {
    // Explicitly using an ArrayDequeue here for performance when used as a queue.
    private val messageQueue = ArrayDeque<BLEMessage>()
    private val payloadStream = BLEMessagePayloadStream()

    private var retryCount = 0

    override var maxWriteSize = 20
    override var callback: BleMessageStreamCallback? = null

    init {
        bleManager.addOnCharacteristicWriteListener(::onCharacteristicWrite)
    }

    /**
     * Writes the given message to the `writeCharacteristic` of this stream.
     *
     * This method will handle the chunking of messages based on [maxWriteSize]. If there is an
     * error during the send, the [callback] of this method will be notified via a call to
     * `onWriteMessageError`.
     *
     * @param message The message to send.
     * @param operationType The [OperationType] of this message.
     * @param isPayloadEncrypted `true` if the message to send has been encrypted.
     */
    override fun writeMessage(
        message: ByteArray,
        operationType: OperationType,
        isPayloadEncrypted: Boolean
    ) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Writing message to device with name: ${device.name}")
        }

        val bleMessages = BLEMessageV1Factory.makeBLEMessages(
            message,
            operationType,
            maxWriteSize,
            isPayloadEncrypted)

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Number of messages to send to device: ${bleMessages.size}")
        }

        // Each write will override previous messages.
        messageQueue.takeUnless { it.isEmpty() }?.apply {
            clear()
            Log.w(TAG, "Request to write a new message when there are still messages in the queue.")
        }

        messageQueue.addAll(bleMessages)

        writeNextMessageInQueue()
    }

    /**
     * Processes a message from the client and notifies the [callback] of the success of this
     * call.
     */
    @VisibleForTesting
    fun onCharacteristicWrite(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (this.device != device) {
            Log.w(
                TAG,
                "Received a message from a device (${device.address}) that is not the " +
                    "expected device (${device.address}) registered to this stream. Ignoring.")
            return
        }

        if (characteristic.uuid != readCharacteristic.uuid) {
            Log.w(
                TAG,
                "Received a write to a characteristic (${characteristic.uuid}) that is not the " +
                    "expected UUID (${readCharacteristic.uuid}). Ignoring.")
            return
        }

        val bleMessage = try {
            BLEMessage.parseFrom(value)
        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "Can not parse BLE message from client.", e)
            callback?.onMessageReceivedError(characteristic.uuid)
            return
        }

        if (bleMessage.operation == OperationType.ACK) {
            handleClientAckMessage()
            return
        }

        try {
            payloadStream.write(bleMessage)
        } catch (e: IOException) {
            Log.e(TAG, "Unable to parse the BLE message's payload from client.", e)
            callback?.onMessageReceivedError(characteristic.uuid)
            return
        }

        // If it's not complete, make sure the client knows that this message was received.
        if (!payloadStream.isComplete()) {
            sendAcknowledgmentMessage()
            return
        }

        callback?.onMessageReceived(payloadStream.toByteArray(), characteristic.uuid)
        payloadStream.reset()
    }

    /**
     * Writes the next message in [messageQueue] to the given characteristic.
     *
     * If the message queue is empty, then this method will do nothing.
     */
    private fun writeNextMessageInQueue() {
        when (messageQueue.size) {
            0 -> Log.e(TAG, "Call to write next message in queue, but the message queue is empty.")
            1 -> writeValueAndNotify(messageQueue.remove().toByteArray())
            else -> handler.post(sendMessageWithTimeout)
        }
    }

    private fun handleClientAckMessage() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Received ACK from client. Attempting to write next message in queue.")
        }

        handler.removeCallbacks(sendMessageWithTimeout)
        retryCount = 0

        if (messageQueue.isEmpty()) {
            Log.e(TAG, "Received ACK, but the message queue is empty. Ignoring.")
            return
        }

        // Previous message has been sent successfully so we can start the next message.
        messageQueue.remove()
        writeNextMessageInQueue()
    }

    private fun sendAcknowledgmentMessage() {
        writeValueAndNotify(BLEMessageV1Factory.makeAcknowledgementMessage().toByteArray())
    }

    /**
     * Convenience method to write the given message to the `writeCharacteristic` of this class.
     * After writing, this method will also send notifications to any listening devices that the
     * write was made.
     */
    private fun writeValueAndNotify(message: ByteArray) {
        writeCharacteristic.setValue(message)

        bleManager.notifyCharacteristicChanged(
            device,
            writeCharacteristic,
            /* confirm= */ false)
    }

    /**
     * A runnable that will write the message at the head of the [messageQueue] and set up a timeout
     * for receiving an ACK for that write.
     *
     * If the timeout is reached before an ACK is received, the message write is retried.
     */
    private val sendMessageWithTimeout by lazy {
        // Note: using explicit "object" declaration to access "this".
        object : Runnable {
            override fun run() {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Sending BLE message; retry count: $retryCount")
                }

                if (retryCount < BLE_MESSAGE_RETRY_LIMIT) {
                    writeValueAndNotify(messageQueue.peek().toByteArray())
                    retryCount++
                    handler.postDelayed(this, BLE_MESSAGE_RETRY_DELAY_MS)
                    return
                }

                handler.removeCallbacks(this)
                retryCount = 0
                messageQueue.clear()

                Log.e(TAG, "Error during BLE message sending - exceeded retry limit.")

                callback?.onWriteMessageError()
            }
        }
    }
}
