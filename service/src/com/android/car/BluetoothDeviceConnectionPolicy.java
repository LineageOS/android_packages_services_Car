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

import static android.car.settings.CarSettings.Secure.KEY_BLUETOOTH_AUTOCONNECT_MESSAGING_DEVICES;
import static android.car.settings.CarSettings.Secure.KEY_BLUETOOTH_AUTOCONNECT_MUSIC_DEVICES;
import static android.car.settings.CarSettings.Secure.KEY_BLUETOOTH_AUTOCONNECT_NETWORK_DEVICES;
import static android.car.settings.CarSettings.Secure.KEY_BLUETOOTH_AUTOCONNECT_PHONE_DEVICES;
import static android.car.settings.CarSettings.Secure.KEY_BLUETOOTH_PROFILES_INHIBITED;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothMapClient;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothPbapClient;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.car.CarBluetoothManager;
import android.car.ICarBluetoothUserService;
import android.car.ICarUserService;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.ICarUxRestrictionsChangeListener;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListenerWithCompletion;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarPropertyEventListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.automotive.vehicle.V2_0.VehicleIgnitionState;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * A Bluetooth Device Connection policy that is specific to the use cases of a Car.  A car's
 * bluetooth capabilities in terms of the profiles it supports and its use cases are unique.
 * Hence the CarService manages the policy that drives when and what to connect to.
 *
 * When to connect:
 * The policy can be configured to listen to various vehicle events that are appropriate to
 * trigger a connection attempt.  Signals like door unlock/open, ignition state changes indicate
 * user entry and there by attempt to connect to their devices. This removes the need for the user
 * to manually connect his device everytime they get in a car.
 *
 * Which device to connect:
 * The policy also keeps track of the {Profile : DevicesThatCanConnectOnTheProfile} and when
 * it is time to connect, picks the device that is appropriate and available.
 * For every profile, the policy attempts to connect to the last connected device first. The policy
 * maintains a list of connect-able devices for every profile, in the order of how recently they
 * connected.  The device that successfully connects on a profile is moved to the top of the list
 * of devices for that profile, so the next time a connection attempt is made, the policy starts
 * with the last connected device first.
 */

public class BluetoothDeviceConnectionPolicy {
    private static final String TAG = "BTDevConnectionPolicy";
    private static final String SETTINGS_DELIMITER = ",";
    private static final boolean DBG = Utils.DBG;

    private static final Binder RESTORED_PROFILE_INHIBIT_TOKEN = new Binder();
    private static final long RESTORE_BACKOFF_MILLIS = 1000L;

    private final Context mContext;
    private boolean mInitialized = false;
    private boolean mUserSpecificInfoInitialized = false;
    private final Object mSetupLock = new Object();

    // The main data structure that holds on to the {profile:list of known and connectible devices}
    HashMap<Integer, BluetoothDevicesInfo> mProfileToConnectableDevicesMap;

    // Keep a map of the maximum number of connections allowed for any profile we plan to support.
    private static final Map<Integer, Integer> sNumSupportedActiveConnections = new HashMap<>();
    static {
        sNumSupportedActiveConnections.put(BluetoothProfile.HEADSET_CLIENT, 4);
        sNumSupportedActiveConnections.put(BluetoothProfile.PBAP_CLIENT, 4);
        sNumSupportedActiveConnections.put(BluetoothProfile.A2DP_SINK, 1);
        sNumSupportedActiveConnections.put(BluetoothProfile.MAP_CLIENT, 4);
        sNumSupportedActiveConnections.put(BluetoothProfile.PAN, 1);
    }

    private BluetoothAutoConnectStateMachine mBluetoothAutoConnectStateMachine;
    private final BluetoothAdapter mBluetoothAdapter;
    private BroadcastReceiver mBluetoothBroadcastReceiver;

    private ICarUserService mCarUserService;
    private PerUserCarServiceHelper mUserServiceHelper;
    private ICarBluetoothUserService mCarBluetoothUserService;
    private ReentrantLock mCarUserServiceAccessLock;

    // Events that are listened to for triggering an auto-connect:
    //  Door unlock and ignition switch ON come from Car Property Service
    private final CarPropertyService mCarPropertyService;
    private final CarPropertyListener mPropertyEventListener;

    private CarPowerManager mCarPowerManager;
    private final CarPowerStateListenerWithCompletion mCarPowerStateListener =
            new CarPowerStateListenerWithCompletion() {
        @Override
        public void onStateChanged(int state, CompletableFuture<Void> future) {
            if (DBG) Log.d(TAG, "Car power state has changed to " + state);

            // ON is the state when user turned on the car (it can be either ignition or
            // door unlock) the policy for ON is defined by OEMs and we can rely on that.
            if (state == CarPowerManager.CarPowerStateListener.ON) {
                Log.i(TAG, "Car is powering on. Enable Bluetooth and auto-connect to devices.");
                if (isBluetoothPersistedOn()) {
                    enabledBluetooth();
                }
                initiateConnection();
                return;
            }

            // Since we're appearing to be off after shutdown prepare, but may stay on in idle mode,
            // we'll turn off Bluetooth to disconnect devices and better the "off" illusion
            if (state == CarPowerManager.CarPowerStateListener.SHUTDOWN_PREPARE) {
                Log.i(TAG, "Car is preparing for shutdown. Disable bluetooth adapter.");
                disableBluetooth();

                // Let CPMS know we're ready to shutdown. Otherwise, CPMS will get stuck for
                // up to an hour.
                if (future != null) {
                    future.complete(null);
                }
                return;
            }
        }
    };

    // PerUserCarService related listeners
    private final UserServiceConnectionCallback mServiceCallback;

    // Car Bluetooth Priority Settings Manager
    private final CarBluetoothService mCarBluetoothService;

    // Car UX Restrictions Manager Service to know when user restrictions are in place
    private final CarUxRestrictionsManagerService mUxRService;
    private final CarUxRServiceListener mUxRListener;
    // Fast Pair Provider to allow discovery of new phones
    private final FastPairProvider mFastPairProvider;

    // The Bluetooth profiles that the CarService will try to auto-connect on.
    final List<Integer> mProfilesToConnect;
    private final List<Integer> mPrioritiesSupported;
    private static final int MAX_CONNECT_RETRIES = 1;
    private static final int PROFILE_NOT_AVAILABLE = -1;
    private int mUserId;

    // Device & Profile currently being connected on
    private ConnectionParams mConnectionInFlight;
    // Allow write to Settings.Secure
    private boolean mAllowReadWriteToSettings = true;
    // Maintain a list of Paired devices which haven't connected on any profiles yet.
    private Set<BluetoothDevice> mPairedButUnconnectedDevices = new HashSet<>();

    // State for profile inhibits.
    @GuardedBy("this")
    private final SetMultimap<ConnectionParams, InhibitRecord> mProfileInhibits =
            new SetMultimap<>();
    @GuardedBy("this")
    private final HashSet<InhibitRecord> mRestoredInhibits = new HashSet<>();
    @GuardedBy("this")
    private final HashSet<ConnectionParams> mAlreadyDisabledProfiles = new HashSet<>();

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public static BluetoothDeviceConnectionPolicy create(Context context,
            CarPropertyService carPropertyService, PerUserCarServiceHelper userServiceHelper,
            CarUxRestrictionsManagerService uxrService, CarBluetoothService bluetoothService) {
        return new BluetoothDeviceConnectionPolicy(context, carPropertyService, userServiceHelper,
                uxrService, bluetoothService);
    }

    private BluetoothDeviceConnectionPolicy(Context context, CarPropertyService carPropertyService,
            PerUserCarServiceHelper userServiceHelper, CarUxRestrictionsManagerService uxrService,
            CarBluetoothService bluetoothService) {
        mContext = context;
        mCarPropertyService = carPropertyService;
        mUserServiceHelper = userServiceHelper;
        mUxRService = uxrService;
        mCarBluetoothService = bluetoothService;
        mCarUserServiceAccessLock = new ReentrantLock();
        mProfilesToConnect = Arrays.asList(
                BluetoothProfile.HEADSET_CLIENT, BluetoothProfile.A2DP_SINK,
                BluetoothProfile.PBAP_CLIENT, BluetoothProfile.MAP_CLIENT, BluetoothProfile.PAN);
        mPrioritiesSupported = Arrays.asList(
                CarBluetoothManager.BLUETOOTH_DEVICE_CONNECTION_PRIORITY_0,
                CarBluetoothManager.BLUETOOTH_DEVICE_CONNECTION_PRIORITY_1
        );

        // Listen to events for triggering auto connect
        mPropertyEventListener = new CarPropertyListener();
        // Listen to UX Restrictions to know when to enable fast-pairing
        mUxRListener = new CarUxRServiceListener();
        // Listen to User switching to connect to per User device.
        mServiceCallback = new UserServiceConnectionCallback();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "No Bluetooth Adapter Available");
        }
        mFastPairProvider = new FastPairProvider(mContext);
    }

    /**
     * ConnectionParams - parameters/objects relevant to the bluetooth connection calls.
     * This encapsulates the information that is passed around across different methods in the
     * policy. Contains the bluetooth device {@link BluetoothDevice} and the list of profiles that
     * we want that device to connect on.
     * Used as the currency that methods use to talk to each other in the policy.
     */
    public static class ConnectionParams {
        // Examples:
        // 01:23:45:67:89:AB/9
        // null/0
        // null/null
        private static final String FLATTENED_PATTERN =
                "^(([0-9A-F]{2}:){5}[0-9A-F]{2}|null)/([0-9]+|null)$";

        @Nullable private final BluetoothDevice mBluetoothDevice;
        @Nullable private final Integer mBluetoothProfile;

        public ConnectionParams() {
            this(null, null);
        }

        public ConnectionParams(@Nullable Integer profile) {
            this(profile, null);
        }

        public ConnectionParams(@Nullable Integer profile, @Nullable BluetoothDevice device) {
            mBluetoothProfile = profile;
            mBluetoothDevice = device;
        }

        @Nullable
        public BluetoothDevice getBluetoothDevice() {
            return mBluetoothDevice;
        }

        @Nullable
        public Integer getBluetoothProfile() {
            return mBluetoothProfile;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ConnectionParams)) {
                return false;
            }
            ConnectionParams otherParams = (ConnectionParams) other;
            return Objects.equals(mBluetoothDevice, otherParams.mBluetoothDevice)
                && Objects.equals(mBluetoothProfile, otherParams.mBluetoothProfile);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mBluetoothDevice, mBluetoothProfile);
        }

        @Override
        public String toString() {
            return flattenToString();
        }

        /** Converts these {@link ConnectionParams} to a parseable string representation. */
        public String flattenToString() {
            return mBluetoothDevice + "/" + mBluetoothProfile;
        }

        /**
         * Creates a {@link ConnectionParams} from a previous output of {@link #flattenToString()}.
         *
         * @param flattenedParams A flattened string representation of a {@link ConnectionParams}.
         * @param adapter A {@link BluetoothAdapter} used to convert Bluetooth addresses into
         *         {@link BluetoothDevice} objects.
         */
        public static ConnectionParams parse(String flattenedParams, BluetoothAdapter adapter) {
            if (!flattenedParams.matches(FLATTENED_PATTERN)) {
                throw new IllegalArgumentException("Bad format for flattened ConnectionParams");
            }
            String[] parts = flattenedParams.split("/");

            BluetoothDevice device;
            if (!"null".equals(parts[0])) {
                device = adapter.getRemoteDevice(parts[0]);
            } else {
                device = null;
            }

            Integer profile;
            if (!"null".equals(parts[1])) {
                profile = Integer.valueOf(parts[1]);
            } else {
                profile = null;
            }

            return new ConnectionParams(profile, device);
        }
    }

    private class InhibitRecord implements IBinder.DeathRecipient {
        private final ConnectionParams mParams;
        private final IBinder mToken;

        private boolean mRemoved = false;

        InhibitRecord(ConnectionParams params, IBinder token) {
            this.mParams = params;
            this.mToken = token;
        }

        public ConnectionParams getParams() {
            return mParams;
        }

        public IBinder getToken() {
            return mToken;
        }

        public boolean removeSelf() {
            synchronized (BluetoothDeviceConnectionPolicy.this) {
                if (mRemoved) {
                    return true;
                }

                if (removeInhibitRecord(this)) {
                    mRemoved = true;
                    return true;
                } else {
                    return false;
                }
            }
        }

        @Override
        public void binderDied() {
            if (DBG) {
                Log.d(TAG, "Releasing inhibit request on profile "
                        + Utils.getProfileName(mParams.getBluetoothProfile())
                        + " for device " + mParams.getBluetoothDevice()
                        + ": requesting process died");
            }
            removeSelf();
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
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (DBG) {
                if (device != null) {
                    Log.d(TAG, "Received Intent for device: " + device + " " + action);
                } else {
                    Log.d(TAG, "Received Intent no device: " + action);
                }
            }
            ConnectionParams connectParams;
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.ERROR);
                updateBondState(device, bondState);

            } else if (BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                connectParams = new ConnectionParams(BluetoothProfile.A2DP_SINK, device);
                int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                notifyConnectionStatus(connectParams, currState);

            } else if (BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                connectParams = new ConnectionParams(BluetoothProfile.HEADSET_CLIENT, device);
                int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                notifyConnectionStatus(connectParams, currState);

            } else if (BluetoothPan.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                connectParams = new ConnectionParams(BluetoothProfile.PAN, device);
                int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                notifyConnectionStatus(connectParams, currState);

            } else if (BluetoothPbapClient.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                connectParams = new ConnectionParams(BluetoothProfile.PBAP_CLIENT, device);
                int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                notifyConnectionStatus(connectParams, currState);

            } else if (BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                connectParams = new ConnectionParams(BluetoothProfile.MAP_CLIENT, device);
                int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                notifyConnectionStatus(connectParams, currState);

            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int currState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (DBG) {
                    Log.d(TAG, "Bluetooth Adapter State: " + currState);
                }
                if (currState == BluetoothAdapter.STATE_ON) {
                    // Read from Settings which devices to connect to and populate
                    // mProfileToConnectableDevicesMap
                    readAndRebuildDeviceMapFromSettings();
                    initiateConnection();
                } else if (currState == BluetoothAdapter.STATE_OFF) {
                    // Write currently connected device snapshot to file.
                    mBluetoothAutoConnectStateMachine.sendMessage(
                            BluetoothAutoConnectStateMachine.ADAPTER_OFF);
                    writeDeviceInfoToSettings();
                    resetBluetoothDevicesConnectionInfo();
                }
            } else if (BluetoothDevice.ACTION_UUID.equals(action)) {
                // Received during pairing with the UUIDs of the Bluetooth profiles supported by
                // the remote device.
                if (DBG) {
                    Log.d(TAG, "Received UUID intent for device " + device);
                }
                Parcelable[] uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                if (uuids != null) {
                    ParcelUuid[] uuidsToSend = new ParcelUuid[uuids.length];
                    for (int i = 0; i < uuidsToSend.length; i++) {
                        uuidsToSend[i] = (ParcelUuid) uuids[i];
                    }
                    setProfilePriorities(device, uuidsToSend, BluetoothProfile.PRIORITY_ON);
                }

            }
        }
    }

    /**
     * Set priority for the Bluetooth profiles.
     *
     * The Bluetooth service stores the priority of a Bluetooth profile per device as a key value
     * pair - BluetoothProfile_device:<Priority>.
     * When we pair a device from the Settings App, the expected behavior is for the app to connect
     * on all appropriate profiles after successful pairing automatically, without the user having
     * to explicitly issue a connect. The settings app checks for the priority of the device from
     * the above key-value pair and if the priority is set to PRIORITY_OFF or PRIORITY_UNDEFINED,
     * the settings app will stop with just pairing and not connect.
     * This scenario will happen when we pair a device, then unpair it and then pair it again.  When
     * the device is unpaired, the BT stack sets the priority for that device to PRIORITY_UNDEFINED
     * ( as a way of resetting).  So, the next time the same device is paired, the Settings app will
     * stop with just pairing and not connect as explained above. Here, we register to receive the
     * ACTION_UUID intent, which will broadcast the UUIDs corresponding to the profiles supported by
     * the remote device which is successfully paired and we turn on the priority so when the
     * Settings app tries to check before connecting, the priority is set to the expected value.
     *
     * @param device   - Remote Bluetooth device
     * @param uuids    - UUIDs of the Bluetooth Profiles supported by the remote device
     * @param priority - priority to set
     */
    private void setProfilePriorities(BluetoothDevice device, ParcelUuid[] uuids, int priority) {
        // need the BluetoothProfile proxy to be able to call the setPriority API
        if (mCarBluetoothUserService == null) {
            mCarBluetoothUserService = setupBluetoothUserService();
        }
        if (mCarBluetoothUserService != null) {
            for (Integer profile : mProfilesToConnect) {
                synchronized (this) {
                    ConnectionParams params = new ConnectionParams(profile, device);
                    // If this profile is inhibited, don't try to change its priority until the
                    // inhibit is released. Instead, if the profile is being enabled, take it off of
                    // the "previously disabled profiles" list, so it will be restored when all
                    // inhibits are removed.
                    if (mProfileInhibits.keySet().contains(params)) {
                        if (DBG) {
                            Log.i(TAG, "Not setting profile " + profile + " priority of "
                                    + device.getAddress() + " to " + priority + ": inhibited");
                        }
                        if (priority == BluetoothProfile.PRIORITY_ON) {
                            mAlreadyDisabledProfiles.remove(params);
                        }
                        continue;
                    }
                }
                setBluetoothProfilePriorityIfUuidFound(uuids, profile, device, priority);
            }
        }
    }

    private void setBluetoothProfilePriorityIfUuidFound(ParcelUuid[] uuids, int profile,
            BluetoothDevice device, int priority) {
        if (mCarBluetoothUserService == null || device == null) {
            return;
        }
        // Build a list of UUIDs that represent a profile.
        List<ParcelUuid> uuidsToCheck = new ArrayList<>();
        switch (profile) {
            case BluetoothProfile.A2DP_SINK:
                uuidsToCheck.add(BluetoothUuid.AudioSource);
                break;
            case BluetoothProfile.HEADSET_CLIENT:
                uuidsToCheck.add(BluetoothUuid.Handsfree_AG);
                uuidsToCheck.add(BluetoothUuid.HSP_AG);
                break;
            case BluetoothProfile.PBAP_CLIENT:
                uuidsToCheck.add(BluetoothUuid.PBAP_PSE);
                break;
            case BluetoothProfile.MAP_CLIENT:
                uuidsToCheck.add(BluetoothUuid.MAS);
                break;
            case BluetoothProfile.PAN:
                uuidsToCheck.add(BluetoothUuid.PANU);
                break;
        }

        for (ParcelUuid uuid : uuidsToCheck) {
            if (BluetoothUuid.isUuidPresent(uuids, uuid)) {
                try {
                    mCarBluetoothUserService.setProfilePriority(profile, device, priority);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException calling setProfilePriority");
                }
                // if any one of the uuid in uuidsTocheck is present, set the priority and break
                break;
            }
        }
    }

    /**
     * Cleanup state and reinitialize whenever we connect to the PerUserCarService.
     * This happens in init() and whenever the PerUserCarService is restarted on User Switch Events
     */
    @VisibleForTesting
    class UserServiceConnectionCallback implements PerUserCarServiceHelper.ServiceCallback {
        @Override
        public void onServiceConnected(ICarUserService carUserService) {
            if (mCarUserServiceAccessLock != null) {
                mCarUserServiceAccessLock.lock();
                try {
                    mCarUserService = carUserService;
                } finally {
                    mCarUserServiceAccessLock.unlock();
                }
            }
            if (DBG) {
                Log.d(TAG, "Connected to PerUserCarService");
            }
            // Get the BluetoothUserService and also setup the Bluetooth Connection Proxy for
            // all profiles.
            mCarBluetoothUserService = setupBluetoothUserService();
            // re-initialize for current user.
            initializeUserSpecificInfo();
            // Restore profile inhibits, if any, that were saved from last run...
            restoreProfileInhibitsFromSettings();
            // ... and start trying to remove them.
            removeRestoredProfileInhibits();
        }

        @Override
        public void onPreUnbind() {
            if (DBG) {
                Log.d(TAG, "Before Unbinding from UserService");
            }

            // Try to release profile inhibits now, before CarBluetoothUserService goes away.
            // This also stops any active attempts to remove restored inhibits.
            //
            // If any can't be released, they'll persist in settings and will be cleaned up
            // next time this user starts. This can happen if the Bluetooth profile proxies in
            // CarBluetoothUserService unbind before we get the chance to make calls on them.
            releaseAllInhibitsBeforeUnbind();

            try {
                if (mCarBluetoothUserService != null) {
                    mCarBluetoothUserService.closeBluetoothConnectionProxy();
                }
            } catch (RemoteException e) {
                Log.e(TAG,
                        "Remote Exception during closeBluetoothConnectionProxy(): "
                                + e.getMessage());
            }

            // Clean up information related to user who went background.
            cleanupUserSpecificInfo();
        }

        @Override
        public void onServiceDisconnected() {
            if (DBG) {
                Log.d(TAG, "Disconnected from PerUserCarService");
            }
            if (mCarUserServiceAccessLock != null) {
                mCarUserServiceAccessLock.lock();
                try {
                    mCarBluetoothUserService = null;
                    mCarUserService = null;
                } finally {
                    mCarUserServiceAccessLock.unlock();
                }
            }
        }
    }

    /**
     * Gets the Per User Car Bluetooth Service (ICarBluetoothService) from the PerUserCarService
     * which acts as a top level Service running in the current user context.
     * Also sets up the connection proxy objects required to communicate with the Bluetooth
     * Profile Services.
     *
     * @return ICarBluetoothUserService running in current user
     */
    private ICarBluetoothUserService setupBluetoothUserService() {
        ICarBluetoothUserService carBluetoothUserService = null;
        if (mCarUserService != null) {
            try {
                carBluetoothUserService = mCarUserService.getBluetoothUserService();
                if (carBluetoothUserService != null) {
                    if (DBG) {
                        Log.d(TAG, "Got CarBTUsrSvc");
                    }
                    carBluetoothUserService.setupBluetoothConnectionProxy();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Remote Service Exception on ServiceConnection Callback: "
                        + e.getMessage());
            }
        } else {
            if (DBG) {
                Log.d(TAG, "PerUserCarService not connected");
            }
        }
        return carBluetoothUserService;
    }

    /**
     * Setup the Bluetooth profile service connections and Vehicle Event listeners.
     * and start the state machine -{@link BluetoothAutoConnectStateMachine}
     */
    public synchronized void init() {
        if (DBG) {
            Log.d(TAG, "init()");
        }
        // Initialize information specific to current user.
        initializeUserSpecificInfo();
        // Listen to various events coming from the vehicle.
        setupEventListenersLocked();
        mInitialized = true;
        mCarPowerManager = CarLocalServices.createCarPowerManager(mContext);
        mCarPowerManager.setListenerWithCompletion(mCarPowerStateListener);
    }

    /**
     * Setup and initialize information that is specific per User account, which involves:
     * 1. Reading the list of devices to connect for current user and initialize the deviceMap
     * with that information.
     * 2. Register a BroadcastReceiver for bluetooth related events for the current user.
     * 3. Start and bind to {@link PerUserCarService} as current user.
     * 4. Start the {@link BluetoothAutoConnectStateMachine}
     */
    private void initializeUserSpecificInfo() {
        synchronized (mSetupLock) {
            if (DBG) {
                Log.d(TAG, "initializeUserSpecificInfo()");
            }
            if (mUserSpecificInfoInitialized) {
                if (DBG) {
                    Log.d(TAG, "Already Initialized");
                }
                return;
            }
            mUserId = ActivityManager.getCurrentUser();
            mBluetoothAutoConnectStateMachine = BluetoothAutoConnectStateMachine.make(this);
            readAndRebuildDeviceMapFromSettings();
            setupBluetoothEventsIntentFilterLocked();

            mConnectionInFlight = new ConnectionParams();
            mUserSpecificInfoInitialized = true;
        }
    }

    /**
     * Setting up the Intent filter for Bluetooth related broadcasts
     * This includes knowing when the
     * 1. Bluetooth Adapter turned on/off
     * 2. Bonding State of a device changes
     * 3. A specific profile's connection state changes.
     */
    private void setupBluetoothEventsIntentFilterLocked() {
        mBluetoothBroadcastReceiver = new BluetoothBroadcastReceiver();
        IntentFilter profileFilter = new IntentFilter();
        profileFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        profileFilter.addAction(BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
        profileFilter.addAction(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
        profileFilter.addAction(BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
        profileFilter.addAction(BluetoothPbapClient.ACTION_CONNECTION_STATE_CHANGED);
        profileFilter.addAction(BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED);
        profileFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        profileFilter.addAction(BluetoothDevice.ACTION_UUID);
        if (mContext != null) {
            mContext.registerReceiverAsUser(mBluetoothBroadcastReceiver, UserHandle.CURRENT,
                    profileFilter, null, null);
        }
    }

    /**
     * Initialize the {@link #mProfileToConnectableDevicesMap}.
     * {@link #mProfileToConnectableDevicesMap} stores the profile:DeviceList information.  This
     * method retrieves it from persistent memory.
     */
    private synchronized void initDeviceMap() {
        if (mProfileToConnectableDevicesMap == null) {
            mProfileToConnectableDevicesMap = new HashMap<>();
            for (Integer profile : mProfilesToConnect) {
                // Build the BluetoothDevicesInfo for this profile.
                BluetoothDevicesInfo devicesInfo = new BluetoothDevicesInfo(profile,
                        sNumSupportedActiveConnections.get(profile));
                mProfileToConnectableDevicesMap.put(profile, devicesInfo);
            }
            if (DBG) {
                Log.d(TAG, "Created a new empty Device Map");
            }
        }
    }

    /**
     * Setting up Listeners to the various events we are interested in listening to for initiating
     * Bluetooth connection attempts.
     */
    private void setupEventListenersLocked() {
        // Setting up a listener for events from CarPropertyService
        // For now, we listen to door unlock signal and Ignition state START coming from
        // {@link CarPropertyService}
        mCarPropertyService.registerListener(VehicleProperty.DOOR_LOCK, 0, mPropertyEventListener);
        mCarPropertyService.registerListener(VehicleProperty.IGNITION_STATE, 0,
                mPropertyEventListener);
        // Get Current restrictions and handle them
        handleUxRestrictionsChanged(mUxRService.getCurrentUxRestrictions());
        // Register for future changes to the DrivingStateRestrictions
        mUxRService.registerUxRestrictionsChangeListener(mUxRListener);
        mUserServiceHelper.registerServiceCallback(mServiceCallback);
    }

    /**
     * Handles events coming in from the {@link CarPropertyService}
     * The events that can trigger Bluetooth Scanning from CarPropertyService are Door Unlock and
     * Igntion START.  Upon an event of interest, initiate a connection attempt by calling
     * the policy {@link BluetoothDeviceConnectionPolicy}
     */
    @VisibleForTesting
    class CarPropertyListener extends ICarPropertyEventListener.Stub {
        @Override
        public void onEvent(List<CarPropertyEvent> events) throws RemoteException {
            for (CarPropertyEvent event : events) {
                if (DBG) {
                    Log.d(TAG, "Cabin change Event : " + event.getEventType());
                }
                CarPropertyValue value = event.getCarPropertyValue();
                Object o = value.getValue();

                switch (value.getPropertyId()) {
                    case VehicleProperty.DOOR_LOCK:
                        if (o instanceof Boolean) {
                            Boolean locked = (Boolean) o;
                            if (DBG) {
                                Log.d(TAG, "Door Lock: " + locked);
                            }
                            // Attempting a connection only on a door unlock
                            if (!locked) {
                                initiateConnection();
                            }
                        }
                        break;
                    case VehicleProperty.IGNITION_STATE:
                        if (o instanceof Integer) {
                            Integer state = (Integer) o;
                            if (DBG) {
                                Log.d(TAG, "Sensor value : " + state);
                            }
                            // Attempting a connection only on IgntionState START
                            if (state == VehicleIgnitionState.START) {
                                initiateConnection();
                            }
                        }
                        break;
                }
            }
        }
    }

    private class CarUxRServiceListener extends ICarUxRestrictionsChangeListener.Stub {
        @Override
        public void onUxRestrictionsChanged(CarUxRestrictions restrictions) {
            handleUxRestrictionsChanged(restrictions);
        }
    }

    private void handleUxRestrictionsChanged(CarUxRestrictions restrictions) {
        if (restrictions == null) {
            return;
        }
        if ((restrictions.getActiveRestrictions() & CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP)
                == CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP) {
            mFastPairProvider.stopAdvertising();
        } else {
            mFastPairProvider.startAdvertising();
        }
    }

    /**
     * Clean up slate. Close the Bluetooth profile service connections and quit the state machine -
     * {@link BluetoothAutoConnectStateMachine}
     */
    public synchronized void release() {
        if (DBG) {
            Log.d(TAG, "release()");
        }
        mInitialized = false;
        mCarPowerManager.clearListener();
        writeDeviceInfoToSettings();
        cleanupUserSpecificInfo();
        closeEventListeners();
    }

    /**
     * Clean up information related to user who went background.
     */
    private void cleanupUserSpecificInfo() {
        synchronized (mSetupLock) {
            if (DBG) {
                Log.d(TAG, "cleanupUserSpecificInfo()");
            }
            if (!mUserSpecificInfoInitialized) {
                if (DBG) {
                    Log.d(TAG, "User specific Info Not initialized..Not cleaning up");
                }
                return;
            }
            mUserSpecificInfoInitialized = false;
            // quit the state machine
            mBluetoothAutoConnectStateMachine.doQuit();
            mProfileToConnectableDevicesMap = null;
            mConnectionInFlight = null;
            if (mBluetoothBroadcastReceiver != null) {
                if (mContext != null) {
                    mContext.unregisterReceiver(mBluetoothBroadcastReceiver);
                }
                mBluetoothBroadcastReceiver = null;
            }
        }
    }

    /**
     * Unregister the listeners to the various Vehicle events coming from other parts of the
     * CarService
     */
    private void closeEventListeners() {
        if (DBG) {
            Log.d(TAG, "closeEventListeners()");
        }
        mCarPropertyService.unregisterListener(VehicleProperty.DOOR_LOCK, mPropertyEventListener);
        mCarPropertyService.unregisterListener(VehicleProperty.IGNITION_STATE,
                mPropertyEventListener);
        mUserServiceHelper.unregisterServiceCallback(mServiceCallback);
    }

    /**
     * Resets the {@link BluetoothDevicesInfo#mConnectionInfo} of all the profiles to start from
     * a clean slate.  The ConnectionInfo has all the book keeping information regarding the state
     * of connection attempts - like which device in the device list for the profile is the next
     * to try connecting etc.
     * This method does not clear the {@link BluetoothDevicesInfo#mDeviceInfoList} like the {@link
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

    @VisibleForTesting
    BroadcastReceiver getBluetoothBroadcastReceiver() {
        return mBluetoothBroadcastReceiver;
    }

    @VisibleForTesting
    UserServiceConnectionCallback getServiceCallback() {
        return mServiceCallback;
    }

    @VisibleForTesting
    CarPropertyListener getCarPropertyListener() {
        return mPropertyEventListener;
    }

    @VisibleForTesting
    synchronized void setAllowReadWriteToSettings(boolean allowWrite) {
        mAllowReadWriteToSettings = allowWrite;
    }

    @VisibleForTesting
    BluetoothDevicesInfo getBluetoothDevicesInfo(int profile) {
        return mProfileToConnectableDevicesMap.get(profile);
    }

    @VisibleForTesting
    String toDebugString() {
        StringBuilder buf = new StringBuilder();
        for (Integer profile : mProfileToConnectableDevicesMap.keySet()) {
            BluetoothDevicesInfo info = mProfileToConnectableDevicesMap.get(profile);
            buf.append(" \n**** profile = " + profile);
            buf.append(", " + info.toDebugString());
        }
        return buf.toString();
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
    public synchronized List<Integer> getProfilesToConnect() {
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
     * Request to disconnect the given profile on the given device, and prevent it from reconnecting
     * until either the request is released, or the process owning the given token dies.
     * @return True if the profile was successfully inhibited, false if an error occurred.
     */
    boolean requestProfileInhibit(BluetoothDevice device, int profile, IBinder token) {
        if (DBG) {
            Log.d(TAG, "Request profile inhibit: profile " + Utils.getProfileName(profile)
                    + ", device " + device.getAddress());
        }
        ConnectionParams params = new ConnectionParams(profile, device);
        InhibitRecord record = new InhibitRecord(params, token);
        return addInhibitRecord(record);
    }

    /**
     * Undo a previous call to {@link #requestProfileInhibit} with the same parameters,
     * and reconnect the profile if no other requests are active.
     *
     * @return True if the request was released, false if an error occurred.
     */
    boolean releaseProfileInhibit(BluetoothDevice device, int profile, IBinder token) {
        if (DBG) {
            Log.d(TAG, "Release profile inhibit: profile " + Utils.getProfileName(profile)
                    + ", device " + device.getAddress());
        }

        ConnectionParams params = new ConnectionParams(profile, device);
        InhibitRecord record;
        synchronized (this) {
            record = findInhibitRecordLocked(params, token);
        }

        if (record == null) {
            Log.e(TAG, "Record not found");
            return false;
        }

        return record.removeSelf();
    }

    /** Add a profile inhibit record, disabling the profile if necessary. */
    private synchronized boolean addInhibitRecord(InhibitRecord record) {
        ConnectionParams params = record.getParams();
        if (!isProxyAvailable(params.getBluetoothProfile())) {
            return false;
        }

        Set<InhibitRecord> previousRecords = mProfileInhibits.get(params);
        if (findInhibitRecordLocked(params, record.getToken()) != null) {
            Log.e(TAG, "Inhibit request already registered - skipping duplicate");
            return false;
        }

        try {
            record.getToken().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not link to death on inhibit token (already dead?)", e);
            return false;
        }

        boolean isNewlyAdded = previousRecords.isEmpty();
        mProfileInhibits.put(params, record);

        if (isNewlyAdded) {
            try {
                int priority =
                        mCarBluetoothUserService.getProfilePriority(
                                params.getBluetoothProfile(),
                                params.getBluetoothDevice());
                if (priority == BluetoothProfile.PRIORITY_OFF) {
                    // This profile was already disabled (and not as the result of an inhibit).
                    // Add it to the already-disabled list, and do nothing else.
                    mAlreadyDisabledProfiles.add(params);

                    if (DBG) {
                        Log.d(TAG, "Profile " + Utils.getProfileName(params.getBluetoothProfile())
                                + " already disabled for device " + params.getBluetoothDevice()
                                + " - suppressing re-enable");
                    }
                } else {
                    mCarBluetoothUserService.setProfilePriority(
                            params.getBluetoothProfile(),
                            params.getBluetoothDevice(),
                            BluetoothProfile.PRIORITY_OFF);
                    mCarBluetoothUserService.bluetoothDisconnectFromProfile(
                            params.getBluetoothProfile(),
                            params.getBluetoothDevice());
                    if (DBG) {
                        Log.d(TAG, "Disabled profile "
                                + Utils.getProfileName(params.getBluetoothProfile())
                                + " for device " + params.getBluetoothDevice());
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Could not disable profile", e);
                record.getToken().unlinkToDeath(record, 0);
                mProfileInhibits.remove(params, record);
                return false;
            }
        }

        saveProfileInhibitsToSettingsLocked();
        return true;
    }

    /** Remove a given profile inhibit record, reconnecting if necessary. */
    private synchronized boolean removeInhibitRecord(InhibitRecord record) {
        ConnectionParams params = record.getParams();
        if (!isProxyAvailable(params.getBluetoothProfile())) {
            return false;
        }
        if (!mProfileInhibits.containsEntry(params, record)) {
            Log.e(TAG, "Record already removed");
            // Removing something a second time vacuously succeeds.
            return true;
        }

        // Re-enable profile before unlinking and removing the record, in case of error.
        // The profile should be re-enabled if this record is the only one left for that
        // device and profile combination.
        if (mProfileInhibits.get(params).size() == 1) {
            if (!restoreProfilePriority(params)) {
                return false;
            }
        }

        record.getToken().unlinkToDeath(record, 0);
        mProfileInhibits.remove(params, record);

        saveProfileInhibitsToSettingsLocked();
        return true;
    }

    /** Find the inhibit record, if any, corresponding to the given parameters and token. */
    @Nullable
    private InhibitRecord findInhibitRecordLocked(ConnectionParams params, IBinder token) {
        return mProfileInhibits.get(params)
            .stream()
            .filter(r -> r.getToken() == token)
            .findAny()
            .orElse(null);
    }

    /** Re-enable and reconnect a given profile for a device. */
    private boolean restoreProfilePriority(ConnectionParams params) {
        if (!isProxyAvailable(params.getBluetoothProfile())) {
            return false;
        }

        if (mAlreadyDisabledProfiles.remove(params)) {
            // The profile does not need any state changes, since it was disabled
            // before it was inhibited. Leave it disabled.
            if (DBG) {
                Log.d(TAG, "Not restoring profile "
                        + Utils.getProfileName(params.getBluetoothProfile()) + " for device "
                        + params.getBluetoothDevice() + " - was manually disabled");
            }
            return true;
        }

        try {
            mCarBluetoothUserService.setProfilePriority(
                    params.getBluetoothProfile(),
                    params.getBluetoothDevice(),
                    BluetoothProfile.PRIORITY_ON);
            mCarBluetoothUserService.bluetoothConnectToProfile(
                    params.getBluetoothProfile(),
                    params.getBluetoothDevice());
            if (DBG) {
                Log.d(TAG, "Restored profile " + Utils.getProfileName(params.getBluetoothProfile())
                        + " for device " + params.getBluetoothDevice());
            }
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Could not enable profile", e);
            return false;
        }
    }

    /** Dump all currently-active profile inhibits to {@link Settings.Secure}. */
    private void saveProfileInhibitsToSettingsLocked() {
        Set<ConnectionParams> inhibitedProfiles = new HashSet<>(mProfileInhibits.keySet());
        // Don't write out profiles that were disabled before a request was made, since
        // restoring those profiles is a no-op.
        inhibitedProfiles.removeAll(mAlreadyDisabledProfiles);
        String savedDisconnects =
                inhibitedProfiles
                        .stream()
                        .map(ConnectionParams::flattenToString)
                        .collect(Collectors.joining(SETTINGS_DELIMITER));

        if (DBG) {
            Log.d(TAG, "Saving inhibits to settings for u" + mUserId + ": " + savedDisconnects);
        }

        Settings.Secure.putStringForUser(
                mContext.getContentResolver(), KEY_BLUETOOTH_PROFILES_INHIBITED,
                savedDisconnects, mUserId);
    }

    /** Create {@link InhibitRecord}s for all profile inhibits written to settings. */
    private synchronized void restoreProfileInhibitsFromSettings() {
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Cannot restore inhibit records - Bluetooth not available");
            return;
        }

        String savedConnectionParams = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                KEY_BLUETOOTH_PROFILES_INHIBITED,
                mUserId);

        if (TextUtils.isEmpty(savedConnectionParams)) {
            return;
        }

        if (DBG) {
            Log.d(TAG, "Restoring profile inhibits: " + savedConnectionParams);
        }

        for (String paramsStr : savedConnectionParams.split(SETTINGS_DELIMITER)) {
            try {
                ConnectionParams params = ConnectionParams.parse(paramsStr, mBluetoothAdapter);
                InhibitRecord record =
                        new InhibitRecord(params, RESTORED_PROFILE_INHIBIT_TOKEN);
                mProfileInhibits.put(params, record);
                mRestoredInhibits.add(record);
                if (DBG) {
                    Log.d(TAG, "Restored profile inhibits for " + params);
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Bad format for saved profile inhibit: " + paramsStr, e);
                // We won't ever be able to fix a bad parse, so skip it and move on.
            }
        }
    }

    /**
     * Try once to remove all restored profile inhibits.
     *
     * If the CarBluetoothUserService is not yet available, or it hasn't yet bound its profile
     * proxies, the removal will fail, and will need to be retried later.
     */
    private void tryRemoveRestoredProfileInhibitsLocked() {
        HashSet<InhibitRecord> successfullyRemoved = new HashSet<>();

        for (InhibitRecord record : mRestoredInhibits) {
            if (removeInhibitRecord(record)) {
                successfullyRemoved.add(record);
            }
        }

        mRestoredInhibits.removeAll(successfullyRemoved);
    }

    /**
     * Keep trying to remove all profile inhibits that were restored from settings
     * until all such inhibits have been removed.
     */
    private synchronized void removeRestoredProfileInhibits() {
        tryRemoveRestoredProfileInhibitsLocked();

        if (!mRestoredInhibits.isEmpty()) {
            if (DBG) {
                Log.d(TAG, "Could not remove all restored profile inhibits - "
                        + "trying again in " + RESTORE_BACKOFF_MILLIS + "ms");
            }
            mHandler.postDelayed(
                    this::removeRestoredProfileInhibits,
                    RESTORED_PROFILE_INHIBIT_TOKEN,
                    RESTORE_BACKOFF_MILLIS);
        }
    }

    /** Release all active inhibit records prior to user switch or shutdown. */
    private synchronized void releaseAllInhibitsBeforeUnbind() {
        if (DBG) {
            Log.d(TAG, "Unbinding CarBluetoothUserService - releasing all profile inhibits");
        }
        for (ConnectionParams params : mProfileInhibits.keySet()) {
            for (InhibitRecord record : mProfileInhibits.get(params)) {
                record.removeSelf();
            }
        }

        // Some inhibits might be hanging around because they couldn't be cleaned up.
        // Make sure they get persisted...
        saveProfileInhibitsToSettingsLocked();
        // ...then clear them from the map.
        mProfileInhibits.clear();

        // We don't need to maintain previously-disabled profiles any more - they were already
        // skipped in saveProfileInhibitsToSettingsLocked() above, and they don't need any
        // further handling when the user resumes.
        mAlreadyDisabledProfiles.clear();

        // Clean up bookkeeping for restored inhibits. (If any are still around, they'll be
        // restored again when this user restarts.)
        mHandler.removeCallbacksAndMessages(RESTORED_PROFILE_INHIBIT_TOKEN);
        mRestoredInhibits.clear();
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
            Log.d(TAG, "BondState :" + bondState + " Device: " + device);
        }
        // Bonded devices are added to a profile's device list after the device CONNECTS on the
        // profile.  When unpaired, we remove the device from all of the profiles' device list.
        if (bondState == BluetoothDevice.BOND_NONE) {
            for (Integer profile : mProfilesToConnect) {
                if (DBG) {
                    Log.d(TAG, "Removing " + device + " from profile: " + profile);
                }
                removeDeviceFromProfile(device, profile);
            }
        } else if (bondState == BluetoothDevice.BOND_BONDED) {
            // A device just paired. When it connects on HFP profile, then immediately initiate
            // connections on PBAP and MAP. for now, maintain this fact in a data structure
            // to be looked up when HFP profile connects.
            mPairedButUnconnectedDevices.add(device);
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
                    Log.d(TAG, "Device Map is empty. Nothing to connect to");
                }
                return;
            }
            resetDeviceAvailableToConnect();
            if (DBG) {
                Log.d(TAG, "initiateConnection() Reset Device Availability");
            }
            mBluetoothAutoConnectStateMachine.sendMessage(BluetoothAutoConnectStateMachine
                    .CONNECT);
        } else {
            if (DBG) {
                Log.d(TAG, "Bluetooth Adapter not enabled.");
            }
        }
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
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()
                || mProfileToConnectableDevicesMap == null || !mInitialized) {
            if (DBG) {
                if (mProfileToConnectableDevicesMap == null) {
                    Log.d(TAG, "findDeviceToConnect(): Device Map null");
                } else {
                    Log.d(TAG, "findDeviceToConnect(): BT Adapter not enabled");
                }
            }
            return false;
        }
        boolean connectingToADevice = false;
        // Get the first unconnected profile that we can try to make a connection
        Integer nextProfile = getNextProfileToConnectLocked();
        // Keep going through the profiles until we find a device that we can connect to
        while (nextProfile != PROFILE_NOT_AVAILABLE) {
            if (DBG) {
                Log.d(TAG, "connectToProfile(): " + nextProfile);
            }
            // find a device that is next in line for a connection attempt for that profile
            // and try connecting to it.
            connectingToADevice = connectToNextDeviceInQueueLocked(nextProfile);
            // If we found a device to connect, break out of the loop
            if (connectingToADevice) {
                if (DBG) {
                    Log.d(TAG, "Found device to connect to");
                }
                // set up a time out
                mBluetoothAutoConnectStateMachine.sendMessageDelayed(
                        BluetoothAutoConnectStateMachine.CONNECT_TIMEOUT, mConnectionInFlight,
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
        return connectingToADevice;
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
                if (devInfo.isProfileConnectableLocked()) {
                    return profile;
                }
            } else {
                Log.e(TAG, "Unexpected: devInfo null for profile: " + profile);
            }
        }
        // Reaching here denotes all profiles are connected or No devices available for any profile
        if (DBG) {
            Log.d(TAG, "No disconnected profiles");
        }
        return PROFILE_NOT_AVAILABLE;
    }

    /**
     * Checks if the Bluetooth profile service's proxy object is available.
     * Proxy obj should be available before we can attempt to connect on that profile.
     *
     * @param profile The profile to check the presence of the proxy object
     * @return True if the proxy obj exists. False otherwise.
     */
    private boolean isProxyAvailable(Integer profile) {
        if (mCarBluetoothUserService == null) {
            mCarBluetoothUserService = setupBluetoothUserService();
            if (mCarBluetoothUserService == null) {
                Log.d(TAG, "CarBluetoothUserSvc null.  Car service not bound to PerUserCarSvc.");
                return false;
            }
        }
        try {
            if (!mCarBluetoothUserService.isBluetoothConnectionProxyAvailable(profile)) {
                // proxy unavailable.
                if (DBG) {
                    Log.d(TAG, "Proxy for Bluetooth Profile Service Unavailable: " + profile);
                }
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Car BT Service Remote Exception.");
            return false;
        }
        return true;
    }

    /**
     * Creates a device connection for the given profile.
     *
     * @param profile The profile on which the connection is being setup
     * @param device  The device for which the connection is to be made.
     * @return true if the connection is setup successfully. False otherwise.
     */
    boolean connectToDeviceOnProfile(Integer profile, BluetoothDevice device) {
        if (DBG) {
            Log.d(TAG, "in connectToDeviceOnProfile for Profile:" + profile +
                    ", device: " + Utils.getDeviceDebugInfo(device));
        }
        if (!isProxyAvailable(profile)) {
            if (DBG) {
                Log.d(TAG, "No proxy available for Profile: " + profile);
            }
            return false;
        }
        BluetoothDevicesInfo devInfo = mProfileToConnectableDevicesMap.get(profile);
        if (devInfo == null) {
            if (DBG) {
                Log.d(TAG, "devInfo NULL on Profile:" + profile);
            }
            return false;
        }
        int state = devInfo.getCurrentConnectionStateLocked(device);
        if (state == BluetoothProfile.STATE_CONNECTED ||
                state == BluetoothProfile.STATE_CONNECTING) {
            if (DBG) {
                Log.d(TAG, "device " + Utils.getDeviceDebugInfo(device) +
                        " is already connected/connecting on Profile:" + profile);
            }
            return true;
        }
        if (!devInfo.isProfileConnectableLocked()) {
            if (DBG) {
                Log.d(TAG, "isProfileConnectableLocked FALSE on Profile:" + profile +
                        "  this means number of connections on this profile max'ed out or " +
                        " no more devices available to connect on this profile");
            }
            return false;
        }
        try {
            if (mCarBluetoothUserService != null) {
                mCarBluetoothUserService.bluetoothConnectToProfile((int) profile, device);
                devInfo.setConnectionStateLocked(device, BluetoothProfile.STATE_CONNECTING);
                // Increment the retry count & cache what is being connected to
                // This method is already called from a synchronized context.
                mConnectionInFlight = new ConnectionParams(profile, device);
                devInfo.incrementRetryCountLocked();
                if (DBG) {
                    Log.d(TAG, "Increment Retry to: " + devInfo.getRetryCountLocked() +
                            ", for Profile: " + profile + ", on device: " +
                            Utils.getDeviceDebugInfo(device));
                }
                return true;
            } else {
                Log.e(TAG, "CarBluetoothUserSvc null");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Remote User Service stopped responding: " + e.getMessage());
        }
        return false;
    }

    /**
     * Helper method to reset info in {@link #mConnectionInFlight} in the given
     * {@link BluetoothDevicesInfo} object.
     *
     * @param devInfo the {@link BluetoothDevicesInfo} where the info is to be reset.
     */
    private void setProfileOnDeviceToUnavailable(BluetoothDevicesInfo devInfo) {
        mConnectionInFlight = new ConnectionParams(0, null);
        devInfo.setDeviceAvailableToConnectLocked(false);
    }

    boolean doesDeviceExistForProfile(Integer profile, BluetoothDevice device) {
        BluetoothDevicesInfo devInfo = mProfileToConnectableDevicesMap.get(profile);
        if (devInfo == null) {
            if (DBG) {
                Log.d(TAG, "devInfo NULL on Profile:" + profile);
            }
            return false;
        }
        boolean b = devInfo.checkDeviceInListLocked(device);
        if (DBG) {
            Log.d(TAG, "Is device " + Utils.getDeviceDebugInfo(device) +
                    " listed for profile " + profile + ": " + b);
        }
        return b;
    }

    /**
     * Try to connect to the next device in the device list for the given profile.
     *
     * @param profile - profile to connect on
     * @return - true if we found a device to connect on for this profile
     * false - if we cannot find a device to connect to.
     */
    private boolean connectToNextDeviceInQueueLocked(Integer profile) {
        // Get the Device Information for the given profile and find the next device to connect on
        BluetoothDevice devToConnect = null;
        BluetoothDevicesInfo devInfo = mProfileToConnectableDevicesMap.get(profile);
        if (devInfo == null) {
            Log.e(TAG, "Unexpected: No device Queue for this profile: " + profile);
            return false;
        }

        if (isProxyAvailable(profile)) {
            // Get the next device in the device list for this profile.
            devToConnect = devInfo.getNextDeviceInQueueLocked();
            if (devToConnect != null) {
                // deviceAvailable && proxyAvailable
                if (connectToDeviceOnProfile(profile, devToConnect)) return true;
            } else {
                // device unavailable
                if (DBG) {
                    Log.d(TAG, "No paired nearby device to connect to for profile: " + profile);
                }
            }
        }

        // reset the mConnectionInFlight
        setProfileOnDeviceToUnavailable(devInfo);
        return false;
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
                    + deviceThatConnected);
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
                    Log.d(TAG, "Updating device: " + deviceThatConnected
                            + " different from connection in flight: "
                            + mConnectionInFlight.getBluetoothDevice());

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
        writeDeviceInfoToSettings(params);
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
            devInfo.resetDeviceIndex();
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
        for (Integer profile : mProfileToConnectableDevicesMap.keySet()) {
            writer.print("Profile: " + Utils.getProfileName(profile) + "\t");
            writer.print("\n" + mProfileToConnectableDevicesMap.get(profile).toDebugString());
            writer.println();
        }
    }

    /**
     * Get the persisted Bluetooth state from Settings
     */
    private boolean isBluetoothPersistedOn() {
        return (Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.BLUETOOTH_ON, -1) != 0);
    }

    /**
     * Turn on the Bluetooth Adapter.
     */
    private void enabledBluetooth() {
        if (DBG) Log.d(TAG, "Enable bluetooth adapter");
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Cannot enable Bluetooth adapter. The object is null.");
            return;
        }
        mBluetoothAdapter.enable();
    }

    /**
     * Turn off the Bluetooth Adapter.
     *
     * Tells BluetoothAdapter to shut down _without_ persisting the off state as the desired state
     * of the Bluetooth adapter for next start up.
     */
    private void disableBluetooth() {
        if (DBG) Log.d(TAG, "Disable bluetooth, do not persist state across reboot");
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Cannot disable Bluetooth adapter. The object is null.");
            return;
        }
        mBluetoothAdapter.disable(false);
    }

    /**
     * Write the device list for all bluetooth profiles that connected.
     *
     * @return true if the write was successful, false otherwise
     */
    private synchronized boolean writeDeviceInfoToSettings() {
        ConnectionParams params;
        boolean writeResult;
        for (Integer profile : mProfilesToConnect) {
            params = new ConnectionParams(profile);
            writeResult = writeDeviceInfoToSettings(params);
            if (!writeResult) {
                Log.e(TAG, "Error writing Device Info for profile:" + profile);
                return writeResult;
            }
        }
        return true;
    }

    /**
     * Write information about which devices connected on which profile to Settings.Secure.
     * Essentially the list of devices that a profile can connect on the next auto-connect
     * attempt.
     *
     * @param params - ConnectionParams indicating which bluetooth profile to write this
     *               information
     *               for.
     * @return true if the write was successful, false otherwise
     */
    public synchronized boolean writeDeviceInfoToSettings(ConnectionParams params) {
        if (!mAllowReadWriteToSettings) {
            return false;
        }
        boolean writeSuccess = true;
        Integer profileToUpdate = params.getBluetoothProfile();

        if (mProfileToConnectableDevicesMap == null) {
            writeSuccess = false;
        } else {
            List<String> deviceNames = new ArrayList<>();
            BluetoothDevicesInfo devicesInfo = mProfileToConnectableDevicesMap.get(profileToUpdate);
            StringBuilder sb = new StringBuilder();
            String delimiter = ""; // start off with no delimiter.

            // Iterate through the List<BluetoothDevice> and build a String that is
            // names of all devices to be connected for this profile joined together and
            // delimited by a delimiter (its a ',' here)
            if (devicesInfo != null && devicesInfo.getDeviceList() != null) {
                for (BluetoothDevice device : devicesInfo.getDeviceList()) {
                    sb.append(delimiter);
                    sb.append(device.getAddress());
                    delimiter = SETTINGS_DELIMITER;
                }

            }
            // joinedDeviceNames has something like "22:22:33:44:55:AB,22:23:xx:xx:xx:xx"
            // mac addresses of connectable devices separated by a delimiter
            String joinedDeviceNames = sb.toString();
            if (DBG) {
                Log.d(TAG, "Profile: " + profileToUpdate + " Writing: " + joinedDeviceNames);
            }
            switch (profileToUpdate) {
                case BluetoothProfile.A2DP_SINK:
                    Settings.Secure.putStringForUser(mContext.getContentResolver(),
                            KEY_BLUETOOTH_AUTOCONNECT_MUSIC_DEVICES,
                            joinedDeviceNames, mUserId);
                    break;

                case BluetoothProfile.HEADSET_CLIENT:
                    Settings.Secure.putStringForUser(mContext.getContentResolver(),
                            KEY_BLUETOOTH_AUTOCONNECT_PHONE_DEVICES,
                            joinedDeviceNames, mUserId);
                    break;

                case BluetoothProfile.PBAP_CLIENT:
                    // use the phone
                    break;

                case BluetoothProfile.MAP_CLIENT:
                    Settings.Secure.putStringForUser(mContext.getContentResolver(),
                            KEY_BLUETOOTH_AUTOCONNECT_MESSAGING_DEVICES,
                            joinedDeviceNames, mUserId);
                    break;
                case BluetoothProfile.PAN:
                    Settings.Secure.putStringForUser(mContext.getContentResolver(),
                            KEY_BLUETOOTH_AUTOCONNECT_NETWORK_DEVICES,
                            joinedDeviceNames, mUserId);
                    break;
            }
        }
        return writeSuccess;
    }

    /**
     * Read the device information from Settings.Secure and populate the
     * {@link #mProfileToConnectableDevicesMap}
     *
     * Device MAC addresses are written to Settings.Secure delimited by a ','.
     * Ex: android.car.BLUETOOTH_AUTOCONNECT_PHONE_DEVICES: xx:xx:xx:xx:xx:xx,yy:yy:yy:yy:yy
     * denotes that two devices with addresses xx:xx:xx:xx:xx:xx & yy:yy:yy:yy:yy:yy were connected
     * as phones (in HFP and PBAP profiles) the last time this user was logged in.
     *
     * @return - true if the read was successful, false if 1. BT Adapter not enabled 2. No prior
     * bonded devices 3. No information stored in Settings for this user.
     */
    public synchronized boolean readAndRebuildDeviceMapFromSettings() {
        List<String> deviceList;
        String devices = null;
        // Create and initialize mProfileToConnectableDevicesMap if needed.
        initDeviceMap();
        if (mBluetoothAdapter != null) {
            if (DBG) {
                Log.d(TAG,
                        "Number of Bonded devices:" + mBluetoothAdapter.getBondedDevices().size());
            }
            if (mBluetoothAdapter.getBondedDevices().isEmpty()) {
                if (DBG) {
                    Log.d(TAG, "No Bonded Devices available. Quit rebuilding");
                }
                return false;
            }
        }
        if (!mAllowReadWriteToSettings) {
            return false;
        }
        // Read from Settings.Secure for the current user.  There are 3 keys 1 each for Phone
        // (HFP & PBAP), 1 for Music (A2DP) and 1 for Messaging device (MAP)
        for (Integer profile : mProfilesToConnect) {
            switch (profile) {
                case BluetoothProfile.A2DP_SINK:
                    devices = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                            KEY_BLUETOOTH_AUTOCONNECT_MUSIC_DEVICES, mUserId);
                    break;
                case BluetoothProfile.PBAP_CLIENT:
                    // fall through
                case BluetoothProfile.HEADSET_CLIENT:
                    devices = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                            KEY_BLUETOOTH_AUTOCONNECT_PHONE_DEVICES, mUserId);
                    break;
                case BluetoothProfile.MAP_CLIENT:
                    devices = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                            KEY_BLUETOOTH_AUTOCONNECT_MESSAGING_DEVICES, mUserId);
                    break;
                case BluetoothProfile.PAN:
                    devices = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                            KEY_BLUETOOTH_AUTOCONNECT_NETWORK_DEVICES, mUserId);
                default:
                    Log.e(TAG, "Unexpected profile");
                    break;
            }

            if (devices == null) {
                if (DBG) {
                    Log.d(TAG, "No device information stored in Settings");
                }
                return false;
            }
            if (DBG) {
                Log.d(TAG, "Devices in Settings: " + devices);
            }
            // Get a list of Device Mac Addresses from the value
            deviceList = Arrays.asList(devices.split(SETTINGS_DELIMITER));
            if (deviceList == null) {
                return false;
            }
            BluetoothDevicesInfo devicesInfo = mProfileToConnectableDevicesMap.get(profile);
            // Do we have a bonded device with this name?  If so, get it and populate the device
            // map.
            for (String address : deviceList) {
                BluetoothDevice deviceToAdd = getBondedDeviceWithGivenName(address);
                if (deviceToAdd != null) {
                    devicesInfo.addDeviceLocked(deviceToAdd);
                } else {
                    if (DBG) {
                        Log.d(TAG, "No device with name " + address + " found in bonded devices");
                    }
                }
            }
            mProfileToConnectableDevicesMap.put(profile, devicesInfo);
            // Check to see if there are any  primary or secondary devices for this profile and
            // update BluetoothDevicesInfo with the priority information.
            for (int priority : mPrioritiesSupported) {
                readAndTagDeviceWithPriorityFromSettings(profile, priority);
            }
        }
        return true;
    }

    /**
     * Read from Secure Settings if there are primary or secondary devices marked for this
     * Bluetooth profile.  If there are tagged devices, update the BluetoothDevicesInfo so the
     * policy can prioritize those devices when making connection attempts.
     *
     * @param profile  - Bluetooth Profile to check
     * @param priority - Priority to check
     */
    private void readAndTagDeviceWithPriorityFromSettings(int profile, int priority) {
        BluetoothDevicesInfo devicesInfo = mProfileToConnectableDevicesMap.get(profile);
        if (devicesInfo == null) {
            return;
        }
        if (!mCarBluetoothService.isPriorityDevicePresent(profile, priority)) {
            // There is no device for this priority - either it hasn't been set or has been removed.
            // So check if the policy has a device associated with this priority and remove it.
            BluetoothDevice deviceToClear = devicesInfo.getBluetoothDeviceForPriorityLocked(
                    priority);
            if (deviceToClear != null) {
                if (DBG) {
                    Log.d(TAG, "Clearing priority for: " +
                            Utils.getDeviceDebugInfo(deviceToClear));
                }
                devicesInfo.removeBluetoothDevicePriorityLocked(deviceToClear);
            }
        } else {
            // There is a device with the given priority for the given profile.  Update the
            // policy's records.
            String deviceName = mCarBluetoothService.getDeviceNameWithPriority(profile,
                    priority);
            if (deviceName != null) {
                BluetoothDevice bluetoothDevice = getBondedDeviceWithGivenName(deviceName);
                if (bluetoothDevice != null) {
                    if (DBG) {
                        Log.d(TAG, "Setting priority: " + priority + " for " + deviceName);
                    }
                    tagDeviceWithPriority(bluetoothDevice, profile, priority);
                }
            }
        }
    }

    /**
     * Tag a Bluetooth device with priority - Primary or Secondary.  This only updates the policy's
     * record (BluetoothDevicesInfo) of the priority information.
     *
     * @param device   - BluetoothDevice to tag
     * @param profile  - BluetoothProfile to tag
     * @param priority - Priority to tag with
     */
    @VisibleForTesting
    void tagDeviceWithPriority(BluetoothDevice device, int profile, int priority) {
        BluetoothDevicesInfo devicesInfo = mProfileToConnectableDevicesMap.get(profile);
        if (device != null) {
            if (DBG) {
                Log.d(TAG, "Profile: " + profile + " : " + device + " Priority: " + priority);
            }
            devicesInfo.setBluetoothDevicePriorityLocked(device, priority);
        }
    }

    /**
     * Given the device name, find the corresponding {@link BluetoothDevice} from the list of
     * Bonded devices.
     *
     * @param name Bluetooth Device name
     */
    @Nullable
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
            if (name.equals(bd.getAddress())) {
                btDevice = bd;
                break;
            }
        }
        return btDevice;
    }


    public void dump(PrintWriter writer) {
        writer.println("*BluetoothDeviceConnectionPolicy*");
        printDeviceMap(writer);
        mBluetoothAutoConnectStateMachine.dump(writer);
        String inhibits;
        synchronized (this) {
            inhibits = mProfileInhibits.keySet().toString();
        }
        writer.println("Inhibited profiles: " + inhibits);
    }
}
