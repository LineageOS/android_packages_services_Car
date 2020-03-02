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

import static com.android.car.audio.CarAudioService.DEFAULT_AUDIO_CONTEXT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.expectThrows;

import android.car.media.CarAudioManager;
import android.content.Context;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.util.SparseIntArray;
import android.view.DisplayAddress;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.R;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public class CarAudioZonesHelperTest {
    private List<CarAudioDeviceInfo> mCarAudioOutputDeviceInfos;
    private AudioDeviceInfo[] mInputAudioDeviceInfos;
    private Context mContext;
    private InputStream mInputStream;
    private static final String BUS_0_ADDRESS = "bus0_media_out";
    private static final String BUS_1_ADDRESS = "bus1_navigation_out";
    private static final String BUS_3_ADDRESS = "bus3_call_ring_out";
    private static final String BUS_100_ADDRESS = "bus100_rear_seat";
    private static final String BUS_1000_ADDRESS_DOES_NOT_EXIST = "bus1000_does_not_exist";

    private static final String PRIMARY_ZONE_MICROPHONE_ADDRESS = "Built-In Mic";
    private static final String PRIMARY_ZONE_FM_TUNER_ADDRESS = "fm_tuner";
    private static final String SECONDARY_ZONE_BACK_MICROPHONE_ADDRESS = "Built-In Back Mic";
    private static final String SECONDARY_ZONE_BUS_1000_INPUT_ADDRESS = "bus_1000_input";

    private static final int PRIMARY_OCCUPANT_ID = 1;
    private static final int SECONDARY_ZONE_ID = 2;

    @Before
    public void setUp() {
        mCarAudioOutputDeviceInfos = generateCarDeviceInfos();
        mInputAudioDeviceInfos = generateInputDeviceInfos();
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

    private AudioDeviceInfo[] generateInputDeviceInfos() {
        return new AudioDeviceInfo[]{
                generateInputAudioDeviceInfo(PRIMARY_ZONE_MICROPHONE_ADDRESS,
                        AudioDeviceInfo.TYPE_BUILTIN_MIC),
                generateInputAudioDeviceInfo(PRIMARY_ZONE_FM_TUNER_ADDRESS,
                        AudioDeviceInfo.TYPE_FM_TUNER),
                generateInputAudioDeviceInfo(SECONDARY_ZONE_BACK_MICROPHONE_ADDRESS,
                        AudioDeviceInfo.TYPE_BUS),
                generateInputAudioDeviceInfo(SECONDARY_ZONE_BUS_1000_INPUT_ADDRESS,
                        AudioDeviceInfo.TYPE_BUILTIN_MIC)
        };
    }

    private CarAudioDeviceInfo generateCarAudioDeviceInfo(String address) {
        CarAudioDeviceInfo cadiMock = mock(CarAudioDeviceInfo.class);
        when(cadiMock.getStepValue()).thenReturn(1);
        when(cadiMock.getDefaultGain()).thenReturn(2);
        when(cadiMock.getMaxGain()).thenReturn(5);
        when(cadiMock.getMinGain()).thenReturn(0);
        when(cadiMock.getAddress()).thenReturn(address);
        return cadiMock;
    }

    private AudioDeviceInfo generateInputAudioDeviceInfo(String address, int type) {
        AudioDeviceInfo inputMock = mock(AudioDeviceInfo.class);
        when(inputMock.getAddress()).thenReturn(address);
        when(inputMock.getType()).thenReturn(type);
        when(inputMock.isSource()).thenReturn(true);
        when(inputMock.isSink()).thenReturn(false);
        return inputMock;
    }

    @Test
    public void loadAudioZones_parsesAllZones() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        assertThat(zones.length).isEqualTo(2);
    }

    @Test
    public void loadAudioZones_versionOneParsesAllZones() throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, versionOneStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

            CarAudioZone[] zones = cazh.loadAudioZones();

            assertThat(zones.length).isEqualTo(2);
        }
    }

    @Test
    public void loadAudioZones_parsesAudioZoneId() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        List<Integer> zoneIds = getListOfZoneIds(zones);
        assertThat(zoneIds.size()).isEqualTo(2);
        assertThat(zoneIds)
                .containsAllOf(CarAudioManager.PRIMARY_AUDIO_ZONE, SECONDARY_ZONE_ID).inOrder();
    }

    @Test
    public void loadAudioZones_parsesOccupantZoneId() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        assertEquals(2, zones.length);

        SparseIntArray audioZoneIdToOccupantZoneIdMapping =
                cazh.getCarAudioZoneIdToOccupantZoneIdMapping();
        assertThat(audioZoneIdToOccupantZoneIdMapping.get(CarAudioManager.PRIMARY_AUDIO_ZONE))
                .isEqualTo(PRIMARY_OCCUPANT_ID);
        assertThat(audioZoneIdToOccupantZoneIdMapping.get(SECONDARY_ZONE_ID, -1))
                .isEqualTo(-1);
    }

    @Test
    public void loadAudioZones_parsesZoneName() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones[0];
        assertEquals("primary zone", primaryZone.getName());
    }

    @Test
    public void loadAudioZones_parsesIsPrimary() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones[0];
        assertTrue(primaryZone.isPrimaryZone());

        CarAudioZone rseZone = zones[1];
        assertFalse(rseZone.isPrimaryZone());
    }

    @Test
    public void loadAudioZones_parsesVolumeGroups() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones[0];
        assertEquals(2, primaryZone.getVolumeGroupCount());
    }

    @Test
    public void loadAudioZones_parsesAddresses() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones[0];
        CarVolumeGroup volumeGroup = primaryZone.getVolumeGroups()[0];
        List<String> addresses = volumeGroup.getAddresses();
        assertEquals(2, addresses.size());
        assertEquals(BUS_0_ADDRESS, addresses.get(0));
        assertEquals(BUS_3_ADDRESS, addresses.get(1));
    }

    @Test
    public void loadAudioZones_parsesContexts() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones[0];
        CarVolumeGroup volumeGroup = primaryZone.getVolumeGroups()[0];
        int[] expectedContextForBus0 = {CarAudioContext.MUSIC};
        assertArrayEquals(expectedContextForBus0, volumeGroup.getContextsForAddress(BUS_0_ADDRESS));

        int[] expectedContextForBus100 = CarAudioContext.CONTEXTS;
        CarAudioZone rearSeatEntertainmentZone = zones[1];
        CarVolumeGroup rseVolumeGroup = rearSeatEntertainmentZone.getVolumeGroups()[0];
        int[] contextForBus100 = rseVolumeGroup.getContextsForAddress(BUS_100_ADDRESS);
        assertArrayEquals(expectedContextForBus100, contextForBus100);
    }

    @Test
    public void loadAudioZones_forVersionOne_bindsNonLegacyContextsToDefault() throws Exception {
        InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1);

        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, versionOneStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone defaultZone = zones[0];
        CarVolumeGroup volumeGroup = defaultZone.getVolumeGroups()[0];
        List<Integer> audioContexts = Arrays.stream(volumeGroup.getContexts()).boxed()
                .collect(Collectors.toList());

        assertThat(audioContexts).containsAllOf(DEFAULT_AUDIO_CONTEXT, CarAudioContext.EMERGENCY,
                CarAudioContext.SAFETY, CarAudioContext.VEHICLE_STATUS,
                CarAudioContext.ANNOUNCEMENT);
    }

    @Test
    public void loadAudioZones_forVersionOneWithNonLegacyContexts_throws() {
        InputStream v1NonLegacyContextStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1_with_non_legacy_contexts);

        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, v1NonLegacyContextStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
                cazh::loadAudioZones);

        assertThat(exception).hasMessageThat().contains("Non-legacy audio contexts such as");
    }

    @Test
    public void loadAudioZones_parsesPhysicalDisplayAddresses() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones[0];
        List<DisplayAddress.Physical> primaryPhysicals = primaryZone.getPhysicalDisplayAddresses();
        assertEquals(2, primaryPhysicals.size());
        assertEquals(1, (long) primaryPhysicals.get(0).getPort());
        assertEquals(2, (long) primaryPhysicals.get(1).getPort());
    }

    @Test
    public void loadAudioZones_defaultsDisplayAddressesToEmptyList() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        CarAudioZone[] zones = cazh.loadAudioZones();

        CarAudioZone rseZone = zones[1];
        List<DisplayAddress.Physical> rsePhysicals = rseZone.getPhysicalDisplayAddresses();
        assertTrue(rsePhysicals.isEmpty());
    }

    @Test(expected = RuntimeException.class)
    public void loadAudioZones_throwsOnDuplicatePorts() throws Exception {
        try (InputStream duplicatePortStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_duplicate_ports)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, duplicatePortStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

            cazh.loadAudioZones();
        }
    }

    @Test
    public void loadAudioZones_throwsOnNonNumericalPort() {
        InputStream duplicatePortStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_non_numerical_port);
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, duplicatePortStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
                cazh::loadAudioZones);
        assertThat(exception).hasMessageThat().contains("Port one is not a number");
    }

    @Test
    public void loadAudioZones_passesOnMissingAudioZoneIdForPrimary() throws Exception {
        try (InputStream missingAudioZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_no_audio_zone_id_for_primary_zone)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, missingAudioZoneIdStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

            CarAudioZone[] zones = cazh.loadAudioZones();

            List<Integer> zoneIds = getListOfZoneIds(zones);
            assertThat(zoneIds.size()).isEqualTo(2);
            assertThat(zoneIds).contains(CarAudioManager.PRIMARY_AUDIO_ZONE);
            assertThat(zoneIds).contains(SECONDARY_ZONE_ID);
        }
    }

    @Test
    public void loadAudioZones_versionOneFailsOnAudioZoneId() throws Exception {
        try (InputStream versionOneAudioZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1_with_audio_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext,
                    versionOneAudioZoneIdStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos);
            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("Invalid audio attribute audioZoneId");
        }
    }

    @Test
    public void loadAudioZones_versionOneFailsOnOccupantZoneId() throws Exception {
        try (InputStream versionOneOccupantIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1_with_occupant_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, versionOneOccupantIdStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);
            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("Invalid audio attribute occupantZoneId");
        }
    }

    @Test
    public void loadAudioZones_parsesInputDevices() throws Exception {
        try (InputStream inputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_input_devices)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, inputDevicesStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

            CarAudioZone[] zones = cazh.loadAudioZones();

            CarAudioZone primaryZone = zones[0];
            List<AudioDeviceAttributes> primaryZoneInputDevices =
                    primaryZone.getInputAudioDevices();
            assertThat(primaryZoneInputDevices).hasSize(2);

            List<String> primaryZoneInputAddresses =
                    primaryZoneInputDevices.stream().map(a -> a.getAddress()).collect(
                            Collectors.toList());
            assertThat(primaryZoneInputAddresses).containsAllOf(PRIMARY_ZONE_FM_TUNER_ADDRESS,
                    PRIMARY_ZONE_MICROPHONE_ADDRESS).inOrder();

            CarAudioZone secondaryZone = zones[1];
            List<AudioDeviceAttributes> secondaryZoneInputDevices =
                    secondaryZone.getInputAudioDevices();
            List<String> secondaryZoneInputAddresses =
                    secondaryZoneInputDevices.stream().map(a -> a.getAddress()).collect(
                            Collectors.toList());
            assertThat(secondaryZoneInputAddresses).containsAllOf(
                    SECONDARY_ZONE_BUS_1000_INPUT_ADDRESS,
                    SECONDARY_ZONE_BACK_MICROPHONE_ADDRESS).inOrder();
        }
    }

    @Test
    public void loadAudioZones_failsOnDuplicateOccupantZoneId() throws Exception {
        try (InputStream duplicateOccupantZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_duplicate_occupant_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext,
                    duplicateOccupantZoneIdStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos);
            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("already associated with a zone");
        }
    }

    @Test
    public void loadAudioZones_failsOnDuplicateAudioZoneId() throws Exception {
        try (InputStream duplicateAudioZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_duplicate_audio_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, duplicateAudioZoneIdStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);
            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("already associated with a zone");
        }
    }

    @Test
    public void loadAudioZones_failsOnEmptyInputDeviceAddress() throws Exception {
        try (InputStream inputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_empty_input_device)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, inputDevicesStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("empty.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNonNumericalAudioZoneId() throws Exception {
        try (InputStream nonNumericalStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_non_numerical_audio_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, nonNumericalStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);
            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("was \"primary\" instead.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNegativeAudioZoneId() throws Exception {
        try (InputStream negativeAudioZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_negative_audio_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, negativeAudioZoneIdStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);
            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("but was \"-1\" instead.");
        }
    }

    @Test
    public void loadAudioZones_failsOnMissingInputDevice() throws Exception {
        try (InputStream inputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_missing_address)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, inputDevicesStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

            NullPointerException thrown =
                    expectThrows(NullPointerException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("present.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNonNumericalOccupantZoneId() throws Exception {
        try (InputStream nonNumericalStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_non_numerical_occupant_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, nonNumericalStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);
            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("was \"one\" instead.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNegativeOccupantZoneId() throws Exception {
        try (InputStream negativeOccupantZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_negative_occupant_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext,
                    negativeOccupantZoneIdStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos);
            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("was \"-1\" instead.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNonExistentInputDevice() throws Exception {
        try (InputStream inputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_non_existent_input_device)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, inputDevicesStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("does not exist");
        }
    }

    @Test
    public void loadAudioZones_failsOnEmptyOccupantZoneId() throws Exception {
        try (InputStream emptyOccupantZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_empty_occupant_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, emptyOccupantZoneIdStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);
            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("but was \"\" instead.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNonZeroAudioZoneIdForPrimary() throws Exception {
        try (InputStream nonZeroForPrimaryStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_primary_zone_with_non_zero_audio_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, nonZeroForPrimaryStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);
            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("it can be left empty.");
        }
    }

    @Test
    public void loadAudioZones_failsOnZeroAudioZoneIdForSecondary() throws Exception {
        try (InputStream zeroZoneIdForSecondaryStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_non_primary_zone_with_primary_audio_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext,
                    zeroZoneIdForSecondaryStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos);
            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("for primary zone");
        }
    }

    @Test
    public void loadAudioZones_failsOnRepeatedInputDevice() throws Exception {
        try (InputStream inputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_repeat_input_device)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, inputDevicesStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

            IllegalArgumentException thrown =
                    expectThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("can not repeat.");
        }
    }

    @Test
    public void loadAudioZones_failsOnMissingOutputDevice() throws Exception {
        try (InputStream outputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_output_address_does_not_exist)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mContext, outputDevicesStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos);

            IllegalStateException thrown =
                    expectThrows(IllegalStateException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains(BUS_1000_ADDRESS_DOES_NOT_EXIST);
        }
    }

    private List<Integer> getListOfZoneIds(CarAudioZone[] zones) {
        return Arrays.stream(zones).map(CarAudioZone::getId).collect(Collectors.toList());
    }
}
