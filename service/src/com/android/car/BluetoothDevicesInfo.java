/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.util.Log;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * BluetoothDevicesInfo contains all the information pertinent to connection on a Bluetooth Profile.
 * It holds
 * 1. a list of devices {@link #mDeviceList} that has previously paired and connected on this
 * profile.
 * 2. a Connection Info object {@link #mConnectionInfo} that has following book keeping information:
 * a) profile
 * b) Current Connection status
 * c) If there are any devices available for connection
 * d) Index of the Device list that a connection is being tried upon currently.
 * e) Number of devices that have been previously paired and connected on this profile.
 * f) How many retry attempts have been made
 *
 * This is used by the {@link BluetoothDeviceConnectionPolicy} to find the device to attempt
 * a connection on for a profile.  The policy also updates this object with the connection
 * results.
 */
public class BluetoothDevicesInfo {
    private final String TAG = "CarBluetoothDevicesInfo";

    private class ConnectionInfo {
        int mProfile;
        boolean mIsConnected;
        boolean mDeviceAvailableToConnect;
        int mDeviceIndex;
        int mNumPairedDevices;
        int mRetryAttempt;

        public ConnectionInfo(int profile) {
            mProfile = profile;
            mIsConnected = false;
            mDeviceAvailableToConnect = true;
            mDeviceIndex = 0;
            mNumPairedDevices = 0;
            mRetryAttempt = 0;
        }
    }

    private List<BluetoothDevice> mDeviceList;
    private ConnectionInfo mConnectionInfo;

    public BluetoothDevicesInfo(int profile) {
        mDeviceList = new ArrayList<BluetoothDevice>();
        mConnectionInfo = new ConnectionInfo(profile);
    }

    /**
     * Get the current list of connectable devices for this profile.
     *
     * @return Device list for this profile.
     */
    public List<BluetoothDevice> getDeviceList() {
        return mDeviceList;
    }

    /**
     * Add a device to the device list.  Used during pairing.
     *
     * @param dev - device to add for further connection attempts on this profile.
     */
    public void addDeviceLocked(BluetoothDevice dev) {
        if (mDeviceList != null) {
            mDeviceList.add(dev);
        } else {
            Log.d(TAG, "Device List is null");
        }
        if (mConnectionInfo != null) {
            mConnectionInfo.mNumPairedDevices++;
        } else {
            Log.d(TAG, "ConnectionInfo is null");
        }
    }

    /**
     * Remove a device from the list.  Used when a device is unpaired
     *
     * @param dev - device to remove from the list.
     */
    public void removeDeviceLocked(BluetoothDevice dev) {
        if (mDeviceList != null) {
            mDeviceList.remove(dev);
        }
    }


    /**
     * Returns the next device to attempt a connection on for this profile.
     *
     * @return {@link BluetoothDevice} that is next in the Queue.
     * null if the Queue has been exhausted (no known device nearby)
     */
    public BluetoothDevice getNextDeviceInQueueLocked() {
        Log.d(TAG, "Getting device " + mConnectionInfo.mDeviceIndex + " from list");
        if (mConnectionInfo.mDeviceIndex >= mConnectionInfo.mNumPairedDevices) {
            Log.d(TAG,
                    "No device available for connection for profile " + mConnectionInfo.mProfile);
            mConnectionInfo.mDeviceIndex = 0; //reset the index
            return null;
        }
        return mDeviceList.get(mConnectionInfo.mDeviceIndex);
    }

    /**
     * Update the connection Status for connection attempts made on this profile.
     * If the attempt was successful, mark it and keep track of the device that was connected.
     * If unsuccessful, check if we can retry on the same device. If no more retry attempts,
     * move to the next device in the Queue.
     *
     * @param success - connection result
     * @param retry   - If Retries are available for the same device.
     */
    public void updateConnectionStatusLocked(boolean success, boolean retry) {
        mConnectionInfo.mIsConnected = success;
        if (success) {
            Log.d(TAG, mConnectionInfo.mProfile + " connected.");
            // b/34722344 - refactor this to address the comments below.
            // swap the devices based on which device connected if needed
            // Swapping the connected device to the top of the slot (0).
            // But 0 won't be appropriate if there multiple connections is possible
            // or the first few slots are fixed for specific device(s) (Primary/Secondary etc)
            if (mConnectionInfo.mDeviceIndex != 0) {
                Collections.swap(mDeviceList, mConnectionInfo.mDeviceIndex, 0);
                mConnectionInfo.mDeviceIndex = 0;
            }
            // Reset the retry count;
            mConnectionInfo.mRetryAttempt = 0;
        } else {
            // if no more retries, move to the next device
            Log.d(TAG, "Connection fail or Disconnected");
            if (!retry) {
                mConnectionInfo.mDeviceIndex++;
                Log.d(TAG, "Moving to device: " + mConnectionInfo.mDeviceIndex);
            } else {
                Log.d(TAG,
                        "Staying with the same device - retrying: " + mConnectionInfo.mDeviceIndex);
            }
        }
    }

    /**
     * Returns the profile that this devicesInfo is for.
     *
     */
    public Integer getProfileLocked() {
        return mConnectionInfo.mProfile;
    }

    /**
     * Return if the profile is currently connected.
     *
     * @return true if connected, false if not.
     */
    public boolean isConnectedLocked() {
        return mConnectionInfo.mIsConnected;
    }

    /**
     * Get the number of devices in the {@link #mDeviceList} - paired and previously connected
     * devices
     *
     * @return number of paired devices on this profile.
     */
    public Integer getNumberOfPairedDevicesLocked() {
        return mConnectionInfo.mNumPairedDevices;
    }

    /**
     * Increment the retry count. Called when a connection is made on the profile.
     */
    public void incrementRetryCountLocked() {
        mConnectionInfo.mRetryAttempt++;
    }

    /**
     * Get the number of times a connection attempt has been tried on a device for this profile.
     *
     * @return number of retry attempts.
     */
    public Integer getRetryCountLocked() {
        return mConnectionInfo.mRetryAttempt;
    }

    /**
     * Check if we have iterated through all the devices of the {@link #mDeviceList}
     *
     * @return true if there are still devices to try connecting
     * false if we have unsuccessfully tried connecting to all the devices in the {@link
     * #mDeviceList}
     */
    public boolean isDeviceAvailableToConnectLocked() {
        return mConnectionInfo.mDeviceAvailableToConnect;
    }

    /**
     * Set the mDeviceAvailableToConnect with the passed value.
     *
     * @param deviceAvailable - true or false.
     */
    public void setDeviceAvailableToConnectLocked(boolean deviceAvailable) {
        mConnectionInfo.mDeviceAvailableToConnect = deviceAvailable;
    }

    /**
     * Reset the connection related bookkeeping information.
     * Called on a BluetoothAdapter Off to clean slate
     */
    public void resetConnectionInfoLocked() {
        mConnectionInfo.mIsConnected = false;
        mConnectionInfo.mDeviceIndex = 0;
        mConnectionInfo.mRetryAttempt = 0;
        mConnectionInfo.mDeviceAvailableToConnect = true;
    }

}
