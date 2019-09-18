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
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.android.car.Utils
import com.android.car.guard
import com.android.internal.annotations.GuardedBy
import java.math.BigInteger
import java.util.UUID
import kotlin.concurrent.thread

private const val TAG = "CarBleManager"

// system/bt/internal_include/bt_target.h#GATT_MAX_PHY_CHANNEL
private const val MAX_CONNECTIONS = 7

private val CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Communication manager for a car that maintains continuous connections with all phones in the car
 * for the duration of a drive.
 *
 * @param context Application's [Context].
 * @param serviceUuid [UUID] of peripheral's service.
 * @param bgServiceMask iOS overflow bit mask for service UUID.
 * @param writeCharacteristicUuid [UUID] of characteristic the central will write to.
 * @param readCharacteristicUuid [UUID] of characteristic the peripheral will write to.
 */
internal class CarBleCentralManager(
    private val context: Context,
    private val bleCentralManager: BleCentralManager,
    private val serviceUuid: UUID,
    private val bgServiceMask: String,
    private val writeCharacteristicUuid: UUID,
    private val readCharacteristicUuid: UUID
) {
    @GuardedBy("connectedDevices")
    private val connectedDevices = mutableSetOf<BleDevice>()

    @GuardedBy("ignoredDevices")
    private val ignoredDevices = mutableSetOf<BleDevice>()

    @GuardedBy("callbacks")
    private val callbacks = mutableListOf<Callback>()

    private val parsedBgServiceBitMask by lazy {
        BigInteger(bgServiceMask, 16)
    }

    private val scanSettings by lazy {
        ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()
    }

    /**
     * Start process of finding and connecting to devices.
     */
    fun start() {
        startScanning()
    }

    private fun startScanning() {
        bleCentralManager.startScanning(null, scanSettings, scanCallback)
    }

    /**
     * Stop process and disconnect from any connected devices.
     */
    fun stop() {
        bleCentralManager.stopScanning()
        synchronized(connectedDevices) {
            connectedDevices.forEach { it.gatt.close() }
            connectedDevices.clear()
        }
        synchronized(callbacks) {
            callbacks.clear()
        }
    }

    /**
     * Send a message to a connected device.
     *
     * @param deviceId Id of connected device.
     * @param message [ByteArray] Message to send.
     * @param isEncrypted Whether data should be encrypted prior to sending.
     */
    fun sendMessage(deviceId: String, message: ByteArray, isEncrypted: Boolean) {
        getConnectedDevice(deviceId)?.also {
            sendMessage(it, message, isEncrypted)
        } ?: Log.w(TAG, "Attempted to send messsage to unknown device $deviceId. Ignored")
    }

    private fun sendMessage(bleDevice: BleDevice, message: ByteArray, isEncrypted: Boolean) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Writing ${message.size} bytes to " +
                "${bleDevice.deviceId ?: "Unidentified device"}.")
        }

        if (isEncrypted) {
            sendEncryptedMessage(bleDevice, message)
        } else {
            sendUnencryptedMessage(bleDevice, message)
        }
    }

    private fun sendUnencryptedMessage(bleDevice: BleDevice, message: ByteArray) {
        // TODO (b/139066995) Replace with unsecure channel implementation
        bleDevice.writeCharacteristic?.also {
            it.value = message
            if (!bleDevice.gatt.writeCharacteristic(it)) {
                Log.e(TAG, "Write to ${it.uuid} failed.")
            }
        }
    }

    private fun sendEncryptedMessage(bleDevice: BleDevice, message: ByteArray) {
        // TODO (b/139066995) Implement secure ble channel
    }

    private fun getConnectedDevice(gatt: BluetoothGatt): BleDevice? {
        synchronized(connectedDevices) {
            return connectedDevices.firstOrNull { it.gatt == gatt }
        }
    }

    private fun getConnectedDevice(device: BluetoothDevice): BleDevice? {
        synchronized(connectedDevices) {
            return connectedDevices.firstOrNull { it.device == device }
        }
    }

    private fun getConnectedDevice(deviceId: String): BleDevice? {
        synchronized(connectedDevices) {
            return connectedDevices.firstOrNull { it.deviceId == deviceId }
        }
    }

    private fun addConnectedDevice(bleDevice: BleDevice) {
        synchronized(connectedDevices) {
            connectedDevices.add(bleDevice)
        }
    }

    private fun countConnectedDevices(): Int {
        synchronized(connectedDevices) {
            return connectedDevices.count()
        }
    }

    private fun removeConnectedDevice(bleDevice: BleDevice) {
        synchronized(connectedDevices) {
            connectedDevices.remove(bleDevice)
        }
    }

    private fun ignoreDevice(deviceToIgnore: BleDevice) {
        synchronized(ignoredDevices) {
            ignoredDevices.add(deviceToIgnore)
        }
    }

    private fun isDeviceIgnored(device: BluetoothDevice): Boolean {
        synchronized(ignoredDevices) {
            return ignoredDevices.any { it.device == device }
        }
    }

    private val scanCallback by lazy {
        object : ScanCallback() {
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                // This method should never be called because our scan mode is
                // SCAN_MODE_LOW_LATENCY, meaning we will be notified of individual matches.
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scanning failed with error code: $errorCode")
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (shouldAttemptConnection(result)) {
                    startDeviceConnection(result.device)
                }
            }
        }
    }

    private fun shouldAttemptConnection(result: ScanResult): Boolean {
        // Ignore any results that are not connectable.
        if (!result.isConnectable) {
            return false
        }

        // Do not attempt to connect if we have already hit our max. This should rarely happen
        // and is protecting against a race condition of scanning stopped and new results coming in.
        if (countConnectedDevices() >= MAX_CONNECTIONS) {
            return false
        }

        val device = result.device

        // Check if already attempting to connect to this device.
        if (getConnectedDevice(device) != null) {
            return false
        }

        // Connect to any device that is advertising our service UUID.
        if (result.scanRecord?.serviceUuids?.contains(ParcelUuid(serviceUuid)) == true ||
            result.containsUuidsInOverflow(parsedBgServiceBitMask)) {
            return true
        }

        // Do not connect if device has already been ignored.
        if (isDeviceIgnored(device)) {
            return false
        }

        // Can safely ignore devices advertising unrecognized service uuids.
        if (result.scanRecord?.serviceUuids?.isNotEmpty() == true) {
            return false
        }

        // TODO (b/139066293) Current implementation quickly exhausts connections resulting in
        // greatly reduced performance for connecting to devices we know we want to connect to.
        // Return true once fixed.
        return false
    }

    private val connectionCallback by lazy {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (gatt == null) {
                    Log.w(TAG, "Null gatt passed to onConnectionStateChange. Ignoring.")
                    return
                }
                val bleDevice = getConnectedDevice(gatt) ?: return
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        deviceConnected(bleDevice)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        deviceDisconnected(bleDevice, status)
                    }
                    else -> {
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "Connection state changed: " +
                                BluetoothProfile.getConnectionStateName(newState) +
                                " state: $status")
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (gatt == null) {
                    Log.w(TAG, "Null gatt passed to onServicesDiscovered. Ignoring.")
                    return
                }
                val connectedDevice = getConnectedDevice(gatt) ?: return
                val service = gatt.getService(serviceUuid) ?: run {
                    ignoreDevice(connectedDevice)
                    gatt.disconnect()
                    return
                }
                connectedDevice.state = BleDeviceState.CONNECTED
                val readCharacteristic = service.getCharacteristic(readCharacteristicUuid)
                val writeCharacteristic = service.getCharacteristic(
                    writeCharacteristicUuid)
                if (readCharacteristic == null || writeCharacteristic == null) {
                    Log.w(TAG, "Unable to find expected characteristics on peripheral")
                    gatt.disconnect()
                    return
                }
                connectedDevice.readCharacteristic = readCharacteristic
                connectedDevice.writeCharacteristic = writeCharacteristic

                // Turn on notifications for read characteristic
                val descriptor = readCharacteristic.getDescriptor(CHARACTERISTIC_CONFIG).apply {
                    value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                if (!gatt.writeDescriptor(descriptor)) {
                    Log.e(TAG, "Write descriptor failed!")
                    gatt.disconnect()
                    return
                }

                if (!gatt.setCharacteristicNotification(readCharacteristic, true)) {
                    Log.e(TAG, "Set notifications failed!")
                    gatt.disconnect()
                    return
                }

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Service and characteristics successfully discovered")
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt?,
                descriptor: BluetoothGattDescriptor?,
                status: Int
            ) {
                if (gatt == null) {
                    Log.w(TAG, "Null gatt passed to onDescriptorWrite. Ignoring.")
                    return
                }

                // TODO (b/139067881) Replace with sending unique device id
                getConnectedDevice(gatt)?.guard {
                    sendMessage(it, Utils.uuidToBytes(UUID.randomUUID()), false)
                } ?: Log.w(TAG, "Descriptor callback called for disconnected device")
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?
            ) {
                if (characteristic?.uuid != readCharacteristicUuid) {
                    return
                }
                if (gatt == null) {
                    Log.w(TAG, "Null gatt passed to onCharacteristicChanged. Ignoring.")
                    return
                }

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Change detected on characteristic " +
                        "${characteristic.uuid}")
                }

                val connectedDevice = getConnectedDevice(gatt) ?: return
                val message = characteristic.value

                connectedDevice.deviceId?.also { deviceId ->
                    notifyCallbacks { it.onMessageReceived(deviceId, message) }
                } ?: run {
                    // Device id is the first message expected back from peripheral
                    val deviceId = String(message)
                    connectedDevice.deviceId = deviceId
                    notifyCallbacks { it.onDeviceConnected(deviceId) }
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
            }
        }
    }

    private fun startDeviceConnection(device: BluetoothDevice) {
        val gatt = device.connectGatt(context, false, connectionCallback,
            BluetoothDevice.TRANSPORT_LE) ?: return
        addConnectedDevice(BleDevice(device, gatt).apply { this.state = BleDeviceState.CONNECTING })

        // Stop scanning if we have reached the maximum connections
        if (countConnectedDevices() >= MAX_CONNECTIONS) {
            bleCentralManager.stopScanning()
        }
    }

    private fun deviceConnected(device: BleDevice) {
        device.state = BleDeviceState.PENDING_VERIFICATION

        device.gatt.discoverServices()

        val connectedCount = countConnectedDevices()
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "New device connected: ${device.gatt.device?.address}. " +
                "Active connections: $connectedCount")
        }
    }

    private fun deviceDisconnected(device: BleDevice, status: Int) {
        removeConnectedDevice(device)
        device.gatt.close()
        device.deviceId?.guard { deviceId ->
            notifyCallbacks { it.onDeviceDisconnected(deviceId) }
        }
        val connectedCount = countConnectedDevices()
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Device disconnected: ${device.gatt.device?.address} with " +
                "state $status. Active connections: $connectedCount")
        }

        // Start scanning if dropping down from max
        if (!bleCentralManager.isScanning() && connectedCount < MAX_CONNECTIONS) {
            startScanning()
        }
    }

    /**
     * Register a callback
     *
     * @param callback The [Callback] to register.
     */
    fun registerCallback(callback: Callback) {
        synchronized(callbacks) {
            callbacks.add(callback)
        }
    }

    /**
     * Unregister a callback
     *
     * @param callback The [Callback] to unregister.
     */
    fun unregisterCallback(callback: Callback) {
        synchronized(callbacks) {
            callbacks.remove(callback)
        }
    }

    /**
     * Trigger notification on registered callbacks.
     *
     * @param notification [Callback] notification function.
     */
    private fun notifyCallbacks(notification: (Callback) -> Unit) {
        val callbacksCopy = synchronized(callbacks) { callbacks.toList() }
        callbacksCopy.forEach {
            thread(isDaemon = true) { notification(it) }
        }
    }

    /**
     * Callback for triggered events from [CarBleManager]
     */
    interface Callback {

        /**
         * Triggered when device is connected and device id retrieved. Device is now ready to
         * receive messages.
         *
         * @param deviceId Id of device that has connected.
         */
        fun onDeviceConnected(deviceId: String)

        /**
         * Triggered when device is disconnected.
         *
         * @param deviceId Id of device that has disconnected.
         */
        fun onDeviceDisconnected(deviceId: String)

        /**
         * Triggered when device has established encryption for secure communication.
         *
         * @param deviceId Id of device that has established encryption.
         */
        fun onSecureChannelEstablished(deviceId: String)

        /**
         * Triggered when a new message is detected.
         *
         * @param deviceId Id of the device that sent the message.
         * @param message [ByteArray] Data from message.
         */
        fun onMessageReceived(deviceId: String, message: ByteArray)
    }

    private data class BleDevice(
        val device: BluetoothDevice,
        val gatt: BluetoothGatt
    ) {
        var state: BleDeviceState = BleDeviceState.UNKNOWN
        var deviceId: String? = null
        var writeCharacteristic: BluetoothGattCharacteristic? = null
        var readCharacteristic: BluetoothGattCharacteristic? = null
    }

    private enum class BleDeviceState {
        CONNECTING,
        PENDING_VERIFICATION,
        CONNECTED,
        UNKNOWN
    }
}
