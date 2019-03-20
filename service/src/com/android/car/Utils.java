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
package com.android.car;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

import java.nio.ByteBuffer;

/**
 * Some potentially useful static methods.
 */
public class Utils {
    static final Boolean DBG = false;

    static String getDeviceDebugInfo(BluetoothDevice device) {
        if (device == null) {
            return "(null)";
        }
        return "(name = " + device.getName() + ", addr = " + device.getAddress() + ")";
    }

    static String getProfileName(int profile) {
        switch (profile) {
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
            case BluetoothProfile.PAN:
                return "PAN";
            default:
                return profile + "";

        }
    }

    /**
     * An utility class to dump transition events across different car service components.
     * The output will be of the form
     * <p>
     * "Time <svc name>: [optional context information] changed from <from state> to <to state>"
     * This can be used in conjunction with the dump() method to dump this information through
     * adb shell dumpsys activity service com.android.car
     * <p>
     * A specific service in CarService can choose to use a circular buffer of N records to keep
     * track of the last N transitions.
     */
    public static class TransitionLog {
        private String mServiceName; // name of the service or tag
        private int mFromState; // old state
        private int mToState; // new state
        private long mTimestampMs; // System.currentTimeMillis()
        private String mExtra; // Additional information as a String

        public TransitionLog(String name, int fromState, int toState, long timestamp,
                String extra) {
            this(name, fromState, toState, timestamp);
            mExtra = extra;
        }

        public TransitionLog(String name, int fromState, int toState, long timeStamp) {
            mServiceName = name;
            mFromState = fromState;
            mToState = toState;
            mTimestampMs = timeStamp;
        }

        private CharSequence timeToLog(long timestamp) {
            return android.text.format.DateFormat.format("MM-dd HH:mm:ss", timestamp);
        }

        @Override
        public String toString() {
            return timeToLog(mTimestampMs) + " " + mServiceName + ": " + (mExtra != null ? mExtra
                    : "") + " changed from " + mFromState + " to " + mToState;
        }
    }

    /**
     * Returns a byte buffer corresponding to the passed long argument.
     *
     * @param primitive data to convert format.
     */
    public static byte[] longToBytes(long primitive) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(primitive);
        return buffer.array();
    }

    /**
     * Returns a byte buffer corresponding to the passed long argument.
     *
     * @param array data to convert format.
     */
    public static long bytesToLong(byte[] array) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.SIZE / Byte.SIZE);
        buffer.put(array);
        buffer.flip();
        long value = buffer.getLong();
        return value;
    }

    /**
     * Returns a String in Hex format that is formed from the bytes in the byte array
     * Useful for debugging
     *
     * @param array the byte array
     * @return the Hex string version of the input byte array
     */
    public static String byteArrayToHexString(byte[] array) {
        StringBuilder sb = new StringBuilder(array.length * 2);
        for (byte b : array) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
