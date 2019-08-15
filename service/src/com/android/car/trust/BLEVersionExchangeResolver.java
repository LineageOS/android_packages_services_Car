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

package com.android.car.trust;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.car.BLEStreamProtos.VersionExchangeProto.BLEVersionExchange;

/**
 * Resolver of version exchanges between this device and a client device.
 */
class BLEVersionExchangeResolver {
    private static final String TAG = "BLEVersionExchangeResolver";

    // Currently, only version 1 of the messaging and security supported.
    private static final int MESSAGING_VERSION = 1;
    private static final int SECURITY_VERSION = 1;

    /**
     * Returns a message stream that can be used to send messages to the given
     * {@link BluetoothDevice} based on the version exchange proto.
     *
     * @param versionExchange The version exchange proto to resolve
     * @param device The device to send messages to.
     * @param readCharacteristic The characteristic the device will use to write messages to.
     * @param writeCharacteristic The characteristic on the device that message can be written to.
     * @return A stream that can send message or {@code null} if resolution was not possible.
     */
    @Nullable
    static BleMessageStream resolveToStream(BLEVersionExchange versionExchange,
            BluetoothDevice device, BluetoothGattServer gattServer,
            BluetoothGattCharacteristic writeCharacteristic) {
        int minMessagingVersion = versionExchange.getMinSupportedMessagingVersion();
        int minSecurityVersion = versionExchange.getMinSupportedSecurityVersion();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Checking for supported version on (minMessagingVersion: "
                    + minMessagingVersion + ", minSecurityVersion: " + minSecurityVersion + ")");
        }

        // Only one supported version, so ensure the minimum version matches.
        if (minMessagingVersion == MESSAGING_VERSION && minSecurityVersion == SECURITY_VERSION) {
            return new BleMessageStreamV1(
                new Handler(Looper.getMainLooper()), device, gattServer, writeCharacteristic);
        }

        return null;
    }

    /**
     * Returns a version exchange proto with the maximum and minimum protocol and security versions
     * this device currently supports.
     */
    static BLEVersionExchange makeVersionExchange() {
        return BLEVersionExchange.newBuilder()
                .setMinSupportedMessagingVersion(MESSAGING_VERSION)
                .setMaxSupportedMessagingVersion(MESSAGING_VERSION)
                .setMinSupportedSecurityVersion(SECURITY_VERSION)
                .setMaxSupportedSecurityVersion(SECURITY_VERSION)
                .build();
    }

    private BLEVersionExchangeResolver() {}
}
