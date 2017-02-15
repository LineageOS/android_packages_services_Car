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
    private static final String TAG = "CarBluetoothDevicesInfo";
    private static final boolean DBG = false;
    private final int DEVICE_NOT_FOUND = -1;

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
     * Get the position of the given device in the list of connectable deviecs for this profile.
     *
     * @param device - {@link BluetoothDevice}
     * @return postion in the {@link #mDeviceList}, DEVICE_NOT_FOUND if the device is not in the
     * list.
     */
    private int getPositionInList(BluetoothDevice device) {
        int index = DEVICE_NOT_FOUND;
        if (mDeviceList != null) {
            int i = 0;
            for (BluetoothDevice dev : mDeviceList) {
                if (dev.getName().equals(device.getName())) {
                    index = i;
                    break;
                }
                i++;
            }
        }
        return index;
    }

    /**
     * Check if the given device is in the {@link #mDeviceList}
     *
     * @param device - {@link BluetoothDevice} to look for
     * @return true if found, false if not found
     */
    private boolean checkDeviceInList(BluetoothDevice device) {
        boolean isPresent = false;
        if (device == null) {
            return isPresent;
        }
        for (BluetoothDevice dev : mDeviceList) {
            if (dev.getAddress().equals(device.getAddress())) {
                isPresent = true;
                break;
            }
        }
        return isPresent;
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
        if (checkDeviceInList(dev)) {
            if (DBG) {
                Log.d(TAG, "Device " + dev.getName() + "already in list.  Not adding");
            }
            return;
        }
        if (mDeviceList != null) {
            mDeviceList.add(dev);
        } else {
            if (DBG) {
                Log.d(TAG, "Device List is null");
            }
        }
        if (mConnectionInfo != null) {
            mConnectionInfo.mNumPairedDevices++;
        } else {
            if (DBG) {
                Log.d(TAG, "ConnectionInfo is null");
            }
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
        } else {
            if (DBG) {
                Log.d(TAG, "Device List is null");
            }
        }
        if (mConnectionInfo != null) {
            mConnectionInfo.mNumPairedDevices--;
        } else {
            if (DBG) {
                Log.d(TAG, "ConnectionInfo is null");
            }
        }
    }


    /**
     * Returns the next device to attempt a connection on for this profile.
     *
     * @return {@link BluetoothDevice} that is next in the Queue.
     * null if the Queue has been exhausted (no known device nearby)
     */
    public BluetoothDevice getNextDeviceInQueueLocked() {
        if (DBG) {
            Log.d(TAG, "Getting device " + mConnectionInfo.mDeviceIndex + " from list");
        }
        if (mConnectionInfo.mDeviceIndex >= mConnectionInfo.mNumPairedDevices) {
            if (DBG) {
                Log.d(TAG,
                        "No device available for profile "
                                + mConnectionInfo.mProfile + " "
                                + mConnectionInfo.mNumPairedDevices);
            }
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
     * @param device  - {@link BluetoothDevice} that connected.
     * @param success - connection result
     * @param retry   - If Retries are available for the same device.
     */
    public void updateConnectionStatusLocked(BluetoothDevice device, boolean success,
            boolean retry) {
        if (device == null) {
            Log.w(TAG, "Updating Status with null BluetoothDevice");
            return;
        }
        mConnectionInfo.mIsConnected = success;
        if (success) {
            if (DBG) {
                Log.d(TAG, mConnectionInfo.mProfile + " connected to " + device.getName());
            }
            // b/34722344 - TODO
            // Get the position of this device in the device list maintained for this profile.
            int positionInQ = getPositionInList(device);
            if (DBG) {
                Log.d(TAG, "Position of " + device.getName() + " in Q: " + positionInQ);
            }
            // If the device that connected is not in the list, it could be because it is being
            // paired and getting added to the device list for this profile for the first time.
            if (positionInQ == DEVICE_NOT_FOUND) {
                Log.d(TAG, "Connected device not in Q: " + device.getName());
                addDeviceLocked(device);
                positionInQ = mDeviceList.size() - 1;
            } else if (positionInQ != mConnectionInfo.mDeviceIndex) {
                // This will happen if auto-connect request a connect on a device from its list,
                // but the device that connected was different.  Maybe there was another requestor
                // and the Bluetooth services chose to honor the other request.  What we do here,
                // is to make sure we note which device connected and not assume that the device
                // that connected is the device we requested.  The ultimate goal of the policy is
                // to remember which devices connected on which profile (regardless of the origin
                // of the connection request) so it knows which device to connect the next time.
                if (DBG) {
                    Log.d(TAG, "Different device connected: " + device.getName());
                }
            }

            // At this point positionInQ reflects where in the list the device that connected is,
            // i.e, its index.  Move the device to the front of the device list, since the policy is
            // to try to connect to the last connected device first.  Hence by moving the device
            // to the front of the list, the next time auto connect triggers, this will be the
            // device that the policy will try to connect on for this profile.
            if (positionInQ != 0) {
                moveToFrontLocked(mDeviceList, positionInQ);
                // reset the device Index back to the first in the Queue
                mConnectionInfo.mDeviceIndex = 0;
            }
            // Reset the retry count
            mConnectionInfo.mRetryAttempt = 0;
        } else {
            // if no more retries, move to the next device
            if (DBG) {
                Log.d(TAG, "Connection fail or Disconnected");
            }
            if (!retry) {
                mConnectionInfo.mDeviceIndex++;
                if (DBG) {
                    Log.d(TAG, "Moving to device: " + mConnectionInfo.mDeviceIndex);
                }
                // Reset the retry count
                mConnectionInfo.mRetryAttempt = 0;
            } else {
                if (DBG) {
                    Log.d(TAG, "Staying with the same device - retrying: "
                            + mConnectionInfo.mDeviceIndex);
                }
            }
        }
    }

    /**
     * Move the item in the given position to the front of the list and push the rest down.
     *
     * @param deviceList - list of bluetooth devices to operate on
     * @param position   - position of the device to move from
     */
    private void moveToFrontLocked(List<BluetoothDevice> deviceList, int position) {
        BluetoothDevice deviceToMove = deviceList.get(position);
        if (deviceToMove == null) {
            if (DBG) {
                Log.d(TAG, "Unexpected: deviceToMove is null");
            }
            return;
        }
        deviceList.remove(position);
        deviceList.add(0, deviceToMove);

    }

    /**
     * Returns the profile that this devicesInfo is for.
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
        if (mConnectionInfo != null) {
            mConnectionInfo.mRetryAttempt++;
        }
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

    public void resetDeviceListLocked() {
        if (mDeviceList != null) {
            mDeviceList.clear();
            mConnectionInfo.mNumPairedDevices = 0;
        }
        resetConnectionInfoLocked();
    }

}
