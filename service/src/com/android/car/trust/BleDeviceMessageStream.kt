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
import android.util.Log
import com.android.car.BLEStreamProtos.VersionExchangeProto.BLEVersionExchange
import com.android.car.BLEStreamProtos.BLEOperationProto.OperationType
import com.android.car.BleStreamProtos.BleDeviceMessageProto.BleDeviceMessage
import com.android.car.BleStreamProtos.BlePacketProto.BlePacket
import com.android.car.Utils
import com.android.car.protobuf.ByteString
import com.android.car.protobuf.InvalidProtocolBufferException
import com.android.internal.annotations.GuardedBy
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.UUID

private const val TAG = "BleDeviceMessageStream"

// Only version 2 of the messaging and version 1 of the security supported.
private const val MESSAGING_VERSION = 2
private const val SECURITY_VERSION = 1

/**
 * Message stream
 */
internal class BleDeviceMessageStream(
    private val blePeripheralManager: BlePeripheralManager,
    private val device: BluetoothDevice,
    private val writeCharacteristic: BluetoothGattCharacteristic,
    private val readCharacteristic: BluetoothGattCharacteristic
) {
    // Explicitly using an ArrayDequeue here for performance when used as a queue.
    private val packetQueue = ArrayDeque<BlePacket>()
    // This maps messageId to ByteArrayOutputStream of received packets
    private val pendingData = mutableMapOf<Int, ByteArrayOutputStream>()
    private val messageIdGenerator = MessageIdGenerator()
    private var isInSending = false
    private var isVersionExchanged = false
    private var messageReceivedListener: MessageReceivedListener? = null
    private var messageReceivedErrorListener: MessageReceivedErrorListener? = null
    /**
     * The maximum amount of bytes that can be written over BLE.
     *
     * This initial value is 20 because BLE has a default write of 23 bytes. However, 3 bytes
     * are subtracted due to bytes being reserved for the command type and attribute ID.
     */
    internal var maxWriteSize = 20

    init {
        blePeripheralManager.addOnCharacteristicWriteListener(::onCharacteristicWrite)
        blePeripheralManager.addOnCharacteristicReadListener(::onCharacteristicRead)
    }

    /**
     * Writes the given message to the [writeCharacteristic] of this stream.
     *
     * This method will handle the chunking of messages based on [maxWriteSize]. If it is
     * a handshake message, the [deviceMessage.recipient] should be `null` and it cannot be
     * encrypted.
     *
     * @param deviceMessage The data object contains recipient, isPayloadEncrypted and message.
     * @param operationType The [OperationType] of this message.
     */
    fun writeMessage(
        deviceMessage: DeviceMessage,
        operationType: OperationType
    ) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Writing message to device with name: ${device.name}")
        }
        val recipientByteString = deviceMessage.recipient?.let {
            ByteString.copyFrom(Utils.uuidToBytes(it))
        }
        val bleDeviceMessage = BleDeviceMessage.newBuilder()
            .setOperation(operationType)
            .setIsPayloadEncrypted(deviceMessage.isMessageEncrypted)
            .setRecipient(recipientByteString)
            .setPayload(ByteString.copyFrom(deviceMessage.message))
            .build()
        val blePackets = makeBlePackets(
            bleDeviceMessage.toByteArray(), messageIdGenerator.next(), maxWriteSize)

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Number of messages to send to device: ${blePackets.size}")
        }
        packetQueue.addAll(blePackets)
        writeNextMessageInQueue()
    }

    private fun writeNextMessageInQueue() {
        if (isInSending or packetQueue.isEmpty()) {
            return
        }
        writeCharacteristic.setValue(packetQueue.remove().toByteArray())
        blePeripheralManager.notifyCharacteristicChanged(device, writeCharacteristic, false)
        isInSending = true
    }

    /**
     * Send the next packet in the queue to the client.
     */
    fun onCharacteristicRead(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (this.device != device) {
            Log.w(
                TAG,
                "Received a message from a device (${device.address}) that is not the " +
                    "expected device (${device.address}) registered to this stream. Ignoring.")
            return
        }
        if (characteristic.uuid != writeCharacteristic.uuid) {
            Log.w(
                TAG,
                "Received a read to a characteristic (${characteristic.uuid}) that is not the " +
                    "expected UUID (${writeCharacteristic.uuid}). Ignoring.")
            return
        }
        isInSending = false
        writeNextMessageInQueue()
    }

    /**
     * Processes a message from the client and notifies the [messageReceivedListener] of the
     * success of this call.
     */
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
        if (!isVersionExchanged) {
            processVersionExchange(device, value)
            return
        }

        val blePacket = try {
            BlePacket.parseFrom(value)
        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "Can not parse Ble packet from client.", e)
            messageReceivedErrorListener?.onMessageReceivedError(e)
            return
        }
        val messageId = blePacket.messageId
        val currentPayloadStream = pendingData[messageId] ?: run {
            val newPayloadStream = ByteArrayOutputStream()
            pendingData[messageId] = newPayloadStream
            newPayloadStream
        }
        currentPayloadStream.write(blePacket.payload.toByteArray())
        if (blePacket.packetNumber != blePacket.totalPackets) {
            return
        }
        val bleDeviceMessage = try {
            BleDeviceMessage.parseFrom(currentPayloadStream.toByteArray())
        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "Can not parse Ble packet from client.", e)
            pendingData.remove(messageId)
            messageReceivedErrorListener?.onMessageReceivedError(e)
            return
        }
        val deviceMessage = DeviceMessage(
            Utils.bytesToUUID(bleDeviceMessage.recipient.toByteArray()),
            bleDeviceMessage.isPayloadEncrypted,
            bleDeviceMessage.payload.toByteArray()
        )
        messageReceivedListener?.onMessageReceived(deviceMessage)
        pendingData.remove(messageId)
    }

    private fun processVersionExchange(device: BluetoothDevice, value: ByteArray) {
        val versionExchange = try {
            BLEVersionExchange.parseFrom(value)
        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "Could not parse version exchange message", e)
            messageReceivedErrorListener?.onMessageReceivedError(e)
            return
        }
        val minMessagingVersion = versionExchange.minSupportedMessagingVersion
        val maxMessagingVersion = versionExchange.maxSupportedMessagingVersion
        val minSecurityVersion = versionExchange.minSupportedSecurityVersion
        val maxSecurityVersion = versionExchange.maxSupportedSecurityVersion
        if ((minMessagingVersion > MESSAGING_VERSION)
            or (maxMessagingVersion < MESSAGING_VERSION)
            or (minSecurityVersion > SECURITY_VERSION)
            or (maxSecurityVersion < SECURITY_VERSION)) {
            Log.d(TAG, "No supported version for $device")
            messageReceivedErrorListener?.onMessageReceivedError(
                java.lang.Exception("No supported version.")
            )
            return
        }
        val headunitVersion = BLEVersionExchange.newBuilder()
            .setMinSupportedMessagingVersion(MESSAGING_VERSION)
            .setMaxSupportedMessagingVersion(MESSAGING_VERSION)
            .setMinSupportedSecurityVersion(SECURITY_VERSION)
            .setMaxSupportedSecurityVersion(SECURITY_VERSION)
            .build()
        writeCharacteristic.setValue(headunitVersion.toByteArray())
        blePeripheralManager.notifyCharacteristicChanged(device, writeCharacteristic, false)
        isVersionExchanged = true
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Sent supported version to the phone.")
        }
    }

    /**
     * Register the given listener to be notified when a complete message is received
     * from the client. If [listener] is `null`, unregister.
     */
    fun registerMessageReceivedListener(listener: MessageReceivedListener?) {
        messageReceivedListener = listener
    }

    /**
     * Register the given listener to be notified when there was an error during receiving
     * message from the client. If [listener] is `null`, unregister.
     */
    fun registerMessageReceivedErrorListener(listener: MessageReceivedErrorListener?) {
        messageReceivedErrorListener = listener
    }

    /**
     * Listener to be invoked when a complete message is received from the client.
     */
    interface MessageReceivedListener {

        /**
         * Called when a complete message is received from the client.
         *
         * @param deviceMessage The message received from the client.
         */
        fun onMessageReceived(deviceMessage: DeviceMessage)
    }

    /**
     * Listener to be invoked when there was an error during receiving message from the client.
     */
    interface MessageReceivedErrorListener {
        /**
         * Called when there was an error during receiving message from the client.
         *
         * @param exception The error.
         */
        fun onMessageReceivedError(exception: Exception)
    }
}

/**
 * Holds the needed data from a [BleDeviceMessage]
 */
internal data class DeviceMessage(
    val recipient: UUID?,
    val isMessageEncrypted: Boolean,
    var message: ByteArray
)

/**
 * A generator of unique IDs for messages.
 */
class MessageIdGenerator {
    private val lock = object
    @Volatile
    @GuardedBy("lock")
    private var messageId = 0

    /**
     * Return a unique id for identifying message
     */
    fun next(): Int {
        synchronized(lock) {
            val currentMessageId = messageId
            messageId = if (messageId < Int.MAX_VALUE) (messageId + 1) else 0
            return currentMessageId
        }
    }
}
