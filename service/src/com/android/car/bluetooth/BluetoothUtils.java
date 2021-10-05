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
import android.bluetooth.BluetoothUuid;
import android.os.ParcelUuid;
import android.util.SparseArray;

import java.util.HashMap;

/** Utils for Bluetooth */
public final class BluetoothUtils {
    private BluetoothUtils() {
        throw new UnsupportedOperationException();
    }

    // TODO (201800664): Profile State Change actions are hidden. This is a work around for now
    public static final String A2DP_SINK_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED";
    public static final String HFP_CLIENT_CONNECTION_STATE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED";
    public static final String MAP_CLIENT_CONNECTION_STATE_CHANGED =
            "android.bluetooth.mapmce.profile.action.CONNECTION_STATE_CHANGED";
    public static final String PAN_CONNECTION_STATE_CHANGED =
            "android.bluetooth.pan.profile.action.CONNECTION_STATE_CHANGED";
    public static final String PBAP_CLIENT_CONNECTION_STATE_CHANGED =
            "android.bluetooth.pbapclient.profile.action.CONNECTION_STATE_CHANGED";

    /*
     * Maps of types and status to human readable strings
     */

    private static final SparseArray<String> sAdapterStates = new SparseArray<String>(4);
    private static final SparseArray<String> sBondStates = new SparseArray<String>(3);
    private static final SparseArray<String> sConnectionStates = new SparseArray<String>(4);
    private static final SparseArray<String> sProfileNames = new SparseArray<String>(6);
    private static final HashMap<String, Integer> sProfileActions = new HashMap<String, Integer>(5);
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
        sProfileNames.put(BluetoothProfile.PAN, "PAN");
        sProfileNames.put(BluetoothProfile.A2DP_SINK, "A2DP Sink");
        sProfileNames.put(BluetoothProfile.AVRCP_CONTROLLER, "AVRCP Controller");
        sProfileNames.put(BluetoothProfile.HEADSET_CLIENT, "HFP Client");
        sProfileNames.put(BluetoothProfile.PBAP_CLIENT, "PBAP Client");
        sProfileNames.put(BluetoothProfile.MAP_CLIENT, "MAP Client");

        // Profile actions to ints
        sProfileActions.put(A2DP_SINK_CONNECTION_STATE_CHANGED, BluetoothProfile.A2DP_SINK);
        sProfileActions.put(HFP_CLIENT_CONNECTION_STATE_CHANGED, BluetoothProfile.HEADSET_CLIENT);
        sProfileActions.put(MAP_CLIENT_CONNECTION_STATE_CHANGED, BluetoothProfile.MAP_CLIENT);
        sProfileActions.put(PAN_CONNECTION_STATE_CHANGED, BluetoothProfile.PAN);
        sProfileActions.put(PBAP_CLIENT_CONNECTION_STATE_CHANGED, BluetoothProfile.PBAP_CLIENT);
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

    static int getProfileFromConnectionAction(String action) {
        Integer profile = sProfileActions.get(action);
        return profile != null ? profile.intValue() : -1;
    }

    static boolean isProfileSupported(BluetoothDevice device, int profile) {
        if (device == null) return false;

        ParcelUuid[] uuids = device.getUuids();

        if (uuids == null || uuids.length == 0) {
            return false;
        }
        switch (profile) {
            case BluetoothProfile.A2DP_SINK:
                return BluetoothUuid.containsAnyUuid(uuids,
                        new ParcelUuid[]{BluetoothUuid.ADV_AUDIO_DIST, BluetoothUuid.A2DP_SOURCE});
            case BluetoothProfile.HEADSET_CLIENT:
                return BluetoothUuid.containsAnyUuid(uuids,
                        new ParcelUuid[]{BluetoothUuid.HFP_AG, BluetoothUuid.HSP_AG});
            case BluetoothProfile.MAP_CLIENT:
                return BluetoothUuid.containsAnyUuid(uuids,
                        new ParcelUuid[]{BluetoothUuid.MAS});
            case BluetoothProfile.PAN:
                return BluetoothUuid.containsAnyUuid(uuids,
                        new ParcelUuid[]{BluetoothUuid.PANU});
            case BluetoothProfile.PBAP_CLIENT:
                return BluetoothUuid.containsAnyUuid(uuids,
                        new ParcelUuid[]{BluetoothUuid.PBAP_PSE});
            default:
                return false;
        }
    }
}
