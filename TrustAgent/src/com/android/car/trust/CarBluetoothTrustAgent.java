/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.car.trust;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.trust.TrustAgentService;
import android.util.Log;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * A simple trust agent that grants trust when a paired bluetooth device is connected.
 */
public class CarBluetoothTrustAgent extends TrustAgentService {
    private static final String TAG = "CarBTTrustAgent";
    private static final long TRUST_DURATION_MS = TimeUnit.MINUTES.toMicros(5);

    private String mTrustGrantedMessage;

    private BluetoothAdapter mBluetoothAdapter;

    // List of paired devices
    private HashMap<String, BluetoothDevice> mBondedDeviceMap = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();

        mTrustGrantedMessage = getString(R.string.trust_granted_explanation);

        registerReceiver(mBtReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));
        registerReceiver(mBtReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));
        registerReceiver(mBtReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        registerReceiver(mBtReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        registerReceiver(mSreenOnOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        registerReceiver(mSreenOnOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));

        setManagingTrust(true);
        mBluetoothAdapter = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE))
                .getAdapter();

        // Bonded/paired devices are only returned if bluetooth is enabled.
        // If not enabled, wait for ACTION_STATE_CHANGED to get bonded/paired devices.
        if (mBluetoothAdapter.isEnabled()) {
            updateBondedDevices();
        }
    }

    @Override
    public void onTrustTimeout() {
        super.onTrustTimeout();
        // If there is still a connected device, we can continue granting trust.
        for (BluetoothDevice device : mBondedDeviceMap.values()) {
            if (device.isConnected()) {
                grantTrust();
                break;
            }
        }
    }

    private final BroadcastReceiver mSreenOnOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "ACTION_SCREEN_ON");
                }
                updateTrustStatus();
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "ACTION_SCREEN_OFF: revoking trust");
                }
                revokeTrust();
            }
        }
    };

    /**
     * The BroadcastReceiver that listens for bluetooth broadcasts.
     */
    private final BroadcastReceiver mBtReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "ACTION_ACL_CONNECTED device: " + device.getName());
                }
                int state = device.getBondState();
                if (state == BluetoothDevice.BOND_BONDED) {
                    mBondedDeviceMap.put(device.getAddress(), device);
                    updateTrustStatus();
                }

            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "ACTION_ACL_DISCONNECTED device: " + device.getName());
                }

                updateTrustStatus();
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "ACTION_BOND_STATE_CHANGED device: " + device.getName());
                }

                int state = device.getBondState();
                if (state == BluetoothDevice.BOND_BONDED) {
                    mBondedDeviceMap.put(device.getAddress(), device);
                    grantTrust();
                } else if (state == BluetoothDevice.BOND_NONE) {
                    mBondedDeviceMap.remove(device.getAddress());
                    updateTrustStatus();
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0);
                // Bluetooth was just turned on.
                if (state == BluetoothAdapter.STATE_ON) {
                    updateBondedDevices();
                }
            }
        }
    };

    private void updateBondedDevices() {
        for (BluetoothDevice d : mBluetoothAdapter.getBondedDevices()) {
            mBondedDeviceMap.put(d.getAddress(), d);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "ACTION_STATE_CHANGED device: " + d.getName());
            }
        }
        updateTrustStatus();
    }

    private void updateTrustStatus() {
        // Nothing is paired, this trust agent should not grant trust
        if (mBondedDeviceMap.size() == 0) {
            revokeTrust();
        }

        boolean deviceConnected = false;
        for (BluetoothDevice device : mBondedDeviceMap.values()) {
            if (device.isConnected()) {
                deviceConnected = true;
                break;
            }
        }
        if (!deviceConnected) {
            revokeTrust();
        } else {
            grantTrust();
        }
    }

    private void grantTrust() {
        try {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Granting Trust");
            }
            grantTrust(mTrustGrantedMessage, TRUST_DURATION_MS,
                    TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD);
        } catch (IllegalStateException e) {
            Log.d(TAG, "IllegalStateException: " + e.getMessage());
        }
    }
}
