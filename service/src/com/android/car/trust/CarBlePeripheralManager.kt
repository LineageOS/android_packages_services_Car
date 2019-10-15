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
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.car.encryptionrunner.Key
import android.os.ParcelUuid
import android.util.Log
import com.android.car.guard
import java.util.UUID

private const val TAG = "CarBlePeripheralManager"

// Attribute protocol bytes attached to message. Available write size is MTU size minus att bytes.
private const val ATT_PROTOCOL_BYTES = 3

/**
 * Communication manager that allows for targeted connections to a specific device in the car.
 *
 * @param blePeripheralManager [BlePeripheralManager] for establishing connection.
 * @param carCompanionDeviceStorage Shared [CarCompanionDeviceStorage] for companion features.
 * @param writeCharacteristicUuid [UUID] of characteristic the car will write to.
 * @param readCharacteristicUuid [UUID] of characteristic the device will write to.
 */
internal class CarBlePeripheralManager(
    private val blePeripheralManager: BlePeripheralManager,
    carCompanionDeviceStorage: CarCompanionDeviceStorage,
    writeCharacteristicUuid: UUID,
    readCharacteristicUuid: UUID
) : CarBleManager(carCompanionDeviceStorage), BlePeripheralManager.Callback {

    private val connectedDevice get() = connectedDevices.firstOrNull()

    private val writeCharacteristic by lazy {
        BluetoothGattCharacteristic(writeCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ)
    }

    private val readCharacteristic by lazy {
        BluetoothGattCharacteristic(readCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE
            or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
    }

    private val secureChannelCallback by lazy {
        object : SecureBleChannel.Callback {
            override fun onSecureChannelEstablished(encryptionKey: Key) {
                connectedDevice?.deviceId?.guard {
                    deviceId -> notifyCallbacks { it.onSecureChannelEstablished(deviceId) }
                } ?: disconnectWithError("Null device id found when secure channel established.")
            }

            override fun onMessageReceived(deviceMessage: DeviceMessage) {
                connectedDevice?.deviceId?.guard {
                    deviceId -> notifyCallbacks {
                        it.onMessageReceived(deviceId, deviceMessage)
                    }
                } ?: disconnectWithError("Null device id found when message received.")
            }

            override fun onMessageReceivedError(exception: Exception) {
                stop()
            }

            override fun onEstablishSecureChannelFailure() {
                // Ignore?
            }

            override fun onDeviceIdReceived(deviceId: String) {
                connectedDevice?.guard {
                    it.deviceId = deviceId
                    notifyCallbacks { callback -> callback.onDeviceConnected(deviceId) }
                } ?: disconnectWithError("Null connected device found when device id received.")
            }
        }
    }

    private var writeSize = 20

    override fun stop() {
        super.stop()
        blePeripheralManager.cleanup()
    }

    /** Connect to device with id of [deviceId]. */
    fun connectToDevice(deviceId: UUID) {
        val gattService = BluetoothGattService(deviceId, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(deviceId))
            .build()
        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                super.onStartSuccess(settingsInEffect)
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Successfully started advertising for device $deviceId.")
                }
            }
        }
        blePeripheralManager.startAdvertising(gattService, advertiseData, advertiseCallback)
    }

    override fun onDeviceNameRetrieved(deviceName: String?) {
        // Ignored
    }

    override fun onMtuSizeChanged(size: Int) {
        writeSize = size - ATT_PROTOCOL_BYTES
        connectedDevice?.guard {
            it.secureChannel?.stream?.maxWriteSize = writeSize
            it.unsecureChannel?.maxWriteSize = writeSize
        }
    }

    override fun onRemoteDeviceConnected(device: BluetoothDevice) {
        val bleMessageStream = BleDeviceMessageStream(blePeripheralManager, device,
            writeCharacteristic, readCharacteristic).apply {
            maxWriteSize = writeSize
        }
        addConnectedDevice(BleDevice(device, null).apply {
            secureChannel = SecureBleChannel(bleMessageStream, carCompanionDeviceStorage,
                secureChannelCallback)
        })
    }

    override fun onRemoteDeviceDisconnected(device: BluetoothDevice) {
        getConnectedDevice(device)?.deviceId?.guard {
            deviceId -> notifyCallbacks { it.onDeviceDisconnected(deviceId) }
        }
    }

    private fun disconnectWithError(error: String) {
        Log.e(TAG, error)
        blePeripheralManager.cleanup()
    }
}
