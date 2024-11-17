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

import static android.hardware.automotive.audiocontrol.VolumeInvocationType.ON_BOOT;
import static android.hardware.automotive.audiocontrol.VolumeInvocationType.ON_PLAYBACK_CHANGED;
import static android.hardware.automotive.audiocontrol.VolumeInvocationType.ON_SOURCE_CHANGED;
import static android.media.audio.common.AudioUsage.ALARM;
import static android.media.audio.common.AudioUsage.ANNOUNCEMENT;
import static android.media.audio.common.AudioUsage.ASSISTANCE_ACCESSIBILITY;
import static android.media.audio.common.AudioUsage.ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.audio.common.AudioUsage.ASSISTANCE_SONIFICATION;
import static android.media.audio.common.AudioUsage.ASSISTANT;
import static android.media.audio.common.AudioUsage.CALL_ASSISTANT;
import static android.media.audio.common.AudioUsage.EMERGENCY;
import static android.media.audio.common.AudioUsage.GAME;
import static android.media.audio.common.AudioUsage.MEDIA;
import static android.media.audio.common.AudioUsage.NOTIFICATION;
import static android.media.audio.common.AudioUsage.NOTIFICATION_EVENT;
import static android.media.audio.common.AudioUsage.NOTIFICATION_TELEPHONY_RINGTONE;
import static android.media.audio.common.AudioUsage.SAFETY;
import static android.media.audio.common.AudioUsage.UNKNOWN;
import static android.media.audio.common.AudioUsage.VEHICLE_STATUS;
import static android.media.audio.common.AudioUsage.VOICE_COMMUNICATION;
import static android.media.audio.common.AudioUsage.VOICE_COMMUNICATION_SIGNALLING;

import static com.android.car.audio.AudioControlZoneConverterUtils.convertAudioContextEntry;
import static com.android.car.audio.AudioControlZoneConverterUtils.convertAudioDevicePort;
import static com.android.car.audio.AudioControlZoneConverterUtils.convertCarAudioContext;
import static com.android.car.audio.AudioControlZoneConverterUtils.convertVolumeGroupConfig;
import static com.android.car.audio.AudioControlZoneConverterUtils.verifyVolumeGroupName;
import static com.android.car.audio.CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT;
import static com.android.car.audio.CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED;
import static com.android.car.audio.CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_SOURCE_CHANGED;
import static com.android.car.audio.CarAudioTestUtils.TEST_CREATED_CAR_AUDIO_CONTEXT;
import static com.android.car.audio.CarAudioTestUtils.createAudioPort;
import static com.android.car.audio.CarAudioTestUtils.createAudioPortDeviceExt;
import static com.android.car.audio.CarAudioUtils.DEFAULT_ACTIVATION_VOLUME;
import static com.android.car.audio.CoreAudioRoutingUtils.createCoreAudioContext;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.hardware.automotive.audiocontrol.AudioDeviceConfiguration;
import android.hardware.automotive.audiocontrol.AudioZoneContext;
import android.hardware.automotive.audiocontrol.AudioZoneContextInfo;
import android.hardware.automotive.audiocontrol.DeviceToContextEntry;
import android.hardware.automotive.audiocontrol.RoutingDeviceConfiguration;
import android.hardware.automotive.audiocontrol.VolumeActivationConfiguration;
import android.hardware.automotive.audiocontrol.VolumeActivationConfigurationEntry;
import android.hardware.automotive.audiocontrol.VolumeGroupConfig;
import android.media.AudioDeviceInfo;
import android.media.MediaRecorder;
import android.media.audio.common.AudioAttributes;
import android.media.audio.common.AudioContentType;
import android.media.audio.common.AudioDeviceDescription;
import android.media.audio.common.AudioDeviceType;
import android.media.audio.common.AudioFlag;
import android.media.audio.common.AudioGain;
import android.media.audio.common.AudioGainMode;
import android.media.audio.common.AudioPort;
import android.media.audio.common.AudioPortDeviceExt;
import android.media.audio.common.AudioPortExt;
import android.media.audio.common.AudioSource;
import android.media.audio.common.AudioUsage;
import android.util.ArrayMap;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


@RunWith(AndroidJUnit4.class)
public class AudioControlZoneConverterUtilsUnitTest extends AbstractExtendedMockitoTestCase {

    private static final String TEST_ACTIVATION = "Test Activation";
    private static final int TEST_MIN_ACTIVATION = 10;
    private static final int TEST_MAX_ACTIVATION = 90;
    private static final CarActivationVolumeConfig TEST_ON_PLAY_ACTIVATION_CONFIG =
            new CarActivationVolumeConfig(ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED,
                    TEST_MIN_ACTIVATION, TEST_MAX_ACTIVATION);
    private static final CarActivationVolumeConfig TEST_ON_SOURCE_ACTIVATION_CONFIG =
            new CarActivationVolumeConfig(ACTIVATION_VOLUME_ON_SOURCE_CHANGED,
                    TEST_MIN_ACTIVATION, TEST_MAX_ACTIVATION);
    private static final CarActivationVolumeConfig TEST_ON_BOOT_ACTIVATION_CONFIG =
            new CarActivationVolumeConfig(ACTIVATION_VOLUME_ON_BOOT,
                    TEST_MIN_ACTIVATION, TEST_MAX_ACTIVATION);
    private static final int INVALID_MIN_ACTIVATION = -1;
    private static final int INVALID_MAX_ACTIVATION = 101;

    private static final int TEST_FLAGS = AudioFlag.AUDIBILITY_ENFORCED;
    private static final String[] TEST_TAGS =  {"OEM_NAV", "OEM_ASSISTANT"};

    private static final int PORT_ID_MEDIA = 10;
    private static final String PORT_MEDIA_NAME = "media_bus";
    private static final String PORT_MEDIA_ADDRESS = "MEDIA_BUS";
    private static final CarAudioDeviceInfo MEDIA_BUS_DEVICE = mock(CarAudioDeviceInfo.class);

    private static final int PORT_ID_NAV = 11;
    private static final String PORT_NAV_NAME = "nav_bus";
    private static final String PORT_NAV_ADDRESS = "NAV_BUS";
    private static final CarAudioDeviceInfo NAV_BUS_DEVICE = mock(CarAudioDeviceInfo.class);

    private static final int PORT_ID_VOICE = 12;
    private static final String PORT_VOICE_NAME = "voice_bus";
    private static final String PORT_VOICE_ADDRESS = "VOICE_BUS";
    private static final CarAudioDeviceInfo VOICE_BUS_DEVICE = mock(CarAudioDeviceInfo.class);

    private static final AudioGain[] GAINS = new AudioGain[] {
            new AudioGain() {{
                mode = AudioGainMode.JOINT;
                minValue = 0;
                maxValue = 100;
                defaultValue = 50;
                stepValue = 2;
            }}
    };

    private static final String MUSIC_CONTEXT = "MUSIC";
    private static final String NAVIGATION_CONTEXT = "NAVIGATION";
    private static final String VOICE_COMMAND_CONTEXT = "VOICE_COMMAND";
    private static final String CALL_RING_CONTEXT = "CALL_RING";
    private static final String INVALID_CONTEXT_NAME = "no context name";
    private static final int MUSIC_CONTEXT_ID = 1;
    private static final int NAVIGATION_CONTEXT_ID = 2;
    private static final int VOICE_COMMAND_CONTEXT_ID = 3;
    private static final int CALL_RING_CONTEXT_ID = 4;

    private static final int MEDIA_VOLUME_GROUP_ID = 1;
    private static final String MEDIA_VOLUME_GROUP_NAME = "media_volume_group";
    private static final int NAV_VOLUME_GROUP_ID = 2;
    private static final String NAV_VOLUME_GROUP_NAME = "nav_volume_group";

    private ArrayMap<String, CarAudioDeviceInfo> mAddressToCarAudioDeviceInfo;

    @Override
    protected void clearInlineMocks(String when) {
        super.clearInlineMocks(when);
    }

    @Mock
    private AudioManagerWrapper mAudioManager;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(CoreAudioHelper.class)
                .spyStatic(AudioManagerWrapper.class);
    }

    @Before
    public void setUp() {
        doReturn(CoreAudioRoutingUtils.getProductStrategies())
                .when(AudioManagerWrapper::getAudioProductStrategies);
        mAddressToCarAudioDeviceInfo = new ArrayMap<>();
        mAddressToCarAudioDeviceInfo.put(PORT_MEDIA_ADDRESS, MEDIA_BUS_DEVICE);
        mAddressToCarAudioDeviceInfo.put(PORT_NAV_ADDRESS, NAV_BUS_DEVICE);
        mAddressToCarAudioDeviceInfo.put(PORT_VOICE_ADDRESS, VOICE_BUS_DEVICE);
    }

    @Test
    public void convertVolumeActivationConfig_withOnPlaybackActivation() {
        VolumeActivationConfiguration configuration =
                createVolumeActivationConfiguration(TEST_ACTIVATION, TEST_MIN_ACTIVATION,
                        TEST_MAX_ACTIVATION, ON_PLAYBACK_CHANGED);

        CarActivationVolumeConfig converted =
                AudioControlZoneConverterUtils.convertVolumeActivationConfig(configuration);

        expectWithMessage("Converted volume activation configuration with on playback activation")
                .that(converted).isEqualTo(TEST_ON_PLAY_ACTIVATION_CONFIG);
    }

    @Test
    public void convertVolumeActivationConfig_withOnSourceActivation() {
        VolumeActivationConfiguration configuration =
                createVolumeActivationConfiguration(TEST_ACTIVATION, TEST_MIN_ACTIVATION,
                        TEST_MAX_ACTIVATION, ON_SOURCE_CHANGED);

        CarActivationVolumeConfig converted =
                AudioControlZoneConverterUtils.convertVolumeActivationConfig(configuration);

        expectWithMessage("Converted volume activation configuration with on source activation")
                .that(converted).isEqualTo(TEST_ON_SOURCE_ACTIVATION_CONFIG);
    }

    @Test
    public void convertVolumeActivationConfig_withOnBootActivation() {
        VolumeActivationConfiguration configuration =
                createVolumeActivationConfiguration(TEST_ACTIVATION, TEST_MIN_ACTIVATION,
                        TEST_MAX_ACTIVATION, ON_BOOT);

        CarActivationVolumeConfig converted =
                AudioControlZoneConverterUtils.convertVolumeActivationConfig(configuration);

        expectWithMessage("Converted volume activation configuration with on boot activation")
                .that(converted).isEqualTo(TEST_ON_BOOT_ACTIVATION_CONFIG);
    }
    @Test
    public void convertVolumeActivationConfig_withInvalidActivation() {
        VolumeActivationConfiguration configuration =
                createVolumeActivationConfiguration(TEST_ACTIVATION, TEST_MIN_ACTIVATION,
                        TEST_MAX_ACTIVATION, /* activation= */ -1);

        CarActivationVolumeConfig converted =
                AudioControlZoneConverterUtils.convertVolumeActivationConfig(configuration);

        expectWithMessage("Converted volume activation configuration with on boot activation")
                .that(converted).isEqualTo(DEFAULT_ACTIVATION_VOLUME);
    }

    @Test
    public void convertVolumeActivationConfig_withEmptyActivationEntry() {
        VolumeActivationConfiguration configuration = new VolumeActivationConfiguration();
        configuration.name = TEST_ACTIVATION;
        configuration.volumeActivationEntries = List.of();

        CarActivationVolumeConfig converted =
                AudioControlZoneConverterUtils.convertVolumeActivationConfig(configuration);

        expectWithMessage("Converted volume activation configuration with on playback activation")
                .that(converted).isEqualTo(DEFAULT_ACTIVATION_VOLUME);
    }

    @Test
    public void convertVolumeActivationConfig_withInvalidMin() {
        VolumeActivationConfiguration configuration =
                createVolumeActivationConfiguration(TEST_ACTIVATION, INVALID_MIN_ACTIVATION,
                        TEST_MAX_ACTIVATION, ON_PLAYBACK_CHANGED);

        CarActivationVolumeConfig converted =
                AudioControlZoneConverterUtils.convertVolumeActivationConfig(configuration);

        expectWithMessage("Converted volume activation configuration with invalid min")
                .that(converted).isEqualTo(DEFAULT_ACTIVATION_VOLUME);
    }

    @Test
    public void convertVolumeActivationConfig_withInvalidMax() {
        VolumeActivationConfiguration configuration =
                createVolumeActivationConfiguration(TEST_ACTIVATION, TEST_MIN_ACTIVATION,
                        INVALID_MAX_ACTIVATION, ON_PLAYBACK_CHANGED);

        CarActivationVolumeConfig converted =
                AudioControlZoneConverterUtils.convertVolumeActivationConfig(configuration);

        expectWithMessage("Converted volume activation configuration with invalid max")
                .that(converted).isEqualTo(DEFAULT_ACTIVATION_VOLUME);
    }

    @Test
    public void convertVolumeActivationConfig_withInvalidMinMaxCombination() {
        VolumeActivationConfiguration configuration =
                createVolumeActivationConfiguration(TEST_ACTIVATION, TEST_MAX_ACTIVATION,
                        TEST_MIN_ACTIVATION, ON_PLAYBACK_CHANGED);

        CarActivationVolumeConfig converted =
                AudioControlZoneConverterUtils.convertVolumeActivationConfig(configuration);

        expectWithMessage("Converted volume activation configuration with invalid min and "
                + "max combination").that(converted).isEqualTo(DEFAULT_ACTIVATION_VOLUME);
    }

    @Test
    public void convertAudioAttributes_forNonSystemUsage() {
        AudioAttributes attributes = createHALAudioAttribute(MEDIA);
        android.media.AudioAttributes results =
                createMediaAudioAttributes(android.media.AudioAttributes.USAGE_MEDIA);

        expectWithMessage("Converted media audio attribute")
                .that(AudioControlZoneConverterUtils.convertAudioAttributes(attributes))
                .isEqualTo(results);
    }

    @Test
    public void convertAudioAttributes_forSystemUsage() {
        AudioAttributes attributes = createHALAudioAttribute(AudioUsage.EMERGENCY);
        android.media.AudioAttributes results =
                createMediaAudioAttributes(android.media.AudioAttributes.USAGE_EMERGENCY);

        expectWithMessage("Converted media audio attribute")
                .that(AudioControlZoneConverterUtils.convertAudioAttributes(attributes))
                .isEqualTo(results);
    }

    @Test
    public void convertCarAudioContext_withDynamicAudioRouting() {
        AudioZoneContext context = createHALAudioContext();
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.routingConfig = RoutingDeviceConfiguration.DYNAMIC_AUDIO_ROUTING;

        expectWithMessage("Converted audio context with dynamic routing")
                .that(convertCarAudioContext(context, configuration))
                .isEqualTo(TEST_CREATED_CAR_AUDIO_CONTEXT);
    }

    @Test
    public void convertCarAudioContext_withConfigurableAudioRouting() {
        CarAudioContext corerRoutingContext = new CarAudioContext(
                CoreAudioRoutingUtils.getCarAudioContextInfos(), /* useCoreAudioRouting= */ true);
        AudioZoneContext context = createCoreAudioContext();
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.routingConfig = RoutingDeviceConfiguration.CONFIGURABLE_AUDIO_ENGINE_ROUTING;

        expectWithMessage("Converted audio context with core routing")
                .that(convertCarAudioContext(context, configuration))
                .isEqualTo(corerRoutingContext);
    }

    @Test
    public void convertAudioDevicePort_withNullPort() {
        expectWithMessage("Converted car audio device with null port")
                .that(convertAudioDevicePort(/* port= */ null, mAudioManager,
                        mAddressToCarAudioDeviceInfo)).isNull();
    }

    @Test
    public void convertAudioDevicePort_withNullExternalDevice() {
        AudioPort audioPort = new AudioPort();
        audioPort.ext = null;

        expectWithMessage("Converted car audio device with null external device")
                .that(convertAudioDevicePort(audioPort, mAudioManager,
                        mAddressToCarAudioDeviceInfo)).isNull();
    }

    @Test
    public void convertAudioDevicePort_withoutExternalDevice() {
        AudioPort audioPort = new AudioPort();
        audioPort.ext = new AudioPortExt();

        expectWithMessage("Converted car audio device without external device tag")
                .that(convertAudioDevicePort(audioPort, mAudioManager,
                        mAddressToCarAudioDeviceInfo)).isNull();
    }

    @Test
    public void convertAudioDevicePort_withoutAudioDevice() {
        AudioPort audioPort = createAudioPort(PORT_ID_MEDIA, PORT_MEDIA_NAME, GAINS,
                new AudioPortDeviceExt());

        expectWithMessage("Converted car audio device with null audio device")
                .that(convertAudioDevicePort(audioPort, mAudioManager,
                        mAddressToCarAudioDeviceInfo)).isNull();
    }

    @Test
    public void convertAudioDevicePort_withBusDevice() {
        AudioPortDeviceExt busPortDevice =
                createAudioPortDeviceExt(AudioDeviceType.OUT_BUS, "", PORT_MEDIA_ADDRESS);
        AudioPort audioPort = createAudioPort(PORT_ID_MEDIA, PORT_MEDIA_NAME, GAINS, busPortDevice);

        expectWithMessage("Converted car audio device with valid bus device")
                .that(convertAudioDevicePort(audioPort, mAudioManager,
                        mAddressToCarAudioDeviceInfo)).isEqualTo(MEDIA_BUS_DEVICE);
    }

    @Test
    public void convertAudioDevicePort_withBTDevice() {
        AudioPortDeviceExt btPortDevice =
                createAudioPortDeviceExt(AudioDeviceType.OUT_SPEAKER,
                        AudioDeviceDescription.CONNECTION_BT_A2DP, "");
        AudioPort audioPort = createAudioPort(PORT_ID_MEDIA, PORT_MEDIA_NAME, GAINS, btPortDevice);

        CarAudioDeviceInfo btAudioDevice = convertAudioDevicePort(audioPort, mAudioManager,
                mAddressToCarAudioDeviceInfo);

        expectWithMessage("Device address of converted car audio device with valid BT device")
                .that(btAudioDevice.getAddress()).isEmpty();
        expectWithMessage("Device type of converted car audio device with valid BT device")
                .that(btAudioDevice.getType()).isEqualTo(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
    }

    @Test
    public void verifyVolumeGroupName_withGroupNameAndUseCoreVolume() {
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.useCoreAudioVolume = true;

        expectWithMessage("Verified group name  configuration and use core volume")
                .that(verifyVolumeGroupName(/* groupName= */ "oem_Volume", configuration)).isTrue();
    }

    @Test
    public void verifyVolumeGroupName_withNullGroupNameAndUseCoreVolume() {
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.useCoreAudioVolume = true;

        expectWithMessage("Verified group name with null configuration and use core volume")
                .that(verifyVolumeGroupName(/* groupName= */ null, configuration)).isFalse();
    }

    @Test
    public void verifyVolumeGroupName_withEmptyGroupNameAndUseCoreVolume() {
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.useCoreAudioVolume = true;

        expectWithMessage("Verified group name with empty configuration and use core volume")
                .that(verifyVolumeGroupName(/* groupName= */ "", configuration)).isFalse();
    }

    @Test
    public void verifyVolumeGroupName_withNullGroupNameAndWithoutUseCoreVolume() {
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.useCoreAudioVolume = false;

        expectWithMessage("Verified group name with null configuration")
                .that(verifyVolumeGroupName(/* groupName= */ null, configuration)).isTrue();
    }

    @Test
    public void verifyVolumeGroupName_withEmptyGroupNameAndWithoutUseCoreVolume() {
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.useCoreAudioVolume = false;

        expectWithMessage("Verified group name with null configuration")
                .that(verifyVolumeGroupName(/* groupName= */ "", configuration)).isTrue();
    }

    @Test
    public void convertAudioContextEntry_withNullVolumeFactory() {
        CarVolumeGroupFactory factory = null;
        var entry = createDeviceToContextEntry();
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        expectWithMessage("Converted context entry with null volume factory")
                .that(convertAudioContextEntry(factory, entry, MEDIA_BUS_DEVICE, contextNameToId))
                .isFalse();
    }

    @Test
    public void convertAudioContextEntry_withNullContextEntry() {
        var factory = mock(CarVolumeGroupFactory.class);
        DeviceToContextEntry entry = null;
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        expectWithMessage("Converted context entry with device to context entry")
                .that(convertAudioContextEntry(factory, entry, MEDIA_BUS_DEVICE, contextNameToId))
                .isFalse();
    }

    @Test
    public void convertAudioContextEntry_withNullContextNameMap() {
        var factory = mock(CarVolumeGroupFactory.class);
        var entry = createDeviceToContextEntry();
        ArrayMap<String, Integer> contextNameToId = null;

        expectWithMessage("Converted context entry with null context name map")
                .that(convertAudioContextEntry(factory, entry, MEDIA_BUS_DEVICE, contextNameToId))
                .isFalse();
    }

    @Test
    public void convertAudioContextEntry_withNullCarAudioDevice() {
        var factory = mock(CarVolumeGroupFactory.class);
        var entry = createDeviceToContextEntry();
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        expectWithMessage("Converted context entry with null car audio device")
                .that(convertAudioContextEntry(factory, entry, /* info= */ null, contextNameToId))
                .isFalse();
    }

    @Test
    public void convertAudioContextEntry_withInvalidContextInList() {
        var factory = mock(CarVolumeGroupFactory.class);
        var entry = createDeviceToContextEntry();
        entry.contextNames.add(INVALID_CONTEXT_NAME);
        ArrayMap<String, Integer> contextNameToId =
                createContextNameToIDMap(createHALAudioContext());

        expectWithMessage("Converted context entry with invalid context name")
                .that(convertAudioContextEntry(factory, entry, MEDIA_BUS_DEVICE, contextNameToId))
                .isFalse();
    }

    @Test
    public void convertAudioContextEntry_withEmptyContextInList() {
        var factory = mock(CarVolumeGroupFactory.class);
        var entry = createDeviceToContextEntry();
        entry.contextNames.add("");
        ArrayMap<String, Integer> contextNameToId =
                createContextNameToIDMap(createHALAudioContext());

        expectWithMessage("Converted context entry with empty context name")
                .that(convertAudioContextEntry(factory, entry, MEDIA_BUS_DEVICE, contextNameToId))
                .isFalse();
    }

    @Test
    public void convertAudioContextEntry_withEmptyContextList() {
        var factory = mock(CarVolumeGroupFactory.class);
        var port = createAudioPort(PORT_ID_MEDIA, PORT_MEDIA_NAME, GAINS, new AudioPortDeviceExt());
        ArrayList<String> contexts = new ArrayList<>(0);
        var entry = createDeviceToContextEntry(port, contexts);
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        expectWithMessage("Converted context entry with empty context list")
                .that(convertAudioContextEntry(factory, entry, MEDIA_BUS_DEVICE, contextNameToId))
                .isFalse();
    }

    @Test
    public void convertAudioContextEntry_withValidGroup() {
        var factory = mock(CarVolumeGroupFactory.class);
        var entry = createDeviceToContextEntry();
        ArrayMap<String, Integer> contextNameToId =
                createContextNameToIDMap(createHALAudioContext());

        expectWithMessage("Converted context entry with valid contexts")
                .that(convertAudioContextEntry(factory, entry, MEDIA_BUS_DEVICE, contextNameToId))
                .isTrue();
        ArgumentCaptor<Integer> contextIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(factory, times(4))
                .setDeviceInfoForContext(contextIdCaptor.capture(), eq(MEDIA_BUS_DEVICE));
        expectWithMessage("Audio context IDs")
                .that(contextIdCaptor.getAllValues())
                .containsExactly(MUSIC_CONTEXT_ID, NAVIGATION_CONTEXT_ID, VOICE_COMMAND_CONTEXT_ID,
                        CALL_RING_CONTEXT_ID);
    }

    @Test
    public void convertVolumeGroupConfig_withNullVolumeGroupFactory() {
        CarVolumeGroupFactory factory = null;
        var volumeGroupConfig = createMediaVolumeGroupConfiguration();
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        var thrown = assertThrows(NullPointerException.class, () ->
                convertVolumeGroupConfig(factory, volumeGroupConfig, mAudioManager,
                        mAddressToCarAudioDeviceInfo, contextNameToId));

        expectWithMessage("Convert volume group config exception for case when volume group"
                + " factory is null").that(thrown).hasMessageThat()
                .contains("Volume group factory");
    }

    @Test
    public void convertVolumeGroupConfig_withNullVolumeGroupConfig() {
        var factory = mock(CarVolumeGroupFactory.class);
        VolumeGroupConfig volumeGroupConfig = null;
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        var thrown = assertThrows(NullPointerException.class, () ->
                convertVolumeGroupConfig(factory, volumeGroupConfig, mAudioManager,
                        mAddressToCarAudioDeviceInfo, contextNameToId));

        expectWithMessage("Convert volume group config exception for case when volume group"
                + " config is null").that(thrown).hasMessageThat().contains("Volume group config");
    }

    @Test
    public void convertVolumeGroupConfig_withNullAudioManager() {
        var factory = mock(CarVolumeGroupFactory.class);
        var volumeGroupConfig = createMediaVolumeGroupConfiguration();
        AudioManagerWrapper audioManager = null;
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        var thrown = assertThrows(NullPointerException.class, () ->
                convertVolumeGroupConfig(factory, volumeGroupConfig, audioManager,
                        mAddressToCarAudioDeviceInfo, contextNameToId));

        expectWithMessage("Convert volume group config exception for case when audio manager"
                + " config is null").that(thrown).hasMessageThat().contains("Audio manager");
    }

    @Test
    public void convertVolumeGroupConfig_withNullAudioDeviceMap() {
        var factory = mock(CarVolumeGroupFactory.class);
        var volumeGroupConfig = createMediaVolumeGroupConfiguration();
        ArrayMap<String, CarAudioDeviceInfo> audioDeviceInfoMap = null;
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        var thrown = assertThrows(NullPointerException.class, () ->
                convertVolumeGroupConfig(factory, volumeGroupConfig, mAudioManager,
                        audioDeviceInfoMap, contextNameToId));

        expectWithMessage("Convert volume group config exception for case when car audio"
                + " map is null").that(thrown).hasMessageThat()
                .contains("Address to car audio device info map");
    }

    @Test
    public void convertVolumeGroupConfig_withNullContextIDMap() {
        var factory = mock(CarVolumeGroupFactory.class);
        var volumeGroupConfig = createMediaVolumeGroupConfiguration();
        ArrayMap<String, Integer> contextNameToId = null;

        var thrown = assertThrows(NullPointerException.class, () ->
                convertVolumeGroupConfig(factory, volumeGroupConfig, mAudioManager,
                        mAddressToCarAudioDeviceInfo, contextNameToId));

        expectWithMessage("Convert volume group config exception for case when context id map")
                .that(thrown).hasMessageThat().contains("Context name to id map");
    }

    @Test
    public void convertVolumeGroupConfig_withMediaGroup() {
        var factory = mock(CarVolumeGroupFactory.class);
        var volumeGroupConfig = createMediaVolumeGroupConfiguration();
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        var message = convertVolumeGroupConfig(factory, volumeGroupConfig, mAudioManager,
                mAddressToCarAudioDeviceInfo, contextNameToId);

        expectWithMessage("Message for converted media volume group config").that(message)
                .isEmpty();
        ArgumentCaptor<Integer> contextIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(factory, times(1))
                .setDeviceInfoForContext(contextIdCaptor.capture(), eq(MEDIA_BUS_DEVICE));
        expectWithMessage("Media audio context ID").that(contextIdCaptor.getAllValues())
                .containsExactly(MUSIC_CONTEXT_ID);
    }

    @Test
    public void convertVolumeGroupConfig_withNavAndVoiceGroup() {
        var factory = mock(CarVolumeGroupFactory.class);
        var volumeGroupConfig = createNavAndVoiceVolumeGroupConfiguration();
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        var message = convertVolumeGroupConfig(factory, volumeGroupConfig, mAudioManager,
                mAddressToCarAudioDeviceInfo, contextNameToId);

        expectWithMessage("Message for converted nav and voice volume group config")
                .that(message).isEmpty();
        ArgumentCaptor<Integer> contextIdCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<CarAudioDeviceInfo> deviceCaptor =
                ArgumentCaptor.forClass(CarAudioDeviceInfo.class);
        verify(factory, times(2))
                .setDeviceInfoForContext(contextIdCaptor.capture(), deviceCaptor.capture());
        expectWithMessage("Nav and voice audio context IDs")
                .that(contextIdCaptor.getAllValues())
                .containsExactly(NAVIGATION_CONTEXT_ID, VOICE_COMMAND_CONTEXT_ID);
        expectWithMessage("Nav and voice audio devices")
                .that(deviceCaptor.getAllValues())
                .containsExactly(NAV_BUS_DEVICE, VOICE_BUS_DEVICE);
    }

    @Test
    public void convertVolumeGroupConfig_withEmptyRoutesGroup() {
        var factory = mock(CarVolumeGroupFactory.class);
        var volumeGroupConfig = createVolumeGroupConfig(NAV_VOLUME_GROUP_ID, NAV_VOLUME_GROUP_NAME,
                Collections.EMPTY_LIST);
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        var message = convertVolumeGroupConfig(factory, volumeGroupConfig, mAudioManager,
                mAddressToCarAudioDeviceInfo, contextNameToId);

        expectWithMessage("Message for converted empty routes group")
                .that(message).contains("empty car audio routes");
    }

    @Test
    public void convertVolumeGroupConfig_withNullDeviceInGroupRoutes() {
        var factory = mock(CarVolumeGroupFactory.class);
        var contextEntries = new ArrayList<DeviceToContextEntry>(1);
        contextEntries.add(createDeviceToContextEntry(/* audioPort= */ null,
                List.of(MUSIC_CONTEXT)));
        var volumeGroupConfig = createVolumeGroupConfig(MEDIA_VOLUME_GROUP_ID,
                MEDIA_VOLUME_GROUP_NAME, contextEntries);
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        var message = convertVolumeGroupConfig(factory, volumeGroupConfig, mAudioManager,
                mAddressToCarAudioDeviceInfo, contextNameToId);

        expectWithMessage("Message for converted with null device in routes")
                .that(message).contains("could not find device info for device");
    }

    @Test
    public void convertVolumeGroupConfig_withEmptyContextListInGroupRoutes() {
        var factory = mock(CarVolumeGroupFactory.class);
        var contextEntries = new ArrayList<DeviceToContextEntry>(1);
        var audioPort = createBusAudioPort(PORT_MEDIA_ADDRESS, PORT_ID_MEDIA, PORT_MEDIA_NAME);
        contextEntries.add(createDeviceToContextEntry(audioPort, List.of()));
        var volumeGroupConfig = createVolumeGroupConfig(MEDIA_VOLUME_GROUP_ID,
                MEDIA_VOLUME_GROUP_NAME, contextEntries);
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        var message = convertVolumeGroupConfig(factory, volumeGroupConfig, mAudioManager,
                mAddressToCarAudioDeviceInfo, contextNameToId);

        expectWithMessage("Message for converted empty context in routes")
                .that(message).contains("could not parse audio context entry");
    }

    private VolumeGroupConfig createMediaVolumeGroupConfiguration() {
        var contextEntries = new ArrayList<DeviceToContextEntry>(1);
        var audioPort = createBusAudioPort(PORT_MEDIA_ADDRESS, PORT_ID_MEDIA, PORT_MEDIA_NAME);
        contextEntries.add(createDeviceToContextEntry(audioPort, List.of(MUSIC_CONTEXT)));
        return createVolumeGroupConfig(MEDIA_VOLUME_GROUP_ID, MEDIA_VOLUME_GROUP_NAME,
                contextEntries);
    }

    private VolumeGroupConfig createNavAndVoiceVolumeGroupConfiguration() {
        var contextEntries = new ArrayList<DeviceToContextEntry>(2);
        var navAudioPort = createBusAudioPort(PORT_NAV_ADDRESS, PORT_ID_NAV, PORT_NAV_NAME);
        contextEntries.add(createDeviceToContextEntry(navAudioPort, List.of(NAVIGATION_CONTEXT)));

        var voiceAudioPort = createBusAudioPort(PORT_VOICE_ADDRESS, PORT_ID_VOICE, PORT_VOICE_NAME);
        contextEntries.add(createDeviceToContextEntry(voiceAudioPort,
                List.of(VOICE_COMMAND_CONTEXT)));
        return createVolumeGroupConfig(NAV_VOLUME_GROUP_ID, NAV_VOLUME_GROUP_NAME, contextEntries);
    }

    @NonNull
    private static AudioPort createBusAudioPort(String portAddress, int portId, String portName) {
        var busPortDevice = createAudioPortDeviceExt(AudioDeviceType.OUT_BUS, /* connection= */ "",
                portAddress);
        return createAudioPort(portId, portName, GAINS, busPortDevice);
    }

    private VolumeGroupConfig createVolumeGroupConfig(int groupId, String groupName,
            List<DeviceToContextEntry> contextEntries) {
        var volumeGroupConfig = new VolumeGroupConfig();
        volumeGroupConfig.id = groupId;
        volumeGroupConfig.name = groupName;
        volumeGroupConfig.activationConfiguration =
                createVolumeActivationConfiguration(TEST_ACTIVATION, TEST_MIN_ACTIVATION,
                        TEST_MAX_ACTIVATION, ON_BOOT);
        volumeGroupConfig.carAudioRoutes = contextEntries;
        return volumeGroupConfig;
    }

    private DeviceToContextEntry createDeviceToContextEntry(AudioPort audioPort,
            List<String> contextList) {
        var entry = new DeviceToContextEntry();
        entry.device = audioPort;
        entry.contextNames = contextList;
        return entry;
    }

    private DeviceToContextEntry createDeviceToContextEntry() {
        var port = createAudioPort(PORT_ID_MEDIA, PORT_MEDIA_NAME, GAINS,
                new AudioPortDeviceExt());
        ArrayList<String> contexts = new ArrayList<>(4);
        contexts.add(MUSIC_CONTEXT);
        contexts.add(NAVIGATION_CONTEXT);
        contexts.add(VOICE_COMMAND_CONTEXT);
        contexts.add(CALL_RING_CONTEXT);
        return createDeviceToContextEntry(port, contexts);
    }

    private ArrayMap<String, Integer> createContextNameToIDMap(AudioZoneContext context) {
        var contextNameToId = new ArrayMap<String, Integer>(context.audioContextInfos.size());
        for (int c = 0; c < context.audioContextInfos.size(); c++) {
            AudioZoneContextInfo info = context.audioContextInfos.get(c);
            contextNameToId.put(info.name, info.id);
        }
        return contextNameToId;
    }

    private AudioZoneContext createHALAudioContext() {
        AudioZoneContext context = new AudioZoneContext();
        context.audioContextInfos = new ArrayList<>();
        context.audioContextInfos.add(createAudioZoneContextInfo(new int[]{UNKNOWN, GAME, MEDIA},
                MUSIC_CONTEXT, MUSIC_CONTEXT_ID));
        context.audioContextInfos.add(createAudioZoneContextInfo(
                new int[]{ASSISTANCE_NAVIGATION_GUIDANCE}, NAVIGATION_CONTEXT,
                NAVIGATION_CONTEXT_ID));
        context.audioContextInfos.add(createAudioZoneContextInfo(
                new int[]{ASSISTANCE_ACCESSIBILITY, ASSISTANT}, VOICE_COMMAND_CONTEXT,
                VOICE_COMMAND_CONTEXT_ID));
        context.audioContextInfos.add(createAudioZoneContextInfo(
                new int[]{NOTIFICATION_TELEPHONY_RINGTONE}, CALL_RING_CONTEXT,
                CALL_RING_CONTEXT_ID));
        context.audioContextInfos.add(createAudioZoneContextInfo(
                new int[]{VOICE_COMMUNICATION, CALL_ASSISTANT, VOICE_COMMUNICATION_SIGNALLING},
                "CALL", 5));
        context.audioContextInfos.add(createAudioZoneContextInfo(new int[]{ALARM}, "ALARM", 6));
        context.audioContextInfos.add(createAudioZoneContextInfo(
                new int[]{NOTIFICATION, NOTIFICATION_EVENT}, "NOTIFICATION", 7));
        context.audioContextInfos.add(createAudioZoneContextInfo(
                new int[]{ASSISTANCE_SONIFICATION}, "SYSTEM_SOUND", 8));
        context.audioContextInfos.add(createAudioZoneContextInfo(
                new int[]{EMERGENCY}, "EMERGENCY", 9));
        context.audioContextInfos.add(createAudioZoneContextInfo(
                new int[]{SAFETY}, "SAFETY", 10));
        context.audioContextInfos.add(createAudioZoneContextInfo(
                new int[]{VEHICLE_STATUS}, "VEHICLE_STATUS", 11));
        context.audioContextInfos.add(createAudioZoneContextInfo(
                new int[]{ANNOUNCEMENT}, "ANNOUNCEMENT", 12));
        return context;
    }

    private AudioZoneContextInfo createAudioZoneContextInfo(int[] usages, String name, int id) {
        AudioZoneContextInfo info = new AudioZoneContextInfo();
        info.id = id;
        info.name = name;
        info.audioAttributes = new ArrayList<>(usages.length);
        for (int usage : usages) {
            AudioAttributes attributes = new AudioAttributes();
            attributes.usage = usage;
            info.audioAttributes.add(attributes);
        }
        return info;
    }

    private AudioAttributes createHALAudioAttribute(int usage) {
        AudioAttributes attributes = new AudioAttributes();
        attributes.usage = usage;
        attributes.flags = TEST_FLAGS;
        attributes.tags = TEST_TAGS;
        attributes.contentType = AudioContentType.MOVIE;
        attributes.source = AudioSource.CAMCORDER;
        return attributes;
    }

    private android.media.AudioAttributes createMediaAudioAttributes(int usage) {
        android.media.AudioAttributes.Builder builder = new android.media.AudioAttributes.Builder()
                .setFlags(TEST_FLAGS)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .setCapturePreset(MediaRecorder.AudioSource.CAMCORDER);
        if (android.media.AudioAttributes.isSystemUsage(usage)) {
            builder.setSystemUsage(usage);
        } else {
            builder.setUsage(usage);
        }
        for (String tag : TEST_TAGS) {
            builder.addTag(tag);
        }
        return builder.build();
    }

    private VolumeActivationConfiguration createVolumeActivationConfiguration(String name,
            int min, int max, int activation) {
        VolumeActivationConfiguration configuration = new VolumeActivationConfiguration();
        configuration.name = name;

        VolumeActivationConfigurationEntry entry = new VolumeActivationConfigurationEntry();
        entry.minActivationVolumePercentage = min;
        entry.maxActivationVolumePercentage = max;
        entry.type = activation;

        configuration.volumeActivationEntries = new ArrayList<>(1);
        configuration.volumeActivationEntries.add(entry);

        return configuration;
    }
}
