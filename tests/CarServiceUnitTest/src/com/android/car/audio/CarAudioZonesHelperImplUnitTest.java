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

import static android.car.PlatformVersion.VERSION_CODES.UPSIDE_DOWN_CAKE_0;
import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.car.test.mocks.AndroidMockitoHelper.mockCarGetPlatformVersion;
import static android.media.AudioAttributes.USAGE_ANNOUNCEMENT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioAttributes.USAGE_VEHICLE_STATUS;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC;
import static android.media.audiopolicy.Flags.FLAG_ENABLE_FADE_MANAGER_CONFIGURATION;

import static com.android.car.audio.CarAudioDeviceInfoTestUtils.ADDRESS_DOES_NOT_EXIST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.ALARM_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.CALL_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.MEDIA_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.MIRROR_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.NAVIGATION_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.NOTIFICATION_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.OEM_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.PRIMARY_ZONE_FM_TUNER_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.PRIMARY_ZONE_MICROPHONE_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.QUATERNARY_TEST_DEVICE_1;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.RING_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.SECONDARY_TEST_DEVICE_CONFIG_0;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.SECONDARY_TEST_DEVICE_CONFIG_1_0;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.SECONDARY_TEST_DEVICE_CONFIG_1_1;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.SECONDARY_ZONE_BACK_MICROPHONE_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.SECONDARY_ZONE_BUS_1000_INPUT_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.SYSTEM_BUS_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.TERTIARY_TEST_DEVICE_1;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.TERTIARY_TEST_DEVICE_2;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.TEST_REAR_ROW_3_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.VOICE_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.generateCarAudioDeviceInfo;
import static com.android.car.audio.CarAudioService.CAR_DEFAULT_AUDIO_ATTRIBUTE;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import android.car.Car;
import android.car.feature.Flags;
import android.car.media.CarAudioZoneConfigInfo;
import android.car.test.AbstractExpectableTestCase;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.test.core.app.ApplicationProvider;

import com.android.car.R;
import com.android.car.internal.util.LocalLog;
import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.MissingResourceException;
import java.util.stream.Collectors;

@RunWith(MockitoJUnitRunner.class)
public final class CarAudioZonesHelperImplUnitTest extends AbstractExpectableTestCase {

    private static final CarAudioContextInfo OEM_CONTEXT_INFO_MUSIC =
            new CarAudioContextInfo(new AudioAttributes[] {
                    CarAudioContext.getAudioAttributeFromUsage(AudioAttributes.USAGE_UNKNOWN),
                    CarAudioContext.getAudioAttributeFromUsage(AudioAttributes.USAGE_GAME),
                    CarAudioContext.getAudioAttributeFromUsage(AudioAttributes.USAGE_MEDIA)
            }, "OEM_MUSIC", 1);

    private static final CarAudioContextInfo OEM_CONTEXT_INFO_NAVIGATION =
            new CarAudioContextInfo(new AudioAttributes[] {
                    CarAudioContext.getAudioAttributeFromUsage(AudioAttributes
                            .USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)}, "OEM_NAVIGATION", 2);

    private static final CarAudioContextInfo OEM_CONTEXT_INFO_VOICE_COMMAND =
            new CarAudioContextInfo(new AudioAttributes[] {
                    CarAudioContext.getAudioAttributeFromUsage(
                            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY),
                    CarAudioContext.getAudioAttributeFromUsage(AudioAttributes.USAGE_ASSISTANT)
            }, "OEM_VOICE_COMMAND", 3);

    private static final CarAudioContextInfo OEM_CONTEXT_INFO_CALL_RING =
            new CarAudioContextInfo(new AudioAttributes[] {
                    CarAudioContext.getAudioAttributeFromUsage(
                            AudioAttributes.USAGE_NOTIFICATION_RINGTONE)}, "OEM_CALL_RING", 4);

    private static final CarAudioContextInfo OEM_CONTEXT_INFO_CALL =
            new CarAudioContextInfo(new AudioAttributes[] {
                    CarAudioContext.getAudioAttributeFromUsage(
                            AudioAttributes.USAGE_VOICE_COMMUNICATION),
                    CarAudioContext.getAudioAttributeFromUsage(
                            AudioAttributes.USAGE_CALL_ASSISTANT),
                    CarAudioContext.getAudioAttributeFromUsage(AudioAttributes
                            .USAGE_VOICE_COMMUNICATION_SIGNALLING)
            }, "OEM_CALL", 5);

    private static final CarAudioContextInfo OEM_CONTEXT_INFO_ALARM =
            new CarAudioContextInfo(new AudioAttributes[]{
                    CarAudioContext.getAudioAttributeFromUsage(AudioAttributes.USAGE_ALARM)
            }, "OEM_ALARM", 6);

    private static final CarAudioContextInfo OEM_CONTEXT_INFO_NOTIFICATION =
            new CarAudioContextInfo(new AudioAttributes[]{
                    CarAudioContext.getAudioAttributeFromUsage(AudioAttributes.USAGE_NOTIFICATION),
                    CarAudioContext.getAudioAttributeFromUsage(
                            AudioAttributes.USAGE_NOTIFICATION_EVENT)}, "OEM_NOTIFICATION", 7);

    private static final CarAudioContextInfo OEM_CONTEXT_INFO_SYSTEM_SOUND =
            new CarAudioContextInfo(new AudioAttributes[]{
                    CarAudioContext.getAudioAttributeFromUsage(
                            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)},
                    "OEM_SYSTEM_SOUND", 8);

    private static final CarAudioContextInfo OEM_CONTEXT_INFO_EMERGENCY =
            new CarAudioContextInfo(new AudioAttributes[]{
                    CarAudioContext.getAudioAttributeFromUsage(AudioAttributes.USAGE_EMERGENCY)
            }, "OEM_EMERGENCY", 9);

    private static final CarAudioContextInfo OEM_CONTEXT_INFO_SAFETY =
            new CarAudioContextInfo(new AudioAttributes[]{
                    CarAudioContext.getAudioAttributeFromUsage(AudioAttributes.USAGE_SAFETY)
            }, "OEM_SAFETY", 10);

    private static final CarAudioContextInfo OEM_CONTEXT_INFO_VEHICLE_STATUS =
            new CarAudioContextInfo(new AudioAttributes[]{
                    CarAudioContext.getAudioAttributeFromUsage(
                            AudioAttributes.USAGE_VEHICLE_STATUS)}, "OEM_VEHICLE_STATUS", 11);

    private static final CarAudioContextInfo OEM_CONTEXT_INFO_ANNOUNCEMENT =
            new CarAudioContextInfo(new AudioAttributes[]{
                    CarAudioContext.getAudioAttributeFromUsage(AudioAttributes.USAGE_ANNOUNCEMENT)
            }, "OEM_ANNOUNCEMENT", 12);

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
    private static final String PRIMARY_ZONE_NAME = "primary zone";
    private static final String PRIMARY_ZONE_CONFIG_NAME = "primary zone config 1";
    private static final String SECONDARY_ZONE_CONFIG_NAME_1 = "secondary zone config 1";
    private static final String SECONDARY_ZONE_CONFIG_NAME_2 = "secondary zone config 2";

    private static final int PRIMARY_ZONE_GROUP_ID_WITHOUT_ACTIVATION_VOLUME = 3;
    private static final int PRIMARY_ZONE_GROUP_0_ACTIVATION_VOLUME_TYPE =
            CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT;
    private static final int PRIMARY_ZONE_DEFAULT_ACTIVATION_VOLUME_TYPE =
            CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT
                    | CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_SOURCE_CHANGED
                    | CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED;

    private static final int PRIMARY_OCCUPANT_ID = 1;
    private static final int SECONDARY_ZONE_ID = 1;
    private static final int ZONE_NUM = 2;
    private List<CarAudioDeviceInfo> mCarAudioOutputDeviceInfos;
    private AudioDeviceInfo[] mInputAudioDeviceInfos;
    private InputStream mInputStream;
    private Context mContext;
    private CarAudioSettings mCarAudioSettings;
    @Mock
    private AudioManagerWrapper mAudioManagerWrapper;

    @Mock
    private CarAudioDeviceInfo mTestCarMirrorDevice;
    @Mock
    private LocalLog mServiceEventLogger;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    private StaticMockitoSession mSession;

    private CarAudioDeviceInfoTestUtils mAudioDeviceInfoTestUtils =
            new CarAudioDeviceInfoTestUtils();

    @Before
    public void setUp() {
        StaticMockitoSessionBuilder builder = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(AudioManagerWrapper.class)
                .spyStatic(Car.class)
                .spyStatic(CoreAudioHelper.class);

        mSession = builder.initMocks(this).startMocking();

        setupAudioManagerMock();

        mCarAudioOutputDeviceInfos = generateCarDeviceInfos();
        mInputAudioDeviceInfos = mAudioDeviceInfoTestUtils.generateInputDeviceInfos();
        mContext = ApplicationProvider.getApplicationContext();
        mInputStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_two_zones);
        mCarAudioSettings = mock(CarAudioSettings.class);
        mockCarGetPlatformVersion(UPSIDE_DOWN_CAKE_0);
    }

    @After
    public void tearDown() throws IOException {
        if (mInputStream != null) {
            mInputStream.close();
        }
        mSession.finishMocking();
    }

    private List<CarAudioDeviceInfo> generateCarDeviceInfos() {
        mTestCarMirrorDevice = generateCarAudioDeviceInfo(MIRROR_TEST_DEVICE);
        return ImmutableList.of(
                generateCarAudioDeviceInfo(MEDIA_TEST_DEVICE),
                generateCarAudioDeviceInfo(NAVIGATION_TEST_DEVICE),
                generateCarAudioDeviceInfo(CALL_TEST_DEVICE),
                generateCarAudioDeviceInfo(NOTIFICATION_TEST_DEVICE),
                generateCarAudioDeviceInfo(VOICE_TEST_DEVICE),
                generateCarAudioDeviceInfo(RING_TEST_DEVICE),
                generateCarAudioDeviceInfo(ALARM_TEST_DEVICE),
                generateCarAudioDeviceInfo(SYSTEM_BUS_DEVICE),
                generateCarAudioDeviceInfo(SECONDARY_TEST_DEVICE_CONFIG_0),
                generateCarAudioDeviceInfo(SECONDARY_TEST_DEVICE_CONFIG_1_0),
                generateCarAudioDeviceInfo(SECONDARY_TEST_DEVICE_CONFIG_1_1),
                generateCarAudioDeviceInfo(TERTIARY_TEST_DEVICE_1),
                generateCarAudioDeviceInfo(QUATERNARY_TEST_DEVICE_1),
                generateCarAudioDeviceInfo(TERTIARY_TEST_DEVICE_2),
                generateCarAudioDeviceInfo(TEST_REAR_ROW_3_DEVICE),
                generateCarAudioDeviceInfo(OEM_TEST_DEVICE),
                generateCarAudioDeviceInfo(""),
                generateCarAudioDeviceInfo(""),
                generateCarAudioDeviceInfo(null),
                generateCarAudioDeviceInfo(null),
                mTestCarMirrorDevice
        );
    }

    @Test
    public void loadAudioZones_parsesAllZones() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        expectWithMessage("Zones parsed").that(zones.size()).isEqualTo(ZONE_NUM);
    }

    @Test
    public void loadAudioZones_versionTwoParsesAllZones() throws Exception {
        try (InputStream versionTwoStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V2)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionTwoStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Zones parsed for version-two configuration")
                    .that(zones.size()).isEqualTo(ZONE_NUM);
        }
    }

    @Test
    public void loadAudioZones_versionOneParsesAllZones() throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            assertThat(zones.size()).isEqualTo(ZONE_NUM);
        }
    }

    @Test
    public void loadAudioZones_parsesAudioZoneId() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        expectWithMessage("Primary zone id in zones parsed")
                .that(zones.contains(PRIMARY_AUDIO_ZONE)).isTrue();
        expectWithMessage("Secondary zone id in zones parsed")
                .that(zones.contains(SECONDARY_ZONE_ID)).isTrue();
    }

    @Test
    public void loadAudioZones_versionTwoParsesAudioZoneId() throws Exception {
        try (InputStream versionTwoStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V2)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionTwoStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone id in zones parsed in version-two configuration")
                    .that(zones.contains(PRIMARY_AUDIO_ZONE)).isTrue();
            expectWithMessage("Secondary zone id in zones parsed in version-two configuration")
                    .that(zones.contains(SECONDARY_ZONE_ID)).isTrue();
        }
    }

    @Test
    public void loadAudioZones_parsesOccupantZoneId() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);

        cazh.loadAudioZones();

        SparseIntArray audioZoneIdToOccupantZoneIdMapping =
                cazh.getCarAudioZoneIdToOccupantZoneIdMapping();
        expectWithMessage("Occupant zone id of primary zone")
                .that(audioZoneIdToOccupantZoneIdMapping.get(PRIMARY_AUDIO_ZONE))
                .isEqualTo(PRIMARY_OCCUPANT_ID);
        expectWithMessage("Occupant zone id of secondary zone")
                .that(audioZoneIdToOccupantZoneIdMapping.get(SECONDARY_ZONE_ID,
                        /* valueIfKeyNotFound= */ -1)).isEqualTo(-1);
    }

    @Test
    public void loadAudioZones_versionTwoParsesOccupantZoneId() throws Exception {
        try (InputStream versionTwoStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V2)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionTwoStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            cazh.loadAudioZones();

            SparseIntArray audioZoneIdToOccupantZoneIdMapping =
                    cazh.getCarAudioZoneIdToOccupantZoneIdMapping();
            expectWithMessage("Occupant zone id of primary zone in version-two configuration")
                    .that(audioZoneIdToOccupantZoneIdMapping.get(PRIMARY_AUDIO_ZONE))
                    .isEqualTo(PRIMARY_OCCUPANT_ID);
            expectWithMessage("Occupant zone id of secondary zone in version-two configuration")
                    .that(audioZoneIdToOccupantZoneIdMapping.get(SECONDARY_ZONE_ID,
                            /* valueIfKeyNotFound= */ -1)).isEqualTo(-1);
        }
    }

    @Test
    public void loadAudioZones_parsesZoneName() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones.get(0);
        expectWithMessage("Primary zone name")
                .that(primaryZone.getName()).isEqualTo(PRIMARY_ZONE_NAME);
    }

    @Test
    public void loadAudioZones_versionTwoParsesZoneName() throws Exception {
        try (InputStream versionTwoStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V2)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionTwoStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            CarAudioZone primaryZone = zones.get(0);
            expectWithMessage("Primary zone name in version-two configuration")
                    .that(primaryZone.getName()).isEqualTo(PRIMARY_ZONE_NAME);
        }
    }

    @Test
    public void loadAudioZones_parsesIsPrimary() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        expectWithMessage("Primary zone").that(zones.get(0).isPrimaryZone()).isTrue();
        expectWithMessage("Primary secondary zone").that(zones.get(SECONDARY_ZONE_ID)
                .isPrimaryZone()).isFalse();
    }

    @Test
    public void loadAudioZones_versionTwoParsesIsPrimary() throws Exception {
        try (InputStream versionTwoStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V2)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionTwoStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone in version-two configuration")
                    .that(zones.get(0).isPrimaryZone()).isTrue();
            expectWithMessage("Primary secondary zone in version-two configuration")
                    .that(zones.get(SECONDARY_ZONE_ID).isPrimaryZone()).isFalse();
        }
    }

    @Test
    public void loadAudioZones_parsesZoneConfigs() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        List<CarAudioZoneConfig> primaryZoneConfigs = zones.get(0).getAllCarAudioZoneConfigs();
        expectWithMessage("Primary zone configs").that(primaryZoneConfigs.size()).isEqualTo(1);
        expectWithMessage("Primary zone config name")
                .that(primaryZoneConfigs.get(0).getName()).isEqualTo(PRIMARY_ZONE_CONFIG_NAME);
        expectWithMessage("Primary zone default config")
                .that(primaryZoneConfigs.get(0).isDefault()).isTrue();
        List<CarAudioZoneConfig> secondaryZoneConfigs = zones.get(SECONDARY_ZONE_ID)
                .getAllCarAudioZoneConfigs();
        expectWithMessage("Secondary zone configs")
                .that(secondaryZoneConfigs.size()).isEqualTo(2);
        expectWithMessage("Secondary zone config names")
                .that(List.of(secondaryZoneConfigs.get(0).getName(),
                        secondaryZoneConfigs.get(1).getName()))
                .containsExactly(SECONDARY_ZONE_CONFIG_NAME_1, SECONDARY_ZONE_CONFIG_NAME_2);
    }

    @Test
    public void loadAudioZones_parsesDefaultZoneConfigs() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZoneConfig primaryZoneConfig = zones.get(0).getCurrentCarAudioZoneConfig();
        expectWithMessage("Default primary zone config name")
                .that(primaryZoneConfig.getName()).isEqualTo(PRIMARY_ZONE_CONFIG_NAME);
        CarAudioZoneConfig secondaryZoneConfig = zones.get(SECONDARY_ZONE_ID)
                .getCurrentCarAudioZoneConfig();
        expectWithMessage("Default secondary zone config name")
                .that(secondaryZoneConfig.getName()).isEqualTo(SECONDARY_ZONE_CONFIG_NAME_1);
    }

    @Test
    public void loadAudioZones_parsesVolumeGroups() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones.get(0);
        CarAudioZone secondaryZone = zones.get(SECONDARY_ZONE_ID);
        expectWithMessage("Volume group count for primary zone")
                .that(primaryZone.getCurrentVolumeGroupCount()).isEqualTo(4);
        expectWithMessage("Volume group count for secondary zone")
                .that(secondaryZone.getCurrentVolumeGroupCount()).isEqualTo(1);
    }

    @Test
    public void loadAudioZones_versionTwoParsesVolumeGroups() throws Exception {
        try (InputStream versionTwoStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V2)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionTwoStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            CarAudioZone primaryZone = zones.get(0);
            expectWithMessage("Volume group count for primary zone in version-two configuration")
                    .that(primaryZone.getCurrentVolumeGroupCount()).isEqualTo(4);
        }
    }

    @Test
    public void loadAudioZones_parsesAddresses() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones.get(0);
        CarVolumeGroup primaryVolumeGroup = primaryZone.getCurrentVolumeGroups()[0];
        List<String> primaryAddresses = primaryVolumeGroup.getAddresses();
        expectWithMessage("Primary zone addresses").that(primaryAddresses)
                .containsExactly(MEDIA_TEST_DEVICE, NOTIFICATION_TEST_DEVICE);
        CarAudioZone secondaryZone = zones.get(SECONDARY_ZONE_ID);
        CarVolumeGroup secondaryVolumeGroup = secondaryZone.getCurrentVolumeGroups()[0];
        List<String> secondaryAddresses = secondaryVolumeGroup.getAddresses();
        expectWithMessage("Secondary zone addresses")
                .that(secondaryAddresses).containsExactly(SECONDARY_TEST_DEVICE_CONFIG_0);
    }

    @Test
    public void loadAudioZones_versionTwoParsesAddresses() throws Exception {
        try (InputStream versionTwoStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V2)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionTwoStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            CarAudioZone primaryZone = zones.get(0);
            CarVolumeGroup volumeGroup = primaryZone.getCurrentVolumeGroups()[0];
            List<String> addresses = volumeGroup.getAddresses();
            expectWithMessage("Primary zone addresses")
                    .that(addresses).containsExactly(MEDIA_TEST_DEVICE, NOTIFICATION_TEST_DEVICE);
        }
    }

    @Test
    public void loadAudioZones_parsesContexts() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarVolumeGroup volumeGroup = zones.get(0).getCurrentVolumeGroups()[0];
        expectWithMessage("Contexts of primary zone address " + MEDIA_TEST_DEVICE)
                .that(volumeGroup.getContextsForAddress(MEDIA_TEST_DEVICE))
                .containsExactly(TEST_CAR_AUDIO_CONTEXT.getContextForAttributes(CarAudioContext
                        .getAudioAttributeFromUsage(AudioAttributes.USAGE_MEDIA)),
                        TEST_CAR_AUDIO_CONTEXT.getContextForAttributes(CarAudioContext
                                .getAudioAttributeFromUsage(AudioAttributes.USAGE_ANNOUNCEMENT)));
        CarVolumeGroup rseVolumeGroup = zones.get(SECONDARY_ZONE_ID).getCurrentVolumeGroups()[0];
        List<Integer> contextForBusList = rseVolumeGroup.getContextsForAddress(
                SECONDARY_TEST_DEVICE_CONFIG_0);
        expectWithMessage("Contexts of secondary zone address" + SECONDARY_TEST_DEVICE_CONFIG_0)
                .that(contextForBusList).containsExactlyElementsIn(TEST_CAR_AUDIO_CONTEXT
                .getAllContextsIds());
    }

    @Test
    public void getCarAudioContext_withOEMContexts() throws Exception {
        try (InputStream oemDefinedContextStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_oem_defined_context)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, oemDefinedContextStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);
            cazh.loadAudioZones();

            CarAudioContext contexts = cazh.getCarAudioContext();

            assertWithMessage("OEM defined contexts")
                    .that(contexts.getContextsInfo()).containsExactly(OEM_CONTEXT_INFO_MUSIC,
                            OEM_CONTEXT_INFO_NAVIGATION, OEM_CONTEXT_INFO_VOICE_COMMAND,
                            OEM_CONTEXT_INFO_CALL_RING, OEM_CONTEXT_INFO_CALL,
                            OEM_CONTEXT_INFO_ALARM, OEM_CONTEXT_INFO_NOTIFICATION,
                            OEM_CONTEXT_INFO_SYSTEM_SOUND, OEM_CONTEXT_INFO_EMERGENCY,
                            OEM_CONTEXT_INFO_SAFETY, OEM_CONTEXT_INFO_VEHICLE_STATUS,
                            OEM_CONTEXT_INFO_ANNOUNCEMENT);
        }
    }

    @Test
    public void loadAudioZones_versionTwoParsesContexts() throws Exception {
        try (InputStream versionTwoStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V2)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionTwoStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            CarVolumeGroup volumeGroup = zones.get(0).getCurrentVolumeGroups()[0];
            expectWithMessage("Contexts of primary zone address in version-two configuration"
                    + MEDIA_TEST_DEVICE).that(volumeGroup.getContextsForAddress(MEDIA_TEST_DEVICE))
                    .containsExactly(TEST_CAR_AUDIO_CONTEXT.getContextForAttributes(CarAudioContext
                            .getAudioAttributeFromUsage(AudioAttributes.USAGE_MEDIA)),
                            TEST_CAR_AUDIO_CONTEXT.getContextForAttributes(CarAudioContext
                                    .getAudioAttributeFromUsage(
                                            AudioAttributes.USAGE_ANNOUNCEMENT)));
            CarVolumeGroup rseVolumeGroup = zones.get(SECONDARY_ZONE_ID)
                    .getCurrentVolumeGroups()[0];
            List<Integer> contextForBus100List =
                    rseVolumeGroup.getContextsForAddress(SECONDARY_TEST_DEVICE_CONFIG_0);
            expectWithMessage("Contexts of secondary zone address in version-two configuration"
                    + SECONDARY_TEST_DEVICE_CONFIG_0).that(contextForBus100List)
                    .containsExactlyElementsIn(TEST_CAR_AUDIO_CONTEXT.getAllContextsIds());
        }
    }

    @Test
    public void loadAudioZones_forVersionOne_bindsNonLegacyContextsToDefault() throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1)) {

            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream,
                    mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                    mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

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

            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, v1NonLegacyContextStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    cazh::loadAudioZones);

            assertThat(exception).hasMessageThat().contains("Non-legacy audio contexts such as");
        }
    }

    @Test
    public void loadAudioZones_passesOnMissingAudioZoneIdForPrimary() throws Exception {
        try (InputStream missingAudioZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_missing_audio_zone_id_for_primary_zone)) {
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, missingAudioZoneIdStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            assertThat(zones.size()).isEqualTo(ZONE_NUM);
            assertThat(zones.contains(PRIMARY_AUDIO_ZONE)).isTrue();
            assertThat(zones.contains(SECONDARY_ZONE_ID)).isTrue();
        }
    }

    @Test
    public void loadAudioZones_versionOneFailsOnAudioZoneId() throws Exception {
        try (InputStream versionOneAudioZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V1_with_audio_zone_id)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneAudioZoneIdStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);
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
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, versionOneOccupantIdStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("Invalid audio attribute occupantZoneId");
        }
    }

    @Test
    public void loadAudioZones_primaryZoneHasInputDevices() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones.get(PRIMARY_AUDIO_ZONE);
        expectWithMessage("Input devices for primary zone")
                .that(primaryZone.getInputAudioDevices()).hasSize(2);
    }

    @Test
    public void loadAudioZones_versionTwoPrimaryZoneHasInputDevices() throws Exception {
        try (InputStream versionTwoStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V2)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionTwoStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            CarAudioZone primaryZone = zones.get(PRIMARY_AUDIO_ZONE);
            expectWithMessage("Input devices for primary zone in version-two configuration")
                    .that(primaryZone.getInputAudioDevices()).hasSize(2);
        }
    }

    @Test
    public void loadAudioZones_primaryZoneHasMicrophoneDevice() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);

        SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

        CarAudioZone primaryZone = zones.get(PRIMARY_AUDIO_ZONE);
        for (AudioDeviceAttributes info : primaryZone.getInputAudioDevices()) {
            assertThat(info.getType()).isEqualTo(TYPE_BUILTIN_MIC);
        }
    }

    @Test
    public void loadAudioZones_versionTwoPrimaryZoneHasMicrophoneDevice() throws Exception {
        try (InputStream versionTwoStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V2)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionTwoStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            CarAudioZone primaryZone = zones.get(PRIMARY_AUDIO_ZONE);
            for (AudioDeviceAttributes info : primaryZone.getInputAudioDevices()) {
                expectWithMessage("Type of primary zone device attribute " + info)
                        .that(info.getType()).isEqualTo(TYPE_BUILTIN_MIC);
            }
        }
    }

    @Test
    public void loadAudioZones_parsesInputDevices() throws Exception {
        try (InputStream inputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_input_devices)) {
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, inputDevicesStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            CarAudioZone primaryZone = zones.get(0);
            List<AudioDeviceAttributes> primaryZoneInputDevices =
                    primaryZone.getInputAudioDevices();
            assertThat(primaryZoneInputDevices).hasSize(2);

            List<String> primaryZoneInputAddresses =
                    primaryZoneInputDevices.stream().map(a -> a.getAddress()).collect(
                            Collectors.toList());
            assertThat(primaryZoneInputAddresses).containsExactly(PRIMARY_ZONE_FM_TUNER_DEVICE,
                    PRIMARY_ZONE_MICROPHONE_DEVICE).inOrder();

            CarAudioZone secondaryZone = zones.get(SECONDARY_ZONE_ID);
            List<AudioDeviceAttributes> secondaryZoneInputDevices =
                    secondaryZone.getInputAudioDevices();
            List<String> secondaryZoneInputAddresses =
                    secondaryZoneInputDevices.stream().map(a -> a.getAddress()).collect(
                            Collectors.toList());
            assertThat(secondaryZoneInputAddresses).containsExactly(
                    SECONDARY_ZONE_BUS_1000_INPUT_DEVICE,
                    SECONDARY_ZONE_BACK_MICROPHONE_DEVICE).inOrder();
        }
    }

    @Test
    public void loadAudioZones_failsOnDuplicateOccupantZoneId() throws Exception {
        try (InputStream duplicateOccupantZoneIdStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_duplicate_occupant_zone_id)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, duplicateOccupantZoneIdStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);
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
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, duplicateAudioZoneIdStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("already associated with a zone");
        }
    }

    @Test
    public void loadAudioZones_failsOnDuplicateZoneConfigName() throws Exception {
        try (InputStream duplicateZoneConfigNameStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_duplicate_zone_config_name)) {
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper, mCarAudioSettings,
                            duplicateZoneConfigNameStream, mCarAudioOutputDeviceInfos,
                            mInputAudioDeviceInfos, mServiceEventLogger,
                            /* useCarVolumeGroupMute= */ false, /* useCoreAudioVolume= */ false,
                            /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> cazh.loadAudioZones());

            expectWithMessage("Exception for duplicate zone config name").that(thrown)
                    .hasMessageThat().contains("can not repeat.");
        }
    }

    @Test
    public void loadAudioZones_failsOnEmptyZoneConfigName() throws Exception {
        try (InputStream emptyZoneConfigNameStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_empty_zone_config_name)) {
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper, mCarAudioSettings,
                            emptyZoneConfigNameStream, mCarAudioOutputDeviceInfos,
                            mInputAudioDeviceInfos, mServiceEventLogger,
                            /* useCarVolumeGroupMute= */ false, /* useCoreAudioVolume= */ false,
                            /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> cazh.loadAudioZones());

            expectWithMessage("Exception for empty zone config name").that(thrown)
                    .hasMessageThat().contains("empty.");
        }
    }

    @Test
    public void loadAudioZones_failsOnMissingZoneConfigName() throws Exception {
        try (InputStream missingZoneConfigNameStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_missing_zone_config_name)) {
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper, mCarAudioSettings,
                            missingZoneConfigNameStream, mCarAudioOutputDeviceInfos,
                            mInputAudioDeviceInfos, mServiceEventLogger,
                            /* useCarVolumeGroupMute= */ false, /* useCoreAudioVolume= */ false,
                            /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);

            NullPointerException thrown =
                    assertThrows(NullPointerException.class, () -> cazh.loadAudioZones());

            expectWithMessage("Exception for missing zone config name").that(thrown)
                    .hasMessageThat().contains("must be present.");
        }
    }

    @Test
    public void loadAudioZones_failsOnPrimaryZoneWithMultipleConfigs() throws Exception {
        try (InputStream missingZoneConfigNameStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_primary_zone_with_multiple_configs)) {
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper, mCarAudioSettings,
                            missingZoneConfigNameStream, mCarAudioOutputDeviceInfos,
                            mInputAudioDeviceInfos, mServiceEventLogger,
                            /* useCarVolumeGroupMute= */ false, /* useCoreAudioVolume= */ false,
                            /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> cazh.loadAudioZones());

            expectWithMessage("Exception for multiple configurations in primary zone").that(thrown)
                    .hasMessageThat().contains(
                            "Primary zone cannot have multiple zone configurations");
        }
    }

    @Test
    public void loadAudioZones_failsOnEmptyInputDeviceAddress() throws Exception {
        try (InputStream inputDevicesStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_empty_input_device)) {
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, inputDevicesStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);

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
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, nonNumericalStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);
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
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper, mCarAudioSettings,
                            negativeAudioZoneIdStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);
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
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, inputDevicesStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);

            NullPointerException thrown =
                    assertThrows(NullPointerException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains("must be present.");
        }
    }

    @Test
    public void loadAudioZones_failsOnNonNumericalOccupantZoneId() throws Exception {
        try (InputStream nonNumericalStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_non_numerical_occupant_zone_id)) {
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, nonNumericalStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);
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
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings,
                    negativeOccupantZoneIdStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);
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
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, inputDevicesStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);

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
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, emptyOccupantZoneIdStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);
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
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper, mCarAudioSettings,
                            nonZeroForPrimaryStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);
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
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings,
                    zeroZoneIdForSecondaryStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);
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
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, inputDevicesStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);

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
            CarAudioZonesHelperImpl cazh =
                    new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                            mCarAudioSettings, outputDevicesStream,
                            mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                            mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                            /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                            /* useFadeManagerConfiguration= */ false,
                            /* carAudioFadeConfigurationHelper= */ null);

            IllegalStateException thrown =
                    assertThrows(IllegalStateException.class,
                            () -> cazh.loadAudioZones());
            assertThat(thrown).hasMessageThat().contains(ADDRESS_DOES_NOT_EXIST_DEVICE);
        }
    }

    @Test
    public void loadAudioZones_usingCoreAudioAndVersionThree_parsesAllZones() throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_routing_and_volume)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ true,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            assertThat(zones.size()).isEqualTo(1);
        }
    }

    @Test
    public void loadAudioZones_usingCoreAudioAndVersionThree_failsOnFirstInvalidAttributes()
            throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_routing_and_volume_invalid_strategy)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream,  mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ true,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);
            AudioAttributes unsupportedAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> cazh.loadAudioZones());

            assertWithMessage("First unsupported attributes exception").that(thrown)
                    .hasMessageThat().contains("Invalid attributes " + unsupportedAttributes
                            + " for context: OEM_CONTEXT");
        }
    }

    @Test
    public void loadAudioZones_usingCoreAudioAndVersionThree_failsOnInvalidAttributes()
            throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_routing_and_volume_invalid_strategy_2)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ true,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);
            AudioAttributes unsupportedAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> cazh.loadAudioZones());

            assertWithMessage("Validation of all supported attributes exception").that(thrown)
                    .hasMessageThat().contains("Invalid attributes "
                            + unsupportedAttributes.toString() + " for context: NAV_CONTEXT");
        }
    }

    @Test
    public void loadAudioZones_usingCoreAudioAndVersionThree_failsOnInvalidContextName()
            throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_routing_and_volume_invalid_context_name)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ true,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> cazh.loadAudioZones());

            expectWithMessage("Invalid context name exception").that(thrown)
                    .hasMessageThat().contains("Cannot find strategy id for context");
        }
    }

    @Test
    @DisableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_usingCoreAudioVersionThree_failsOnEmptyGroupName()
            throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_routing_and_volume_empty_group_name)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ true,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            RuntimeException thrown =
                    assertThrows(RuntimeException.class, () -> cazh.loadAudioZones());

            assertWithMessage("Empty group name exception").that(thrown).hasMessageThat().contains(
                    "group name attribute can not be empty when relying on core volume groups");
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_usingCoreVolumeAndWithoutVolumeGroupNames()
            throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_volume_config_and_missing_group_names)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Parsed zones with wrong use volume group config")
                    .that(zones.size()).isEqualTo(1);
            expectWithMessage("Use core volume config").that(cazh.useCoreAudioVolume()).isFalse();
        }
    }

    @Test
    public void loadAudioZones_usingCoreAudioVersionThree_failsOnInvalidOrder()
            throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_routing_and_volume_invalid_order)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ true,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            RuntimeException thrown =
                    assertThrows(IllegalArgumentException.class, () -> cazh.loadAudioZones());

            expectWithMessage("After zone OEM audio contexts definition exception")
                    .that(thrown).hasMessageThat().matches("Car audio context .* is invalid");
        }
    }

    @Test
    public void loadAudioZones_usingCoreAudioVersionThree_succeedsOnAttributesWithOptionalFields()
            throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_routing_attr_valid_optional_fields)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ true,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Succeeded to parse zones").that(zones.size()).isEqualTo(1);
        }
    }

    @Test
    public void loadAudioZones_usingCoreAudioVersionThree_failsOnEmptyAttributes()
            throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_routing_attr_invalid_empty_fields)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ true,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            RuntimeException thrown =
                    assertThrows(RuntimeException.class, () -> cazh.loadAudioZones());

            expectWithMessage("Music context has empty attributes").that(thrown)
                    .hasMessageThat().contains("Empty attributes for context: MUSIC_CONTEXT");
        }
    }

    @Test
    public void getMirrorDeviceInfos() throws Exception {
        CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                mCarAudioSettings, mInputStream, mCarAudioOutputDeviceInfos, mInputAudioDeviceInfos,
                mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                /* useFadeManagerConfiguration= */ false,
                /* carAudioFadeConfigurationHelper= */ null);
        cazh.loadAudioZones();

        expectWithMessage("Mirror devices").that(cazh.getMirrorDeviceInfos())
                .containsExactly(mTestCarMirrorDevice);
    }

    @Test
    public void getMirrorDeviceInfos_withOutMirroringDevices() throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_without_mirroring)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);
            cazh.loadAudioZones();

            expectWithMessage("Mirror devices for empty configuration")
                    .that(cazh.getMirrorDeviceInfos()).isEmpty();
        }
    }

    @Test
    public void loadAudioZones_failsOnMirroringDevicesInV2() throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_mirroring_V2)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> cazh.loadAudioZones());

            expectWithMessage("Mirror devices in v2 configuration exception")
                    .that(thrown).hasMessageThat().contains("mirroringDevices");
        }
    }

    @Test
    public void loadAudioZones_failsOnDuplicateMirroringDevices() throws Exception {
        try (InputStream versionOneStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_duplicate_mirror_devices)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionOneStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> cazh.loadAudioZones());

            expectWithMessage("Duplicates mirror devices in configuration exception")
                    .that(thrown).hasMessageThat().contains("can not repeat");
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES})
    public void loadAudioZones_withPrimaryZoneAndDynamicAudioDevicesAndCoreVolumeEnabled()
            throws Exception {
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_dynamic_devices_for_primary_zone)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with dynamic device configurations")
                    .that(zones.size()).isAtLeast(1);
            CarAudioZone zone = zones.get(0);
            List<CarAudioZoneConfig> configs = zone.getAllCarAudioZoneConfigs();
            expectWithMessage("Configurations for primary zone with dynamic devices")
                    .that(configs).hasSize(3);
            CarAudioZoneConfig configBTMedia = configs.get(1);
            CarVolumeGroup mediaBTVolumeGroup = configBTMedia.getVolumeGroup("MUSIC_GROUP");
            expectWithMessage("Media BT dynamic device").that(
                    mediaBTVolumeGroup.getAudioDeviceForContext(OEM_CONTEXT_INFO_MUSIC.getId())
                            .getType()).isEqualTo(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
            CarAudioZoneConfig configHeadphoneMedia = configs.get(2);
            CarVolumeGroup mediaHeadphoneVolumeGroup = configHeadphoneMedia
                    .getVolumeGroup("MUSIC_GROUP");
            expectWithMessage("Media BT dynamic device").that(
                    mediaHeadphoneVolumeGroup.getAudioDeviceForContext(OEM_CONTEXT_INFO_MUSIC
                                    .getId()).getType())
                    .isEqualTo(AudioDeviceInfo.TYPE_WIRED_HEADPHONES);
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES})
    public void loadAudioZones_withPrimaryZoneAndDynamicAudioDevicesAndCoreVolumeDisabled()
            throws Exception {
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_dynamic_devices_for_primary_zone)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone without dynamic device configurations")
                    .that(zones.size()).isAtLeast(1);
            CarAudioZone zone = zones.get(0);
            List<CarAudioZoneConfig> configs = zone.getAllCarAudioZoneConfigs();
            expectWithMessage("Configurations for primary zone without dynamic devices")
                    .that(configs).hasSize(1);
            CarAudioZoneConfigInfo defaultConfig = configs.get(0).getCarAudioZoneConfigInfo();
            expectWithMessage("Default config after failing to load dynamic configurations")
                    .that(CarAudioUtils.getDynamicDevicesInConfig(defaultConfig,
                            mAudioManagerWrapper)).isEmpty();
        }
    }

    @Test
    public void loadAudioZones_withDynamicAudioDevices_forVersionThree_fails() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_dynamic_devices_for_primary_zone_in_v3)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Dynamic devices support in v3 exception").that(thrown)
                    .hasMessageThat().contains("Audio device type");
        }
    }

    @Test
    public void loadAudioZones_withPrimaryZoneAndDynamicAudioDevicesAndNoDynamicSupport()
            throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_dynamic_devices_for_primary_zone)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with dynamic device configurations"
                    + " and dynamic flag disabled").that(zones.size()).isAtLeast(1);
            CarAudioZone zone = zones.get(0);
            List<CarAudioZoneConfig> configs = zone.getAllCarAudioZoneConfigs();
            expectWithMessage("Configurations for primary zone with dynamic devices"
                    + " and dynamic flag disabled").that(configs).hasSize(1);
            CarAudioZoneConfig defaultConfig = configs.get(0);
            expectWithMessage("Default configuration for dynamic configuration with dynamic"
                    + " devices disabled").that(defaultConfig.isDefault()).isTrue();
        }
    }

    @Test
    public void loadAudioZones_withMinMaxActivationVolumeAndNoActivationVolumeSupport()
            throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_CAR_AUDIO_MIN_MAX_ACTIVATION_VOLUME);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_min_max_activation_volume)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            CarAudioZoneConfig zoneConfig = zones.get(0).getCurrentCarAudioZoneConfig();
            CarVolumeGroup[] volumeGroups = zoneConfig.getVolumeGroups();
            expectWithMessage(
                    "Primary zone volume group 0 min activation volume with disabled flag")
                    .that(volumeGroups[0].getMinActivationGainIndex())
                    .isEqualTo(volumeGroups[0].getMinGainIndex());
            expectWithMessage(
                    "Primary zone volume group 0 max activation volume with disabled flag")
                    .that(volumeGroups[0].getMaxActivationGainIndex())
                    .isEqualTo(volumeGroups[0].getMaxGainIndex());
            expectWithMessage("Primary zone volume group min activation volume with disabled flag"
                            + " and without activation config")
                    .that(volumeGroups[PRIMARY_ZONE_GROUP_ID_WITHOUT_ACTIVATION_VOLUME]
                            .getMinActivationGainIndex())
                    .isEqualTo(volumeGroups[PRIMARY_ZONE_GROUP_ID_WITHOUT_ACTIVATION_VOLUME]
                            .getMinGainIndex());
            expectWithMessage("Primary zone volume group max activation volume with disabled flag"
                    + " and without activation config")
                    .that(volumeGroups[PRIMARY_ZONE_GROUP_ID_WITHOUT_ACTIVATION_VOLUME]
                            .getMaxActivationGainIndex())
                    .isEqualTo(volumeGroups[PRIMARY_ZONE_GROUP_ID_WITHOUT_ACTIVATION_VOLUME]
                            .getMaxGainIndex());
        }
    }

    @Test
    public void loadAudioZones_withMinMaxActivationVolume() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_MIN_MAX_ACTIVATION_VOLUME);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_min_max_activation_volume)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            CarAudioZoneConfig zoneConfig = zones.get(0).getCurrentCarAudioZoneConfig();
            CarVolumeGroup[] volumeGroups = zoneConfig.getVolumeGroups();
            expectWithMessage("Primary zone volume group 0 min activation volume")
                    .that(volumeGroups[0].getMinActivationGainIndex())
                    .isGreaterThan(volumeGroups[0].getMinGainIndex());
            expectWithMessage("Primary zone volume group 0 max activation volume")
                    .that(volumeGroups[0].getMaxActivationGainIndex())
                    .isLessThan(volumeGroups[0].getMaxGainIndex());
            expectWithMessage("Primary zone volume group 0 activation volume invocation types")
                    .that(volumeGroups[0].getActivationVolumeInvocationType())
                    .isEqualTo(PRIMARY_ZONE_GROUP_0_ACTIVATION_VOLUME_TYPE);
            expectWithMessage("Primary zone volume group min activation volume with activation"
                    + " config").that(volumeGroups[PRIMARY_ZONE_GROUP_ID_WITHOUT_ACTIVATION_VOLUME]
                            .getMinActivationGainIndex())
                    .isEqualTo(volumeGroups[PRIMARY_ZONE_GROUP_ID_WITHOUT_ACTIVATION_VOLUME]
                            .getMinGainIndex());
            expectWithMessage("Primary zone volume group max activation volume with activation"
                    + " config").that(volumeGroups[PRIMARY_ZONE_GROUP_ID_WITHOUT_ACTIVATION_VOLUME]
                            .getMaxActivationGainIndex())
                    .isEqualTo(volumeGroups[PRIMARY_ZONE_GROUP_ID_WITHOUT_ACTIVATION_VOLUME]
                            .getMaxGainIndex());
            expectWithMessage("Primary zone volume group activation volume invocation types with"
                    + " activation config")
                    .that(volumeGroups[PRIMARY_ZONE_GROUP_ID_WITHOUT_ACTIVATION_VOLUME]
                            .getActivationVolumeInvocationType())
                    .isEqualTo(PRIMARY_ZONE_DEFAULT_ACTIVATION_VOLUME_TYPE);
        }
    }

    @Test
    public void loadAudioZones_withMinMaxActivationVolume_forVersionThree_fails()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_MIN_MAX_ACTIVATION_VOLUME);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_min_max_activation_volume_in_v3)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Min/max activation volume support in v3 exception")
                    .that(thrown).hasMessageThat().contains("not supported for versions less than");
        }
    }

    @Test
    public void loadAudioZones_withMinMaxActivationVolumeOutOfRange_fails() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_MIN_MAX_ACTIVATION_VOLUME);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_min_max_activation_volume_out_of_range)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Min/max activation volume out of range exception")
                    .that(thrown).hasMessageThat().contains("can not be outside the range");
        }
    }

    @Test
    public void loadAudioZones_withMinGreaterThanMaxActivationVolume_fails() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_MIN_MAX_ACTIVATION_VOLUME);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_min_greater_than_max_activation_volume)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Min greater than max activation volume exception")
                    .that(thrown).hasMessageThat().contains("can not be larger than or equal to");
        }
    }

    @Test
    public void loadAudioZones_withInvalidMinMaxActivationVolumeActivationType_fails()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_MIN_MAX_ACTIVATION_VOLUME);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_invalid_activation_volume_type)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Invalid activation volume invocation type exception")
                    .that(thrown).hasMessageThat()
                    .contains("is invalid for group invocationType");
        }
    }

    @Test
    public void loadAudioZones_withMultipleActivationVolumeConfigEntriesInOneConfig_fails()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_MIN_MAX_ACTIVATION_VOLUME);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_activation_volume_multiple_entries)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Multiple activation volume entries in one config exception")
                    .that(thrown).hasMessageThat().contains("is not supported");
        }
    }

    @Test
    public void loadAudioZones_withRepeatedActivationVolumeConfigName_fails()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_MIN_MAX_ACTIVATION_VOLUME);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_activation_volume_repeated_config_name)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Repeated activation volume config name exception")
                    .that(thrown).hasMessageThat().contains("can not repeat");
        }
    }

    @Test
    public void loadAudioZones_withoutActivationVolumeConfigName_fails()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_MIN_MAX_ACTIVATION_VOLUME);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_missing_activation_volume_config_name)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            NullPointerException thrown = assertThrows(NullPointerException.class,
                    cazh::loadAudioZones);

            expectWithMessage("No activation volume config name exception")
                    .that(thrown).hasMessageThat().contains("must be present");
        }
    }

    @Test
    public void loadAudioZones_withInvalidActivationVolumeConfigName_fails()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_MIN_MAX_ACTIVATION_VOLUME);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_invalid_activation_volume_config_name)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Invalid activation volume config name exception")
                    .that(thrown).hasMessageThat().contains("does not exist");
        }
    }

    @Test
    public void loadAudioZones_applyFadeConfigs_forVersionThree_fails() throws Exception {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION);
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_FADE_MANAGER_CONFIGURATION);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_apply_fade_configs_in_v3)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ true,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, cazh::loadAudioZones);

            expectWithMessage("Fade configs in v3 exception").that(thrown)
                    .hasMessageThat().contains("Fade configurations not");
        }
    }

    @Test
    public void loadAudioZones_applyFadeConfigs_nullCarAudioFadeConfigurationHelper_fails()
            throws Exception {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION);
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_FADE_MANAGER_CONFIGURATION);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_apply_fade_configs)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ true,
                    /* carAudioFadeConfigurationHelper= */ null);

            NullPointerException thrown =
                    assertThrows(NullPointerException.class, () -> cazh.loadAudioZones());

            expectWithMessage("Fade configs with null car audio fade config helper exception")
                    .that(thrown).hasMessageThat()
                    .contains("Car audio fade configuration helper can not be");
        }
    }

    @Test
    public void loadAudioZones_applyFadeConfigs_withEmptyAddress_fails() throws Exception {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION);
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_FADE_MANAGER_CONFIGURATION);
        CarAudioFadeConfigurationHelper fadeConfiguration = getCarAudioFadeConfigurationHelper(
                R.raw.car_audio_fade_configuration);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_apply_fade_configs_with_empty_address)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ true, fadeConfiguration);

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, cazh::loadAudioZones);

            expectWithMessage("Fade configs with empty config name exception").that(thrown)
                    .hasMessageThat().contains("Fade config name");
        }
    }

    @Test
    public void loadAudioZones_applyFadeConfigs_withNeitherDefaultNorAudioAttributes_fails()
            throws Exception {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION);
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_FADE_MANAGER_CONFIGURATION);
        CarAudioFadeConfigurationHelper fadeConfiguration = getCarAudioFadeConfigurationHelper(
                R.raw.car_audio_fade_configuration);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(R.raw
                .car_audio_configuration_apply_fade_configs_with_neither_default_nor_transient)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ true, fadeConfiguration);

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, cazh::loadAudioZones);

            expectWithMessage("Fade configs with neither default or audio attributes").that(thrown)
                    .hasMessageThat().contains("Transient fade configs must have valid");
        }
    }

    @Test
    public void loadAudioZones_applyFadeConfigs_withUnavailableConfig_fails()
            throws Exception {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION);
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_FADE_MANAGER_CONFIGURATION);
        CarAudioFadeConfigurationHelper fadeConfiguration = getCarAudioFadeConfigurationHelper(
                R.raw.car_audio_fade_configuration);
        try (InputStream versionFourStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_apply_fade_configs_with_unavailable_config)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, versionFourStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ true, fadeConfiguration);

            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, cazh::loadAudioZones);

            expectWithMessage("Fade configs with unavailable config exception").that(thrown)
                    .hasMessageThat().contains("No config available");
        }
    }

    @Test
    public void loadAudioZones_withUnsupportedVersion_fails() throws Exception {
        try (InputStream v1NonLegacyContextStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_invalid_version)) {

            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, v1NonLegacyContextStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Unsupported version exception").that(exception)
                    .hasMessageThat().contains("Latest Supported version");
        }
    }

    @Test
    public void loadAudioZones_withoutZones_fails() throws Exception {
        try (InputStream v1NonLegacyContextStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_missing_zones)) {

            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, v1NonLegacyContextStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            MissingResourceException exception = assertThrows(MissingResourceException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Missing audio zones exception")
                    .that(exception).hasMessageThat().contains("is missing from configuration");
        }
    }

    @Test
    public void loadAudioZones_withoutPrimaryZone_fails() throws Exception {
        try (InputStream v1NonLegacyContextStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_missing_primary_zone)) {

            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, v1NonLegacyContextStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            RuntimeException exception = assertThrows(RuntimeException.class, cazh::loadAudioZones);

            expectWithMessage("Missing primary zone exception")
                    .that(exception).hasMessageThat().contains("Primary audio zone is required");
        }
    }

    @Test
    public void loadAudioZones_withoutOutputDeviceAddressInVersion3_fails() throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);
        try (InputStream v1NonLegacyContextStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V3_missing_output_address)) {

            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, v1NonLegacyContextStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Missing output device address exception in version 3")
                    .that(exception).hasMessageThat()
                    .contains("Output device address must be specified");
        }
    }

    @Test
    public void loadAudioZones_withoutOutputDeviceAddressInVersion4AndWithDynamicSupport_fails()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);
        try (InputStream v1NonLegacyContextStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_V4_missing_output_address)) {

            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, v1NonLegacyContextStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Missing output device address exception in version 4 with dynamic"
                    + " support").that(exception).hasMessageThat()
                    .contains("does not belong to any configured output device.");
        }
    }

    @Test
    public void loadAudioZones_withInvalidInputDeviceTypeAndWithDynamicSupport_throws()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);
        try (InputStream v1NonLegacyContextStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_invalid_device_type)) {

            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, v1NonLegacyContextStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    cazh::loadAudioZones);

            expectWithMessage("Invalid input device type exception with dynamic support")
                    .that(exception).hasMessageThat().contains("Output device type");
        }
    }

    @Test
    public void loadAudioZones_withInvalidInputDeviceTypeAndWithoutDynamicSupport()
            throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_CAR_AUDIO_DYNAMIC_DEVICES);
        try (InputStream v1NonLegacyContextStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_invalid_device_type)) {

            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, v1NonLegacyContextStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with invalid input device type"
                    + " and dynamic flag disabled").that(zones.size()).isEqualTo(1);
            CarAudioZone zone = zones.get(0);
            List<CarAudioZoneConfig> configs = zone.getAllCarAudioZoneConfigs();
            expectWithMessage("Configurations for primary zone with invalid input device type"
                    + " and dynamic flag disabled").that(configs).hasSize(1);
            CarAudioZoneConfig defaultConfig = configs.get(0);
            expectWithMessage("Default configuration for zone with invalid input device type "
                    + " and dynamic devices disabled").that(defaultConfig.isDefault()).isTrue();
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_withInvalidDeviceConfig()
            throws Exception {
        boolean useCoreVolume = true;
        try (InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_invalid_device_config)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, inputStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    useCoreVolume, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with invalid device config")
                    .that(zones.size()).isEqualTo(1);
            expectWithMessage("Use core volume config with invalid device config")
                    .that(cazh.useCoreAudioVolume()).isEqualTo(useCoreVolume);
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_withUseCoreVolumeDeviceConfig()
            throws Exception {
        boolean useCoreVolume = false;
        try (InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_use_core_volume)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, inputStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    useCoreVolume, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with invalid device config")
                    .that(zones.size()).isEqualTo(1);
            expectWithMessage("Use core volume config with use core volume device config")
                    .that(cazh.useCoreAudioVolume()).isTrue();
        }
    }

    @Test
    @DisableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_withUseCoreVolumeDeviceConfigAndVendorFreezeFlagDisabled()
            throws Exception {
        boolean useCoreVolume = false;
        try (InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_use_core_volume)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, inputStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    useCoreVolume, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with invalid device config")
                    .that(zones.size()).isEqualTo(1);
            expectWithMessage("Use core volume config with invalid use core volume device config")
                    .that(cazh.useCoreAudioVolume()).isEqualTo(useCoreVolume);
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_withEmptyUseCoreVolumeDeviceConfig()
            throws Exception {
        boolean useCoreVolume = true;
        try (InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_empty_use_core_volume_config)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, inputStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    useCoreVolume, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with empty use core volume config")
                    .that(zones.size()).isEqualTo(1);
            expectWithMessage("Use core volume config with empty use core volume device config")
                    .that(cazh.useCoreAudioVolume()).isEqualTo(useCoreVolume);
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_withMultipleDefinitionsOfUseCoreVolumeDeviceConfig()
            throws Exception {
        try (InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_multiple_use_core_volume_config)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, inputStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioRouting= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with multiple use core volume config")
                    .that(zones.size()).isEqualTo(1);
            expectWithMessage("Use core volume config with multiple use core volume device config")
                    .that(cazh.useCoreAudioVolume()).isTrue();
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_withUseCoreRoutingDeviceConfig()
            throws Exception {
        boolean useCoreRouting = false;
        try (InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_using_core_routing)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, inputStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, useCoreRouting,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with use core routing enabled")
                    .that(zones.size()).isEqualTo(1);
            expectWithMessage("Use core volume config with use core routing enabled")
                    .that(cazh.useCoreAudioRouting()).isTrue();
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_withInvalidUseCoreRoutingDeviceConfig()
            throws Exception {
        boolean useCoreRouting = false;
        try (InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_invalid_use_core_routing)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, inputStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioRouting= */ true, useCoreRouting,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with invalid use core routing enabled")
                    .that(zones.size()).isEqualTo(1);
            expectWithMessage("Use core volume config with invalid use core routing enabled")
                    .that(cazh.useCoreAudioRouting()).isEqualTo(useCoreRouting);
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_withUseCarVolumeGroupMutingConfig()
            throws Exception {
        try (InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_valid_use_group_muting_config)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, inputStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with valid use group muting config")
                    .that(zones.size()).isEqualTo(1);
            expectWithMessage("Use group muting config with valid use group muting config")
                    .that(cazh.useVolumeGroupMuting()).isTrue();
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_withInvalidUseCarVolumeGroupMutingConfig()
            throws Exception {
        try (InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_invalid_use_group_muting_config)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, inputStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ true, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with invalid use group muting config")
                    .that(zones.size()).isEqualTo(1);
            expectWithMessage("Use group muting config with invalid use group muting config")
                    .that(cazh.useVolumeGroupMuting()).isFalse();
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_withUseHalDuckingSignalsConfig()
            throws Exception {
        boolean defaultUseHalDuckingSignal = false;
        try (InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_valid_use_hal_ducking_config)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, inputStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with valid use HAL ducking config")
                    .that(zones.size()).isEqualTo(1);
            expectWithMessage("Use HAL ducking config with valid use HAL ducking config")
                    .that(cazh.useHalDuckingSignalOrDefault(defaultUseHalDuckingSignal)).isTrue();
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_AUDIO_VENDOR_FREEZE_IMPROVEMENTS})
    public void loadAudioZones_withInvalidUseHalDuckingSignalsConfig()
            throws Exception {
        boolean defaultUseHalDuckingSignal = false;
        try (InputStream inputStream = mContext.getResources().openRawResource(
                R.raw.car_audio_configuration_with_invalid_use_hal_ducking_config)) {
            CarAudioZonesHelperImpl cazh = new CarAudioZonesHelperImpl(mAudioManagerWrapper,
                    mCarAudioSettings, inputStream, mCarAudioOutputDeviceInfos,
                    mInputAudioDeviceInfos, mServiceEventLogger, /* useCarVolumeGroupMute= */ false,
                    /* useCoreAudioVolume= */ false, /* useCoreAudioRouting= */ false,
                    /* useFadeManagerConfiguration= */ false,
                    /* carAudioFadeConfigurationHelper= */ null);

            SparseArray<CarAudioZone> zones = cazh.loadAudioZones();

            expectWithMessage("Primary zone with invalid use HAL ducking config")
                    .that(zones.size()).isEqualTo(1);
            expectWithMessage("Use HAL ducking config with invalid use HAL ducking config")
                    .that(cazh.useHalDuckingSignalOrDefault(defaultUseHalDuckingSignal)).isFalse();
        }
    }

    private CarAudioFadeConfigurationHelper getCarAudioFadeConfigurationHelper(int resource) {
        try (InputStream inputStream = mContext.getResources().openRawResource(resource)) {
            return new CarAudioFadeConfigurationHelper(inputStream);
        } catch (IOException | XmlPullParserException e) {
            throw new RuntimeException("Failed to parse audio fade configuration", e);
        }
    }

    private void setupAudioManagerMock() {
        doReturn(CoreAudioRoutingUtils.getProductStrategies())
                .when(AudioManagerWrapper::getAudioProductStrategies);
        doReturn(CoreAudioRoutingUtils.getVolumeGroups())
                .when(AudioManagerWrapper::getAudioVolumeGroups);

        doReturn(CoreAudioRoutingUtils.MUSIC_GROUP_ID)
                .when(() -> CoreAudioHelper.getVolumeGroupIdForAudioAttributes(
                        CoreAudioRoutingUtils.MUSIC_ATTRIBUTES));
        doReturn(CoreAudioRoutingUtils.MUSIC_ATTRIBUTES)
                .when(() -> CoreAudioHelper.selectAttributesForVolumeGroupName(
                        CoreAudioRoutingUtils.MUSIC_GROUP_NAME));

        doReturn(CoreAudioRoutingUtils.NAV_GROUP_ID)
                .when(() -> CoreAudioHelper.getVolumeGroupIdForAudioAttributes(
                        CoreAudioRoutingUtils.NAV_ATTRIBUTES));
        doReturn(CoreAudioRoutingUtils.NAV_ATTRIBUTES)
                .when(() -> CoreAudioHelper.selectAttributesForVolumeGroupName(
                        CoreAudioRoutingUtils.NAV_GROUP_NAME));

        doReturn(CoreAudioRoutingUtils.OEM_GROUP_ID)
                .when(() -> CoreAudioHelper.getVolumeGroupIdForAudioAttributes(
                        CoreAudioRoutingUtils.OEM_ATTRIBUTES));
        doReturn(CoreAudioRoutingUtils.OEM_ATTRIBUTES)
                .when(() -> CoreAudioHelper.selectAttributesForVolumeGroupName(
                        CoreAudioRoutingUtils.OEM_GROUP_NAME));
    }
}
