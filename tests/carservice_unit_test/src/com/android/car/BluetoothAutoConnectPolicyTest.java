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
import android.car.ICarUserService;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.cabin.CarCabinManager;
import android.car.hardware.property.CarPropertyEvent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.test.AndroidTestCase;

import static org.mockito.Mockito.*;

import org.mockito.Matchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.junit.Test;

import java.util.List;

/**
 * Unit tests for the {@link BluetoothDeviceConnectionPolicy}.
 * Isolate and test the policy's functionality - test if it finds the right device(s) per profile to
 * connect to when triggered by an appropriate event.
 *
 * The following services are mocked:
 * 1. {@link CarBluetoothUserService} - connect requests to the Bluetooth stack are stubbed out
 * and connection results can be injected (imitating results from the stack)
 * 2. {@link CarCabinService} & {@link CarSensorService} - Fake vehicle events are injected to the
 * policy's Broadcast Receiver.
 */
public class BluetoothAutoConnectPolicyTest extends AndroidTestCase {
    private static final String TAG = "BTPolicyTest";
    private BluetoothDeviceConnectionPolicy mBluetoothDeviceConnectionPolicyTest;
    private BluetoothAdapter mBluetoothAdapter;
    private BroadcastReceiver mReceiver;
    private BluetoothDeviceConnectionPolicy.CarPropertyListener mCabinEventListener;
    private Handler mMainHandler;
    private Context mockContext;
    // Mock of Services that the policy interacts with
    private CarCabinService mockCarCabinService;
    private CarSensorService mockCarSensorService;
    private CarBluetoothUserService mockBluetoothUserService;
    private PerUserCarServiceHelper mockPerUserCarServiceHelper;
    private ICarUserService mockPerUserCarService;
    // Timeouts
    private static final int CONNECTION_STATE_CHANGE_TIME = 500; //ms
    private static final int CONNECTION_REQUEST_TIMEOUT = 10000;//ms
    private static final int WAIT_FOR_COMPLETION_TIME = 1000;//ms

    @Override
    protected void setUp() throws Exception {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mMainHandler = new Handler(Looper.getMainLooper());
        makeMockServices();
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        mBluetoothDeviceConnectionPolicyTest.release();
        super.tearDown();
    }
    /****************************************** Utility methods **********************************/

    /**
     * Pair a device on the given profile.  Note that this is not real Bluetooth Pairing.  This is
     * just adding a (fake) BluetoothDevice to the policy's records, which is what a real Bluetooth
     * pairing would have done.
     *
     * @param device  - Bluetooth device
     * @param profile - Profile to pair on.
     */
    private void pairDeviceOnProfile(BluetoothDevice device, Integer profile) {
        sendFakeConnectionStateChangeOnProfile(device, profile, true);
    }

    /**
     * Inject a fake connection state changed intent to the policy's Broadcast Receiver
     *
     * @param device  - Bluetooth Device
     * @param profile - Bluetooth Profile
     * @param connect - connection Success or Failure
     */
    private void sendFakeConnectionStateChangeOnProfile(BluetoothDevice device, Integer profile,
            boolean connect) {
        assertNotNull(mReceiver);
        Intent connectionIntent = createBluetoothConnectionStateChangedIntent(profile, device,
                connect);
        mReceiver.onReceive(null, connectionIntent);
    }

    /**
     * Utility function to create a Connection State Changed Intent for the given profile and device
     *
     * @param profile - Bluetooth profile
     * @param device  - Bluetooth Device
     * @param connect - Connection Success or Failure
     * @return - Connection State Changed Intent with the filled up EXTRAs
     */
    private Intent createBluetoothConnectionStateChangedIntent(int profile, BluetoothDevice device,
            boolean connect) {
        Intent connectionIntent;
        switch (profile) {
            case BluetoothProfile.A2DP_SINK:
                connectionIntent = new Intent(BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
                break;
            case BluetoothProfile.HEADSET_CLIENT:
                connectionIntent = new Intent(
                        BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
                break;
            case BluetoothProfile.MAP_CLIENT:
                connectionIntent = new Intent(BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED);
                break;
            case BluetoothProfile.PBAP_CLIENT:
                connectionIntent = new Intent(BluetoothPbapClient.ACTION_CONNECTION_STATE_CHANGED);
                break;
            default:
                return null;
        }
        connectionIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        if (connect) {
            connectionIntent.putExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_CONNECTED);
        } else {
            connectionIntent.putExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
        }
        return connectionIntent;
    }

    /**
     * Trigger a fake vehicle Event by injecting directly into the policy's Event Listener.
     * Note that a Cabin Event (Door unlock) is used here.  The default policy has a Cabin Event
     * listener.
     * The event can be changed to what is appropriate as long as there is a corresponding listener
     * implemented in the policy
     */
    private void triggerFakeVehicleEvent() throws RemoteException {
        assertNotNull(mCabinEventListener);
        CarPropertyValue<Boolean> value = new CarPropertyValue<>(CarCabinManager.ID_DOOR_LOCK,
                false);
        CarPropertyEvent event = new CarPropertyEvent(
                CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, value);
        mCabinEventListener.onEvent(event);
    }

    /**
     * Put all the mock creations in one place.  To be called from setup()
     */
    private void makeMockServices() {
        mockContext = mock(Context.class);
        mockCarCabinService = mock(CarCabinService.class);
        mockCarSensorService = mock(CarSensorService.class);
        mockPerUserCarServiceHelper = mock(PerUserCarServiceHelper.class);
        mockPerUserCarService = mock(ICarUserService.class);
        mockBluetoothUserService = mock(CarBluetoothUserService.class,
                Mockito.withSettings().verboseLogging());
    }

    /**
     * Set up the common mock behavior to be used in all the tests.
     * Mainly mocks the Bluetooth Stack behavior
     *
     * @param connectionResult - result to return when a connection is requested.
     */
    private void setupBluetoothMockBehavior(final boolean connectionResult) throws Exception {
        // Return the mock Bluetooth User Service when asked for
        when(mockPerUserCarService.getBluetoothUserService()).thenReturn(mockBluetoothUserService);
        when(mockBluetoothUserService.isBluetoothConnectionProxyAvailable(
                Matchers.anyInt())).thenReturn(true);

        // When the policy issues a connect request, mimic a connection state changed intent
        // broadcast on the policy's main thread.  The connect request comes in from the
        // BluetoothAutoConnectStateMachine's thread.
        Mockito.doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocationOnMock) throws Throwable {
                if (invocationOnMock.getArguments().length < 2) {
                    return null;
                }
                final int profile = (int) invocationOnMock.getArguments()[0];
                final BluetoothDevice device = (BluetoothDevice) invocationOnMock.getArguments()[1];
                // BroadcastReceivers run on the main thread.  Post a Bluetooth Profile Connection
                // State Change intent on the main thread imitating what the receiver would have
                // received from the Bluetooth stack
                mMainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mReceiver.onReceive(null,
                                createBluetoothConnectionStateChangedIntent(profile, device,
                                        connectionResult));
                    }
                }, CONNECTION_STATE_CHANGE_TIME);
                return null;
            }
        }).when(mockBluetoothUserService).bluetoothConnectToProfile(anyInt(),
                any(BluetoothDevice.class));
    }

    /**
     * Utility method called from the beginning of every test to create and init the policy
     */
    private void createAndSetupBluetoothPolicy() {
        mBluetoothDeviceConnectionPolicyTest = BluetoothDeviceConnectionPolicy.create(mockContext,
                mockCarCabinService, mockCarSensorService, mockPerUserCarServiceHelper);
        mBluetoothDeviceConnectionPolicyTest.setAllowReadWriteToSettings(false);
        mBluetoothDeviceConnectionPolicyTest.init();

        mReceiver = mBluetoothDeviceConnectionPolicyTest.getBluetoothBroadcastReceiver();
        assertNotNull(mReceiver);
        BluetoothDeviceConnectionPolicy.UserServiceConnectionCallback serviceConnectionCallback =
                mBluetoothDeviceConnectionPolicyTest.getServiceCallback();
        assertNotNull(serviceConnectionCallback);
        mCabinEventListener = mBluetoothDeviceConnectionPolicyTest.getCarPropertyListener();
        assertNotNull(mCabinEventListener);

        serviceConnectionCallback.onServiceConnected(mockPerUserCarService);
    }

    /**
     * Utility method called from the end of every test to cleanup and release the policy
     */
    private Intent createBluetoothBondStateChangedIntent(BluetoothDevice device, boolean bonded) {
        // Unbond the device
        Intent bondStateIntent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (!bonded) {
            bondStateIntent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
        } else {
            bondStateIntent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED);
        }
        bondStateIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        return bondStateIntent;
    }

    /************************************** Test Methods *****************************************/
    /**
     * Basic test -
     * 1. Pair one device to the car on all profiles.
     * 2. Disconnect the device
     * 3. Inject a fake vehicle event
     * 4. Verify that we get connection requests on all the profiles with that paired device
     */
    @Test
    public void testAutoConnectOneDevice() throws Exception {
        setupBluetoothMockBehavior(true); // For this test always return Connection Success
        createAndSetupBluetoothPolicy();
        // Tell the policy a new device connected - this mimics pairing
        BluetoothDevice device1 = mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:01");
        // Pair (and Connect) device1 on all the Bluetooth profiles.
        List<Integer> profilesToConnect =
                mBluetoothDeviceConnectionPolicyTest.getProfilesToConnect();
        for (Integer profile : profilesToConnect) {
            pairDeviceOnProfile(device1, profile);
        }

        // Disconnect so we can test Autoconnect by sending a vehicle event
        for (Integer profile : profilesToConnect) {
            sendFakeConnectionStateChangeOnProfile(device1, profile, false);
        }
        // At this point DEADBEEF0001 is paired but disconnected to the vehicle
        // Now, trigger a connection and check if we connected to DEADBEEF0001 on all profiles
        triggerFakeVehicleEvent();
        // Verify that on all profiles, device1 connected
        for (Integer profile : mBluetoothDeviceConnectionPolicyTest.getProfilesToConnect()) {
            verify(mockBluetoothUserService,
                    Mockito.timeout(CONNECTION_REQUEST_TIMEOUT).times(1)).bluetoothConnectToProfile(
                    profile, device1);
        }

        // Before we cleanup wait for the last Connection Status change from
        // setupBluetoothMockBehavior
        // is broadcast to the policy.
        Thread.sleep(WAIT_FOR_COMPLETION_TIME);
        // Inject an Unbond event to the policy
        mReceiver.onReceive(null, createBluetoothBondStateChangedIntent(device1, false));
    }

    /**
     * Multi device test
     * 1. Pair 4 different devices 2 on HFP and PBAP (since they allow 2 connections) and 1 each on
     * A2DP and MAP
     * 2. Disconnect all devices
     * 3. Inject a fake vehicle event.
     * 4. Verify that the right devices connect on the right profiles ( the snapshot recreated)
     */
    @Test
    public void testAutoConnectMultiDevice() throws Exception {
        setupBluetoothMockBehavior(true); // For this test always return Connection Success
        createAndSetupBluetoothPolicy();
        BluetoothDevice device1 = mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:01");
        BluetoothDevice device2 = mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:02");
        BluetoothDevice device3 = mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:03");
        BluetoothDevice device4 = mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:04");

        // Pair 4 different devices on the 4 profiles. HFP and PBAP are connected on the same
        // device(s)
        pairDeviceOnProfile(device1, BluetoothProfile.HEADSET_CLIENT);
        pairDeviceOnProfile(device1, BluetoothProfile.PBAP_CLIENT);
        pairDeviceOnProfile(device2, BluetoothProfile.HEADSET_CLIENT);
        pairDeviceOnProfile(device2, BluetoothProfile.PBAP_CLIENT);
        pairDeviceOnProfile(device3, BluetoothProfile.A2DP_SINK);
        pairDeviceOnProfile(device4, BluetoothProfile.MAP_CLIENT);

        // Disconnect all the 4 devices on the respective connected profiles
        sendFakeConnectionStateChangeOnProfile(device1, BluetoothProfile.HEADSET_CLIENT, false);
        sendFakeConnectionStateChangeOnProfile(device1, BluetoothProfile.PBAP_CLIENT, false);
        sendFakeConnectionStateChangeOnProfile(device2, BluetoothProfile.HEADSET_CLIENT, false);
        sendFakeConnectionStateChangeOnProfile(device2, BluetoothProfile.PBAP_CLIENT, false);
        sendFakeConnectionStateChangeOnProfile(device3, BluetoothProfile.A2DP_SINK, false);
        sendFakeConnectionStateChangeOnProfile(device4, BluetoothProfile.MAP_CLIENT, false);

        triggerFakeVehicleEvent();

        verify(mockBluetoothUserService,
                Mockito.timeout(CONNECTION_REQUEST_TIMEOUT).times(1)).bluetoothConnectToProfile(
                BluetoothProfile.HEADSET_CLIENT, device1);

        verify(mockBluetoothUserService,
                Mockito.timeout(CONNECTION_REQUEST_TIMEOUT).times(1)).bluetoothConnectToProfile(
                BluetoothProfile.PBAP_CLIENT, device1);
        verify(mockBluetoothUserService,
                Mockito.timeout(CONNECTION_REQUEST_TIMEOUT).times(1)).bluetoothConnectToProfile(
                BluetoothProfile.HEADSET_CLIENT, device2);
        verify(mockBluetoothUserService,
                Mockito.timeout(CONNECTION_REQUEST_TIMEOUT).times(1)).bluetoothConnectToProfile(
                BluetoothProfile.PBAP_CLIENT, device2);
        verify(mockBluetoothUserService,
                Mockito.timeout(CONNECTION_REQUEST_TIMEOUT).times(1)).bluetoothConnectToProfile(
                BluetoothProfile.A2DP_SINK, device3);
        verify(mockBluetoothUserService,
                Mockito.timeout(CONNECTION_REQUEST_TIMEOUT).times(1)).bluetoothConnectToProfile(
                BluetoothProfile.MAP_CLIENT, device4);

        // Before we cleanup wait for the last Connection Status change from
        // setupBluetoothMockBehavior is broadcast to the policy.
        Thread.sleep(WAIT_FOR_COMPLETION_TIME);
        // Inject an Unbond event to the policy
        mReceiver.onReceive(null, createBluetoothBondStateChangedIntent(device1, false));
        mReceiver.onReceive(null, createBluetoothBondStateChangedIntent(device2, false));
        mReceiver.onReceive(null, createBluetoothBondStateChangedIntent(device3, false));
        mReceiver.onReceive(null, createBluetoothBondStateChangedIntent(device4, false));
    }
}