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

import android.util.Log
import com.android.car.BleStreamProtos.BlePacketProto.BlePacket
import com.android.car.protobuf.ByteString
import kotlin.math.ceil
import kotlin.math.min

private const val TAG = "BleDeviceMessageFactory"

/**
 * The size in bytes of a `fixed32` field in the proto.
 */
private const val FIXED_32_SIZE = 4
/**
 * The bytes needed to encode the field number in the proto.
 * Since the [BlePacket] only has 4 fields, it will only take 1 additional byte to encode.
 */
private const val FIELD_NUMBER_ENCODING_SIZE = 1

private val messageIdGenerator = MessageIdGenerator()

/**
 * Split given data if necessary to fit within the given [maxSize].
 *
 * @param payload The payload to potentially split across multiple [BlePacket]s.
 * @param messageId The unique id for identifying message.
 * @param maxSize The maximum size of each chunk.
 * @return A list of [BlePacket]s.
 */
fun makeBlePackets(payload: ByteArray, messageId: Int, maxSize: Int): List<BlePacket> {
    val blePackets = mutableListOf<BlePacket>()
    val payloadSize = payload.size
    val totalPackets = getTotalPacketNumber(messageId, payloadSize, maxSize)
    val maxPayloadSize = maxSize -
        getPacketHeaderSize(totalPackets, messageId, min(payloadSize, maxSize))
    if (payloadSize < maxPayloadSize) {
        blePackets.add(BlePacket.newBuilder()
            .setPacketNumber(1)
            .setTotalPackets(1)
            .setMessageId(messageId)
            .setPayload(ByteString.copyFrom(payload))
            .build()
        )
        return blePackets
    }
    var start = 0
    var end = maxPayloadSize
    for (packetNum in 1..totalPackets) {
        blePackets.add(BlePacket.newBuilder()
            .setPacketNumber(packetNum)
            .setTotalPackets(totalPackets)
            .setMessageId(messageId)
            .setPayload(ByteString.copyFrom(payload.copyOfRange(start, end)))
            .build()
        )
        start = end
        end = min(start + maxPayloadSize, payloadSize)
    }
    return blePackets
}

// Compute the header size for the [BlePacket] proto in bytes. This method assumes that
// the proto contain a payload.
private fun getPacketHeaderSize(
    totalPackets: Int,
    messageId: Int,
    payloadSize: Int
): Int {
    return FIXED_32_SIZE + FIELD_NUMBER_ENCODING_SIZE +
        getEncodedSize(totalPackets) + FIELD_NUMBER_ENCODING_SIZE +
        getEncodedSize(messageId) + FIELD_NUMBER_ENCODING_SIZE +
        getEncodedSize(payloadSize) + FIELD_NUMBER_ENCODING_SIZE
}

// Compute the min totalPackets.
// The size of totalPackets is a 32-bit variant, assume the value it is in bytes.
// Then compute the totalPackets based on the assumption, exam the assumption and return the min
// valid totalPackets.
private fun getTotalPacketNumber(messageId: Int, payloadSize: Int, maxSize: Int): Int {
    val headerSizeWithoutTotalPackets = FIXED_32_SIZE + FIELD_NUMBER_ENCODING_SIZE +
        getEncodedSize(messageId) + FIELD_NUMBER_ENCODING_SIZE +
        getEncodedSize(min(payloadSize, maxSize)) + FIELD_NUMBER_ENCODING_SIZE
    for (value in 1..FIXED_32_SIZE) {
        val packetHeaderSize = headerSizeWithoutTotalPackets + value +
            FIELD_NUMBER_ENCODING_SIZE
        val maxPayloadSize = maxSize - packetHeaderSize
        if (maxPayloadSize < 0) {
            throw BleDeviceMessageFactoryException("Packet header size too large.")
        }
        val totalPackets = ceil(payloadSize.toDouble() / maxPayloadSize).toInt()
        if (getEncodedSize(totalPackets) == value) {
            return totalPackets
        }
    }
    Log.e(TAG, "Cannot get valid total packet number for message: messageId: $messageId," +
        "payloadSize: $payloadSize, maxSize: $maxSize.")
    throw BleDeviceMessageFactoryException("No valid total packet number.")
}

/**
 * This method implements Protocol Buffers encoding algorithm:
 * https://developers.google.com/protocol-buffers/docs/encoding#varints
 *
 * Computes the number of bytes that would be needed to store a 32-bit variant. Negative
 * value is not considered because all proto values should be positive.
 *
 * @param value the data that need to be encoded
 * @return the size of the encoded data
 */
private fun getEncodedSize(value: Int): Int {
    return when {
        value < 0 -> 10
        value and (0.inv() shl 7) == 0 -> {
            Log.e(TAG, "Get a negative value from proto")
            1
        }
        value and (0.inv() shl 14) == 0 -> 2
        value and (0.inv() shl 21) == 0 -> 3
        value and (0.inv() shl 28) == 0 -> 4
        else -> 5
    }
}

class BleDeviceMessageFactoryException(message: String) : Exception(message)
