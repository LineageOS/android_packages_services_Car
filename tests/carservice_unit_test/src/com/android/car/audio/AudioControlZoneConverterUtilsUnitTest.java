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
import static android.media.audio.common.AudioUsage.EMERGENCY;
import static android.media.audio.common.AudioUsage.MEDIA;
import static android.media.audio.common.AudioUsage.SAFETY;
import static android.media.audio.common.AudioUsage.VEHICLE_STATUS;

import static com.android.car.audio.AudioControlZoneConverterUtils.convertAudioContextEntry;
import static com.android.car.audio.AudioControlZoneConverterUtils.convertAudioDevicePort;
import static com.android.car.audio.AudioControlZoneConverterUtils.convertAudioFadeConfiguration;
import static com.android.car.audio.AudioControlZoneConverterUtils.convertCarAudioContext;
import static com.android.car.audio.AudioControlZoneConverterUtils.convertTransientFadeConfiguration;
import static com.android.car.audio.AudioControlZoneConverterUtils.convertVolumeGroupConfig;
import static com.android.car.audio.AudioControlZoneConverterUtils.verifyVolumeGroupName;
import static com.android.car.audio.CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT;
import static com.android.car.audio.CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED;
import static com.android.car.audio.CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_SOURCE_CHANGED;
import static com.android.car.audio.CarAudioTestUtils.GAINS;
import static com.android.car.audio.CarAudioTestUtils.INVALID_CONTEXT_NAME;
import static com.android.car.audio.CarAudioTestUtils.MUSIC_CONTEXT;
import static com.android.car.audio.CarAudioTestUtils.MUSIC_CONTEXT_ID;
import static com.android.car.audio.CarAudioTestUtils.NAVIGATION_CONTEXT;
import static com.android.car.audio.CarAudioTestUtils.NAVIGATION_CONTEXT_ID;
import static com.android.car.audio.CarAudioTestUtils.RING_CONTEXT;
import static com.android.car.audio.CarAudioTestUtils.RING_CONTEXT_ID;
import static com.android.car.audio.CarAudioTestUtils.TEST_ACTIVATION;
import static com.android.car.audio.CarAudioTestUtils.TEST_CREATED_CAR_AUDIO_CONTEXT;
import static com.android.car.audio.CarAudioTestUtils.TEST_EMERGENCY_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_FADE_CONFIGURATION_NAME;
import static com.android.car.audio.CarAudioTestUtils.TEST_MAX_ACTIVATION;
import static com.android.car.audio.CarAudioTestUtils.TEST_MIN_ACTIVATION;
import static com.android.car.audio.CarAudioTestUtils.TEST_SAFETY_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_VEHICLE_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.VOICE_COMMAND_CONTEXT;
import static com.android.car.audio.CarAudioTestUtils.VOICE_COMMAND_CONTEXT_ID;
import static com.android.car.audio.CarAudioTestUtils.createAudioPort;
import static com.android.car.audio.CarAudioTestUtils.createAudioPortDeviceExt;
import static com.android.car.audio.CarAudioTestUtils.createBusAudioPort;
import static com.android.car.audio.CarAudioTestUtils.createDeviceToContextEntry;
import static com.android.car.audio.CarAudioTestUtils.createHALAudioAttribute;
import static com.android.car.audio.CarAudioTestUtils.createHALAudioContext;
import static com.android.car.audio.CarAudioTestUtils.createMediaAudioAttributes;
import static com.android.car.audio.CarAudioTestUtils.createTestFadeConfiguration;
import static com.android.car.audio.CarAudioTestUtils.createVolumeActivationConfiguration;
import static com.android.car.audio.CarAudioTestUtils.createVolumeGroupConfig;
import static com.android.car.audio.CarAudioTestUtils.getTestCarFadeConfiguration;
import static com.android.car.audio.CarAudioUtils.DEFAULT_ACTIVATION_VOLUME;
import static com.android.car.audio.CoreAudioRoutingUtils.createCoreHALAudioContext;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.hardware.automotive.audiocontrol.AudioDeviceConfiguration;
import android.hardware.automotive.audiocontrol.AudioFadeConfiguration;
import android.hardware.automotive.audiocontrol.AudioZoneContext;
import android.hardware.automotive.audiocontrol.AudioZoneContextInfo;
import android.hardware.automotive.audiocontrol.DeviceToContextEntry;
import android.hardware.automotive.audiocontrol.FadeState;
import android.hardware.automotive.audiocontrol.RoutingDeviceConfiguration;
import android.hardware.automotive.audiocontrol.TransientFadeConfigurationEntry;
import android.hardware.automotive.audiocontrol.VolumeActivationConfiguration;
import android.hardware.automotive.audiocontrol.VolumeGroupConfig;
import android.media.AudioDeviceInfo;
import android.media.FadeManagerConfiguration;
import android.media.audio.common.AudioAttributes;
import android.media.audio.common.AudioDeviceDescription;
import android.media.audio.common.AudioDeviceType;
import android.media.audio.common.AudioPort;
import android.media.audio.common.AudioPortDeviceExt;
import android.media.audio.common.AudioPortExt;
import android.media.audio.common.AudioUsage;
import android.util.ArrayMap;

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

    private static final CarActivationVolumeConfig TEST_ON_PLAY_ACTIVATION_CONFIG =
            new CarActivationVolumeConfig(ACTIVATION_VOLUME_ON_BOOT
                    | ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED | ACTIVATION_VOLUME_ON_SOURCE_CHANGED,
                    TEST_MIN_ACTIVATION, TEST_MAX_ACTIVATION);
    private static final CarActivationVolumeConfig TEST_ON_SOURCE_ACTIVATION_CONFIG =
            new CarActivationVolumeConfig(ACTIVATION_VOLUME_ON_BOOT
                    | ACTIVATION_VOLUME_ON_SOURCE_CHANGED,
                    TEST_MIN_ACTIVATION, TEST_MAX_ACTIVATION);
    private static final CarActivationVolumeConfig TEST_ON_BOOT_ACTIVATION_CONFIG =
            new CarActivationVolumeConfig(ACTIVATION_VOLUME_ON_BOOT,
                    TEST_MIN_ACTIVATION, TEST_MAX_ACTIVATION);
    private static final int INVALID_MIN_ACTIVATION = -1;
    private static final int INVALID_MAX_ACTIVATION = 101;

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
    public void convertCarAudioContext_withNullContext() {
        AudioZoneContext context = null;
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.routingConfig = RoutingDeviceConfiguration.DYNAMIC_AUDIO_ROUTING;

        expectWithMessage("Converted audio context with null context")
                .that(convertCarAudioContext(context, configuration)).isNull();
    }

    @Test
    public void convertCarAudioContext_withNullContextInfos() {
        AudioZoneContext context = new AudioZoneContext();
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.routingConfig = RoutingDeviceConfiguration.DYNAMIC_AUDIO_ROUTING;

        expectWithMessage("Converted audio context with null context infos")
                .that(convertCarAudioContext(context, configuration)).isNull();
    }

    @Test
    public void convertCarAudioContext_withEmptyContextInfos() {
        AudioZoneContext context = new AudioZoneContext();
        context.audioContextInfos = Collections.EMPTY_LIST;
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.routingConfig = RoutingDeviceConfiguration.DYNAMIC_AUDIO_ROUTING;

        expectWithMessage("Converted audio context with empty context infos")
                .that(convertCarAudioContext(context, configuration)).isNull();
    }

    @Test
    public void convertCarAudioContext_withNullContextInfo() {
        AudioZoneContext context = new AudioZoneContext();
        context.audioContextInfos = new ArrayList<>(1);
        context.audioContextInfos.add(null);
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.routingConfig = RoutingDeviceConfiguration.DYNAMIC_AUDIO_ROUTING;

        expectWithMessage("Converted audio context with null context info")
                .that(convertCarAudioContext(context, configuration)).isNull();
    }

    @Test
    public void convertCarAudioContext_withNullAudioAttributesInContextInfo() {
        AudioZoneContext context = new AudioZoneContext();
        context.audioContextInfos = List.of(new AudioZoneContextInfo());
        AudioDeviceConfiguration configuration = new AudioDeviceConfiguration();
        configuration.routingConfig = RoutingDeviceConfiguration.DYNAMIC_AUDIO_ROUTING;

        expectWithMessage("Converted audio context with null audio attributes in context info")
                .that(convertCarAudioContext(context, configuration)).isNull();
    }

    @Test
    public void convertCarAudioContext_withNullAudioDeviceConfiguration() {
        AudioZoneContext context = createHALAudioContext();
        AudioDeviceConfiguration configuration = null;

        expectWithMessage("Converted audio context with null device configuration")
                .that(convertCarAudioContext(context, configuration)).isNull();
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
        AudioZoneContext context = createCoreHALAudioContext();
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
        var entry = createTestDeviceToContextEntry();
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
        var entry = createTestDeviceToContextEntry();
        ArrayMap<String, Integer> contextNameToId = null;

        expectWithMessage("Converted context entry with null context name map")
                .that(convertAudioContextEntry(factory, entry, MEDIA_BUS_DEVICE, contextNameToId))
                .isFalse();
    }

    @Test
    public void convertAudioContextEntry_withNullCarAudioDevice() {
        var factory = mock(CarVolumeGroupFactory.class);
        var entry = createTestDeviceToContextEntry();
        var contextNameToId = createContextNameToIDMap(createHALAudioContext());

        expectWithMessage("Converted context entry with null car audio device")
                .that(convertAudioContextEntry(factory, entry, /* info= */ null, contextNameToId))
                .isFalse();
    }

    @Test
    public void convertAudioContextEntry_withInvalidContextInList() {
        var factory = mock(CarVolumeGroupFactory.class);
        var entry = createTestDeviceToContextEntry();
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
        var entry = createTestDeviceToContextEntry();
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
        var entry = createTestDeviceToContextEntry();
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
                        RING_CONTEXT_ID);
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

    @Test
    public void convertAudioFadeConfiguration_withNullConfiguration() {
        AudioFadeConfiguration configuration = null;

        var thrown = assertThrows(NullPointerException.class,
                () -> convertAudioFadeConfiguration(configuration));

        expectWithMessage("Convert audio fade configuration exception").that(thrown)
                .hasMessageThat().contains("Audio fade configuration");
    }

    @Test
    public void convertAudioFadeConfiguration_withEnabledConfig() {
        AudioFadeConfiguration configuration = createTestFadeConfiguration();

        var carFadeConfiguration = convertAudioFadeConfiguration(configuration);

        expectWithMessage("Converted enabled fade configuration name")
                .that(carFadeConfiguration.getName()).isEqualTo(TEST_FADE_CONFIGURATION_NAME);
        var fadeConfiguration = carFadeConfiguration.getFadeManagerConfiguration();
        expectWithMessage("Converted enabled fade configuration").that(fadeConfiguration)
                .isEqualTo(getTestCarFadeConfiguration().getFadeManagerConfiguration());
    }

    @Test
    public void convertAudioFadeConfiguration_withDisabledConfig() {
        AudioFadeConfiguration configuration = new AudioFadeConfiguration();
        configuration.fadeState = FadeState.FADE_STATE_DISABLED;

        var carFadeConfiguration = convertAudioFadeConfiguration(configuration);

        expectWithMessage("Converted disabled fade configuration name")
                .that(carFadeConfiguration.getName()).contains("FADE_STATE_DISABLED");
        var fadeManagerConfiguration = new FadeManagerConfiguration.Builder().setFadeState(
                        FadeManagerConfiguration.FADE_STATE_DISABLED).build();
        expectWithMessage("Converted disabled fade manager configuration")
                .that(carFadeConfiguration.getFadeManagerConfiguration())
                .isEqualTo(fadeManagerConfiguration);
    }

    @Test
    public void convertAudioFadeConfiguration_withEmptyFadeableConfigs() {
        AudioFadeConfiguration configuration = new AudioFadeConfiguration();
        configuration.fadeableUsages = new int[0];
        configuration.fadeState = FadeState.FADE_STATE_ENABLED_DEFAULT;

        var carFadeConfiguration = convertAudioFadeConfiguration(configuration);

        expectWithMessage("Name for converted fade configuration with empty fadeable usages")
                .that(carFadeConfiguration.getName()).contains("FADE_STATE_ENABLED_DEFAULT");
        var fadeManagerConfiguration = new FadeManagerConfiguration.Builder().setFadeState(
                FadeManagerConfiguration.FADE_STATE_ENABLED_DEFAULT).build();
        expectWithMessage("Converted disabled fade manager configuration")
                .that(carFadeConfiguration.getFadeManagerConfiguration())
                .isEqualTo(fadeManagerConfiguration);
    }

    @Test
    public void convertTransientFadeConfiguration_withNullTransientEntry() {
        TransientFadeConfigurationEntry entry = null;

        var thrown = assertThrows(NullPointerException.class,
                () -> convertTransientFadeConfiguration(entry));

        expectWithMessage("Convert transient fade configuration exception for null entry")
                .that(thrown).hasMessageThat().contains("Transient fade configuration");
    }

    @Test
    public void convertTransientFadeConfiguration_withNullFadeConfigInTransientEntry() {
        var entry = new TransientFadeConfigurationEntry();
        entry.transientUsages = new int[] {EMERGENCY};

        var thrown = assertThrows(NullPointerException.class,
                () -> convertTransientFadeConfiguration(entry));

        expectWithMessage("Convert transient fade configuration exception for null fade config")
                .that(thrown).hasMessageThat().contains("Fade configuration in transient");
    }

    @Test
    public void convertTransientFadeConfiguration_withNullUsagesInTransientEntry() {
        var entry = new TransientFadeConfigurationEntry();
        entry.transientFadeConfiguration = new AudioFadeConfiguration();

        var thrown = assertThrows(NullPointerException.class,
                () -> convertTransientFadeConfiguration(entry));

        expectWithMessage("Convert transient fade configuration exception for null usages")
                .that(thrown).hasMessageThat().contains("Audio attribute usages in transient");
    }

    @Test
    public void convertTransientFadeConfiguration_withEmptyUsagesInTransientEntry() {
        var entry = new TransientFadeConfigurationEntry();
        entry.transientFadeConfiguration = new AudioFadeConfiguration();
        entry.transientUsages = new int[0];

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> convertTransientFadeConfiguration(entry));

        expectWithMessage("Convert transient fade configuration exception for empty usages")
                .that(thrown).hasMessageThat().contains("Audio attribute usages in transient");
    }

    @Test
    public void convertTransientFadeConfiguration_withEnableConfigurationInEntry() {
        var entry = new TransientFadeConfigurationEntry();
        entry.transientFadeConfiguration = createTestFadeConfiguration();
        entry.transientUsages = new int[]{EMERGENCY};

        var transientConfig = convertTransientFadeConfiguration(entry);

        expectWithMessage("Converted transient fade configuration with enabled configuration")
                .that(transientConfig.getCarAudioFadeConfiguration())
                .isEqualTo(getTestCarFadeConfiguration());
        expectWithMessage("Converter transient audio attributes with enabled configuration")
                .that(transientConfig.getAudioAttributes())
                .containsExactly(TEST_EMERGENCY_ATTRIBUTE);
    }

    @Test
    public void convertTransientFadeConfiguration_withDisabledConfigurationInEntry() {
        AudioFadeConfiguration configuration = new AudioFadeConfiguration();
        var entry = new TransientFadeConfigurationEntry();
        entry.transientUsages = new int[]{EMERGENCY, SAFETY, VEHICLE_STATUS};

        configuration.fadeState = FadeState.FADE_STATE_DISABLED;
        entry.transientFadeConfiguration = configuration;

        var transientConfig = convertTransientFadeConfiguration(entry);

        var carFadeConfiguration = transientConfig.getCarAudioFadeConfiguration();
        expectWithMessage("Converted disabled fade configuration name in transient "
                + "fade configuration")
                .that(carFadeConfiguration.getName()).contains("FADE_STATE_DISABLED");
        expectWithMessage("Converted disabled fade configuration state in transient fade "
                + "configuration")
                .that(carFadeConfiguration.getFadeManagerConfiguration().getFadeState())
                .isEqualTo(FadeManagerConfiguration.FADE_STATE_DISABLED);
        expectWithMessage("Converter transient audio attributes with disabled configuration")
                .that(transientConfig.getAudioAttributes()).containsExactly(
                        TEST_EMERGENCY_ATTRIBUTE, TEST_SAFETY_ATTRIBUTE, TEST_VEHICLE_ATTRIBUTE);
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

    private DeviceToContextEntry createTestDeviceToContextEntry() {
        var port = createAudioPort(PORT_ID_MEDIA, PORT_MEDIA_NAME, GAINS, new AudioPortDeviceExt());
        ArrayList<String> contexts = new ArrayList<>(4);
        contexts.add(MUSIC_CONTEXT);
        contexts.add(NAVIGATION_CONTEXT);
        contexts.add(VOICE_COMMAND_CONTEXT);
        contexts.add(RING_CONTEXT);
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
}
