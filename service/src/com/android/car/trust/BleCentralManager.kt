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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.util.Log
import com.android.car.guard
import com.android.internal.annotations.GuardedBy

private const val TAG = "BleCentralManager"

private const val RETRY_LIMIT = 5
private const val RETRY_INTERVAL_MS = 1000

/**
 * Class that manages BLE scanning operations.
 */
open class BleCentralManager(val context: Context) {

    private var scanFilters: List<ScanFilter>? = null
    private var scanSettings: ScanSettings? = null
    private var scanCallback: ScanCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var scannerStartCount = 0
    private val handler = Handler()

    // Internally track scanner state to avoid restarting a stopped scanner
    private enum class ScannerState {
        STOPPED,
        STARTED,
        SCANNING
    }

    @GuardedBy("this")
    @Volatile
    private var scannerState = ScannerState.STOPPED

    /**
     * Start the BLE scanning process.
     *
     * @param filters Optional [ScanFilter]s to apply to scan results.
     * @param settings [ScanSettings] to apply to scanner.
     * @param callback [ScanCallback] for scan events.
     */
    fun startScanning(
        filters: List<ScanFilter>?,
        settings: ScanSettings,
        callback: ScanCallback
    ) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(TAG, "Attempted start scanning, but system does not support BLE. Ignoring")
            return
        }

        scannerStartCount = 0
        scanFilters = filters
        scanSettings = settings
        scanCallback = callback
        updateScannerState(ScannerState.STARTED)
        startScanningInternally()
    }

    private fun startScanningInternally() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Attempting to start scanning")
        }
        if (scanner == null && BluetoothAdapter.getDefaultAdapter() != null) {
            scanner = BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner
        }
        scanner?.guard {
            it.startScan(scanFilters, scanSettings, internalScanCallback)
            updateScannerState(ScannerState.SCANNING)
        } ?: run {
            // Keep trying
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Scanner unavailable. Trying again.")
            }
            handler.postDelayed({ startScanningInternally() }, RETRY_INTERVAL_MS.toLong())
        }
    }

    /** Stop the scanner */
    fun stopScanning() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Attempting to stop scanning")
        }
        scanner?.stopScan(internalScanCallback)
        scanCallback = null
        updateScannerState(ScannerState.STOPPED)
    }

    private fun updateScannerState(newState: ScannerState) {
        synchronized(this) {
            scannerState = newState
        }
    }

    /** Returns [true] if currently scanning. */
    fun isScanning(): Boolean {
        synchronized(this) {
            return scannerState === ScannerState.SCANNING
        }
    }

    /** Clean up the scanning process. */
    fun cleanup() {
        scanner?.cleanup()
    }

    private val internalScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanCallback?.onScanResult(callbackType, result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Batch scan found " + results.size + " results.")
            }
            scanCallback?.onBatchScanResults(results)
        }

        override fun onScanFailed(errorCode: Int) {
            if (scannerStartCount >= RETRY_LIMIT) {
                Log.e(TAG, "Cannot start BLE Scanner. BT Adapter: " +
                    "${BluetoothAdapter.getDefaultAdapter()} Scanning Retry count: " +
                    scannerStartCount)
                scanCallback?.onScanFailed(errorCode)
                return
            }
            scannerStartCount++
            Log.w(TAG, "BLE Scanner failed to start. Error: $errorCode Retry: $scannerStartCount")
            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> { } // Do nothing
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED, SCAN_FAILED_INTERNAL_ERROR -> {
                    handler.postDelayed({ this@BleCentralManager.startScanningInternally() },
                        RETRY_INTERVAL_MS.toLong())
                }
                else -> { } // Ignore other codes
            }
        }
    }
}