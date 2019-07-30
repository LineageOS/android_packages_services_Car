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

import static junit.framework.TestCase.fail;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.automotive.audiocontrol.V1_0.ContextNumber;
import android.view.DisplayAddress;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.R;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CarAudioZonesHelperTest {
    private List<CarAudioDeviceInfo> mCarAudioDeviceInfos;
    private Context mContext;
    private InputStream mInputStream;
    private static final String BUS_0_ADDRESS = "bus0_media_out";
    private static final String BUS_1_ADDRESS = "bus1_navigation_out";
    private static final String BUS_3_ADDRESS = "bus3_call_ring_out";
    private static final String BUS_100_ADDRESS = "bus100_rear_seat";

    @Before
    public void setUp() {
        mCarAudioDeviceInfos = generateCarDeviceInfos();
        mContext = ApplicationProvider.getApplicationContext();
        mInputStream = mContext.getResources().openRawResource(R.raw.car_audio_configuration);
    }

    @After
    public void tearDown() throws IOException {
        if (mInputStream != null) {
            mInputStream.close();
        }
    }

    private List<CarAudioDeviceInfo> generateCarDeviceInfos() {
        return ImmutableList.of(
                generateCarAudioDeviceInfo(BUS_0_ADDRESS),
                generateCarAudioDeviceInfo(BUS_1_ADDRESS),
                generateCarAudioDeviceInfo(BUS_3_ADDRESS),
                generateCarAudioDeviceInfo(BUS_100_ADDRESS),
                generateCarAudioDeviceInfo(""),
                generateCarAudioDeviceInfo(""),
                generateCarAudioDeviceInfo(null),
                generateCarAudioDeviceInfo(null)

        );
    }

    private CarAudioDeviceInfo generateCarAudioDeviceInfo(String address) {
        CarAudioDeviceInfo cadiMock = Mockito.mock(CarAudioDeviceInfo.class);
        when(cadiMock.getStepValue()).thenReturn(1);
        when(cadiMock.getDefaultGain()).thenReturn(2);
        when(cadiMock.getMaxGain()).thenReturn(5);
        when(cadiMock.getMinGain()).thenReturn(0);
        when(cadiMock.getAddress()).thenReturn(address);
        return cadiMock;
    }

    @Test
    public void loadAudioZones_parsesAllZones() throws IOException, XmlPullParserException {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        assertEquals(2, zones.length);
    }

    @Test
    public void loadAudioZones_parsesZoneName() throws IOException, XmlPullParserException {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones[0];
        assertEquals("primary zone", primaryZone.getName());
    }

    @Test
    public void loadAudioZones_parsesIsPrimary() throws IOException, XmlPullParserException {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones[0];
        assertTrue(primaryZone.isPrimaryZone());

        CarAudioZone rseZone = zones[1];
        assertFalse(rseZone.isPrimaryZone());
    }

    @Test
    public void loadAudioZones_parsesVolumeGroups() throws IOException, XmlPullParserException {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones[0];
        assertEquals(2, primaryZone.getVolumeGroupCount());
    }

    @Test
    public void loadAudioZones_parsesAddresses() throws IOException, XmlPullParserException {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones[0];
        CarVolumeGroup volumeGroup = primaryZone.getVolumeGroups()[0];
        List<String> addresses = volumeGroup.getAddresses();
        assertEquals(2, addresses.size());
        assertEquals(BUS_0_ADDRESS, addresses.get(0));
        assertEquals(BUS_3_ADDRESS, addresses.get(1));
    }

    @Test
    public void loadAudioZones_parsesContexts() throws IOException, XmlPullParserException {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones[0];
        CarVolumeGroup volumeGroup = primaryZone.getVolumeGroups()[0];
        int[] expectedContextForBus0 = {ContextNumber.MUSIC};
        assertArrayEquals(expectedContextForBus0, volumeGroup.getContextsForAddress(BUS_0_ADDRESS));

        int[] expectedContextForBus100 = new int[]{ContextNumber.MUSIC, ContextNumber.NAVIGATION,
                ContextNumber.VOICE_COMMAND, ContextNumber.CALL_RING, ContextNumber.CALL,
                ContextNumber.ALARM, ContextNumber.NOTIFICATION, ContextNumber.SYSTEM_SOUND};
        CarAudioZone rearSeatEntertainmentZone = zones[1];
        CarVolumeGroup rseVolumeGroup = rearSeatEntertainmentZone.getVolumeGroups()[0];
        int[] contextForBus100 = rseVolumeGroup.getContextsForAddress(BUS_100_ADDRESS);
        assertArrayEquals(expectedContextForBus100, contextForBus100);
    }

    @Test
    public void loadAudioZones_parsesPhysicalDisplayAddresses()
            throws IOException, XmlPullParserException {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones[0];
        List<DisplayAddress.Physical> primaryPhysicals = primaryZone.getPhysicalDisplayAddresses();
        assertEquals(2, primaryPhysicals.size());
        assertEquals(1, (long) primaryPhysicals.get(0).getPort());
        assertEquals(2, (long) primaryPhysicals.get(1).getPort());
    }

    @Test
    public void loadAudioZones_defaultsDisplayAddressesToEmptyList()
            throws IOException, XmlPullParserException {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone rseZone = zones[1];
        List<DisplayAddress.Physical> rsePhysicals = rseZone.getPhysicalDisplayAddresses();
        assertTrue(rsePhysicals.isEmpty());
    }

    @Test(expected = RuntimeException.class)
    public void loadAudioZones_throwsOnDuplicatePorts() throws IOException, XmlPullParserException {
        try (InputStream duplicatePortStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_duplicate_ports)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, duplicatePortStream,
                    mCarAudioDeviceInfos);

            cazh.loadAudioZones();
        }
    }

    @Test
    public void loadAudioZones_throwsOnNonNumericalPort()
            throws IOException, XmlPullParserException {
        try (InputStream duplicatePortStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_non_numerical_port)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, duplicatePortStream,
                    mCarAudioDeviceInfos);

            try {
                cazh.loadAudioZones();
                fail();
            } catch (RuntimeException e) {
                assertEquals(NumberFormatException.class, e.getCause().getClass());
            }
        }
    }
}
