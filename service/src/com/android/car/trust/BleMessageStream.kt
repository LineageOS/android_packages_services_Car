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
import com.android.car.BLEStreamProtos.BLEOperationProto.OperationType

/**
 * Handles the streaming of BLE messages to a specific [BluetoothDevice].
 *
 * This stream will handle if messages to a particular peripheral need to be split into
 * multiple messages or if the messages can be sent all at once. Internally, it will have its own
 * protocol for how the split messages are structured.
 */
internal interface BleMessageStream {
    /** The [BluetoothDevice] to write and receive messages from. */
    val device: BluetoothDevice

    /** The characteristic on the device to write messages to. */
    val writeCharacteristic: BluetoothGattCharacteristic

    /** The characteristic that messages will be written to by the remote device. */
    val readCharacteristic: BluetoothGattCharacteristic

    /** An optional callback that will be notified of the progress of writes and reads. */
    var callback: BleMessageStreamCallback?

    /** The maximum size of a message that can be sent. */
    var maxWriteSize: Int

    /**
     * Writes the given message to the [writeCharacteristic] of the [device] of this stream.
     *
     * The given message will adhere to the [maxWriteSize]. If the message is larger than this size,
     * then this stream should take the appropriate actions necessary to chunk the message to
     * the device so that no parts of the message is dropped.
     *
     * If there was an error, then this stream will notify the [callback] of this stream via a call
     * to its `onWriteMessageError` mthod.
     *
     * @param message The message to send.
     * @param operationType The [OperationType] of this message.
     * @param isPayloadEncrypted `true` if the message to send has been encrypted.
     */
    fun writeMessage(message: ByteArray, operationType: OperationType, isPayloadEncrypted: Boolean)
}
