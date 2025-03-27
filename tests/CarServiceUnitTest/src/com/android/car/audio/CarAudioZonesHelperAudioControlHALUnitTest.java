/*
 * Copyright (C) 2024 The Android Open Source Project
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
import static android.media.AudioManager.GET_DEVICES_INPUTS;
import static android.media.AudioManager.GET_DEVICES_OUTPUTS;

import static com.android.car.audio.CarAudioDeviceInfoTestUtils.MEDIA_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.NAVIGATION_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.PRIMARY_ZONE_MICROPHONE_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.SECONDARY_ZONE_BUS_1000_INPUT_DEVICE;
import static com.android.car.audio.CarAudioTestUtils.GAINS;
import static com.android.car.audio.CarAudioTestUtils.SECONDARY_ZONE_ID;
import static com.android.car.audio.CarAudioTestUtils.TEST_CREATED_CAR_AUDIO_CONTEXT;
import static com.android.car.audio.CarAudioTestUtils.createAudioPort;
import static com.android.car.audio.CarAudioTestUtils.createAudioPortDeviceExt;
import static com.android.car.audio.CarAudioTestUtils.createPrimaryAudioZone;
import static com.android.car.audio.CarAudioTestUtils.createSecondaryAudioZone;
import static com.android.car.audio.CoreAudioRoutingUtils.getCoreAudioZone;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.hardware.automotive.audiocontrol.AudioDeviceConfiguration;
import android.hardware.automotive.audiocontrol.AudioZone;
import android.hardware.automotive.audiocontrol.RoutingDeviceConfiguration;
import android.media.AudioDeviceAttributes;
import android.media.audio.common.AudioDeviceType;
import android.media.audio.common.AudioPort;
import android.media.audio.common.AudioPortDeviceExt;

import com.android.car.audio.hal.AudioControlWrapper;
import com.android.car.internal.util.LocalLog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public final class CarAudioZonesHelperAudioControlHALUnitTest
        extends AbstractExtendedMockitoTestCase {

    private static final int TEST_PRIMARY_ZONE_OCCUPANT_ID = 1;
    private static final int TEST_SECONDARY_ZONE_OCCUPANT_ID = 2;

    @Mock
    private CarAudioSettings mCarAudioSettings;
    @Mock
    private AudioManagerWrapper mAudioManager;
    @Mock
    private LocalLog mServiceLog;
    @Mock
    private AudioControlWrapper mAudioControlWrapper;
    private boolean mUseAudioFadeManager = true;

    private final List<AudioZone> mHALAudioZones = new ArrayList<>();

    private final CarAudioDeviceInfoTestUtils mDeviceTestUtils = new CarAudioDeviceInfoTestUtils();
    private final AudioDeviceConfiguration mAudioDeviceConfig = new AudioDeviceConfiguration();
    private final List<AudioPort> mMirrorPorts = new ArrayList<>();

    @Override
    protected void clearInlineMocks(String when) {
        super.clearInlineMocks(when);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(AudioManagerWrapper.class);
    }

    @Before
    public void setUp() {
        doReturn(CoreAudioRoutingUtils.getProductStrategies())
                .when(AudioManagerWrapper::getAudioProductStrategies);

        var outputDevice = mDeviceTestUtils.generateOutputDeviceInfos();
        var inputDevices = mDeviceTestUtils.generateInputDeviceInfos();
        when(mAudioManager.getDevices(GET_DEVICES_OUTPUTS)).thenReturn(outputDevice);
        when(mAudioManager.getDevices(GET_DEVICES_INPUTS)).thenReturn(inputDevices);

        when(mAudioControlWrapper.supportsFeature(
                AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION)).thenReturn(true);
        when(mAudioControlWrapper.getCarAudioZones()).thenReturn(mHALAudioZones);
        mAudioDeviceConfig.routingConfig = RoutingDeviceConfiguration.DYNAMIC_AUDIO_ROUTING;
        when(mAudioControlWrapper.getAudioDeviceConfiguration()).thenReturn(mAudioDeviceConfig);
        when(mAudioControlWrapper.getOutputMirroringDevices()).thenReturn(mMirrorPorts);
    }

    @Test
    public void constructor_withNullAudioControlHAL() {
        AudioControlWrapper audioControl = null;

        var thrown = assertThrows(NullPointerException.class,
                () -> new CarAudioZonesHelperAudioControlHAL(audioControl, mAudioManager,
                        mCarAudioSettings, mServiceLog, mUseAudioFadeManager));

        expectWithMessage("Exception for null audio control HAL").that(thrown)
                .hasMessageThat().contains("Audio control HAL");
    }

    @Test
    public void constructor_withNullAudioManager() {
        AudioManagerWrapper audioManager = null;

        var thrown = assertThrows(NullPointerException.class,
                () -> new CarAudioZonesHelperAudioControlHAL(mAudioControlWrapper, audioManager,
                        mCarAudioSettings, mServiceLog, mUseAudioFadeManager));

        expectWithMessage("Exception for null audio manager").that(thrown)
                .hasMessageThat().contains("Audio manager");
    }

    @Test
    public void constructor_withNullAudioSettings() {
        CarAudioSettings audioSettings = null;

        var thrown = assertThrows(NullPointerException.class,
                () -> new CarAudioZonesHelperAudioControlHAL(mAudioControlWrapper, mAudioManager,
                        audioSettings, mServiceLog, mUseAudioFadeManager));

        expectWithMessage("Exception for null car audio settings").that(thrown)
                .hasMessageThat().contains("Car audio settings");
    }

    @Test
    public void constructor_withNullServiceLogs() {
        LocalLog serviceLogs = null;

        var thrown = assertThrows(NullPointerException.class,
                () -> new CarAudioZonesHelperAudioControlHAL(mAudioControlWrapper, mAudioManager,
                        mCarAudioSettings, serviceLogs, mUseAudioFadeManager));

        expectWithMessage("Exception for null car audio logs").that(thrown)
                .hasMessageThat().contains("Car audio log");
    }

    @Test
    public void loadAudioZones_withUnsupportedOperationFromHal() {
        when(mAudioControlWrapper.supportsFeature(
                AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION)).thenReturn(false);
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var audioZones = helper.loadAudioZones();

        expectWithMessage("Loaded audio zones with unsupported feature from HAL")
                .that(audioZones.size()).isEqualTo(0);
    }

    @Test
    public void loadAudioZones_withDefaultDeviceConfig() {
        mAudioDeviceConfig.routingConfig = RoutingDeviceConfiguration.DEFAULT_AUDIO_ROUTING;
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var audioZones = helper.loadAudioZones();

        expectWithMessage("Loaded audio zones with default audio routing")
                .that(audioZones.size()).isEqualTo(0);
    }

    @Test
    public void loadAudioZones_withNullZonesFromHal() {
        when(mAudioControlWrapper.getCarAudioZones()).thenReturn(null);
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var audioZones = helper.loadAudioZones();

        expectWithMessage("Loaded audio zones with null audio zones from HAL")
                .that(audioZones.size()).isEqualTo(0);
    }

    @Test
    public void loadAudioZones_withEmptyZonesFromHal() {
        mHALAudioZones.clear();
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var audioZones = helper.loadAudioZones();

        expectWithMessage("Loaded audio zones with null audio zones from HAL")
                .that(audioZones.size()).isEqualTo(0);
    }

    @Test
    public void loadAudioZones_withNullZoneInZonesFromHal() {
        mHALAudioZones.add(null);
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var audioZones = helper.loadAudioZones();

        expectWithMessage("Loaded audio zones with null audio zone in audio zones from HAL")
                .that(audioZones.size()).isEqualTo(0);
    }

    @Test
    public void loadAudioZones_withRepeatingZonesFromHal() {
        mHALAudioZones.add(createPrimaryAudioZone());
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var audioZones = helper.loadAudioZones();

        expectWithMessage("Loaded audio zones with repeating audio zones from HAL")
                .that(audioZones.size()).isEqualTo(0);
    }

    @Test
    public void loadAudioZones_withValidPrimaryZonesFromHal() {
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var audioZones = helper.loadAudioZones();

        expectWithMessage("Loaded audio zones with valid primary zone from HAL")
                .that(audioZones.size()).isEqualTo(1);
        var primaryZone = audioZones.get(PRIMARY_AUDIO_ZONE);
        assertWithMessage("Loaded primary audio zone").that(primaryZone).isNotNull();
        expectWithMessage("Loaded primary audio zone configs")
                .that(primaryZone.getAllCarAudioZoneConfigs()).hasSize(3);
        var inputAddresses = primaryZone.getInputAudioDevices().stream()
                .map(AudioDeviceAttributes::getAddress).toList();
        expectWithMessage("Loaded primary zone input devices").that(inputAddresses)
                .containsExactly(PRIMARY_ZONE_MICROPHONE_DEVICE,
                        SECONDARY_ZONE_BUS_1000_INPUT_DEVICE);
    }

    @Test
    public void loadAudioZones_withValidSecondaryZoneFromHal() {
        mHALAudioZones.add(createPrimaryAudioZone());
        mHALAudioZones.add(createSecondaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var audioZones = helper.loadAudioZones();

        expectWithMessage("Loaded audio zones with valid secondary zone from HAL")
                .that(audioZones.size()).isEqualTo(2);
        var secondaryZone = audioZones.get(SECONDARY_ZONE_ID);
        assertWithMessage("Loaded secondary audio zone").that(secondaryZone).isNotNull();
        expectWithMessage("Loaded secondary audio zone configs")
                .that(secondaryZone.getAllCarAudioZoneConfigs()).hasSize(2);
    }

    @Test
    public void loadAudioZones_withoutPrimaryZoneFromHal() {
        mHALAudioZones.add(createSecondaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var audioZones = helper.loadAudioZones();

        expectWithMessage("Loaded audio zones without primary zone")
                .that(audioZones.size()).isEqualTo(0);
    }

    @Test
    public void loadAudioZones_withInvalidUseCoreVolumeConfiguration() {
        mHALAudioZones.add(createPrimaryAudioZone());
        mAudioDeviceConfig.useCoreAudioVolume = true;
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var audioZones = helper.loadAudioZones();

        expectWithMessage("Loaded audio zones with invalid use core volume configuration")
                .that(audioZones.size()).isEqualTo(0);
    }

    @Test
    public void loadAudioZones_withCoreAudioRoutingAndValidPrimaryZonesFromHal() {
        mHALAudioZones.add(getCoreAudioZone());
        mAudioDeviceConfig.routingConfig =
                RoutingDeviceConfiguration.CONFIGURABLE_AUDIO_ENGINE_ROUTING;
        mAudioDeviceConfig.useCoreAudioVolume = true;
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var audioZones = helper.loadAudioZones();

        expectWithMessage("Loaded audio zones with core audio routing")
                .that(audioZones.size()).isEqualTo(1);
        var primaryZone = audioZones.get(PRIMARY_AUDIO_ZONE);
        assertWithMessage("Loaded primary audio zone with core audio routing")
                .that(primaryZone).isNotNull();
        expectWithMessage("Loaded primary audio zone configs with core audio routing")
                .that(primaryZone.getAllCarAudioZoneConfigs()).hasSize(1);
    }

    @Test
    public void getCarAudioZoneIdToOccupantZoneIdMapping_withPrimaryZoneMapped() {
        var primaryZone = createPrimaryAudioZone();
        primaryZone.occupantZoneId = TEST_PRIMARY_ZONE_OCCUPANT_ID;
        mHALAudioZones.add(primaryZone);
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        var audioZoneIdToOccupantZoneId = helper.getCarAudioZoneIdToOccupantZoneIdMapping();

        expectWithMessage("Loaded audio zones to occupant zone mapping")
                .that(audioZoneIdToOccupantZoneId.size()).isEqualTo(1);
        expectWithMessage("Loaded primary occupant zone mapping").that(audioZoneIdToOccupantZoneId
                .get(PRIMARY_AUDIO_ZONE)).isEqualTo(TEST_PRIMARY_ZONE_OCCUPANT_ID);
    }

    @Test
    public void getCarAudioZoneIdToOccupantZoneIdMapping_withPrimaryAndSecondaryZoneMapped() {
        var primaryZone = createPrimaryAudioZone();
        primaryZone.occupantZoneId = TEST_PRIMARY_ZONE_OCCUPANT_ID;
        var secondaryZone = createSecondaryAudioZone();
        secondaryZone.occupantZoneId = TEST_SECONDARY_ZONE_OCCUPANT_ID;
        mHALAudioZones.add(primaryZone);
        mHALAudioZones.add(secondaryZone);
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        var audioZoneIdToOccupantZoneId = helper.getCarAudioZoneIdToOccupantZoneIdMapping();

        expectWithMessage("Loaded audio zones to occupant zone mapping with primary and secondary"
                + " zones").that(audioZoneIdToOccupantZoneId.size()).isEqualTo(2);
        expectWithMessage("Loaded primary occupant zone with both primary and secondary zones")
                .that(audioZoneIdToOccupantZoneId.get(PRIMARY_AUDIO_ZONE))
                .isEqualTo(TEST_PRIMARY_ZONE_OCCUPANT_ID);
        expectWithMessage("Loaded secondary  occupant zone mapping with both primary and secondary"
                + " zones").that(audioZoneIdToOccupantZoneId.get(SECONDARY_ZONE_ID))
                .isEqualTo(TEST_SECONDARY_ZONE_OCCUPANT_ID);
    }

    @Test
    public void getCarAudioZoneIdToOccupantZoneIdMapping_withLoadFailures() {
        var secondaryZone = createSecondaryAudioZone();
        secondaryZone.occupantZoneId = TEST_SECONDARY_ZONE_OCCUPANT_ID;
        mHALAudioZones.add(secondaryZone);
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        var audioZoneIdToOccupantZoneId = helper.getCarAudioZoneIdToOccupantZoneIdMapping();

        expectWithMessage("Loaded audio zones to occupant zone mapping with load zones failure")
                .that(audioZoneIdToOccupantZoneId.size()).isEqualTo(0);
    }

    @Test
    public void getCarAudioContext_withPrimaryZoneFromHal() {
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        var carAudioContext = helper.getCarAudioContext();

        expectWithMessage("Loaded car audio context with primary zone")
                .that(carAudioContext).isEqualTo(TEST_CREATED_CAR_AUDIO_CONTEXT);
    }

    @Test
    public void getCarAudioContext_withoutPrimaryZoneFromHal() {
        mHALAudioZones.add(createSecondaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        var carAudioContext = helper.getCarAudioContext();

        expectWithMessage("Loaded car audio context without primary zone")
                .that(carAudioContext).isNull();
    }

    @Test
    public void getCarAudioContext_withoutCallingLoadZonesFirst() {
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var carAudioContext = helper.getCarAudioContext();

        expectWithMessage("Loaded car audio context without initializing helper")
                .that(carAudioContext).isNull();
    }

    @Test
    public void useCoreAudioRouting_withoutCallingLoadZonesFirst() {
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        expectWithMessage("Loaded car audio routing without initializing helper")
                .that(helper.useCoreAudioRouting()).isFalse();
    }

    @Test
    public void useCoreAudioRouting_withUseDynamicRoutingFromHal() {
        mAudioDeviceConfig.routingConfig = RoutingDeviceConfiguration.DYNAMIC_AUDIO_ROUTING;
        mHALAudioZones.add(createSecondaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        expectWithMessage("Loaded car audio routing with dynamic audio routing")
                .that(helper.useCoreAudioRouting()).isFalse();
    }

    @Test
    public void useCoreAudioRouting_withCoreAudioRoutingFromHal() {
        mHALAudioZones.add(getCoreAudioZone());
        mAudioDeviceConfig.routingConfig =
                RoutingDeviceConfiguration.CONFIGURABLE_AUDIO_ENGINE_ROUTING;
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        expectWithMessage("Loaded car audio routing with core audio routing")
                .that(helper.useCoreAudioRouting()).isTrue();
    }

    @Test
    public void useCoreAudioRouting_withCoreAudioRoutingFromHalAndLoadFailure() {
        mHALAudioZones.add(createSecondaryAudioZone());
        mAudioDeviceConfig.routingConfig =
                RoutingDeviceConfiguration.CONFIGURABLE_AUDIO_ENGINE_ROUTING;
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        expectWithMessage("Loaded car audio routing with core audio routing and load failure")
                .that(helper.useCoreAudioRouting()).isFalse();
    }

    @Test
    public void useCoreAudioVolume_withoutCallingLoadZonesFirst() {
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        expectWithMessage("Loaded car audio volume without initializing helper")
                .that(helper.useCoreAudioVolume()).isFalse();
    }

    @Test
    public void useCoreAudioVolume_withoutUseCoreVolumeFromHal() {
        mAudioDeviceConfig.useCoreAudioVolume = false;
        mHALAudioZones.add(createSecondaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        expectWithMessage("Loaded car audio volume without use core volume")
                .that(helper.useCoreAudioVolume()).isFalse();
    }

    @Test
    public void useCoreAudioVolume_withUseCoreVolumeFromHal() {
        mAudioDeviceConfig.useCoreAudioVolume = true;
        mHALAudioZones.add(getCoreAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        expectWithMessage("Loaded car audio volume with use core volume")
                .that(helper.useCoreAudioVolume()).isTrue();
    }

    @Test
    public void useCoreAudioVolume_withUseCoreVolumeFromHalAndLoadFailure() {
        mAudioDeviceConfig.useCoreAudioVolume = true;
        mHALAudioZones.add(createSecondaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        expectWithMessage("Loaded car audio volume with use core volume and load failure")
                .that(helper.useCoreAudioVolume()).isFalse();
    }

    @Test
    public void useVolumeGroupMuting_withoutCallingLoadZonesFirst() {
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        expectWithMessage("Loaded car audio volume muting without initializing helper")
                .that(helper.useVolumeGroupMuting()).isFalse();
    }

    @Test
    public void useVolumeGroupMuting_withoutUseVolumeGroupMutingFromHal() {
        mAudioDeviceConfig.useCarVolumeGroupMuting = false;
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        expectWithMessage("Loaded car audio volume muting without use volume muting")
                .that(helper.useVolumeGroupMuting()).isFalse();
    }

    @Test
    public void useVolumeGroupMuting_withUseVolumeGroupMutingFromHal() {
        mAudioDeviceConfig.useCarVolumeGroupMuting = true;
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        expectWithMessage("Loaded car audio volume muting with use volume muting")
                .that(helper.useVolumeGroupMuting()).isTrue();
    }

    @Test
    public void useVolumeGroupMuting_withUseVolumeGroupMutingFromHalAndLoadFailure() {
        mAudioDeviceConfig.useCarVolumeGroupMuting = true;
        mHALAudioZones.add(createSecondaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        expectWithMessage("Loaded car audio volume muting with use volume muting and load failure")
                .that(helper.useVolumeGroupMuting()).isFalse();
    }

    @Test
    public void useHalDuckingSignalOrDefault_withoutCallingLoadZonesFirst() {
        mAudioDeviceConfig.useHalDuckingSignals = true;
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        boolean useHalDucking = helper.useHalDuckingSignalOrDefault(
                /* unusedDefaultUseHalDuckingSignal= */ true);

        expectWithMessage("Loaded HAL ducking without initializing helper").that(useHalDucking)
                .isFalse();
    }

    @Test
    public void useHalDuckingSignalOrDefault_withoutUseHalDuckingFromHal() {
        mAudioDeviceConfig.useHalDuckingSignals = false;
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        boolean useHalDucking = helper.useHalDuckingSignalOrDefault(
                /* unusedDefaultUseHalDuckingSignal= */ true);

        expectWithMessage("Loaded HAL ducking without use HAL ducking").that(useHalDucking)
                .isFalse();
    }

    @Test
    public void useHalDuckingSignalOrDefault_withUseHalDuckingFromHal() {
        mAudioDeviceConfig.useHalDuckingSignals = true;
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        boolean useHalDucking = helper.useHalDuckingSignalOrDefault(
                /* unusedDefaultUseHalDuckingSignal= */ true);

        expectWithMessage("Loaded HAL ducking with use HAL ducking").that(useHalDucking).isTrue();
    }

    @Test
    public void useHalDuckingSignalOrDefault_withUseHalDuckingFromHalAndOverwriteFromParameter() {
        mAudioDeviceConfig.useHalDuckingSignals = true;
        mHALAudioZones.add(createPrimaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        boolean useHalDucking = helper.useHalDuckingSignalOrDefault(
                /* unusedDefaultUseHalDuckingSignal= */ false);

        expectWithMessage("Loaded HAL ducking with use HAL ducking and false overwritten from "
                + "parameter").that(useHalDucking).isTrue();
    }

    @Test
    public void useHalDuckingSignalOrDefault_withUseHalDuckingFromHalAndLoadFailure() {
        mAudioDeviceConfig.useHalDuckingSignals = true;
        mHALAudioZones.add(createSecondaryAudioZone());
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();
        helper.loadAudioZones();

        boolean useHalDucking = helper.useHalDuckingSignalOrDefault(
                /* unusedDefaultUseHalDuckingSignal= */ true);

        expectWithMessage("Loaded HAL ducking with use HAL ducking and load failure")
                .that(useHalDucking).isFalse();
    }

    @Test
    public void getMirrorDeviceInfos_withNullPortList() {
        when(mAudioControlWrapper.getOutputMirroringDevices()).thenReturn(null);
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        expectWithMessage("Converted output mirror devices for null ports list")
                .that(helper.getMirrorDeviceInfos()).isEmpty();
    }

    @Test
    public void getMirrorDeviceInfos_withEmptyPortList() {
        mMirrorPorts.clear();
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        expectWithMessage("Converted output mirror devices for empty ports list")
                .that(helper.getMirrorDeviceInfos()).isEmpty();
    }

    @Test
    public void getMirrorDeviceInfos_withValidPortList() {
        AudioPortDeviceExt navPortDevice =
                createAudioPortDeviceExt(AudioDeviceType.OUT_BUS, "", NAVIGATION_TEST_DEVICE);
        AudioPort navPort = createAudioPort(2, "nav port", GAINS, navPortDevice);
        AudioPortDeviceExt mediaPortDevice =
                createAudioPortDeviceExt(AudioDeviceType.OUT_BUS, "", MEDIA_TEST_DEVICE);
        AudioPort mediaPort = createAudioPort(1, "media port", GAINS, mediaPortDevice);
        mMirrorPorts.add(mediaPort);
        mMirrorPorts.add(navPort);
        CarAudioZonesHelperAudioControlHAL helper = createAudioZonesHelper();

        var mirroringDevices = helper.getMirrorDeviceInfos();

        expectWithMessage("Converted output mirror devices for valid ports list")
                .that(mirroringDevices).hasSize(2);
        var addresses = mirroringDevices.stream().map(CarAudioDeviceInfo::getAddress).toList();
        expectWithMessage("Converted output mirror device addresses").that(addresses)
                .containsExactly(NAVIGATION_TEST_DEVICE, MEDIA_TEST_DEVICE);
    }

    private CarAudioZonesHelperAudioControlHAL createAudioZonesHelper() {
        return new CarAudioZonesHelperAudioControlHAL(mAudioControlWrapper, mAudioManager,
                mCarAudioSettings, mServiceLog, mUseAudioFadeManager);
    }
}
