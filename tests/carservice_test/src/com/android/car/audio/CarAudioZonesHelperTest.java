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

import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.media.AudioAttributes.USAGE_ANNOUNCEMENT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioAttributes.USAGE_VEHICLE_STATUS;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC;
import static android.media.AudioDeviceInfo.TYPE_BUS;
import static android.media.AudioDeviceInfo.TYPE_FM_TUNER;

import static com.android.car.audio.CarAudioService.CAR_DEFAULT_AUDIO_ATTRIBUTE;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.test.core.app.ApplicationProvider;

import com.android.car.R;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(MockitoJUnitRunner.class)
public class CarAudioZonesHelperTest extends AbstractExtendedMockitoTestCase {
    private static final String TAG = CarAudioZonesHelperTest.class.getSimpleName();

    public static final CarAudioContext TEST_CAR_AUDIO_CONTEXT =
            new CarAudioContext(CarAudioContext.getAllContextsInfo(),
                    /* useCoreAudioRouting= */ false);
    public static final int TEST_DEFAULT_CONTEXT_ID = TEST_CAR_AUDIO_CONTEXT
            .getContextForAudioAttribute(CAR_DEFAULT_AUDIO_ATTRIBUTE);
    public static final int TEST_EMERGENCY_CONTEXT_ID = TEST_CAR_AUDIO_CONTEXT
            .getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_EMERGENCY));
    public static final int TEST_SAFETY_CONTEXT_ID = TEST_CAR_AUDIO_CONTEXT
            .getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_SAFETY));
    public static final int TEST_VEHICLE_STATUS_CONTEXT_ID = TEST_CAR_AUDIO_CONTEXT
            .getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_VEHICLE_STATUS));
    public static final int TEST_ANNOUNCEMENT_CONTEXT_ID = TEST_CAR_AUDIO_CONTEXT
            .getContextForAudioAttribute(CarAudioContext
                    .getAudioAttributeFromUsage(USAGE_ANNOUNCEMENT));
    private List<CarAudioDeviceInfo> mCarAudioOutputDeviceInfos;
    private AudioDeviceInfo[] mInputAudioDeviceInfos;
    private InputStream mInputStream;
    private Context mContext;
    private CarAudioSettings mCarAudioSettings;
    @Mock
    private AudioManager mAudioManager;

    private static final String PRIMARY_ZONE_NAME = "primary zone";

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

    public CarAudioZonesHelperTest() {
        super(TAG);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(AudioManager.class);
    }

    @Before
    public void setUp() {
        setupAudioManagerMock();

        mCarAudioOutputDeviceInfos = generateCarDeviceInfos();
        mInputAudioDeviceInfos = generateInputDeviceInfos();
        mContext = ApplicationProvider.getApplicationContext();
        mInputStream = mContext.getResources().openRawResource(R.raw.car_audio_configuration);
        mCarAudioSettings = mock(CarAudioSettings.class);
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
                generateInputAudioDeviceInfo(PRIMARY_ZONE_MICROPHONE_ADDRESS, TYPE_BUILTIN_MIC),
                generateInputAudioDeviceInfo(PRIMARY_ZONE_FM_TUNER_ADDRESS, TYPE_FM_TUNER),
                generateInputAudioDeviceInfo(SECONDARY_ZONE_BACK_MICROPHONE_ADDRESS, TYPE_BUS),
                generateInputAudioDeviceInfo(SECONDARY_ZONE_BUS_1000_INPUT_ADDRESS,
                        TYPE_BUILTIN_MIC)
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
        when(inputMock.getInternalType()).thenReturn(
                AudioDeviceInfo.convertDeviceTypeToInternalInputDevice(type));
        return inputMock;
    }

    @Test
    public void loadAudioZones_parsesAllZones() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                mCarAudioSettings, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        assertThat(zones.size()).isEqualTo(2);
    }

    @Test
    public void loadAudioZones_versionOneParsesAllZones() throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                    mCarAudioSettings, versionOneStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                    /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            assertThat(zones.size()).isEqualTo(2);
        }
    }

    @Test
    public void loadAudioZones_parsesAudioZoneId() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                mCarAudioSettings, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();


        assertThat(zones.size()).isEqualTo(2);
        assertThat(zones.contains(PRIMARY_AUDIO_ZONE)).isTrue();
        assertThat(zones.contains(SECONDARY_ZONE_ID)).isTrue();
    }

    @Test
    public void loadAudioZones_parsesOccupantZoneId() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                mCarAudioSettings, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        assertThat(zones.size()).isEqualTo(2);

        SparseIntArray audioZoneIdToOccupantZoneIdMapping =
                cazh.getCarAudioZoneIdToOccupantZoneIdMapping();
        assertThat(audioZoneIdToOccupantZoneIdMapping.get(PRIMARY_AUDIO_ZONE))
                .isEqualTo(PRIMARY_OCCUPANT_ID);
        assertThat(audioZoneIdToOccupantZoneIdMapping.get(SECONDARY_ZONE_ID, -1))
                .isEqualTo(-1);
    }

    @Test
    public void loadAudioZones_parsesZoneName() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                mCarAudioSettings, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones.get(0);
        assertThat(primaryZone.getName()).isEqualTo(PRIMARY_ZONE_NAME);
    }

    @Test
    public void loadAudioZones_parsesIsPrimary() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                mCarAudioSettings, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones.get(0);
        assertThat(primaryZone.isPrimaryZone()).isTrue();

        CarAudioZone rseZone = zones.get(2);
        assertThat(rseZone.isPrimaryZone()).isFalse();
    }

    @Test
    public void loadAudioZones_parsesVolumeGroups() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                mCarAudioSettings, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones.get(0);
        assertThat(primaryZone.getCurrentVolumeGroupCount()).isEqualTo(2);
    }

    @Test
    public void loadAudioZones_parsesAddresses() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                mCarAudioSettings, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones.get(0);
        CarVolumeGroup volumeGroup = primaryZone.getCurrentVolumeGroups()[0];
        List<String> addresses = volumeGroup.getAddresses();
        assertThat(addresses).containsExactly(BUS_0_ADDRESS, BUS_3_ADDRESS);
    }

    @Test
    public void loadAudioZones_parsesContexts() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                mCarAudioSettings, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones.get(0);
        CarVolumeGroup volumeGroup = primaryZone.getCurrentVolumeGroups()[0];
        assertThat(volumeGroup.getContextsForAddress(BUS_0_ADDRESS))
                .containsExactly(TEST_CAR_AUDIO_CONTEXT.getContextForAttributes(CarAudioContext
                        .getAudioAttributeFromUsage(AudioAttributes.USAGE_MEDIA)));

        CarAudioZone rearSeatEntertainmentZone = zones.get(2);
        CarVolumeGroup rseVolumeGroup = rearSeatEntertainmentZone.getCurrentVolumeGroups()[0];
        List<Integer> contextForBus100List = rseVolumeGroup.getContextsForAddress(BUS_100_ADDRESS);
        List<Integer> contextsList = TEST_CAR_AUDIO_CONTEXT.getAllContextsIds();
        assertThat(contextForBus100List).containsExactlyElementsIn(contextsList);
    }

    @Test
    public void loadAudioZones_forVersionOne_bindsNonLegacyContextsToDefault() throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1)) {

            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                    mCarAudioSettings, versionOneStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                    /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            CarAudioZone defaultZone = zones.get(0);
            CarVolumeGroup volumeGroup = defaultZone.getCurrentVolumeGroups()[0];
            List<Integer> audioContexts = Arrays.stream(volumeGroup.getContexts()).boxed()
                    .collect(Collectors.toList());

            assertThat(audioContexts).containsAtLeast(TEST_DEFAULT_CONTEXT_ID,
                    TEST_EMERGENCY_CONTEXT_ID, TEST_SAFETY_CONTEXT_ID,
                    TEST_VEHICLE_STATUS_CONTEXT_ID, TEST_ANNOUNCEMENT_CONTEXT_ID);
        }
    }

    @Test
    public void loadAudioZones_forVersionOneWithNonLegacyContexts_throws() throws Exception {
        try (InputStream v1NonLegacyContextStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1_with_non_legacy_contexts)) {

            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, v1NonLegacyContextStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    cazh::loadAudioZones);

            assertThat(exception).hasMessageThat().contains("Non-legacy audio contexts such as");
        }
    }

    @Test
    public void loadAudioZones_passesOnMissingAudioZoneIdForPrimary() throws Exception {
        try (InputStream missingAudioZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_no_audio_zone_id_for_primary_zone)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, missingAudioZoneIdStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            assertThat(zones.size()).isEqualTo(2);
            assertThat(zones.contains(PRIMARY_AUDIO_ZONE)).isTrue();
            assertThat(zones.contains(SECONDARY_ZONE_ID)).isTrue();
        }
    }

    @Test
    public void loadAudioZones_versionOneFailsOnAudioZoneId() throws Exception {
        try (InputStream versionOneAudioZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1_with_audio_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                    mCarAudioSettings,
                    versionOneAudioZoneIdStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("Invalid audio attribute audioZoneId");
        }
    }

    @Test
    public void loadAudioZones_versionOneFailsOnOccupantZoneId() throws Exception {
        try (InputStream versionOneOccupantIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1_with_occupant_zone_id)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, versionOneOccupantIdStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("Invalid audio attribute occupantZoneId");
        }
    }

    @Test
    public void loadAudioZones_primaryZoneHasInputDevices() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                mCarAudioSettings, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones.get(PRIMARY_AUDIO_ZONE);
        assertThat(primaryZone.getInputAudioDevices()).hasSize(2);
    }

    @Test
    public void loadAudioZones_primaryZoneHasMicrophoneDevice() throws Exception {
        CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                mCarAudioSettings, mInputStream,
                mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones.get(PRIMARY_AUDIO_ZONE);
        for (AudioDeviceAttributes info : primaryZone.getInputAudioDevices()) {
            assertThat(info.getType()).isEqualTo(TYPE_BUILTIN_MIC);
        }
    }

    @Test
    public void loadAudioZones_parsesInputDevices() throws Exception {
        try (InputStream inputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_input_devices)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, inputDevicesStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            CarAudioZone primaryZone = zones.get(0);
            List<AudioDeviceAttributes> primaryZoneInputDevices =
                    primaryZone.getInputAudioDevices();
            assertThat(primaryZoneInputDevices).hasSize(2);

            List<String> primaryZoneInputAddresses =
                    primaryZoneInputDevices.stream().map(a -> a.getAddress()).collect(
                            Collectors.toList());
            assertThat(primaryZoneInputAddresses).containsExactly(PRIMARY_ZONE_FM_TUNER_ADDRESS,
                    PRIMARY_ZONE_MICROPHONE_ADDRESS).inOrder();

            CarAudioZone secondaryZone = zones.get(1);
            List<AudioDeviceAttributes> secondaryZoneInputDevices =
                    secondaryZone.getInputAudioDevices();
            List<String> secondaryZoneInputAddresses =
                    secondaryZoneInputDevices.stream().map(a -> a.getAddress()).collect(
                            Collectors.toList());
            assertThat(secondaryZoneInputAddresses).containsExactly(
                    SECONDARY_ZONE_BUS_1000_INPUT_ADDRESS,
                    SECONDARY_ZONE_BACK_MICROPHONE_ADDRESS).inOrder();
        }
    }

    @Test
    public void loadAudioZones_failsOnDuplicateOccupantZoneId() throws Exception {
        try (InputStream duplicateOccupantZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_duplicate_occupant_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                    mCarAudioSettings,
                    duplicateOccupantZoneIdStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("already associated with a zone");
        }
    }

    @Test
    public void loadAudioZones_failsOnDuplicateAudioZoneId() throws Exception {
        try (InputStream duplicateAudioZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_duplicate_audio_zone_id)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, duplicateAudioZoneIdStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("already associated with a zone");
        }
    }

    @Test
    public void loadAudioZones_failsOnEmptyInputDeviceAddress() throws Exception {
        try (InputStream inputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_empty_input_device)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, inputDevicesStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("empty.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNonNumericalAudioZoneId() throws Exception {
        try (InputStream nonNumericalStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_non_numerical_audio_zone_id)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, nonNumericalStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("was \"primary\" instead.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNegativeAudioZoneId() throws Exception {
        try (InputStream negativeAudioZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_negative_audio_zone_id)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager, mCarAudioSettings,
                            negativeAudioZoneIdStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("but was \"-1\" instead.");
        }
    }

    @Test
    public void loadAudioZones_failsOnMissingInputDevice() throws Exception {
        try (InputStream inputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_missing_address)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, inputDevicesStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

            NullPointerException thrown =
                    assertThrows(NullPointerException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("present.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNonNumericalOccupantZoneId() throws Exception {
        try (InputStream nonNumericalStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_non_numerical_occupant_zone_id)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, nonNumericalStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("was \"one\" instead.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNegativeOccupantZoneId() throws Exception {
        try (InputStream negativeOccupantZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_negative_occupant_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                    mCarAudioSettings,
                    negativeOccupantZoneIdStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("was \"-1\" instead.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNonExistentInputDevice() throws Exception {
        try (InputStream inputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_non_existent_input_device)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, inputDevicesStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("does not exist");
        }
    }

    @Test
    public void loadAudioZones_failsOnEmptyOccupantZoneId() throws Exception {
        try (InputStream emptyOccupantZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_empty_occupant_zone_id)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, emptyOccupantZoneIdStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("but was \"\" instead.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNonZeroAudioZoneIdForPrimary() throws Exception {
        try (InputStream nonZeroForPrimaryStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_primary_zone_with_non_zero_audio_zone_id)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager, mCarAudioSettings,
                            nonZeroForPrimaryStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("it can be left empty.");
        }
    }

    @Test
    public void loadAudioZones_failsOnZeroAudioZoneIdForSecondary() throws Exception {
        try (InputStream zeroZoneIdForSecondaryStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_non_primary_zone_with_primary_audio_zone_id)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager,
                    mCarAudioSettings,
                    zeroZoneIdForSecondaryStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains(PRIMARY_ZONE_NAME);
        }
    }

    @Test
    public void loadAudioZones_failsOnRepeatedInputDevice() throws Exception {
        try (InputStream inputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_repeat_input_device)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, inputDevicesStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("can not repeat.");
        }
    }

    @Test
    public void loadAudioZones_failsOnMissingOutputDevice() throws Exception {
        try (InputStream outputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_output_address_does_not_exist)) {
            CarAudioZonesHelper cazh =
                    new CarAudioZonesHelper(mAudioManager,
                            mCarAudioSettings, outputDevicesStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false);

            IllegalStateException thrown =
                    assertThrows(IllegalStateException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains(BUS_1000_ADDRESS_DOES_NOT_EXIST);
        }
    }

    @Test
    public void loadAudioZones_usingCoreAudioVersionThreeParsesAllZones() throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_routing_and_volume)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager, mCarAudioSettings,
                    versionOneStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                    /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ true);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            assertThat(zones.size()).isEqualTo(1);
        }
    }

    @Test
    public void loadAudioZones_usingCoreAudioVersionThree_failsOnEmptyGroupName()
            throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_routing_and_volume_empty_group_name)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager, mCarAudioSettings,
                    versionOneStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                    /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ true);

            RuntimeException thrown =
                    assertThrows(RuntimeException.class, () -> cazh.loadAudioZones());

            assertThat(thrown).hasMessageThat().contains(
                    "group name attribute can not be empty when relying on core volume groups");
        }
    }

    @Test
    public void loadAudioZones_usingCoreAudioVersionThree_failsOnInvalidOrder()
            throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_routing_and_volume_invalid_order)) {
            CarAudioZonesHelper cazh = new CarAudioZonesHelper(mAudioManager, mCarAudioSettings,
                    versionOneStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                    /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ true);

            RuntimeException thrown =
                    assertThrows(RuntimeException.class, () -> cazh.loadAudioZones());

            assertThat(thrown).hasMessageThat().contains(
                    "Egg and chicken issue, context shall be parsed first");
        }
    }

    private void setupAudioManagerMock() {
        doReturn(CoreAudioRoutingUtils.getProductStrategies())
                .when(() -> AudioManager.getAudioProductStrategies());
        doReturn(CoreAudioRoutingUtils.getVolumeGroups())
                .when(() -> AudioManager.getAudioVolumeGroups());
    }
}
