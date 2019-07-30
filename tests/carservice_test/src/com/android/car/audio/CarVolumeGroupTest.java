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
package com.android.car.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.automotive.audiocontrol.V1_0.ContextNumber;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.primitives.Ints;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CarVolumeGroupTest {
    private static final int STEP_VALUE = 2;
    private static final int MIN_GAIN = 0;
    private static final int MAX_GAIN = 5;
    private static final int DEFAULT_GAIN = 0;
    private static final String OTHER_ADDRESS = "other_address";
    private static final String MEDIA_DEVICE_ADDRESS = "music";
    private static final String NAVIGATION_DEVICE_ADDRESS = "navigation";

    @Rule
    public final ExpectedException thrown = ExpectedException.none();


    private CarAudioDeviceInfo mMediaDevice;
    private CarAudioDeviceInfo mNavigationDevice;
    private Context mContext;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mMediaDevice = generateCarAudioDeviceInfo(MEDIA_DEVICE_ADDRESS);
        mNavigationDevice = generateCarAudioDeviceInfo(NAVIGATION_DEVICE_ADDRESS);
    }

    @Test
    public void bind_associatesDeviceAddresses() {
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(mContext, 0, 0);

        carVolumeGroup.bind(ContextNumber.MUSIC, mMediaDevice);
        assertEquals(1, carVolumeGroup.getAddresses().size());

        carVolumeGroup.bind(ContextNumber.NAVIGATION, mNavigationDevice);

        List<String> addresses = carVolumeGroup.getAddresses();
        assertEquals(2, addresses.size());
        assertTrue(addresses.contains(MEDIA_DEVICE_ADDRESS));
        assertTrue(addresses.contains(NAVIGATION_DEVICE_ADDRESS));
    }

    @Test
    public void bind_checksForSameStepSize() {
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(mContext, 0, 0);

        carVolumeGroup.bind(ContextNumber.MUSIC, mMediaDevice);
        CarAudioDeviceInfo differentStepValueDevice = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE + 1,
                MIN_GAIN, MAX_GAIN);

        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("Gain controls within one group must have same step value");
        carVolumeGroup.bind(ContextNumber.NAVIGATION, differentStepValueDevice);
    }

    @Test
    public void bind_updatesMinGainToSmallestValue() {
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(mContext, 0, 0);

        CarAudioDeviceInfo largestMinGain = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, 1, 10, 10);
        carVolumeGroup.bind(ContextNumber.NAVIGATION, largestMinGain);

        assertEquals(0, carVolumeGroup.getMaxGainIndex());

        CarAudioDeviceInfo smallestMinGain = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, 1, 2, 10);
        carVolumeGroup.bind(ContextNumber.NAVIGATION, smallestMinGain);

        assertEquals(8, carVolumeGroup.getMaxGainIndex());

        CarAudioDeviceInfo middleMinGain = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, 1, 7, 10);
        carVolumeGroup.bind(ContextNumber.NAVIGATION, middleMinGain);

        assertEquals(8, carVolumeGroup.getMaxGainIndex());
    }

    @Test
    public void bind_updatesMaxGainToLargestValue() {
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(mContext, 0, 0);

        CarAudioDeviceInfo smallestMaxGain = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, 1, 1, 5);
        carVolumeGroup.bind(ContextNumber.NAVIGATION, smallestMaxGain);

        assertEquals(4, carVolumeGroup.getMaxGainIndex());

        CarAudioDeviceInfo largestMaxGain = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, 1, 1, 10);
        carVolumeGroup.bind(ContextNumber.NAVIGATION, largestMaxGain);

        assertEquals(9, carVolumeGroup.getMaxGainIndex());

        CarAudioDeviceInfo middleMaxGain = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, 1, 1, 7);
        carVolumeGroup.bind(ContextNumber.NAVIGATION, middleMaxGain);

        assertEquals(9, carVolumeGroup.getMaxGainIndex());
    }

    @Test
    public void getContexts_returnsAllContextsBoundToVolumeGroup() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        int[] contexts = carVolumeGroup.getContexts();

        assertEquals(6, contexts.length);

        List<Integer> contextsList = Ints.asList(contexts);
        assertTrue(contextsList.contains(ContextNumber.MUSIC));
        assertTrue(contextsList.contains(ContextNumber.CALL));
        assertTrue(contextsList.contains(ContextNumber.CALL_RING));
        assertTrue(contextsList.contains(ContextNumber.NAVIGATION));
        assertTrue(contextsList.contains(ContextNumber.ALARM));
        assertTrue(contextsList.contains(ContextNumber.NOTIFICATION));
    }

    @Test
    public void getContextsForAddress_returnsContextsBoundToThatAddress() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        int[] contexts = carVolumeGroup.getContextsForAddress(MEDIA_DEVICE_ADDRESS);

        assertEquals(3, contexts.length);
        List<Integer> contextsList = Ints.asList(contexts);
        assertTrue(contextsList.contains(ContextNumber.MUSIC));
        assertTrue(contextsList.contains(ContextNumber.CALL));
        assertTrue(contextsList.contains(ContextNumber.CALL_RING));
    }

    @Test
    public void getContextsForAddress_returnsEmptyArrayIfAddressNotBound() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        int[] contexts = carVolumeGroup.getContextsForAddress(OTHER_ADDRESS);

        assertEquals(0, contexts.length);
    }

    @Test
    public void getCarAudioDeviceInfoForAddress_returnsExpectedDevice() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        CarAudioDeviceInfo actualDevice = carVolumeGroup.getCarAudioDeviceInfoForAddress(
                MEDIA_DEVICE_ADDRESS);

        assertEquals(mMediaDevice, actualDevice);
    }

    @Test
    public void getCarAudioDeviceInfoForAddress_returnsNullIfAddressNotBound() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        CarAudioDeviceInfo actualDevice = carVolumeGroup.getCarAudioDeviceInfoForAddress(
                OTHER_ADDRESS);

        assertNull(actualDevice);
    }

    @Test
    public void setCurrentGainIndex_setsGainOnAllBoundDevices() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        carVolumeGroup.setCurrentGainIndex(2);
        verify(mMediaDevice).setCurrentGain(4);
        verify(mNavigationDevice).setCurrentGain(4);
    }

    @Test
    public void setCurrentGainIndex_updatesCurrentGainIndex() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        carVolumeGroup.setCurrentGainIndex(2);

        assertEquals(2, carVolumeGroup.getCurrentGainIndex());
    }

    @Test
    public void setCurrentGainIndex_checksNewGainIsAboveMin() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("Gain out of range (0:5) -2index -1");

        carVolumeGroup.setCurrentGainIndex(-1);
    }

    @Test
    public void setCurrentGainIndex_checksNewGainIsBelowMax() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("Gain out of range (0:5) 6index 3");

        carVolumeGroup.setCurrentGainIndex(3);
    }

    @Test
    public void getMinGainIndex_alwaysReturnsZero() {
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(mContext, 0, 0);
        CarAudioDeviceInfo minGainPlusOneDevice = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, 10, MAX_GAIN);
        carVolumeGroup.bind(ContextNumber.NAVIGATION, minGainPlusOneDevice);

        assertEquals(0, carVolumeGroup.getMinGainIndex());

        CarAudioDeviceInfo minGainDevice = generateCarAudioDeviceInfo(
                NAVIGATION_DEVICE_ADDRESS, STEP_VALUE, 1, MAX_GAIN);
        carVolumeGroup.bind(ContextNumber.NAVIGATION, minGainDevice);

        assertEquals(0, carVolumeGroup.getMinGainIndex());
    }

    private CarVolumeGroup testVolumeGroupSetup() {
        CarVolumeGroup carVolumeGroup = new CarVolumeGroup(mContext, 0, 0);

        carVolumeGroup.bind(ContextNumber.MUSIC, mMediaDevice);
        carVolumeGroup.bind(ContextNumber.CALL, mMediaDevice);
        carVolumeGroup.bind(ContextNumber.CALL_RING, mMediaDevice);

        carVolumeGroup.bind(ContextNumber.NAVIGATION, mNavigationDevice);
        carVolumeGroup.bind(ContextNumber.ALARM, mNavigationDevice);
        carVolumeGroup.bind(ContextNumber.NOTIFICATION, mNavigationDevice);

        return carVolumeGroup;
    }

    private CarAudioDeviceInfo generateCarAudioDeviceInfo(String address) {
        return generateCarAudioDeviceInfo(address, STEP_VALUE, MIN_GAIN, MAX_GAIN);
    }

    private CarAudioDeviceInfo generateCarAudioDeviceInfo(String address, int stepValue,
            int minGain, int maxGain) {
        CarAudioDeviceInfo cadiMock = Mockito.mock(CarAudioDeviceInfo.class);
        when(cadiMock.getStepValue()).thenReturn(stepValue);
        when(cadiMock.getDefaultGain()).thenReturn(DEFAULT_GAIN);
        when(cadiMock.getMaxGain()).thenReturn(maxGain);
        when(cadiMock.getMinGain()).thenReturn(minGain);
        when(cadiMock.getAddress()).thenReturn(address);
        return cadiMock;
    }

}
