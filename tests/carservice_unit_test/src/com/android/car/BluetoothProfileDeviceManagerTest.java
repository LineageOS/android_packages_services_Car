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

import static android.car.settings.CarSettings.Secure.KEY_BLUETOOTH_HFP_CLIENT_DEVICES;

import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.car.ICarBluetoothUserService;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.Suppress;
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link BluetoothProfileDeviceManager}
 *
 * Run:
 * atest BluetoothProfileDeviceManagerTest
 *
 * As a note, content provider based tests that intercept requests from Settings.Secure do not
 * test well in atest with other test modules. They have been suppressed for now.
 */
@RunWith(AndroidJUnit4.class)
public class BluetoothProfileDeviceManagerTest {
    private static final int CONNECT_TIMEOUT_MS = 8000;

    BluetoothProfileDeviceManager mProfileDeviceManager;

    private final int mUserId = 0;
    private final int mProfileId = BluetoothProfile.HEADSET_CLIENT;
    private final String mSettingsKey = KEY_BLUETOOTH_HFP_CLIENT_DEVICES;
    private final String mConnectionAction = BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED;
    private ParcelUuid[] mUuids = new ParcelUuid[] {
            BluetoothUuid.Handsfree_AG,
            BluetoothUuid.HSP_AG};
    private final int[] mProfileTriggers = new int[] {
            BluetoothProfile.MAP_CLIENT,
            BluetoothProfile.PBAP_CLIENT};

    @Mock private ICarBluetoothUserService mMockProxies;

    private MockContext mMockContext;

    private BluetoothAdapterHelper mBluetoothAdapterHelper;
    private BluetoothAdapter mBluetoothAdapter;

    /**
     * A mock context that will override the relevant functions to allow us to grab broadcast
     * receivers and dictate which content resolver to use.
     */
    public class MockContext extends ContextWrapper {
        private MockContentResolver mContentResolver;
        private MockContentProvider mContentProvider;
        private String mSettingsContents;
        private BroadcastReceiver mReceiver;

        MockContext(Context base) {
            super(base);
            mContentResolver = new MockContentResolver(this);
            mContentProvider = new MockContentProvider(this) {
                @Override
                public Bundle call(String method, String request, Bundle args) {
                    if (Settings.CALL_METHOD_GET_SECURE.equals(method)) {
                        String ret = getSettingsContents();
                        Bundle b = new Bundle(1);
                        b.putCharSequence("value", ret);
                        return b;
                    } else if (Settings.CALL_METHOD_PUT_SECURE.equals(method)) {
                        String newSettings = (String) args.get("value");
                        setSettingsContents(newSettings);
                    }
                    return new Bundle();
                }
            };
            mContentResolver.addProvider(Settings.AUTHORITY, mContentProvider);
            setSettingsContents("");
        }

        public void release() {
            setReceiver(null);
            setSettingsContents("");
            mContentResolver = null;
            mContentProvider = null;
        }

        private synchronized BroadcastReceiver getReceiver() {
            return mReceiver;
        }

        private synchronized void setReceiver(BroadcastReceiver receiver) {
            mReceiver = receiver;
        }

        @Override
        public int getUserId() {
            return mUserId;
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver,
                UserHandle user, IntentFilter filter, String broadcastPermission,
                Handler scheduler) {
            setReceiver(receiver);
            return null;
        }

        @Override
        public void unregisterReceiver(BroadcastReceiver receiver) {
            setReceiver(null);
        }

        @Override
        public void sendBroadcast(Intent intent) {
            synchronized (this) {
                if (mReceiver != null) {
                    mReceiver.onReceive(null, intent);
                }
            }
        }

        public synchronized String getSettingsContents() {
            return mSettingsContents;
        }

        public synchronized void setSettingsContents(String s) {
            if (s == null) s = "";
            mSettingsContents = s;
        }
    }

    //--------------------------------------------------------------------------------------------//
    // Setup/TearDown                                                                             //
    //--------------------------------------------------------------------------------------------//

    @Before
    public void setUp() {

        MockitoAnnotations.initMocks(this);

        mMockContext = new MockContext(InstrumentationRegistry.getTargetContext());
        setSettingsDeviceList("");
        assertSettingsContains("");

        mBluetoothAdapterHelper = new BluetoothAdapterHelper();
        mBluetoothAdapterHelper.init();

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        Assert.assertTrue(mBluetoothAdapter != null);

        mProfileDeviceManager = BluetoothProfileDeviceManager.create(mMockContext, mUserId,
                mMockProxies, mProfileId);
        Assert.assertTrue(mProfileDeviceManager != null);
    }

    @After
    public void tearDown() {
        mProfileDeviceManager.stop();
        mProfileDeviceManager = null;
        mBluetoothAdapter = null;
        mBluetoothAdapterHelper.release();
        mBluetoothAdapterHelper = null;
        mMockProxies = null;
        mMockContext.release();
        mMockContext = null;
    }

    //--------------------------------------------------------------------------------------------//
    // Utilities                                                                                  //
    //--------------------------------------------------------------------------------------------//

    private void setSettingsDeviceList(String devicesStr) {
        Settings.Secure.putStringForUser(mMockContext.getContentResolver(), mSettingsKey,
                devicesStr, mUserId);
    }

    private String getSettingsDeviceList() {
        String devices = Settings.Secure.getStringForUser(mMockContext.getContentResolver(),
                mSettingsKey, mUserId);
        if (devices == null) devices = "";
        return devices;
    }

    private ArrayList<BluetoothDevice> makeDeviceList(List<String> addresses) {
        ArrayList<BluetoothDevice> devices = new ArrayList<>();

        for (String address : addresses) {
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
            if (device == null) continue;
            devices.add(device);
        }
        return devices;
    }

    private void mockDeviceAvailability(BluetoothDevice device, boolean available)
            throws Exception {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] arguments = invocation.getArguments();
                if (arguments != null && arguments.length == 2 && arguments[1] != null) {
                    BluetoothDevice device = (BluetoothDevice) arguments[1];
                    int state = (available
                            ? BluetoothProfile.STATE_CONNECTED
                            : BluetoothProfile.STATE_DISCONNECTED);
                    sendConnectionStateChanged(device, state);
                }
                return null;
            }
        }).when(mMockProxies).bluetoothConnectToProfile(mProfileId, device);
    }

    private void captureDevicePriority(BluetoothDevice device) throws Exception {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] arguments = invocation.getArguments();
                if (arguments != null && arguments.length == 3 && arguments[1] != null) {
                    BluetoothDevice device = (BluetoothDevice) arguments[1];
                    int priority = (int) arguments[2];
                    mockDevicePriority(device, priority);
                }
                return null;
            }
        }).when(mMockProxies).setProfilePriority(mProfileId, device, anyInt());
    }

    private void mockDevicePriority(BluetoothDevice device, int priority) throws Exception {
        when(mMockProxies.getProfilePriority(mProfileId, device)).thenReturn(priority);
    }

    private void sendAdapterStateChanged(int newState) {
        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, newState);
        mMockContext.sendBroadcast(intent);
    }

    private void sendBondStateChanged(BluetoothDevice device, int newState) {
        Intent intent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, newState);
        mMockContext.sendBroadcast(intent);
    }

    private void sendDeviceUuids(BluetoothDevice device, ParcelUuid[] uuids) {
        Intent intent = new Intent(BluetoothDevice.ACTION_UUID);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothDevice.EXTRA_UUID, uuids);
        mMockContext.sendBroadcast(intent);
    }

    private void sendConnectionStateChanged(BluetoothDevice device, int newState) {
        Intent intent = new Intent(mConnectionAction);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        mMockContext.sendBroadcast(intent);
    }

    private synchronized void assertSettingsContains(String expected) {
        Assert.assertTrue(expected != null);
        String settings = Settings.Secure.getStringForUser(mMockContext.getContentResolver(),
                mSettingsKey, mUserId);
        if (settings == null) settings = "";
        Assert.assertEquals(expected, settings);
    }

    private void assertDeviceList(ArrayList<BluetoothDevice> devices,
            ArrayList<BluetoothDevice> expected) {
        Assert.assertEquals(expected, devices);
    }

    //--------------------------------------------------------------------------------------------//
    // Load from persistent memory tests                                                          //
    //--------------------------------------------------------------------------------------------//

    /**
     * Preconditions:
     * - Settings contains no devices
     *
     * Actions:
     * - Initialize the device manager
     *
     * Outcome:
     * - device manager should initialize
     */
    @Test
    @Suppress
    public void testEmptySettingsString_loadNoDevices() {
        mProfileDeviceManager.start();

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());
    }

    /**
     * Preconditions:
     * - Settings contains a single device
     *
     * Actions:
     * - Initialize the device manager
     *
     * Outcome:
     * - The single device is now located in the device manager's device list
     */
    @Test
    @Suppress
    public void testSingleDeviceSettingsString_loadSingleDevice() {
        final List<String> addresses = Arrays.asList("DE:AD:BE:EF:00:00");
        String devicesStr = TextUtils.join(",", addresses);
        setSettingsDeviceList(devicesStr);

        mProfileDeviceManager.start();

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), makeDeviceList(addresses));
    }

    /**
     * Preconditions:
     * - Settings contains several devices
     *
     * Actions:
     * - Initialize the device manager
     *
     * Outcome:
     * - All devices are now in the device manager's list, all in the proper order.
     */
    @Test
    @Suppress
    public void testSeveralDevicesSettingsString_loadAllDevices() {
        final List<String> addresses = Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02",
                "DE:AD:BE:EF:00:03", "DE:AD:BE:EF:00:04", "DE:AD:BE:EF:00:05",
                "DE:AD:BE:EF:00:06", "DE:AD:BE:EF:00:07");
        String devicesStr = TextUtils.join(",", addresses);
        setSettingsDeviceList(devicesStr);
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), makeDeviceList(addresses));
    }

    //--------------------------------------------------------------------------------------------//
    // Commit to persistent memory tests                                                          //
    //--------------------------------------------------------------------------------------------//

    /**
     * Preconditions:
     * - The device manager is initialized and the list contains no devices
     *
     * Actions:
     * - An event forces the device manager to commit it's list
     *
     * Outcome:
     * - The empty list should be written to Settings.Secure as an empty string, ""
     */
    @Test
    @Suppress
    public void testNoDevicesCommit_commitEmptyDeviceString() {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());
        sendAdapterStateChanged(BluetoothAdapter.STATE_OFF);
        assertSettingsContains("");
    }

    /**
     * Preconditions:
     * - The device manager contains several devices
     *
     * Actions:
     * - An event forces the device manager to commit it's list
     *
     * Outcome:
     * - The ordered device list should be written to Settings.Secure as a comma separated list
     */
    @Test
    @Suppress
    public void testSeveralDevicesCommit_commitAllDeviceString() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02",
                "DE:AD:BE:EF:00:03", "DE:AD:BE:EF:00:04", "DE:AD:BE:EF:00:05",
                "DE:AD:BE:EF:00:06", "DE:AD:BE:EF:00:07"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }

        sendAdapterStateChanged(BluetoothAdapter.STATE_OFF);

        assertSettingsContains(TextUtils.join(",", devices));
    }

    //--------------------------------------------------------------------------------------------//
    // Add Device tests                                                                           //
    //--------------------------------------------------------------------------------------------//

    /**
     * Preconditions:
     * - The device manager is initialized and contains no devices.
     *
     * Actions:
     * - Add several devices
     *
     * Outcome:
     * - The device manager contains all devices, ordered properly
     */
    @Test
    public void testAddDevices_devicesAppearInPriorityList() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02",
                "DE:AD:BE:EF:00:03", "DE:AD:BE:EF:00:04", "DE:AD:BE:EF:00:05",
                "DE:AD:BE:EF:00:06", "DE:AD:BE:EF:00:07"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);
    }

    /**
     * Preconditions:
     * - The device manager is initialized and contains one device
     *
     * Actions:
     * - Add the device that is already in the list
     *
     * Outcome:
     * - The device manager's list remains unchanged with only one device in it
     */
    @Test
    public void testAddDeviceAlreadyInList_priorityListUnchanged() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        mProfileDeviceManager.addDevice(mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:00"));
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mProfileDeviceManager.addDevice(mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:00"));
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);
    }

    //--------------------------------------------------------------------------------------------//
    // Remove Device tests                                                                        //
    //--------------------------------------------------------------------------------------------//

    /**
     * Preconditions:
     * - The device manager is initialized and contains no devices.
     *
     * Actions:
     * - Remove a device from the list
     *
     * Outcome:
     * - The device manager does not error out and continues to have an empty list
     */
    @Test
    public void testRemoveDeviceFromEmptyList_priorityListUnchanged() {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        mProfileDeviceManager.removeDevice(mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:00"));
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());
    }

    /**
     * Preconditions:
     * - The device manager is initialized and contains several devices.
     *
     * Actions:
     * - Remove the device with the highest priority (front of list)
     *
     * Outcome:
     * - The device manager removes the leading device. The other devices have been shifted down.
     */
    @Test
    public void testRemoveDeviceFront_deviceNoLongerInPriorityList() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mProfileDeviceManager.removeDevice(mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:00"));
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02")));
    }

    /**
     * Preconditions:
     * - The device manager is initialized and contains several devices.
     *
     * Actions:
     * - Remove a device from the middle of the list
     *
     * Outcome:
     * - The device manager removes the device. The other devices with larger priorities have been
     *   shifted down.
     */
    @Test
    public void testRemoveDeviceMiddle_deviceNoLongerInPriorityList() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mProfileDeviceManager.removeDevice(mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:01"));
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:02")));
    }

    /**
     * Preconditions:
     * - The device manager is initialized and contains several devices.
     *
     * Actions:
     * - Remove the device from the end of the list
     *
     * Outcome:
     * - The device manager removes the device. The other devices remain in their places, unchanged
     */
    @Test
    public void testRemoveDeviceEnd_deviceNoLongerInPriorityList() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mProfileDeviceManager.removeDevice(mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:02"));
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01")));
    }

    /**
     * Preconditions:
     * - The device manager is initialized and contains several devices.
     *
     * Actions:
     * - Remove a device thats not in the list
     *
     * Outcome:
     * - The device manager's list remains unchanged.
     */
    @Test
    public void testRemoveDeviceNotInList_priorityListUnchanged() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mProfileDeviceManager.removeDevice(mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:03"));
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);
    }

    //--------------------------------------------------------------------------------------------//
    // GetDeviceConnectionPriority() tests                                                        //
    //--------------------------------------------------------------------------------------------//

    /**
     * Preconditions:
     * - The device manager is initialized and contains several devices.
     *
     * Actions:
     * - Get the priority of each device in the list
     *
     * Outcome:
     * - The device manager returns the proper priority for each device
     */
    @Test
    public void testGetConnectionPriority_prioritiesReturned() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        for (int i = 0; i < devices.size(); i++) {
            int priority = mProfileDeviceManager.getDeviceConnectionPriority(devices.get(i));
            Assert.assertTrue(priority == i);
        }
    }

    /**
     * Preconditions:
     * - The device manager is initialized and contains several devices.
     *
     * Actions:
     * - Get the priority of a device that is not in the list
     *
     * Outcome:
     * - The device manager returns a -1
     */
    @Test
    public void testGetConnectionPriorityDeviceNotInList_negativeOneReturned() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        int priority = mProfileDeviceManager.getDeviceConnectionPriority(
                mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:03"));
        Assert.assertTrue(priority == -1);
    }

    //--------------------------------------------------------------------------------------------//
    // setDeviceConnectionPriority() tests                                                        //
    //--------------------------------------------------------------------------------------------//

    /**
     * Preconditions:
     * - The device manager is initialized and contains several devices.
     *
     * Actions:
     * - Set the priority of several devices in the list, testing the following moves:
     *      Mid priority -> higher priority
     *      Mid priority -> lower priority
     *      Highest priority -> lower priority
     *      Lowest priority -> higher priority
     *      Any priority -> same priority
     *
     * Outcome:
     * - Increased prioritied shuffle devices to proper lower priorities, decreased priorities
     *   shuffle devices to proper high priorities, and a request to set the same priority yields no
     *   change.
     */
    @Test
    public void testSetConnectionPriority_listOrderedCorrectly() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        // move middle device to front
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:01");
        mProfileDeviceManager.setDeviceConnectionPriority(device, 0);
        Assert.assertTrue(mProfileDeviceManager.getDeviceConnectionPriority(device) == 0);
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList(
                        "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:02")));

        // move front device to the end
        mProfileDeviceManager.setDeviceConnectionPriority(device, 2);
        Assert.assertTrue(mProfileDeviceManager.getDeviceConnectionPriority(device) == 2);
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList(
                        "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:02", "DE:AD:BE:EF:00:01")));

        // move end device to middle
        mProfileDeviceManager.setDeviceConnectionPriority(device, 1);
        Assert.assertTrue(mProfileDeviceManager.getDeviceConnectionPriority(device) == 1);
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList(
                        "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02")));

        // move middle to end
        mProfileDeviceManager.setDeviceConnectionPriority(device, 2);
        Assert.assertTrue(mProfileDeviceManager.getDeviceConnectionPriority(device) == 2);
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList(
                        "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:02", "DE:AD:BE:EF:00:01")));

        // move end to front
        mProfileDeviceManager.setDeviceConnectionPriority(device, 0);
        Assert.assertTrue(mProfileDeviceManager.getDeviceConnectionPriority(device) == 0);
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList(
                        "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:02")));

        // move front to middle
        mProfileDeviceManager.setDeviceConnectionPriority(device, 1);
        Assert.assertTrue(mProfileDeviceManager.getDeviceConnectionPriority(device) == 1);
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList(
                        "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02")));

        // move middle to middle
        mProfileDeviceManager.setDeviceConnectionPriority(device, 1);
        Assert.assertTrue(mProfileDeviceManager.getDeviceConnectionPriority(device) == 1);
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList(
                        "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02")));
    }

    /**
     * Preconditions:
     * - The device manager is initialized and contains several devices.
     *
     * Actions:
     * - Set the priority of a device that is not currently in the list
     *
     * Outcome:
     * - Device is added to the list in the requested spot. Devices with lower priorities have had
     *   their priorities adjusted accordingly.
     */
    @Test
    public void testSetConnectionPriorityNewDevice_listOrderedCorrectly() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        // move middle device to front
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:03");
        mProfileDeviceManager.setDeviceConnectionPriority(device, 1);
        Assert.assertTrue(mProfileDeviceManager.getDeviceConnectionPriority(device) == 1);
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList(
                        "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:03", "DE:AD:BE:EF:00:01",
                        "DE:AD:BE:EF:00:02")));
    }

    /**
     * Preconditions:
     * - The device manager is initialized and contains several devices.
     *
     * Actions:
     * - Request to set a priority that exceeds the bounds of the list (upper)
     *
     * Outcome:
     * - No operation is taken
     */
    @Test
    public void testSetConnectionPriorityLargerThanSize_priorityListUnchanged() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        // move middle device to end with huge end priority
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:01");
        mProfileDeviceManager.setDeviceConnectionPriority(device, 100000);
        Assert.assertTrue(mProfileDeviceManager.getDeviceConnectionPriority(device) == 1);
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList(
                        "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02")));
    }

    /**
     * Preconditions:
     * - The device manager is initialized and contains several devices.
     *
     * Actions:
     * - Request to set a priority that exceeds the bounds of the list (lower)
     *
     * Outcome:
     * - No operation is taken
     */
    @Test
    public void testSetConnectionPriorityNegative_priorityListUnchanged() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice("DE:AD:BE:EF:00:01");
        mProfileDeviceManager.setDeviceConnectionPriority(device, -1);
        Assert.assertTrue(mProfileDeviceManager.getDeviceConnectionPriority(device) == 1);
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);
    }

    /**
     * Preconditions:
     * - The device manager is initialized and contains several devices.
     *
     * Actions:
     * - Request to set a priority for a null device
     *
     * Outcome:
     * - No operation is taken
     */
    @Test
    public void testSetConnectionPriorityNullDevice_priorityListUnchanged() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mProfileDeviceManager.setDeviceConnectionPriority(null, 1);
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);
    }

    //--------------------------------------------------------------------------------------------//
    // beginAutoConnecting() tests                                                                //
    //--------------------------------------------------------------------------------------------//

    /**
     * Preconditions:
     * - The Bluetooth adapter is ON, the device manager is initialized and no devices are in the
     *   list.
     *
     * Actions:
     * - Initiate an auto connection
     *
     * Outcome:
     * - Auto connect returns immediately with no connection attempts made.
     */
    @Test
    public void testAutoConnectNoDevices_returnsImmediately() throws Exception {
        mBluetoothAdapterHelper.forceAdapterOn();
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        mProfileDeviceManager.beginAutoConnecting();

        verify(mMockProxies, times(0)).bluetoothConnectToProfile(eq(mProfileId),
                any(BluetoothDevice.class));

        Assert.assertFalse(mProfileDeviceManager.isAutoConnecting());
    }

    /**
     * Preconditions:
     * - The Bluetooth adapter is OFF, the device manager is initialized and there are several
     *    devices are in the list.
     *
     * Actions:
     * - Initiate an auto connection
     *
     * Outcome:
     * - Auto connect returns immediately with no connection attempts made.
     */
    @Test
    public void testAutoConnectAdapterOff_returnsImmediately() throws Exception {
        mBluetoothAdapterHelper.forceAdapterOff();
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mProfileDeviceManager.beginAutoConnecting();

        verify(mMockProxies, times(0)).bluetoothConnectToProfile(eq(mProfileId),
                any(BluetoothDevice.class));

        Assert.assertFalse(mProfileDeviceManager.isAutoConnecting());
    }

    /**
     * Preconditions:
     * - The Bluetooth adapter is ON, the device manager is initialized and there are several
     *    devices are in the list.
     *
     * Actions:
     * - Initiate an auto connection
     *
     * Outcome:
     * - Auto connect attempts to connect each device in the list, in order of priority.
     */
    @Test
    public void testAutoConnectSeveralDevices_attemptsToConnectEachDevice() throws Exception {
        mBluetoothAdapterHelper.forceAdapterOn();
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mockDeviceAvailability(devices.get(0), true);
        mockDeviceAvailability(devices.get(1), true);
        mockDeviceAvailability(devices.get(2), true);

        mProfileDeviceManager.beginAutoConnecting();

        InOrder ordered = inOrder(mMockProxies);
        ordered.verify(mMockProxies, timeout(CONNECT_TIMEOUT_MS).times(1))
                .bluetoothConnectToProfile(mProfileId, devices.get(0));
        ordered.verify(mMockProxies, timeout(CONNECT_TIMEOUT_MS).times(1))
                .bluetoothConnectToProfile(mProfileId, devices.get(1));
        ordered.verify(mMockProxies, timeout(CONNECT_TIMEOUT_MS).times(1))
                .bluetoothConnectToProfile(mProfileId, devices.get(2));
    }

    //--------------------------------------------------------------------------------------------//
    // Bluetooth stack device connection status changed event tests                               //
    //--------------------------------------------------------------------------------------------//

    /**
     * Preconditions:
     * - The device manager is initialized, there are no devices in the list.
     *
     * Actions:
     * - A connection action comes in for the profile we're tracking and the device's priority is
     *   PRIORITY_AUTO_CONNECT.
     *
     * Outcome:
     * - The device is added to the list.
     */
    @Test
    public void testReceiveDeviceConnectPriorityAutoConnect_deviceAdded() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));
        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_AUTO_CONNECT);
        sendConnectionStateChanged(devices.get(0), BluetoothProfile.STATE_CONNECTED);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        for (int profile : mProfileTriggers) {
            verify(mMockProxies, times(1)).bluetoothConnectToProfile(profile, devices.get(0));
        }
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are no devices in the list.
     *
     * Actions:
     * - A connection action comes in for the profile we're tracking and the device's priority is
     *   PRIORITY_ON.
     *
     * Outcome:
     * - The device is added to the list.
     */
    @Test
    public void testReceiveDeviceConnectPriorityOn_deviceAdded() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));
        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_ON);
        sendConnectionStateChanged(devices.get(0), BluetoothProfile.STATE_CONNECTED);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        for (int profile : mProfileTriggers) {
            verify(mMockProxies, times(1)).bluetoothConnectToProfile(profile, devices.get(0));
        }
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are no devices in the list.
     *
     * Actions:
     * - A connection action comes in for the profile we're tracking and the device's priority is
     *   PRIORITY_OFF.
     *
     * Outcome:
     * - The device is not added to the list.
     */
    @Test
    public void testReceiveDeviceConnectPriorityOff_deviceNotAdded() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));
        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_OFF);
        sendConnectionStateChanged(devices.get(0), BluetoothProfile.STATE_CONNECTED);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (int profile : mProfileTriggers) {
            verify(mMockProxies, times(1)).bluetoothConnectToProfile(profile, devices.get(0));
        }
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are no devices in the list.
     *
     * Actions:
     * - A connection action comes in for the profile we're tracking and the device's priority is
     *   PRIORITY_UNDEFINED.
     *
     * Outcome:
     * - The device is not added to the list.
     */
    @Test
    public void testReceiveDeviceConnectPriorityUndefined_deviceNotAdded() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));
        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_UNDEFINED);
        sendConnectionStateChanged(devices.get(0), BluetoothProfile.STATE_CONNECTED);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (int profile : mProfileTriggers) {
            verify(mMockProxies, times(1)).bluetoothConnectToProfile(profile, devices.get(0));
        }
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are is one device in the list.
     *
     * Actions:
     * - A disconnection action comes in for the profile we're tracking and the device's priority is
     *   PRIORITY_AUTO_CONNECT.
     *
     * Outcome:
     * - The device list is unchanged.
     */
    @Test
    public void testReceiveDeviceDisconnectPriorityAutoConnect_listUnchanged() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_AUTO_CONNECT);
        sendConnectionStateChanged(devices.get(0), BluetoothProfile.STATE_DISCONNECTED);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are is one device in the list.
     *
     * Actions:
     * - A disconnection action comes in for the profile we're tracking and the device's priority is
     *   PRIORITY_ON.
     *
     * Outcome:
     * - The device list is unchanged.
     */
    @Test
    public void testReceiveDeviceDisconnectPriorityOn_listUnchanged() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_ON);
        sendConnectionStateChanged(devices.get(0), BluetoothProfile.STATE_DISCONNECTED);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are is one device in the list.
     *
     * Actions:
     * - A disconnection action comes in for the profile we're tracking and the device's priority is
     *   PRIORITY_OFF.
     *
     * Outcome:
     * - The device is removed from the list.
     */
    @Test
    public void testReceiveDeviceDisconnectPriorityOff_deviceRemoved() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_OFF);
        sendConnectionStateChanged(devices.get(0), BluetoothProfile.STATE_DISCONNECTED);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are is one device in the list.
     *
     * Actions:
     * - A disconnection action comes in for the profile we're tracking and the device's priority is
     *   PRIORITY_OFF.
     *
     * Outcome:
     * - The device list is unchanged.
     */
    @Test
    public void testReceiveDeviceDisconnectPriorityUndefined_listUnchanged() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_UNDEFINED);
        sendConnectionStateChanged(devices.get(0), BluetoothProfile.STATE_DISCONNECTED);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);
    }

    //--------------------------------------------------------------------------------------------//
    // Bluetooth stack device bond status changed event tests                                     //
    //--------------------------------------------------------------------------------------------//

    /**
     * Preconditions:
     * - The device manager is initialized, there are is one device in the list.
     *
     * Actions:
     * - A device from the list has unbonded
     *
     * Outcome:
     * - The device is removed from the list.
     */
    @Test
    public void testReceiveDeviceUnbonded_deviceRemoved() {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        sendBondStateChanged(devices.get(0), BluetoothDevice.BOND_NONE);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are no devices in the list.
     *
     * Actions:
     * - A device has bonded and its UUID set claims it supports this profile.
     *
     * Outcome:
     * - The device is added to the list.
     *
     * NOTE: Car Service version of Mockito does not support mocking final classes and
     * BluetoothDevice is a final class. Unsuppress this when we can support it.
     */
    @Test
    @Suppress
    public void testReceiveSupportedDeviceBonded_deviceAdded() {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        BluetoothDevice device = mock(BluetoothDevice.class);
        doReturn("DE:AD:BE:EF:00:00").when(device).getAddress();
        doReturn(mUuids).when(device).getUuids();

        sendBondStateChanged(device, BluetoothDevice.BOND_BONDED);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00")));
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are no devices in the list.
     *
     * Actions:
     * - A device has bonded and its UUID set claims it does not support this profile.
     *
     * Outcome:
     * - The device is ignored.
     */
    @Test
    public void testReceiveUnsupportedDeviceBonded_deviceNotAdded() {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));

        sendBondStateChanged(devices.get(0), BluetoothDevice.BOND_BONDED);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());
    }

    //--------------------------------------------------------------------------------------------//
    // Bluetooth stack device UUID event tests                                                    //
    //--------------------------------------------------------------------------------------------//

    /**
     * Preconditions:
     * - The device manager is initialized, there are no devices in the list.
     *
     * Actions:
     * - A Uuid set is received for a device that has PRIORITY_AUTO_CONNECT
     *
     * Outcome:
     * - The device is ignored, no priority update is made.
     */
    @Test
    public void testReceiveUuidDevicePriorityAutoConnect_doNothing() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));

        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_AUTO_CONNECT);
        sendDeviceUuids(devices.get(0), mUuids);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        verify(mMockProxies, times(0)).setProfilePriority(eq(mProfileId),
                any(BluetoothDevice.class), anyInt());
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are no devices in the list.
     *
     * Actions:
     * - A Uuid set is received for a device that has PRIORITY_ON
     *
     * Outcome:
     * - The device is ignored, no priority update is made.
     */
    @Test
    public void testReceiveUuidDevicePriorityOn_doNothing() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));

        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_ON);
        sendDeviceUuids(devices.get(0), mUuids);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());


        verify(mMockProxies, times(0)).setProfilePriority(eq(mProfileId),
                any(BluetoothDevice.class), anyInt());
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are no devices in the list.
     *
     * Actions:
     * - A Uuid set is received for a device that has PRIORITY_OFF
     *
     * Outcome:
     * - The device is ignored, no priority update is made.
     */
    @Test
    public void testReceiveUuidDevicePriorityOff_doNothing() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));

        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_OFF);
        sendDeviceUuids(devices.get(0), mUuids);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        verify(mMockProxies, times(0)).setProfilePriority(eq(mProfileId),
                any(BluetoothDevice.class), anyInt());
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are no devices in the list.
     *
     * Actions:
     * - A Uuid set is received for a device that has PRIORITY_UNDEFINED
     *
     * Outcome:
     * - The device has its priority updated to PRIORITY_ON.
     */
    @Test
    public void testReceiveUuidDevicePriorityUndefined_setPriorityOn() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));

        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_UNDEFINED);
        sendDeviceUuids(devices.get(0), mUuids);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        verify(mMockProxies, times(1)).setProfilePriority(mProfileId, devices.get(0),
                BluetoothProfile.PRIORITY_ON);
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are no devices in the list.
     *
     * Actions:
     * - A Uuid set is received for a device that is not supported for this profile
     *
     * Outcome:
     * - The device is ignored, no priority update is made.
     */
    @Test
    public void testReceiveUuidsDeviceUnsupported_doNothing() throws Exception {
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList("DE:AD:BE:EF:00:00"));

        mockDevicePriority(devices.get(0), BluetoothProfile.PRIORITY_UNDEFINED);

        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        verify(mMockProxies, times(0)).getProfilePriority(eq(mProfileId),
                any(BluetoothDevice.class));
    }

    //--------------------------------------------------------------------------------------------//
    // Bluetooth stack adapter status changed event tests                                         //
    //--------------------------------------------------------------------------------------------//

    /**
     * Preconditions:
     * - The device manager is initialized, there are several devices in the list. We are currently
     *   connecting devices.
     *
     * Actions:
     * - The adapter is turning off
     *
     * Outcome:
     * - Auto-connecting is cancelled
     */
    @Test
    public void testReceiveAdapterTurningOff_cancel() {
        mBluetoothAdapterHelper.forceAdapterOn();
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mProfileDeviceManager.beginAutoConnecting();
        Assert.assertTrue(mProfileDeviceManager.isAutoConnecting());

        sendAdapterStateChanged(BluetoothAdapter.STATE_TURNING_OFF);

        Assert.assertFalse(mProfileDeviceManager.isAutoConnecting());
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are several devices in the list. We are currently
     *   connecting devices.
     *
     * Actions:
     * - The adapter becomes off
     *
     * Outcome:
     * - Auto-connecting is cancelled. The device list is committed
     */
    @Test
    @Suppress
    public void testReceiveAdapterOff_cancelAndCommit() {
        mBluetoothAdapterHelper.forceAdapterOn();
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mProfileDeviceManager.beginAutoConnecting();
        Assert.assertTrue(mProfileDeviceManager.isAutoConnecting());

        sendAdapterStateChanged(BluetoothAdapter.STATE_OFF);

        Assert.assertFalse(mProfileDeviceManager.isAutoConnecting());

        assertSettingsContains(TextUtils.join(",", devices));
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are several devices in the list. We are currently
     *   connecting devices.
     *
     * Actions:
     * - The adapter sends a turning on. (This can happen in weird cases in the stack where the
     *   adapter is ON but the intent is sent away. Additionally, being ON and sending the intent is
     *   a great way to make sure we called cancel)
     *
     * Outcome:
     * - Auto-connecting is cancelled
     */
    @Test
    public void testReceiveAdapterTurningOn_cancel() {
        mBluetoothAdapterHelper.forceAdapterOn();
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        mProfileDeviceManager.beginAutoConnecting();
        Assert.assertTrue(mProfileDeviceManager.isAutoConnecting());

        sendAdapterStateChanged(BluetoothAdapter.STATE_TURNING_ON);

        Assert.assertFalse(mProfileDeviceManager.isAutoConnecting());
    }

    /**
     * Preconditions:
     * - The device manager is initialized, there are several devices in the list. We are currently
     *   connecting devices.
     *
     * Actions:
     * - The adapter becomes on
     *
     * Outcome:
     * - No actions are taken
     */
    @Test
    public void testReceiveAdapterOn_doNothing() {
        ArrayList<BluetoothDevice> devices = makeDeviceList(Arrays.asList(
                "DE:AD:BE:EF:00:00", "DE:AD:BE:EF:00:01", "DE:AD:BE:EF:00:02"));
        mProfileDeviceManager.start();
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(),
                new ArrayList<BluetoothDevice>());

        for (BluetoothDevice device : devices) {
            mProfileDeviceManager.addDevice(device);
        }
        assertDeviceList(mProfileDeviceManager.getDeviceListSnapshot(), devices);

        sendAdapterStateChanged(BluetoothAdapter.STATE_ON);

        verifyNoMoreInteractions(mMockProxies);
    }
}
