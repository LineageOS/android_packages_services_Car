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
import android.bluetooth.BluetoothPbapClient;
import android.bluetooth.BluetoothProfile;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.CarSensorEvent;
import android.car.hardware.CarSensorManager;
import android.car.hardware.ICarSensorEventListener;
import android.car.hardware.cabin.CarCabinManager;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarPropertyEventListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Bluetooth Device Connection policy that is specific to the use cases of a Car.  A car's
 * bluetooth capabilities in terms of the profiles it supports and its use cases are unique.  Hence
 * the CarService manages the policy that drives when and what to connect to.
 *
 * When to connect:
 * The policy can be configured to listen to various vehicle events that are appropriate to trigger
 * a connection attempt.  Signals like door unlock/open, ignition state changes indicate user entry
 * and there by attempt to connect to their devices. This removes the need for the user to manually
 * connect his device everytime they get in a car.
 *
 * Which device to connect:
 * The policy also keeps track of the {Profile : DevicesThatCanConnectOnTheProfile} and when it is
 * time to connect, picks the device that is appropriate and available.
 * For every profile, the policy attempts to connect to the last connected device first. The policy
 * maintains a list of connect-able devices for every profile, in the order of how recently they
 * connected.  The device that successfully connects on a profile is moved to the top of the list
 * of devices for that profile, so the next time a connection attempt is made, the policy starts
 * with the last connected device first.
 */

public class BluetoothDeviceConnectionPolicy {
    private static final String TAG = "BTDevConnectionPolicy";
    private static final boolean DBG = false;
    private Context mContext;

    // The main datastructure that holds on to the {profile:list of known and connectible devices}
    private HashMap<Integer, BluetoothDevicesInfo> mProfileToConnectableDevicesMap;
    // mProfileToConnectableDevicesInfo - holds information to serialize and write
    // to file, that can be used to rebuild the mProfileToConnectableDevicesMap on a reboot.
    private HashMap<Integer, List<String>> mProfileToConnectableDevicesInfo;
    BluetoothAutoConnectStateMachine mBluetoothAutoConnectStateMachine;
    private final BluetoothAdapter mBluetoothAdapter;
    private Set<BluetoothDevice> mBondedDevices;
    private BroadcastReceiver mReceiver;
    private IntentFilter mProfileFilter;

    // Events that are listened to for triggering an auto-connect:
    // Cabin events like Door unlock coming from the Cabin Service.
    private final CarCabinService mCarCabinService;
    private final CarPropertyListener mCabinEventListener;
    // Sensor events like Ignition switch ON from the Car Sensor Service
    private final CarSensorService mCarSensorService;
    private final CarSensorEventListener mCarSensorEventListener;

    // Profile Proxies.
    private BluetoothHeadsetClient mBluetoothHeadsetClient;
    private BluetoothA2dpSink mBluetoothA2dpSink;
    private BluetoothPbapClient mBluetoothPbapClient;
    private BluetoothMapClient mBluetoothMapClient;

    // The Bluetooth profiles that the CarService will try to autoconnect on.
    private List<Integer> mProfilesToConnect;
    private static final int MAX_CONNECT_RETRIES = 1;

    // File to write connectable devices for a profile information
    private static final String DEVICE_INFO_FILE = "BluetoothDevicesInfo.map";
    private static final int PROFILE_NOT_AVAILABLE = -1;

    // Device & Profile currently being connected on
    private ConnectionParams mConnectionInFlight;

    public static BluetoothDeviceConnectionPolicy create(Context context,
            CarCabinService carCabinService, CarSensorService carSensorService) {
        return new BluetoothDeviceConnectionPolicy(context, carCabinService, carSensorService);
    }

    private BluetoothDeviceConnectionPolicy(Context context, CarCabinService carCabinService,
            CarSensorService carSensorService) {
        mContext = context;
        mCarCabinService = carCabinService;
        mCarSensorService = carSensorService;
        mProfilesToConnect = Arrays.asList(new Integer[]
                {BluetoothProfile.HEADSET_CLIENT,
                        BluetoothProfile.PBAP_CLIENT, BluetoothProfile.A2DP_SINK,
                        BluetoothProfile.MAP_CLIENT,});
        mCabinEventListener = new CarPropertyListener();
        mCarSensorEventListener = new CarSensorEventListener();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "No Bluetooth Adapter Available");
        }
    }

    /**
     * ConnectionParams - parameters/objects relevant to the bluetooth connection calls.
     * This encapsulates the information that is passed around across different methods in the
     * policy. Contains the bluetooth device {@link BluetoothDevice} and the list of profiles that
     * we want that device to connect on.
     * Used as the currency that methods use to talk to each other in the policy.
     */
    public static class ConnectionParams {
        private BluetoothDevice mBluetoothDevice;
        private Integer mBluetoothProfile;

        public ConnectionParams() {
            // default constructor
        }

        public ConnectionParams(Integer profile) {
            mBluetoothProfile = profile;
        }

        public ConnectionParams(BluetoothDevice device, Integer profile) {
            mBluetoothProfile = profile;
            mBluetoothDevice = device;
        }

        // getters & Setters
        public void setBluetoothDevice(BluetoothDevice device) {
            mBluetoothDevice = device;
        }

        public void setBluetoothProfile(Integer profile) {
            mBluetoothProfile = profile;
        }

        public BluetoothDevice getBluetoothDevice() {
            return mBluetoothDevice;
        }

        public Integer getBluetoothProfile() {
            return mBluetoothProfile;
        }
    }

    /**
     * BluetoothBroadcastReceiver receives the bluetooth related intents that are relevant to
     * connection
     * and bonding state changes.  Reports the information to the {@link
     * BluetoothDeviceConnectionPolicy}
     * for it update its status.
     */
    public class BluetoothBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DBG) {
                Log.d(TAG, "Received Intent " + action);
            }
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            ConnectionParams connectParams;
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.ERROR);
                updateBondState(device, bondState);

            } else if (BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                connectParams = new ConnectionParams(device, BluetoothProfile.A2DP_SINK);
                int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                notifyConnectionStatus(connectParams, currState);

            } else if (BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                connectParams = new ConnectionParams(device, BluetoothProfile.HEADSET_CLIENT);
                int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                notifyConnectionStatus(connectParams, currState);

            } else if (BluetoothPbapClient.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                connectParams = new ConnectionParams(device, BluetoothProfile.PBAP_CLIENT);
                int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                notifyConnectionStatus(connectParams, currState);

            } else if (BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                connectParams = new ConnectionParams(device, BluetoothProfile.MAP_CLIENT);
                int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                notifyConnectionStatus(connectParams, currState);

            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int currState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        -1);
                if (DBG) {
                    Log.d(TAG, "Bluetooth Adapter State: " + currState);
                }
                if (currState == BluetoothAdapter.STATE_ON) {
                    // Initialize and populate (from file if available) the
                    // mProfileToConnectableDevicesMap
                    rebuildDeviceMapFromDeviceInfoLocked();
                    initiateConnection();
                } else if (currState == BluetoothAdapter.STATE_OFF) {
                    // Write currently connected device snapshot to file.
                    writeDeviceInfoToFile();
                    resetBluetoothDevicesConnectionInfo();
                }
            }
        }
    }

    /**
     * Setup the Bluetooth profile service connections and Vehicle Event listeners.
     * and start the state machine -{@link BluetoothAutoConnectStateMachine}
     */
    public void init() {
        if (DBG) {
            Log.d(TAG, "init()");
        }
        initDeviceMap();
        // Register for various intents from the Bluetooth service.
        mReceiver = new BluetoothBroadcastReceiver();
        // Create a new ConnectionParams object to keep track of device & profile that are being
        // connected to.
        mConnectionInFlight = new ConnectionParams();
        setupIntentFilter();
        // Make connections to Profile Services.
        setupProfileProxy();
        mBluetoothAutoConnectStateMachine = BluetoothAutoConnectStateMachine.make(this);
        // Listen to various events coming from the vehicle.
        setupEventListeners();
    }

    /**
     * Setting up the intent filter to the connection and bonding state changes we are interested
     * in.
     * This includes knowing when the
     * 1. Bluetooth Adapter turned on/off
     * 2. Bonding State of a device changes
     * 3. A specific profile's connection state changes.
     */
    private void setupIntentFilter() {
        mProfileFilter = new IntentFilter();
        mProfileFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mProfileFilter.addAction(BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
        mProfileFilter.addAction(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
        mProfileFilter.addAction(BluetoothPbapClient.ACTION_CONNECTION_STATE_CHANGED);
        mProfileFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiver(mReceiver, mProfileFilter);
        if (DBG) {
            Log.d(TAG, "Intent Receiver Registered");
        }
    }

    /**
     * Initialize the {@link #mProfileToConnectableDevicesMap}.
     * {@link #mProfileToConnectableDevicesMap} stores the profile:DeviceList information.  This
     * method retrieves it from persistent memory.
     */
    private synchronized void initDeviceMap() {
        boolean result = readDeviceInfoFromFile();
        if (result == false || mProfileToConnectableDevicesMap == null) {
            if (mProfileToConnectableDevicesMap == null) {
                mProfileToConnectableDevicesMap = new HashMap<Integer, BluetoothDevicesInfo>();
                for (Integer profile : mProfilesToConnect) {
                    mProfileToConnectableDevicesMap.put(profile, new BluetoothDevicesInfo(profile));
                }
                if (DBG) {
                    Log.d(TAG, "new Device Map created");
                }
            }
        }
    }

    /**
     * Setup connections to the profile proxy object to talk to the Bluetooth profile services
     */
    private void setupProfileProxy() {
        if (DBG) {
            Log.d(TAG, "setupProfileProxy()");
        }
        if (mBluetoothAdapter == null) {
            return;
        }
        for (Integer profile : mProfilesToConnect) {
            mBluetoothAdapter.getProfileProxy(mContext, mProfileListener, profile);
        }
    }

    /**
     * Setting up Listeners to the various events we are interested in listening to for initiating
     * Bluetooth connection attempts.
     */
    private void setupEventListeners() {
        // Setting up a listener for events from CarCabinService
        // For now, we listen to door unlock signal coming from {@link CarCabinService},
        // and Ignition state START from {@link CarSensorService}
        mCarCabinService.registerListener(mCabinEventListener);
        mCarSensorService.registerOrUpdateSensorListener(
                CarSensorManager.SENSOR_TYPE_IGNITION_STATE, 0, mCarSensorEventListener);
    }

    /**
     * Handles events coming in from the {@link CarCabinService}
     * The events that can trigger Bluetooth Scanning from CarCabinService is Door Unlock.
     * Upon receiving the event that is of interest, initiate a connection attempt by calling
     * the policy {@link BluetoothDeviceConnectionPolicy}
     */
    private class CarPropertyListener extends ICarPropertyEventListener.Stub {
        @Override
        public void onEvent(CarPropertyEvent event) throws RemoteException {
            if (DBG) {
                Log.d(TAG, "Cabin change Event : " + event.getEventType());
            }
            Boolean locked;
            CarPropertyValue value = event.getCarPropertyValue();
            Object o = value.getValue();

            if (value.getPropertyId() == CarCabinManager.ID_DOOR_LOCK) {
                if (o instanceof Boolean) {
                    locked = (Boolean) o;
                    if (DBG) {
                        Log.d(TAG, "Door Lock: " + locked);
                    }
                    // Attempting a connection only on a door unlock
                    if (!locked) {
                        initiateConnection();
                    }
                }
            }
        }
    }

    /**
     * Handles events coming in from the {@link CarSensorService}
     * The events that can trigger Bluetooth Scanning from CarSensorService is Ignition START.
     * Upon receiving the event that is of interest, initiate a connection attempt by calling
     * the policy {@link BluetoothDeviceConnectionPolicy}
     */

    private class CarSensorEventListener extends ICarSensorEventListener.Stub {
        @Override
        public void onSensorChanged(List<CarSensorEvent> events) throws RemoteException {
            if (events != null & events.size() > 0) {
                CarSensorEvent event = events.get(0);
                if (DBG) {
                    Log.d(TAG, "Sensor event Type : " + event.sensorType);
                }
                if (event.sensorType == CarSensorManager.SENSOR_TYPE_IGNITION_STATE) {
                    if (DBG) {
                        Log.d(TAG, "Sensor value : " + event.intValues[0]);
                    }
                    if (event.intValues[0] == CarSensorEvent.IGNITION_STATE_START) {
                        initiateConnection();
                    }
                }
            }
        }
    }

    /**
     * Clean up slate. Close the Bluetooth profile service connections and quit the state machine -
     * {@link BluetoothAutoConnectStateMachine}
     */
    public void release() {
        if (DBG) {
            Log.d(TAG, "release()");
        }
        writeDeviceInfoToFile();
        closeEventListeners();
        // Closing the connections to the Profile Services
        closeProfileProxy();
        mConnectionInFlight = null;
        // quit the state machine
        mBluetoothAutoConnectStateMachine.doQuit();

    }

    /**
     * Unregister the listeners to the various Vehicle events coming from other parts of the
     * CarService
     */
    private void closeEventListeners() {
        // b/34723490 - Need to add more events other than the Cabin Event.
        if (mCabinEventListener != null) {
            mCarCabinService.unregisterListener(mCabinEventListener);
        }
    }

    /**
     * Close connections to the profile proxy object
     */
    private void closeProfileProxy() {
        if (mBluetoothAdapter == null) {
            return;
        }
        if (DBG) {
            Log.d(TAG, "closeProfileProxy()");
        }
        mBluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP_SINK, mBluetoothA2dpSink);
        mBluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET_CLIENT,
                mBluetoothHeadsetClient);
        mBluetoothAdapter.closeProfileProxy(BluetoothProfile.PBAP_CLIENT, mBluetoothPbapClient);
        mBluetoothAdapter.closeProfileProxy(BluetoothProfile.MAP_CLIENT, mBluetoothMapClient);
    }

    /**
     * Resets the {@link BluetoothDevicesInfo#mConnectionInfo} of all the profiles to start from
     * a clean slate.  The ConnectionInfo has all the book keeping information regarding the state
     * of connection attempts - like which device in the device list for the profile is the next
     * to try connecting etc.
     * This method does not clear the {@link BluetoothDevicesInfo#mDeviceList} like the {@link
     * #resetProfileToConnectableDevicesMap()} method does.
     */
    private synchronized void resetBluetoothDevicesConnectionInfo() {
        if (DBG) {
            Log.d(TAG, "Resetting ConnectionInfo for all profiles");
        }
        for (BluetoothDevicesInfo devInfo : mProfileToConnectableDevicesMap.values()) {
            devInfo.resetConnectionInfoLocked();
        }
    }

    /**
     * Resets the {@link #mProfileToConnectableDevicesMap} to a clean and empty slate.
     */
    public synchronized void resetProfileToConnectableDevicesMap() {
        if (DBG) {
            Log.d(TAG, "Resetting the mProfilesToConnectableDevicesMap");
        }
        for (BluetoothDevicesInfo devInfo : mProfileToConnectableDevicesMap.values()) {
            devInfo.resetDeviceListLocked();
        }
    }

    /**
     * Returns the list of profiles that the Autoconnection policy attempts to connect on
     *
     * @return profile list.
     */
    public List<Integer> getProfilesToConnect() {
        return mProfilesToConnect;
    }

    /**
     * Add a new Profile to the list of To Be Connected profiles.
     *
     * @param profile - ProfileInfo of the new profile to be added.
     */
    public synchronized void addProfile(Integer profile) {
        mProfilesToConnect.add(profile);
    }

    /**
     * Add or remove a device based on the bonding state change.
     *
     * @param device    - device to add/remove
     * @param bondState - current bonding state
     */
    private void updateBondState(BluetoothDevice device, int bondState) {
        if (device == null) {
            Log.e(TAG, "updateBondState: device Null");
            return;
        }
        if (DBG) {
            Log.d(TAG, "BondState :" + bondState + " Device: " + device.getName());
        }
        // Bonded devices are added to a profile's device list after the device CONNECTS on the
        // profile.  When unpaired, we remove the device from all of the profiles' device list.
        if (bondState == BluetoothDevice.BOND_NONE) {
            for (Integer profile : mProfilesToConnect) {
                removeDeviceFromProfile(device, profile);
            }
        }

    }

    /**
     * Add a new device to the list of devices connect-able on the given profile
     *
     * @param device  - Bluetooth device to be added
     * @param profile - profile to add the device to.
     */
    private synchronized void addDeviceToProfile(BluetoothDevice device, Integer profile) {
        BluetoothDevicesInfo devInfo = mProfileToConnectableDevicesMap.get(profile);
        if (devInfo == null) {
            if (DBG) {
                Log.d(TAG, "Creating devInfo for profile: " + profile);
            }
            devInfo = new BluetoothDevicesInfo(profile);
            mProfileToConnectableDevicesMap.put(profile, devInfo);
        }
        devInfo.addDeviceLocked(device);
    }

    /**
     * Remove the device from the list of devices connect-able on the gievn profile.
     *
     * @param device  - Bluetooth device to be removed
     * @param profile - profile to remove the device from
     */
    private synchronized void removeDeviceFromProfile(BluetoothDevice device, Integer profile) {
        BluetoothDevicesInfo devInfo = mProfileToConnectableDevicesMap.get(profile);
        if (devInfo != null) {
            devInfo.removeDeviceLocked(device);
        }
    }

    /**
     * Initiate a bluetooth connection.
     */
    private void initiateConnection() {
        // Make sure the bluetooth adapter is available & enabled.
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth Adapter null");
            return;
        }

        if (mBluetoothAdapter.isEnabled()) {
            if (isDeviceMapEmpty()) {
                if (DBG) {
                    Log.d(TAG, "Device Map is empty. Querying bonded devices");
                }
                if (populateDeviceMapFromBondedDevices() == false) {
                    if (DBG) {
                        Log.d(TAG, "No bonded devices");
                    }
                    return;
                }
            }
            resetDeviceAvailableToConnect();
            if (DBG) {
                Log.d(TAG, "initiateConnection() Reset Device Availability");
            }
            mBluetoothAutoConnectStateMachine.sendMessage(BluetoothAutoConnectStateMachine.CONNECT);
        } else {
            if (DBG) {
                Log.d(TAG, "Bluetooth Adapter not enabled.");
            }
        }
    }

    /**
     * If, for some reason, the {@link #mProfileToConnectableDevicesMap} is empty, query the
     * Bluetooth stack
     * for the list of bonded devices and use it to populate the {@link
     * #mProfileToConnectableDevicesMap}.
     *
     * @return true if devices were added
     * false if the bonded device list is also empty.
     */
    private synchronized boolean populateDeviceMapFromBondedDevices() {
        if (mBluetoothAdapter == null) {
            return false;
        }
        mBondedDevices = mBluetoothAdapter.getBondedDevices();
        if (mBondedDevices.size() == 0) {
            if (DBG) {
                Log.d(TAG, "populateDeviceMapFromBondedDevices() - No bonded devices");
            }
            return false;
        }

        for (BluetoothDevice bd : mBondedDevices) {
            for (Integer profile : mProfilesToConnect) {
                if (bd != null) {
                    if (DBG) {
                        Log.d(TAG, "Adding device: " + bd.getName() + " profile: " + profile);
                    }
                    mProfileToConnectableDevicesMap.get(profile).addDeviceLocked(bd);
                }
            }
        }
        return true;
    }

    /**
     * Find an unconnected profile and find a device to connect on it.
     * Finds the appropriate device for the profile from the information available in
     * {@link #mProfileToConnectableDevicesMap}
     *
     * @return true - if we found a device to connect on for any of the {@link #mProfilesToConnect}
     * false - if we cannot find a device to connect to or if we are not ready to connect yet.
     */
    public synchronized boolean findDeviceToConnect() {
        if (mBluetoothAdapter == null || mBluetoothAdapter.isEnabled() == false
                || mProfileToConnectableDevicesMap == null) {
            return false;
        }
        boolean deviceFound = false;
        // Get the first unconnected profile that we can try to make a connection
        Integer nextProfile = getNextProfileToConnectLocked();
        // Keep going through the profiles until we find a device that we can connect to
        while (nextProfile != PROFILE_NOT_AVAILABLE) {
            if (DBG) {
                Log.d(TAG, "connectToProfile(): " + nextProfile);
            }
            // find a device that is next in line for a connection attempt for that profile
            deviceFound = tryNextDeviceInQueueLocked(nextProfile);
            // If we found a device to connect, break out of the loop
            if (deviceFound) {
                if (DBG) {
                    Log.d(TAG, "Found device to connect to");
                }
                BluetoothDeviceConnectionPolicy.ConnectionParams btParams =
                        new BluetoothDeviceConnectionPolicy.ConnectionParams(
                                mConnectionInFlight.getBluetoothDevice(),
                                mConnectionInFlight.getBluetoothProfile());
                // set up a time out
                mBluetoothAutoConnectStateMachine.sendMessageDelayed(
                        BluetoothAutoConnectStateMachine.CONNECT_TIMEOUT, btParams,
                        BluetoothAutoConnectStateMachine.CONNECTION_TIMEOUT_MS);
                break;
            } else {
                // result will be false, if there are no more devices to connect
                // or if the ProfileProxy objects are null (ServiceConnection
                // not yet established for this profile)
                if (DBG) {
                    Log.d(TAG, "No more device to connect on Profile: " + nextProfile);
                }
                nextProfile = getNextProfileToConnectLocked();
            }
        }
        return deviceFound;
    }

    /**
     * Get the first unconnected profile.
     *
     * @return profile to connect.
     * Special return value 0 if
     * 1. all profiles have been connected on.
     * 2. no profile connected but no nearby known device that can be connected to
     */
    private Integer getNextProfileToConnectLocked() {
        for (Integer profile : mProfilesToConnect) {
            BluetoothDevicesInfo devInfo = mProfileToConnectableDevicesMap.get(profile);
            if (devInfo != null) {
                if (devInfo.isConnectedLocked() == false
                        && devInfo.isDeviceAvailableToConnectLocked() == true) {
                    return profile;
                }
            } else {
                Log.e(TAG, "Unexpected: devInfo null for profile: " + profile);
            }
        }
        // Reaching here denotes all profiles are connected or No devices available for any profile
        return PROFILE_NOT_AVAILABLE;
    }

    /**
     * Try to connect to the next device in the device list for the given profile.
     *
     * @param profile - profile to connect on
     * @return - true if we found a device to connect on for this profile
     * false - if we cannot find a device to connect to.
     */

    private boolean tryNextDeviceInQueueLocked(Integer profile) {
        // Get the Device Information for the given profile and find the next device to connect on
        boolean deviceAvailable = true;
        BluetoothDevicesInfo devInfo = mProfileToConnectableDevicesMap.get(profile);
        if (devInfo == null) {
            Log.e(TAG, "Unexpected: No device Queue for this profile: " + profile);
            return false;
        }
        BluetoothDevice devToConnect = devInfo.getNextDeviceInQueueLocked();
        if (devToConnect != null) {
            switch (profile) {
                case BluetoothProfile.A2DP_SINK:
                    if (mBluetoothA2dpSink != null) {
                        if (DBG) {
                            Log.d(TAG,
                                    "Connecting device " + devToConnect.getName() + " on A2DPSink");
                        }
                        mBluetoothA2dpSink.connect(devToConnect);
                    } else {
                        if (DBG) {
                            Log.d(TAG, "Unexpected: BluetoothA2dpSink Profile proxy null");
                        }
                        deviceAvailable = false;
                    }
                    break;

                case BluetoothProfile.HEADSET_CLIENT:
                    if (mBluetoothHeadsetClient != null) {
                        if (DBG) {
                            Log.d(TAG, "Connecting device " + devToConnect.getName()
                                    + " on HeadSetClient");
                        }
                        mBluetoothHeadsetClient.connect(devToConnect);
                    } else {
                        if (DBG) {
                            Log.d(TAG, "Unexpected: BluetoothHeadsetClient Profile proxy null");
                        }
                        deviceAvailable = false;
                    }
                    break;

                case BluetoothProfile.PBAP_CLIENT:
                    if (mBluetoothPbapClient != null) {
                        if (DBG) {
                            Log.d(TAG, "Connecting device " + devToConnect.getName()
                                    + " on PbapClient");
                        }
                        mBluetoothPbapClient.connect(devToConnect);
                    } else {
                        if (DBG) {
                            Log.d(TAG, "Unexpected: BluetoothPbapClient Profile proxy null");
                        }
                        deviceAvailable = false;
                    }
                    break;

                case BluetoothProfile.MAP_CLIENT:
                    if (mBluetoothMapClient != null) {
                        if (DBG) {
                            Log.d(TAG, "Connecting device " + devToConnect.getName()
                                    + " on MAPClient");
                        }
                        mBluetoothMapClient.connect(devToConnect);
                    } else {
                        if (DBG) {
                            Log.d(TAG, "Unexpected: BluetoothMAPClient Profile proxy null");
                        }
                        deviceAvailable = false;
                    }
                    break;

                default:
                    if (DBG) {
                        Log.d(TAG, "Unsupported Bluetooth profile being tried for connection: "
                                + profile);
                    }
                    break;
            }
            // Increment the retry count & cache what is being connected to
            if (deviceAvailable) {
                // This method is already called from a synchronized context.
                mConnectionInFlight.setBluetoothDevice(devToConnect);
                mConnectionInFlight.setBluetoothProfile(profile);
                devInfo.incrementRetryCountLocked();
                if (DBG) {
                    Log.d(TAG, "Increment Retry to: " + devInfo.getRetryCountLocked());
                }
            } else {
                if (DBG) {
                    Log.d(TAG, "Not incrementing retry.");
                }
            }
        } else {
            if (DBG) {
                Log.d(TAG, "No paired nearby device to connect to for profile: " + profile);
            }
            // reset the mConnectionInFlight
            mConnectionInFlight.setBluetoothProfile(0);
            mConnectionInFlight.setBluetoothDevice(null);
            devInfo.setDeviceAvailableToConnectLocked(false);
            deviceAvailable = false;
        }
        return deviceAvailable;
    }

    /**
     * Update the device connection status for a profile and also notify the state machine.
     * This gets called from {@link BluetoothBroadcastReceiver} when it receives a Profile's
     * CONNECTION_STATE_CHANGED intent.
     *
     * @param params       - {@link ConnectionParams} device and profile list info
     * @param currentState - connection result to update
     */
    private void notifyConnectionStatus(ConnectionParams params, int currentState) {
        // Update the profile's BluetoothDevicesInfo.
        boolean isConnected;
        switch (currentState) {
            case BluetoothProfile.STATE_DISCONNECTED: {
                isConnected = false;
                break;
            }

            case BluetoothProfile.STATE_CONNECTED: {
                isConnected = true;
                break;
            }

            default: {
                if (DBG) {
                    Log.d(TAG, "notifyConnectionStatus() Ignoring state: " + currentState);
                }
                return;
            }

        }

        boolean updateSuccessful = updateDeviceConnectionStatus(params, isConnected);
        if (updateSuccessful) {
            if (isConnected) {
                mBluetoothAutoConnectStateMachine.sendMessage(
                        BluetoothAutoConnectStateMachine.DEVICE_CONNECTED,
                        params);
            } else {
                mBluetoothAutoConnectStateMachine.sendMessage(
                        BluetoothAutoConnectStateMachine.DEVICE_DISCONNECTED,
                        params);
            }
        }
    }

    /**
     * Update the profile's {@link BluetoothDevicesInfo} with the result of the connection
     * attempt.  This gets called from the {@link BluetoothAutoConnectStateMachine} when the
     * connection attempt times out or from {@link BluetoothBroadcastReceiver} when it receives
     * a Profile's CONNECTION_STATE_CHANGED intent.
     *
     * @param params     - {@link ConnectionParams} device and profile list info
     * @param didConnect - connection result to update
     */
    public synchronized boolean updateDeviceConnectionStatus(ConnectionParams params,
            boolean didConnect) {
        if (params == null || params.getBluetoothDevice() == null) {
            Log.e(TAG, "updateDeviceConnectionStatus: null params");
            return false;
        }
        // Get the profile to update
        Integer profileToUpdate = params.getBluetoothProfile();
        BluetoothDevice deviceThatConnected = params.getBluetoothDevice();
        if (DBG) {
            Log.d(TAG, "Profile: " + profileToUpdate + " Connected: " + didConnect + " on "
                    + deviceThatConnected.getName());
        }

        // If the connection update is on a different profile or device (a very rare possibility),
        // it is handled automatically.  Just logging it here.
        if (DBG) {
            if (mConnectionInFlight != null && mConnectionInFlight.getBluetoothProfile() != null) {
                if (profileToUpdate.equals(mConnectionInFlight.getBluetoothProfile()) == false) {
                    Log.d(TAG, "Updating profile " + profileToUpdate
                            + " different from connection in flight "
                            + mConnectionInFlight.getBluetoothProfile());
                }
            }

            if (mConnectionInFlight != null && mConnectionInFlight.getBluetoothDevice() != null) {
                if (deviceThatConnected.equals(mConnectionInFlight.getBluetoothDevice()) == false) {
                    Log.d(TAG, "Connected device " + deviceThatConnected.getName()
                            + " different from connection in flight");

                }
            }
        }
        BluetoothDevicesInfo devInfo = null;
        devInfo = mProfileToConnectableDevicesMap.get(profileToUpdate);
        if (devInfo == null) {
            Log.e(TAG, "Unexpected: devInfo null for profile: " + profileToUpdate);
            return false;
        }

        boolean retry = canRetryConnection(profileToUpdate);
        // Update the status and also if a retry attempt can be made if the
        // connection timed out in the previous attempt.
        if (DBG) {
            Log.d(TAG, "Retry? : " + retry);
        }
        devInfo.updateConnectionStatusLocked(deviceThatConnected, didConnect, retry);
        // Write to persistent memory to have the latest snapshot available
        writeDeviceInfoToFile();
        return true;
    }

    /**
     * Returns if we can retry connection attempt on the given profile for the device that is
     * currently in the head of the queue.
     *
     * @param profile - Profile to check
     */
    private synchronized boolean canRetryConnection(Integer profile) {
        BluetoothDevicesInfo devInfo = mProfileToConnectableDevicesMap.get(profile);
        if (devInfo == null) {
            Log.e(TAG, "Unexpected: No device Queue for this profile: " + profile);
            return false;
        }
        if (devInfo.getRetryCountLocked() < MAX_CONNECT_RETRIES) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Helper method to see if there are any connect-able devices on any of the
     * profiles.
     *
     * @return true - if {@link #mProfileToConnectableDevicesMap} does not have any devices for any
     * profiles.
     * false - if {@link #mProfileToConnectableDevicesMap} has a device for at least one profile.
     */
    private synchronized boolean isDeviceMapEmpty() {
        boolean empty = true;
        for (Integer profile : mProfilesToConnect) {
            BluetoothDevicesInfo devInfo = mProfileToConnectableDevicesMap.get(profile);
            if (devInfo != null) {
                if (devInfo.getNumberOfPairedDevicesLocked() != 0) {
                    if (DBG) {
                        Log.d(TAG, "Device map not empty. Profile: " + profile + " has "
                                + devInfo.getNumberOfPairedDevicesLocked() + " paired devices");
                    }
                    empty = false;
                    break;
                }
            }
        }
        return empty;
    }

    /**
     * Reset the Device Available to Connect information for all profiles to Available.
     * If in a previous connection attempt, we failed to connect on all devices for a profile,
     * we would update deviceAvailableToConnect for that profile to false.  That information
     * is used to deduce if we should move to the next profile. If marked false, we will not
     * try to connect on that profile anymore as part of that connection attempt.
     * However, when we get another connection trigger from the vehicle, we need to reset the
     * deviceAvailableToConnect information so we can start the connection attempts all over
     * again.
     */
    private synchronized void resetDeviceAvailableToConnect() {
        for (BluetoothDevicesInfo devInfo : mProfileToConnectableDevicesMap.values()) {
            devInfo.setDeviceAvailableToConnectLocked(true);
        }
    }

    /**
     * Utility function - Prints the Profile: list of devices information to log
     * Caller should wrap a DBG around this, since this is for debugging purpose.
     *
     * @param writer - PrintWriter
     */
    private synchronized void printDeviceMap(PrintWriter writer) {
        if (mProfileToConnectableDevicesMap == null) {
            return;
        }
        writer.println("Bluetooth Profile -> Connectable Device List ");
        for (BluetoothDevicesInfo devInfo : mProfileToConnectableDevicesMap.values()) {
            writer.println("Profile: " + devInfo.getProfileLocked() + "\t");
            writer.print("Connected: " + devInfo.isConnectedLocked() +
                    "\t Device Available: " + devInfo.isDeviceAvailableToConnectLocked());
            writer.println();
            writer.println("Device List:");
            List<BluetoothDevice> deviceList = devInfo.getDeviceList();
            if (deviceList != null) {
                for (BluetoothDevice device : deviceList) {
                    writer.print(device.getName() + "\t");
                }
            }
        }
    }

    /**
     * Utility function - could be called from a adb shell dump command to dump the
     * {@link #mProfileToConnectableDevicesInfo}
     *
     * @param writer - PrintWriter
     */
    public synchronized void printDeviceInfo(PrintWriter writer) {
        if (mProfileToConnectableDevicesInfo == null) {
            Log.d(TAG, "mProfileToConnectableDevicesInfo null");
            return;
        }
        writer.println("Bluetooth Profile -> device Info");
        for (Map.Entry<Integer, List<String>> entry : mProfileToConnectableDevicesInfo.entrySet()) {
            writer.println("Profile: " + entry.getKey());
            for (String devname : entry.getValue()) {
                writer.print(devname + "\t");
            }
            writer.println();
        }
    }

    /**
     * Write information about which devices connected on which profile to persistent memory.
     * Essentially the list of devices that a profile can connect on the next auto-connect
     * attempt.
     *
     * @return true if the write was successful, false otherwise
     */
    public synchronized boolean writeDeviceInfoToFile() {
        boolean writeSuccess = true;
        if (mProfileToConnectableDevicesMap == null) {
            writeSuccess = false;
        } else {
            extractDeviceInfoFromDeviceMapLocked();
            try {
                AtomicFile oFile = new AtomicFile(
                        new File(mContext.getFilesDir(), DEVICE_INFO_FILE));
                FileOutputStream outFile = oFile.startWrite();
                ObjectOutputStream ostream = new ObjectOutputStream(outFile);
                ostream.writeObject(mProfileToConnectableDevicesInfo);
                ostream.flush();
                ostream.close();
                oFile.finishWrite(outFile);
                outFile.close();
                if (DBG) {
                    Log.d(TAG, "Writing successful");
                }
            } catch (IOException e) {
                Log.e(TAG, e.getMessage());
                writeSuccess = false;
            }
        }
        return writeSuccess;
    }


    /**
     * Extracts {@link #mProfileToConnectableDevicesInfo} from
     * {@link #mProfileToConnectableDevicesMap}
     * {@link #mProfileToConnectableDevicesInfo} is a map of Profiles to List of names of
     * Connectable
     * devices that gets written to a file.
     */
    private void extractDeviceInfoFromDeviceMapLocked() {
        mProfileToConnectableDevicesInfo = new HashMap<Integer, List<String>>();

        for (Map.Entry<Integer, BluetoothDevicesInfo> entry : mProfileToConnectableDevicesMap
                .entrySet()) {
            // for every entry, extract the Profile and the list of device names
            Integer profile = entry.getKey();
            if (DBG) {
                Log.d(TAG, "Extracting for profile " + profile);
            }
            List<String> deviceNames = new ArrayList<>();
            BluetoothDevicesInfo devicesInfo = entry.getValue();
            // Iterate through the List<BluetoothDevice> and build List<DeviceNames>
            if (devicesInfo != null && devicesInfo.getDeviceList() != null) {
                for (BluetoothDevice device : devicesInfo.getDeviceList()) {
                    deviceNames.add(device.getName());
                    if (DBG) {
                        Log.d(TAG, "Device: " + device.getName());
                    }
                }
            }
            mProfileToConnectableDevicesInfo.put(profile, deviceNames);
        }
    }

    /**
     * Read the device information from file and populate the
     * {@link #mProfileToConnectableDevicesMap}
     *
     * @return - true if the read was successful, false if not.
     */
    public synchronized boolean readDeviceInfoFromFile() {
        boolean readSuccess = true;
        try {
            AtomicFile iFile = new AtomicFile(new File(mContext.getFilesDir(), DEVICE_INFO_FILE));
            if (DBG) {
                Log.d(TAG, "Reading from file");
            }
            FileInputStream inFile = iFile.openRead();
            ObjectInputStream istream = new ObjectInputStream(inFile);
            mProfileToConnectableDevicesInfo =
                    (HashMap<Integer, List<String>>) istream.readObject();
            istream.close();
            inFile.close();
        } catch (IOException | ClassNotFoundException e) {
            Log.e(TAG, e.getMessage());
            if (DBG) {
                Log.d(TAG, "No previously connected device information available");
            }
            readSuccess = false;
        }
        if (readSuccess) {
            readSuccess = rebuildDeviceMapFromDeviceInfoLocked();
        }
        return readSuccess;
    }

    /**
     * Rebuild the {@link #mProfileToConnectableDevicesMap} from the {@link
     * #mProfileToConnectableDevicesInfo}
     *
     * @return true if the reconstruction was successful, false if not.
     */
    private boolean rebuildDeviceMapFromDeviceInfoLocked() {
        if (DBG) {
            Log.d(TAG, "Rebuilding device map");
        }

        if (mProfileToConnectableDevicesInfo == null) {
            Log.w(TAG, "No Device Info to rebuild the Device Map");
            return false;
        }

        if (mBluetoothAdapter != null) {
            if (DBG) {
                Log.d(TAG, "Bonded devices size:" + mBluetoothAdapter.getBondedDevices().size());
            }
            if (mBluetoothAdapter.getBondedDevices().isEmpty()) {
                if (DBG) {
                    Log.d(TAG, "No Bonded Devices available. Quit rebuilding");
                }
                return false;
            }
        }

        boolean rebuildSuccess = true;
        // Iterate through the Map's entries and build the {@link #mProfileToConnectableDevicesMap}
        if (mProfileToConnectableDevicesMap == null) {
            mProfileToConnectableDevicesMap = new HashMap<Integer, BluetoothDevicesInfo>();
        }

        for (Map.Entry<Integer, List<String>> entry : mProfileToConnectableDevicesInfo.entrySet()) {
            Integer profile = entry.getKey();
            List<String> deviceList = entry.getValue();
            // Build the BluetoothDevicesInfo for this profile.
            BluetoothDevicesInfo devicesInfo = new BluetoothDevicesInfo(profile);
            // Do we have a bonded device with this name?  If so, get it and populate the device
            // map.
            for (String name : deviceList) {
                BluetoothDevice deviceToAdd = getBondedDeviceWithGivenName(name);
                if (deviceToAdd != null) {
                    devicesInfo.addDeviceLocked(deviceToAdd);
                } else {
                    if (DBG) {
                        Log.d(TAG, "No device with name " + name + " found in bonded devices");
                    }
                }
            }
            mProfileToConnectableDevicesMap.put(profile, devicesInfo);
        }

        return rebuildSuccess;
    }

    /**
     * Given the device name, find the corresponding {@link BluetoothDevice} from the list of
     * Bonded devices.
     *
     * @param name Bluetooth Device name
     */
    private BluetoothDevice getBondedDeviceWithGivenName(String name) {
        if (mBluetoothAdapter == null) {
            if (DBG) {
                Log.d(TAG, "Bluetooth Adapter Null");
            }
            return null;
        }
        if (name == null) {
            Log.w(TAG, "getBondedDeviceWithGivenName() Passing in a null name");
            return null;
        }
        if (DBG) {
            Log.d(TAG, "Looking for bonded device: " + name);
        }
        BluetoothDevice btDevice = null;
        Set<BluetoothDevice> bondedDevices = mBluetoothAdapter.getBondedDevices();
        for (BluetoothDevice bd : bondedDevices) {
            if (name.equals(bd.getName())) {
                btDevice = bd;
                break;
            }
        }
        return btDevice;
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

                        default:
                            if (DBG) {
                                Log.d(TAG, "Unhandled profile");
                            }
                            break;
                    }
                }
            };

    public void dump(PrintWriter writer) {
        writer.println("*BluetoothDeviceConnectionPolicy*");
        printDeviceMap(writer);
        mBluetoothAutoConnectStateMachine.dump(writer);
    }
}
