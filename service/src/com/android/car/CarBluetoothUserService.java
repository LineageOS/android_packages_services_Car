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

import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothMapClient;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothPbapClient;
import android.bluetooth.BluetoothProfile;
import android.car.ICarBluetoothUserService;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

public class CarBluetoothUserService extends ICarBluetoothUserService.Stub {
    private static final boolean DBG = true;
    private static final String TAG = "CarBluetoothUsrSvc";
    private BluetoothAdapter mBluetoothAdapter = null;
    private final PerUserCarService mService;
    // Profile Proxies.
    private BluetoothA2dpSink mBluetoothA2dpSink = null;
    private BluetoothHeadsetClient mBluetoothHeadsetClient = null;
    private BluetoothPbapClient mBluetoothPbapClient = null;
    private BluetoothMapClient mBluetoothMapClient = null;
    private BluetoothPan mBluetoothPan = null;
    private List<Integer> mProfilesToConnect;

    public CarBluetoothUserService(PerUserCarService service) {
        mService = service;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mProfilesToConnect = Arrays.asList(
                BluetoothProfile.HEADSET_CLIENT,
                BluetoothProfile.PBAP_CLIENT,
                BluetoothProfile.A2DP_SINK,
                BluetoothProfile.MAP_CLIENT,
                BluetoothProfile.PAN);
    }

    /**
     * Setup connections to the profile proxy object to talk to the Bluetooth profile services
     */
    @Override
    public void setupBluetoothConnectionProxy() {
        if (DBG) {
            Log.d(TAG, "setupProfileProxy()");
        }
        if (mBluetoothAdapter == null) {
            Log.d(TAG, "Null BT Adapter");
            return;
        }
        for (Integer profile : mProfilesToConnect) {
            mBluetoothAdapter.getProfileProxy(mService.getApplicationContext(), mProfileListener,
                    profile);
        }
    }

    /**
     * Close connections to the profile proxy object
     */
    @Override
    public void closeBluetoothConnectionProxy() {
        if (mBluetoothAdapter == null) {
            return;
        }
        if (DBG) {
            Log.d(TAG, "closeProfileProxy()");
        }
        // Close those profile proxy objects for profiles that have not yet disconnected
        if (mBluetoothA2dpSink != null) {
            mBluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP_SINK, mBluetoothA2dpSink);
        }
        if (mBluetoothHeadsetClient != null) {
            mBluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET_CLIENT,
                    mBluetoothHeadsetClient);
        }
        if (mBluetoothPbapClient != null) {
            mBluetoothAdapter.closeProfileProxy(BluetoothProfile.PBAP_CLIENT, mBluetoothPbapClient);
        }
        if (mBluetoothMapClient != null) {
            mBluetoothAdapter.closeProfileProxy(BluetoothProfile.MAP_CLIENT, mBluetoothMapClient);
        }
        if (mBluetoothPan != null) {
            mBluetoothAdapter.closeProfileProxy(BluetoothProfile.PAN, mBluetoothPan);
        }
    }

    /**
     * Check if a proxy is available for the given profile to talk to the Profile's bluetooth
     * service.
     * @param profile - Bluetooth profile to check for
     * @return - true if proxy available, false if not.
     */
    @Override
    public boolean isBluetoothConnectionProxyAvailable(int profile) {
        switch (profile) {
            case BluetoothProfile.A2DP_SINK:
                if (mBluetoothA2dpSink != null) {
                    return true;
                }
                break;
            case BluetoothProfile.HEADSET_CLIENT:
                if (mBluetoothHeadsetClient != null) {
                    return true;
                }
                break;
            case BluetoothProfile.PBAP_CLIENT:
                if (mBluetoothPbapClient != null) {
                    return true;
                }
                break;
            case BluetoothProfile.MAP_CLIENT:
                if (mBluetoothMapClient != null) {
                    return true;
                }
                break;
            case BluetoothProfile.PAN:
                if (mBluetoothPan != null) {
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public void bluetoothConnectToProfile(int profile, BluetoothDevice device) {
        if (!isBluetoothConnectionProxyAvailable(profile)) {
            Log.e(TAG, "Cannot connect to Profile. Proxy Unavailable");
            return;
        }
        if (device == null) {
            Log.e(TAG, "Cannot connect to profile on null device");
            return;
        }
        if (DBG) {
            Log.d(TAG, "Trying to connect to " + device.getName() + " (" + device.getAddress()
                    + ") Profile: " + Utils.getProfileName(profile));
        }
        switch (profile) {
            case BluetoothProfile.A2DP_SINK:
                mBluetoothA2dpSink.connect(device);
                break;

            case BluetoothProfile.HEADSET_CLIENT:
                mBluetoothHeadsetClient.connect(device);
                break;

            case BluetoothProfile.MAP_CLIENT:
                mBluetoothMapClient.connect(device);
                break;

            case BluetoothProfile.PBAP_CLIENT:
                mBluetoothPbapClient.connect(device);
                break;

            case BluetoothProfile.PAN:
                mBluetoothPan.connect(device);
                break;

            default:
                Log.d(TAG, "Unknown profile");
                break;
        }
    }

    @Override
    public void bluetoothDisconnectFromProfile(int profile, BluetoothDevice device) {
        if (!isBluetoothConnectionProxyAvailable(profile)) {
            Log.e(TAG, "Cannot disconnect from profile. Proxy Unavailable");
            return;
        }
        if (device == null) {
            Log.e(TAG, "Cannot disconnect from profile on null device");
            return;
        }
        if (DBG) {
            Log.d(TAG, "Trying to disconnect from " + device.getName() + " (" + device.getAddress()
                    + ") Profile: " + Utils.getProfileName(profile));
        }
        switch (profile) {
            case BluetoothProfile.A2DP_SINK:
                mBluetoothA2dpSink.disconnect(device);
                break;

            case BluetoothProfile.HEADSET_CLIENT:
                mBluetoothHeadsetClient.disconnect(device);
                break;

            case BluetoothProfile.MAP_CLIENT:
                mBluetoothMapClient.disconnect(device);
                break;

            case BluetoothProfile.PBAP_CLIENT:
                mBluetoothPbapClient.disconnect(device);
                break;

            case BluetoothProfile.PAN:
                mBluetoothPan.disconnect(device);
                break;

            default:
                Log.d(TAG, "Unknown profile");
                break;
        }
    }

    /**
     * Get the priority of the given Bluetooth profile for the given remote device
     * @param profile - Bluetooth profile
     * @param device - remote Bluetooth device
     */
    @Override
    public int getProfilePriority(int profile, BluetoothDevice device) {
        if (!isBluetoothConnectionProxyAvailable(profile)) {
            Log.e(TAG, "Cannot get profile priority. Proxy Unavailable");
            return BluetoothProfile.PRIORITY_UNDEFINED;
        }
        if (device == null) {
            Log.e(TAG, "Cannot get profile priority on null device");
            return BluetoothProfile.PRIORITY_UNDEFINED;
        }
        int priority;
        switch (profile) {
            case BluetoothProfile.A2DP_SINK:
                priority = mBluetoothA2dpSink.getPriority(device);
                break;
            case BluetoothProfile.HEADSET_CLIENT:
                priority = mBluetoothHeadsetClient.getPriority(device);
                break;
            case BluetoothProfile.MAP_CLIENT:
                priority = mBluetoothMapClient.getPriority(device);
                break;
            case BluetoothProfile.PBAP_CLIENT:
                priority = mBluetoothPbapClient.getPriority(device);
                break;
            default:
                Log.d(TAG, "Unknown Profile");
                return BluetoothProfile.PRIORITY_UNDEFINED;
        }
        if (DBG) {
            Log.d(TAG, Utils.getProfileName(profile) + " priority for " + device.getName() + " ("
                    + device.getAddress() + ") = " + priority);
        }
        return priority;
    }

    /**
     * Set the priority of the given Bluetooth profile for the given remote device
     * @param profile - Bluetooth profile
     * @param device - remote Bluetooth device
     * @param priority - priority to set
     */
    @Override
    public void setProfilePriority(int profile, BluetoothDevice device, int priority) {
        if (!isBluetoothConnectionProxyAvailable(profile)) {
            Log.e(TAG, "Cannot set profile priority. Proxy Unavailable");
            return;
        }
        if (device == null) {
            Log.e(TAG, "Cannot set profile priority on null device");
            return;
        }
        if (DBG) {
            Log.d(TAG, "Setting " + Utils.getProfileName(profile) + " priority for "
                    + device.getName() + " (" + device.getAddress() + ") to " + priority);
        }
        switch (profile) {
            case BluetoothProfile.A2DP_SINK:
                mBluetoothA2dpSink.setPriority(device, priority);
                break;
            case BluetoothProfile.HEADSET_CLIENT:
                mBluetoothHeadsetClient.setPriority(device, priority);
                break;
            case BluetoothProfile.MAP_CLIENT:
                mBluetoothMapClient.setPriority(device, priority);
                break;
            case BluetoothProfile.PBAP_CLIENT:
                mBluetoothPbapClient.setPriority(device, priority);
                break;
            default:
                Log.d(TAG, "Unknown Profile");
                break;
        }
    }
    /**
     * All the BluetoothProfile.ServiceListeners to get the Profile Proxy objects
     */
    private BluetoothProfile.ServiceListener mProfileListener =
            new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (DBG) {
                        Log.d(TAG, "OnServiceConnected profile: " + profile);
                    }
                    switch (profile) {
                        case BluetoothProfile.A2DP_SINK:
                            mBluetoothA2dpSink = (BluetoothA2dpSink) proxy;
                            break;

                        case BluetoothProfile.HEADSET_CLIENT:
                            mBluetoothHeadsetClient = (BluetoothHeadsetClient) proxy;
                            break;

                        case BluetoothProfile.PBAP_CLIENT:
                            mBluetoothPbapClient = (BluetoothPbapClient) proxy;
                            break;

                        case BluetoothProfile.MAP_CLIENT:
                            mBluetoothMapClient = (BluetoothMapClient) proxy;
                            break;

                        case BluetoothProfile.PAN:
                            mBluetoothPan = (BluetoothPan) proxy;
                            break;

                        default:
                            if (DBG) {
                                Log.d(TAG, "Unhandled profile");
                            }
                            break;
                    }
                }

                public void onServiceDisconnected(int profile) {
                    if (DBG) {
                        Log.d(TAG, "onServiceDisconnected profile: " + profile);
                    }
                    switch (profile) {
                        case BluetoothProfile.A2DP_SINK:
                            mBluetoothA2dpSink = null;
                            break;

                        case BluetoothProfile.HEADSET_CLIENT:
                            mBluetoothHeadsetClient = null;
                            break;

                        case BluetoothProfile.PBAP_CLIENT:
                            mBluetoothPbapClient = null;
                            break;

                        case BluetoothProfile.MAP_CLIENT:
                            mBluetoothMapClient = null;
                            break;

                        case BluetoothProfile.PAN:
                            mBluetoothPan = null;
                            break;

                        default:
                            if (DBG) {
                                Log.d(TAG, "Unhandled profile");
                            }
                            break;
                    }
                }
            };
}
