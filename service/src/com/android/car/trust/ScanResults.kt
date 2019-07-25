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

@file:JvmName("ScanResults")

package com.android.car.trust

import android.bluetooth.le.ScanResult
import android.util.Log

import java.math.BigInteger

private const val TAG = "ScanResults"

private const val IOS_OVERFLOW_LENGTH = 0x14
private const val IOS_ADVERTISING_TYPE = 0xff
private const val IOS_ADVERTISING_TYPE_LENGTH = 1
private const val IOS_OVERFLOW_CUSTOM_ID: Long = 0x4c0001
private const val IOS_OVERFLOW_CUSTOM_ID_LENGTH = 3
private const val IOS_OVERFLOW_CONTENT_LENGTH = IOS_OVERFLOW_LENGTH -
    IOS_OVERFLOW_CUSTOM_ID_LENGTH - IOS_ADVERTISING_TYPE_LENGTH

/**
 * When an iOS peripheral device goes into a background state, the service UUIDs and other
 * identifying information are removed from the advertising data and replaced with a hashed
 * bit in a special "overflow" area. There is no documentation on the layout of this area,
 * and the below was compiled from experimentation and examples from others who have worked
 * on reverse engineering iOS background peripherals.
 *
 * My best guess is Apple is taking the service UUID and hashing it into a bloom filter. This
 * would allow any device with the same hashing function to filter for all devices that
 * might contain the desired service. Since we do not have access to this hashing function,
 * we must first advertise our service from an iOS device and manually inspect the bit that
 * is flipped. Once known, it can be passed to [serviceUuidMask] and used as a filter.
 *
 * EXAMPLE
 *
 * Foreground contents:
 * 02011A1107FB349B5F8000008000100000C53A00000709546573746572000000000000000000000000000000000000000000000000000000000000000000
 *
 * Background contents:
 * 02011A14FF4C0001000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000000000000000000000
 *
 * The overflow bytes are comprised of four parts:
 * Length -> 14
 * Advertising type -> FF
 * Id custom to Apple -> 4C0001
 * Contents where hashed values are stored -> 00000000000000000000000000200000
 *
 * Apple's documentation on advertising from the background:
 * https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html#//apple_ref/doc/uid/TP40013257-CH7-SW9
 *
 * Other similar reverse engineering:
 * http://www.pagepinner.com/2014/04/how-to-get-ble-overflow-hash-bit-from.html
 */
fun ScanResult.containsUuidsInOverflow(serviceUuidMask: BigInteger): Boolean {
    val overflowBytes = ByteArray(IOS_OVERFLOW_CONTENT_LENGTH)
    val scanData = scanRecord.bytes
    var outPtr = 0
    var overflowPtr = 0

    try {
        while (overflowPtr < scanData.size - IOS_OVERFLOW_LENGTH) {
            val length = scanData[overflowPtr++].toInt()
            if (length == 0) {
                break
            }

            if (scanData[overflowPtr].toInt() and 0xff != IOS_ADVERTISING_TYPE) {
                continue
            }
            overflowPtr++

            if (length != IOS_OVERFLOW_LENGTH) {
                return false
            }

            val idBytes = ByteArray(IOS_OVERFLOW_CUSTOM_ID_LENGTH)
            for (i in 0 until IOS_OVERFLOW_CUSTOM_ID_LENGTH) {
                idBytes[i] = scanData[overflowPtr++]
            }
            if (BigInteger(idBytes) != BigInteger.valueOf(IOS_OVERFLOW_CUSTOM_ID)) {
                return false
            }

            for (i in 0 until IOS_OVERFLOW_CONTENT_LENGTH) {
                val value = scanData[overflowPtr++]
                overflowBytes[outPtr] = value
                outPtr++
            }

            break
        }

        if (outPtr == IOS_OVERFLOW_CONTENT_LENGTH) {
            val overflowBytesValue = BigInteger(overflowBytes)
            // Did the overflow contain the service uuid bit?
            return (overflowBytesValue and serviceUuidMask).signum() == 1
        }
    } catch (e: ArrayIndexOutOfBoundsException) {
        Log.w(TAG, "Inspecting advertisement overflow bytes went out of bounds.")
    }

    return false
}