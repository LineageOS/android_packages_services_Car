package com.android.car;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

/**
 * Some potentially useful static methods.
 */
public class Utils {
    static final Boolean DBG = false;

    static String getDeviceDebugInfo(BluetoothDevice device) {
        return "(name = " + device.getName() + ", addr = " + device.getAddress() + ")";
    }

    static String getProfileName(int profile) {
        switch(profile) {
            case BluetoothProfile.A2DP_SINK:
                return "A2DP_SINK";
            case BluetoothProfile.HEADSET_CLIENT:
                return "HFP";
            case BluetoothProfile.PBAP_CLIENT:
                return "PBAP";
            case BluetoothProfile.MAP_CLIENT:
                return "MAP";
            case BluetoothProfile.AVRCP_CONTROLLER:
                return "AVRCP";
            default:
                return profile + "";

        }
    }
}
