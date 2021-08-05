/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.util.SparseArray;

/** Utils for Bluetooth */
public final class BluetoothUtils {
    private BluetoothUtils() {
        throw new UnsupportedOperationException();
    }

    /*
     * Maps of types and status to human readable strings
     */

    private static final SparseArray<String> sAdapterStates = new SparseArray<String>();
    private static final SparseArray<String> sBondStates = new SparseArray<String>();
    private static final SparseArray<String> sConnectionStates = new SparseArray<String>();
    private static final SparseArray<String> sProfileNames = new SparseArray<String>();
    static {
        // Bluetooth Adapter states
        sAdapterStates.put(BluetoothAdapter.STATE_ON, "On");
        sAdapterStates.put(BluetoothAdapter.STATE_OFF, "Off");
        sAdapterStates.put(BluetoothAdapter.STATE_TURNING_ON, "Turning On");
        sAdapterStates.put(BluetoothAdapter.STATE_TURNING_OFF, "Turning Off");

        // Device Bonding states
        sBondStates.put(BluetoothDevice.BOND_BONDED, "Bonded");
        sBondStates.put(BluetoothDevice.BOND_BONDING, "Bonding");
        sBondStates.put(BluetoothDevice.BOND_NONE, "Unbonded");

        // Device and Profile Connection states
        sConnectionStates.put(BluetoothAdapter.STATE_CONNECTED, "Connected");
        sConnectionStates.put(BluetoothAdapter.STATE_DISCONNECTED, "Disconnected");
        sConnectionStates.put(BluetoothAdapter.STATE_CONNECTING, "Connecting");
        sConnectionStates.put(BluetoothAdapter.STATE_DISCONNECTING, "Disconnecting");

        // Profile Names
        sProfileNames.put(BluetoothProfile.HEADSET, "HFP Server");
        sProfileNames.put(BluetoothProfile.A2DP, "A2DP Source");
        sProfileNames.put(BluetoothProfile.HEALTH, "HDP");
        sProfileNames.put(BluetoothProfile.HID_HOST, "HID Host");
        sProfileNames.put(BluetoothProfile.PAN, "PAN");
        sProfileNames.put(BluetoothProfile.PBAP, "PBAP Server");
        sProfileNames.put(BluetoothProfile.GATT, "GATT Client");
        sProfileNames.put(BluetoothProfile.GATT_SERVER, "GATT Server");
        sProfileNames.put(BluetoothProfile.MAP, "MAP Server");
        sProfileNames.put(BluetoothProfile.SAP, "SAP");
        sProfileNames.put(BluetoothProfile.A2DP_SINK, "A2DP Sink");
        sProfileNames.put(BluetoothProfile.AVRCP_CONTROLLER, "AVRCP Controller");
        sProfileNames.put(BluetoothProfile.AVRCP, "AVRCP Target");
        sProfileNames.put(BluetoothProfile.HEADSET_CLIENT, "HFP Client");
        sProfileNames.put(BluetoothProfile.PBAP_CLIENT, "PBAP Client");
        sProfileNames.put(BluetoothProfile.MAP_CLIENT, "MAP Client");
        sProfileNames.put(BluetoothProfile.HID_DEVICE, "HID Device");
        sProfileNames.put(BluetoothProfile.OPP, "OPP");
        sProfileNames.put(BluetoothProfile.HEARING_AID, "Hearing Aid");
    }

    static String getDeviceDebugInfo(BluetoothDevice device) {
        if (device == null) {
            return "(null)";
        }
        return "(name = " + device.getName() + ", addr = " + device.getAddress() + ")";
    }

    static String getProfileName(int profile) {
        String name = sProfileNames.get(profile, "Unknown");
        return "(" + profile + ") " + name;
    }

    static String getConnectionStateName(int state) {
        String name = sConnectionStates.get(state, "Unknown");
        return "(" + state + ") " + name;
    }

    static String getBondStateName(int state) {
        String name = sBondStates.get(state, "Unknown");
        return "(" + state + ") " + name;
    }

    static String getAdapterStateName(int state) {
        String name = sAdapterStates.get(state, "Unknown");
        return "(" + state + ") " + name;
    }

    static String getConnectionPolicyName(int priority) {
        String name = "";
        switch (priority) {
            case BluetoothProfile.CONNECTION_POLICY_ALLOWED:
                name = "CONNECTION_POLICY_ALLOWED";
                break;
            case BluetoothProfile.CONNECTION_POLICY_FORBIDDEN:
                name = "CONNECTION_POLICY_FORBIDDEN";
                break;
            case BluetoothProfile.CONNECTION_POLICY_UNKNOWN:
                name = "CONNECTION_POLICY_UNKNOWN";
                break;
            default:
                name = "Unknown";
                break;
        }
        return "(" + priority + ") " + name;
    }
}
