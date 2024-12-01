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
import static android.hardware.automotive.audiocontrol.VolumeInvocationType.ON_BOOT;
import static android.hardware.automotive.audiocontrol.VolumeInvocationType.ON_PLAYBACK_CHANGED;
import static android.hardware.automotive.audiocontrol.VolumeInvocationType.ON_SOURCE_CHANGED;
import static android.media.AudioManager.GET_DEVICES_INPUTS;
import static android.media.AudioManager.GET_DEVICES_OUTPUTS;

import static com.android.car.audio.CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT;
import static com.android.car.audio.CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED;
import static com.android.car.audio.CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_SOURCE_CHANGED;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.MIRROR_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.OEM_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.PRIMARY_ZONE_FM_TUNER_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.PRIMARY_ZONE_MICROPHONE_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.SECONDARY_ZONE_BUS_1000_INPUT_DEVICE;
import static com.android.car.audio.CarAudioTestUtils.GAINS;
import static com.android.car.audio.CarAudioTestUtils.PRIMARY_ZONE_NAME;
import static com.android.car.audio.CarAudioTestUtils.TEST_CREATED_CAR_AUDIO_CONTEXT;
import static com.android.car.audio.CarAudioTestUtils.TEST_EMERGENCY_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.createAudioPort;
import static com.android.car.audio.CarAudioTestUtils.createAudioPortDeviceExt;
import static com.android.car.audio.CarAudioTestUtils.createCarAudioContextNameToIdMap;
import static com.android.car.audio.CarAudioTestUtils.createPrimaryAudioZone;
import static com.android.car.audio.CarAudioTestUtils.getContextForVolumeGroupConfig;
import static com.android.car.audio.CarAudioTestUtils.getTestCarFadeConfiguration;
import static com.android.car.audio.CarAudioTestUtils.getTestDisabledCarFadeConfiguration;
import static com.android.car.audio.CarAudioUtils.ACTIVATION_VOLUME_INVOCATION_TYPE;
import static com.android.car.audio.CarAudioUtils.ACTIVATION_VOLUME_PERCENTAGE_MAX;
import static com.android.car.audio.CarAudioUtils.ACTIVATION_VOLUME_PERCENTAGE_MIN;
import static com.android.car.audio.CoreAudioRoutingUtils.CORE_PRIMARY_ZONE;
import static com.android.car.audio.CoreAudioRoutingUtils.getCoreAudioZone;
import static com.android.car.audio.CoreAudioRoutingUtils.getCoreCarAudioContext;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.hardware.automotive.audiocontrol.AudioDeviceConfiguration;
import android.hardware.automotive.audiocontrol.AudioZone;
import android.hardware.automotive.audiocontrol.AudioZoneConfig;
import android.hardware.automotive.audiocontrol.AudioZoneContext;
import android.hardware.automotive.audiocontrol.AudioZoneContextInfo;
import android.hardware.automotive.audiocontrol.RoutingDeviceConfiguration;
import android.hardware.automotive.audiocontrol.TransientFadeConfigurationEntry;
import android.hardware.automotive.audiocontrol.VolumeActivationConfiguration;
import android.hardware.automotive.audiocontrol.VolumeGroupConfig;
import android.media.AudioDeviceAttributes;
import android.media.AudioProductStrategy;
import android.media.audio.common.AudioDeviceType;
import android.media.audio.common.AudioGain;
import android.media.audio.common.AudioPort;
import android.media.audio.common.AudioPortDeviceExt;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.CarServiceUtils;
import com.android.car.internal.util.LocalLog;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class AudioControlZoneConverterUnitTest extends AbstractExtendedMockitoTestCase {

    private static final String TAG = AudioControlZoneConverterUnitTest.class.getSimpleName();

    @Mock
    private CarAudioSettings mCarAudioSettings;
    @Mock
    private AudioManagerWrapper mAudioManager;
    @Mock
    private LocalLog mServiceLog;

    private CarAudioDeviceInfoTestUtils mAudioDeviceInfoTestUtils =
            new CarAudioDeviceInfoTestUtils();

    private ArrayMap<String, Integer> mCarAudioContextMap;
    private boolean mUseFadeManagerConfiguration;

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
        var outputDevice = mAudioDeviceInfoTestUtils.generateOutputDeviceInfos();
        var inputDevices = mAudioDeviceInfoTestUtils.generateInputDeviceInfos();
        when(mAudioManager.getDevices(GET_DEVICES_OUTPUTS)).thenReturn(outputDevice);
        when(mAudioManager.getDevices(GET_DEVICES_INPUTS)).thenReturn(inputDevices);
        mCarAudioContextMap = createCarAudioContextNameToIdMap(TEST_CREATED_CAR_AUDIO_CONTEXT);
        mUseFadeManagerConfiguration = true;
    }

    @Test
    public void constructor_withNullAudioManager() {
        AudioManagerWrapper audioManager = null;
        var serviceLog = new LocalLog(10);

        var thrown = assertThrows(NullPointerException.class, () ->
                new AudioControlZoneConverter(audioManager, mCarAudioSettings, serviceLog,
                        mUseFadeManagerConfiguration));

        expectWithMessage("Constructor exception for null audio manager").that(thrown)
                .hasMessageThat().contains("Audio manager");
    }

    @Test
    public void constructor_withNullCarAudioSetting() {
        var serviceLog = new LocalLog(10);
        CarAudioSettings carAudioSettings = null;

        var thrown = assertThrows(NullPointerException.class, () ->
                new AudioControlZoneConverter(mAudioManager, carAudioSettings, serviceLog,
                        mUseFadeManagerConfiguration));

        expectWithMessage("Constructor exception for null car audio settings").that(thrown)
                .hasMessageThat().contains("Car audio settings");
    }

    @Test
    public void constructor_withNullServiceLog() {
        LocalLog serviceLog = null;

        var thrown = assertThrows(NullPointerException.class, () ->
                new AudioControlZoneConverter(mAudioManager, mCarAudioSettings, serviceLog,
                        mUseFadeManagerConfiguration));

        expectWithMessage("Constructor exception for null car service log").that(thrown)
                .hasMessageThat().contains("Local car service logs");
    }

    @Test
    public void convertAudioZone_withNullAudioZone() {
        AudioZone zone = null;
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var thrown = assertThrows(NullPointerException.class, () ->
                audioZoneConverter.convertAudioZone(zone, audioDeviceConfig));

        expectWithMessage("Convert audio zone exception for null audio zone").that(thrown)
                .hasMessageThat().contains("Audio zone");
    }

    @Test
    public void convertAudioZone_withNullAudioDeviceConfiguration() {
        var zone = new AudioZone();
        AudioDeviceConfiguration audioDeviceConfig = null;
        var audioZoneConverter = setupAudioZoneConverter();

        var thrown = assertThrows(NullPointerException.class, () ->
                audioZoneConverter.convertAudioZone(zone, audioDeviceConfig));

        expectWithMessage("Convert audio zone exception for null audio zone").that(thrown)
                .hasMessageThat().contains("Audio device configuration");
    }

    @Test
    public void convertAudioZone_withNullAudioContextInZone() {
        var zone = new AudioZone();
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var thrown = assertThrows(NullPointerException.class, () ->
                audioZoneConverter.convertAudioZone(zone, audioDeviceConfig));

        expectWithMessage("Convert audio zone exception for null context").that(thrown)
                .hasMessageThat().contains("Audio zone context");
    }

    @Test
    public void convertAudioZone_withNullAudioContextInfosInZone() {
        var zone = new AudioZone();
        zone.audioZoneContext = new AudioZoneContext();
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var thrown = assertThrows(NullPointerException.class, () ->
                audioZoneConverter.convertAudioZone(zone, audioDeviceConfig));

        expectWithMessage("Convert audio zone exception for null context infos").that(thrown)
                .hasMessageThat().contains("Audio zone context infos can not be null");
    }

    @Test
    public void convertAudioZone_withEmptyAudioContextInfosInZone() {
        var zone = new AudioZone();
        var audioZoneContext = new AudioZoneContext();
        audioZoneContext.audioContextInfos = Collections.EMPTY_LIST;
        zone.audioZoneContext = audioZoneContext;
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var thrown = assertThrows(IllegalArgumentException.class, () ->
                audioZoneConverter.convertAudioZone(zone, audioDeviceConfig));

        expectWithMessage("Convert audio zone exception for empty context infos").that(thrown)
                .hasMessageThat().contains("Audio zone context infos can not be empty");
    }

    @Test
    public void convertAudioZone_withMalformedAudioContextInZone() {
        var zone = new AudioZone();
        var audioZoneContext = new AudioZoneContext();
        audioZoneContext.audioContextInfos = List.of(new AudioZoneContextInfo());
        zone.audioZoneContext = audioZoneContext;
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        expectWithMessage("Converted audio zone with malformed audio context")
                .that(carAudioZone).isNull();
    }

    @Test
    public void convertAudioZone_withNonExistContextInVolumeGroup() {
        var zone = createPrimaryAudioZone();
        zone.audioZoneConfigs.getFirst().volumeGroups.getFirst().carAudioRoutes.getFirst()
                .contextNames.add("Non-existing context");
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        expectWithMessage("Converted audio zone with non existing context")
                .that(carAudioZone).isNull();
    }

    @Test
    public void convertAudioZone_withCoreVolumeAndVolumeGroupName() {
        var zone = createPrimaryAudioZone();
        var audioDeviceConfig = new AudioDeviceConfiguration();
        audioDeviceConfig.useCoreAudioVolume = true;
        audioDeviceConfig.routingConfig =
                RoutingDeviceConfiguration.CONFIGURABLE_AUDIO_ENGINE_ROUTING;
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        expectWithMessage("Converted audio zone with for core volume and empty volme group name")
                .that(carAudioZone).isNull();
    }

    @Test
    public void convertAudioZone_withCoreRoutingAndMissingAudiProductStrategies() {
        doReturn(new ArrayList<AudioProductStrategy>())
                .when(AudioManagerWrapper::getAudioProductStrategies);
        AudioZone zone = getCoreAudioZone();
        zone.audioZoneContext.audioContextInfos.getFirst().id =
                AudioZoneContextInfo.UNASSIGNED_CONTEXT_ID;
        var audioDeviceConfig = new AudioDeviceConfiguration();
        audioDeviceConfig.useCoreAudioVolume = true;
        audioDeviceConfig.routingConfig =
                RoutingDeviceConfiguration.CONFIGURABLE_AUDIO_ENGINE_ROUTING;
        var audioZoneConverter = setupAudioZoneConverter();

        var thrown = assertThrows(IllegalArgumentException.class, () ->
                audioZoneConverter.convertAudioZone(zone, audioDeviceConfig));

        expectWithMessage("Convert audio zone exception for missing product strategy")
                .that(thrown).hasMessageThat().contains("product strategy id");
    }

    @Test
    public void convertAudioZone_forPrimaryZone_withCoreAudioZone() {
        var zone = getCoreAudioZone();
        var audioDeviceConfig = new AudioDeviceConfiguration();
        audioDeviceConfig.useCoreAudioVolume = true;
        audioDeviceConfig.routingConfig =
                RoutingDeviceConfiguration.CONFIGURABLE_AUDIO_ENGINE_ROUTING;
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        var carAudioContextMap =
                createCarAudioContextNameToIdMap(carAudioZone.getCarAudioContext());
        SparseArray<CarAudioZone> zones = new SparseArray<>(1);
        boolean useCoreAudioRouting = true;
        zones.append(PRIMARY_AUDIO_ZONE, carAudioZone);
        CarAudioZonesValidator.validateWithoutInputDevicesCheck(zones, useCoreAudioRouting);
        assertWithMessage("Converted primary car audio zone").that(carAudioZone)
                .isNotNull();
        expectWithMessage("Converted primary car audio zone ID")
                .that(carAudioZone.getId()).isEqualTo(PRIMARY_AUDIO_ZONE);
        expectWithMessage("Converted primary car audio zone name")
                .that(carAudioZone.getName()).isEqualTo(CORE_PRIMARY_ZONE);
        expectWithMessage("Converted primary car audio zone context")
                .that(carAudioZone.getCarAudioContext()).isEqualTo(getCoreCarAudioContext());
        var carAudioZoneConfigs = carAudioZone.getAllCarAudioZoneConfigs();
        assertWithMessage("Converted primary car audio zone configs")
                .that(carAudioZoneConfigs.size()).isEqualTo(zone.audioZoneConfigs.size());
        for (int c = 0; c < carAudioZoneConfigs.size(); c++) {
            var zoneConfig = zone.audioZoneConfigs.get(c);
            var zoneConfigInfo = carAudioZoneConfigs.get(c);
            Log.i(TAG, "Testing zone config[" + c + "] " + zoneConfig.name);
            expectWithMessage("Converted primary zone id in config %s", zoneConfig.name)
                    .that(zoneConfigInfo.getZoneId()).isEqualTo(PRIMARY_AUDIO_ZONE);
            validateZoneConfigInfo(carAudioZone.getName(), zoneConfigInfo, zoneConfig,
                    PRIMARY_AUDIO_ZONE, audioDeviceConfig.useCoreAudioVolume, carAudioContextMap);
        }
    }

    @Test
    public void convertAudioZone_forPrimaryZone() {
        var zone = createPrimaryAudioZone();
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        SparseArray<CarAudioZone> zones = new SparseArray<>(1);
        boolean useCoreAudioRouting = false;
        zones.append(PRIMARY_AUDIO_ZONE, carAudioZone);
        CarAudioZonesValidator.validateWithoutInputDevicesCheck(zones, useCoreAudioRouting);
        assertWithMessage("Converted primary car audio zone").that(carAudioZone)
                .isNotNull();
        expectWithMessage("Converted primary car audio zone ID")
                .that(carAudioZone.getId()).isEqualTo(PRIMARY_AUDIO_ZONE);
        expectWithMessage("Converted primary car audio zone name")
                .that(carAudioZone.getName()).isEqualTo(PRIMARY_ZONE_NAME);
        expectWithMessage("Converted primary car audio zone context")
                .that(carAudioZone.getCarAudioContext()).isEqualTo(TEST_CREATED_CAR_AUDIO_CONTEXT);
        var carAudioZoneConfigs = carAudioZone.getAllCarAudioZoneConfigs();
        assertWithMessage("Converted primary car audio zone configs")
                .that(carAudioZoneConfigs.size()).isEqualTo(zone.audioZoneConfigs.size());
        for (int c = 0; c < carAudioZoneConfigs.size(); c++) {
            var zoneConfig = zone.audioZoneConfigs.get(c);
            var zoneConfigInfo = carAudioZoneConfigs.get(c);
            Log.i(TAG, "Testing zone config[" + c + "] " + zoneConfig.name);
            expectWithMessage("Converted primary zone id in config %s", zoneConfig.name)
                    .that(zoneConfigInfo.getZoneId()).isEqualTo(PRIMARY_AUDIO_ZONE);
            validateZoneConfigInfo(carAudioZone.getName(), zoneConfigInfo, zoneConfig,
                    PRIMARY_AUDIO_ZONE, audioDeviceConfig.useCoreAudioVolume, mCarAudioContextMap);
        }
    }

    @Test
    public void convertAudioZone_forPrimaryZoneAndWithCoreAudioZoneButMissingContextID() {
        var zone = getCoreAudioZone();
        zone.audioZoneContext.audioContextInfos.getFirst().id =
                AudioZoneContextInfo.UNASSIGNED_CONTEXT_ID;
        var audioDeviceConfig = new AudioDeviceConfiguration();
        audioDeviceConfig.useCoreAudioVolume = true;
        audioDeviceConfig.routingConfig =
                RoutingDeviceConfiguration.CONFIGURABLE_AUDIO_ENGINE_ROUTING;
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        SparseArray<CarAudioZone> zones = new SparseArray<>(1);
        boolean useCoreAudioRouting = true;
        zones.append(PRIMARY_AUDIO_ZONE, carAudioZone);
        CarAudioZonesValidator.validateWithoutInputDevicesCheck(zones, useCoreAudioRouting);
        assertWithMessage("Converted primary car audio zone with missing context ID")
                .that(carAudioZone).isNotNull();
    }

    @Test
    public void convertAudioZone_forPrimaryZoneAndWithFadeConfigurationEnabled() {
        var zone = createPrimaryAudioZone();
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        SparseArray<CarAudioZone> zones = new SparseArray<>(1);
        boolean useCoreAudioRouting = false;
        zones.append(PRIMARY_AUDIO_ZONE, carAudioZone);
        CarAudioZonesValidator.validateWithoutInputDevicesCheck(zones, useCoreAudioRouting);
        var defaultZoneConfig = carAudioZone.getAllCarAudioZoneConfigs().stream()
                .filter(CarAudioZoneConfig::isDefault).findFirst().orElseThrow();
        var defaultFadeConfig = defaultZoneConfig.getDefaultCarAudioFadeConfiguration();
        expectWithMessage("Primary zone default config's default fade configuration")
                .that(defaultFadeConfig).isEqualTo(getTestCarFadeConfiguration());
        var emergencyFadeConfig = defaultZoneConfig
                .getCarAudioFadeConfigurationForAudioAttributes(TEST_EMERGENCY_ATTRIBUTE);
        // Actual car configuration is different since the default name changes for each config
        expectWithMessage("Emergency fade configuration for default config in primary zone")
                .that(emergencyFadeConfig.getFadeManagerConfiguration())
                .isEqualTo(getTestDisabledCarFadeConfiguration().getFadeManagerConfiguration());
    }

    @Test
    public void convertAudioZone_forPrimaryZoneAndWithFadeConfigurationDisabled() {
        mUseFadeManagerConfiguration = false;
        var zone = createPrimaryAudioZone();
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        SparseArray<CarAudioZone> zones = new SparseArray<>(1);
        boolean useCoreAudioRouting = false;
        zones.append(PRIMARY_AUDIO_ZONE, carAudioZone);
        CarAudioZonesValidator.validateWithoutInputDevicesCheck(zones, useCoreAudioRouting);
        CarAudioZoneConfig defaultZoneConfig = carAudioZone.getAllCarAudioZoneConfigs().stream()
                .filter(carAudioZoneConfig -> carAudioZoneConfig.isDefault())
                .findFirst().orElseThrow();
        var defaultFadeConfig = defaultZoneConfig.getDefaultCarAudioFadeConfiguration();
        expectWithMessage("Primary zone default config's disabled default fade configuration")
                .that(defaultFadeConfig).isNull();
    }

    @Test
    public void convertAudioZone_forPrimaryZoneAndWithNullDefaultFadeConfig() {
        var zone = createPrimaryAudioZone();
        zone.audioZoneConfigs.getFirst().fadeConfiguration.defaultConfiguration = null;
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        expectWithMessage("Converted primary zone with null default fade config")
                .that(carAudioZone).isNull();
    }

    @Test
    public void convertAudioZone_forPrimaryZoneAndWithNullTransientFadeConfig() {
        var zone = createPrimaryAudioZone();
        var transientFadeConfigs = new ArrayList<TransientFadeConfigurationEntry>();
        transientFadeConfigs.add(null);
        zone.audioZoneConfigs.getFirst().fadeConfiguration.transientConfiguration =
                transientFadeConfigs;
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        expectWithMessage("Converted primary zone with null transient fade config")
                .that(carAudioZone).isNull();
    }

    @Test
    public void convertZonesMirroringAudioPorts_forNullAudioPorts() {
        var audioZoneConverter = setupAudioZoneConverter();

        var devices = audioZoneConverter.convertZonesMirroringAudioPorts(
                /* mirroringPorts= */ null);

        expectWithMessage("Converted mirroring devices for null ports").that(devices).isEmpty();
    }

    @Test
    public void convertZonesMirroringAudioPorts_forEmptyAudioPorts() {
        var audioPorts = new ArrayList<AudioPort>(0);
        var audioZoneConverter = setupAudioZoneConverter();

        var devices = audioZoneConverter.convertZonesMirroringAudioPorts(audioPorts);

        expectWithMessage("Converted mirroring devices for empty ports").that(devices).isEmpty();
    }

    @Test
    public void convertZonesMirroringAudioPorts_withNullDeviceInPortsList() {
        AudioPortDeviceExt mirrorPortDevice = createAudioPortDeviceExt(AudioDeviceType.OUT_BUS,
                /* connection= */ "", MIRROR_TEST_DEVICE);
        AudioPort mirrorPort =
                createAudioPort(/* id= */ 10, "mirror port", GAINS, mirrorPortDevice);
        var audioPorts = new ArrayList<AudioPort>(2);
        audioPorts.add(mirrorPort);
        audioPorts.add(null);
        var audioZoneConverter = setupAudioZoneConverter();

        var devices = audioZoneConverter.convertZonesMirroringAudioPorts(audioPorts);

        expectWithMessage("Converted mirroring devices for null device in ports")
                .that(devices).isEmpty();

    }

    @Test
    public void convertZonesMirroringAudioPorts_forAudioPorts() {
        AudioPortDeviceExt mirrorPortDevice = createAudioPortDeviceExt(AudioDeviceType.OUT_BUS,
                /* connection= */ "", MIRROR_TEST_DEVICE);
        AudioPort mirrorPort =
                createAudioPort(/* id= */ 10, "mirror port", GAINS, mirrorPortDevice);
        AudioPortDeviceExt oemPortDevice = createAudioPortDeviceExt(AudioDeviceType.OUT_BUS,
                /* connection= */ "", OEM_TEST_DEVICE);
        AudioPort oemPort = createAudioPort(/* id= */ 11, "oem port", GAINS, oemPortDevice);
        var audioPorts = List.of(mirrorPort, oemPort);
        var audioZoneConverter = setupAudioZoneConverter();

        var devices = audioZoneConverter.convertZonesMirroringAudioPorts(audioPorts);

        var addresses = devices.stream().map(CarAudioDeviceInfo::getAddress).toList();
        expectWithMessage("Converted mirroring devices for empty ports")
                .that(addresses).containsExactly(MIRROR_TEST_DEVICE, OEM_TEST_DEVICE);

    }

    @Test
    public void convertAudioZone_forPrimaryZoneAndWithEmptyListOfInputDevices() {
        var zone = createPrimaryAudioZone();
        zone.inputAudioDevices = List.of();
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        expectWithMessage("Converted primary zone empty list of input devices")
                .that(carAudioZone.getInputAudioDevices()).isEmpty();
    }

    @Test
    public void convertAudioZone_forPrimaryZoneAndWithNullDeviceInputDevices() {
        var zone = createPrimaryAudioZone();
        var inputDevices = new ArrayList<AudioPort>(1);
        inputDevices.add(null);
        zone.inputAudioDevices = inputDevices;
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        expectWithMessage("Converted primary zone with invalid null input device zone")
                .that(carAudioZone).isNull();
    }

    @Test
    public void convertAudioZone_forPrimaryZoneAndWithNullExtDeviceInputDevices() {
        var zone = createPrimaryAudioZone();
        var portDeviceExt = createAudioPortDeviceExt(AudioDeviceType.IN_BUS, /* connection= */ "",
                /* address= */ "invalid address");
        var audioPort = createAudioPort(10, "input bus", new AudioGain[0], portDeviceExt);
        zone.inputAudioDevices = List.of(audioPort);
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        expectWithMessage("Converted primary zone with invalid null input ext device")
                .that(carAudioZone).isNull();
    }

    @Test
    public void convertAudioZone_forPrimaryZoneAndWithInputDevices() {
        var zone = createPrimaryAudioZone();
        zone.inputAudioDevices = getInputDevicePorts();
        var audioDeviceConfig = new AudioDeviceConfiguration();
        var audioZoneConverter = setupAudioZoneConverter();

        var carAudioZone = audioZoneConverter.convertAudioZone(zone, audioDeviceConfig);

        var zones = new SparseArray<CarAudioZone>(1);
        boolean useCoreAudioRouting = false;
        zones.append(PRIMARY_AUDIO_ZONE, carAudioZone);
        CarAudioZonesValidator.validate(zones, useCoreAudioRouting);
        var inputDevices = carAudioZone.getInputAudioDevices().stream()
                .map(AudioDeviceAttributes::getAddress).toList();
        expectWithMessage("Converted input devices").that(inputDevices)
                .containsExactly(SECONDARY_ZONE_BUS_1000_INPUT_DEVICE, PRIMARY_ZONE_FM_TUNER_DEVICE,
                        PRIMARY_ZONE_MICROPHONE_DEVICE);
    }

    private List<AudioPort> getInputDevicePorts() {
        var portBusDeviceExt = createAudioPortDeviceExt(AudioDeviceType.IN_BUS,
                /* connection= */ "", SECONDARY_ZONE_BUS_1000_INPUT_DEVICE);
        var busPort = createAudioPort(11, "bus", new AudioGain[0], portBusDeviceExt);
        var portFMDeviceExt = createAudioPortDeviceExt(AudioDeviceType.IN_FM_TUNER,
                /* connection= */ "", PRIMARY_ZONE_FM_TUNER_DEVICE);
        var fmTunerPort = createAudioPort(10, "fm tuner", new AudioGain[0], portFMDeviceExt);
        var portMicDeviceExt = createAudioPortDeviceExt(AudioDeviceType.IN_MICROPHONE,
                /* connection= */ "", PRIMARY_ZONE_MICROPHONE_DEVICE);
        var micPort = createAudioPort(11, "mic", new AudioGain[0], portMicDeviceExt);
        return List.of(busPort, fmTunerPort, micPort);
    }

    private void validateZoneConfigInfo(String zoneName, CarAudioZoneConfig carZoneConfig,
            AudioZoneConfig zoneConfig, int zoneId, boolean useCoreVolume,
            ArrayMap<String, Integer> carAudioContextMap) {
        expectWithMessage("Name for converted audio zone %s config %s", zoneName, zoneConfig.name)
                .that(carZoneConfig.getName()).isEqualTo(zoneConfig.name);
        expectWithMessage("Primary status for converted audio zone %s config %s", zoneName,
                zoneConfig.name).that(carZoneConfig.isDefault())
                .isEqualTo(zoneConfig.isDefault);
        var carVolumeGroups = carZoneConfig.getVolumeGroups();
        var volumeGroupConfigs = zoneConfig.volumeGroups;
        assertWithMessage("Volume groups for converted audio zone %s config %s", zoneName,
                zoneConfig.name).that(carVolumeGroups.length).isEqualTo(volumeGroupConfigs.size());
        String configName = zoneName + " config id " + carZoneConfig.getName();
        Set<Integer> contexts = new ArraySet<>();
        for (int c = 0; c < carVolumeGroups.length; c++) {
            var carVolumeGroup = carVolumeGroups[c];
            var volumeGroupConfig = volumeGroupConfigs.get(c);
            Log.i(TAG, "Testing volume group [" + c + "] " + volumeGroupConfig.name + " in "
                    + configName);
            expectWithMessage("Volume group zone id for %s", configName)
                    .that(carVolumeGroup.mZoneId).isEqualTo(zoneId);
            expectWithMessage("Volume group config id for %s", configName)
                    .that(carVolumeGroup.mConfigId).isEqualTo(carZoneConfig.getZoneConfigId());
            validateCarVolumeGroup(configName, carVolumeGroup, volumeGroupConfig, useCoreVolume,
                    contexts, carAudioContextMap);
        }
        expectWithMessage("Total context count %s", configName).that(contexts.size())
                .isEqualTo(carAudioContextMap.size());
    }

    private void validateCarVolumeGroup(String configInfo, CarVolumeGroup carVolumeGroup,
            VolumeGroupConfig groupConfig, boolean useCoreVolume, Set<Integer> contexts,
            ArrayMap<String, Integer> carAudioContextMap) {
        if (useCoreVolume || (groupConfig.name != null && !groupConfig.name.isEmpty())) {
            expectWithMessage("Volume group name for %s", configInfo)
                    .that(carVolumeGroup.getName()).isEqualTo(groupConfig.name);
        }
        var groupContexts = CarServiceUtils.asList(carVolumeGroup.getContexts());
        expectWithMessage("Volume group ID group %s for %s", carVolumeGroup.getName(),
                configInfo).that(carVolumeGroup.mId).isEqualTo(groupConfig.id);
        expectWithMessage("Volume group contexts group %s for %s", carVolumeGroup.getName(),
                configInfo).that(groupContexts).containsExactlyElementsIn(
                        getContextForVolumeGroupConfig(groupConfig, carAudioContextMap));
        String groupInfo = "Volume group " + carVolumeGroup.getName() + " for " + configInfo;
        validateMinMaxActivation(groupInfo, carVolumeGroup.getCarActivationVolumeConfig(),
                groupConfig.activationConfiguration);
        for (int c = 0; c < groupContexts.size(); c++) {
            int context = groupContexts.get(c);
            expectWithMessage("Volume group context %s for %s", context, groupInfo)
                    .that(contexts.add(context)).isTrue();
        }
    }

    private void validateMinMaxActivation(String groupInfo, CarActivationVolumeConfig config,
            VolumeActivationConfiguration activationConfiguration) {
        int activation = ACTIVATION_VOLUME_INVOCATION_TYPE;
        int min = ACTIVATION_VOLUME_PERCENTAGE_MIN;
        int max = ACTIVATION_VOLUME_PERCENTAGE_MAX;
        if (activationConfiguration != null
                && activationConfiguration.volumeActivationEntries != null
                && !activationConfiguration.volumeActivationEntries.isEmpty()) {
            var entry = activationConfiguration.volumeActivationEntries.getFirst();
            activation = getActivationFromHalActivation(entry.type);
            min = entry.minActivationVolumePercentage;
            max = entry.maxActivationVolumePercentage;
        }
        expectWithMessage("Volume group activation for %s", groupInfo)
                .that(config.getInvocationType()).isEqualTo(activation);
        expectWithMessage("Volume group min activation value for %s", groupInfo)
                .that(config.getMinActivationVolumePercentage()).isEqualTo(min);
        expectWithMessage("Volume group max activation value for %s", groupInfo)
                .that(config.getMaxActivationVolumePercentage()).isEqualTo(max);
    }

    private int getActivationFromHalActivation(int halActivationType) {
        return switch (halActivationType) {
            case ON_PLAYBACK_CHANGED -> ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED;
            case ON_SOURCE_CHANGED -> ACTIVATION_VOLUME_ON_SOURCE_CHANGED;
            case ON_BOOT -> ACTIVATION_VOLUME_ON_BOOT;
            default -> ACTIVATION_VOLUME_INVOCATION_TYPE;
        };
    }

    private AudioControlZoneConverter setupAudioZoneConverter() {
        return new AudioControlZoneConverter(mAudioManager, mCarAudioSettings, mServiceLog,
                mUseFadeManagerConfiguration);
    }
}
