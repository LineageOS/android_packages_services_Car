/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.media.AudioAttributes.USAGE_ALARM;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_ASSISTANT;
import static android.media.AudioAttributes.USAGE_MEDIA;

import static com.android.car.audio.CarAudioContext.AudioContext;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.test.AbstractExpectableTestCase;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioPlaybackConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public final class CarAudioZoneUnitTest extends AbstractExpectableTestCase {
    private static final String MUSIC_ADDRESS = "bus0_music";
    private static final String NAV_ADDRESS = "bus1_nav";
    private static final String VOICE_ADDRESS = "bus3_voice";
    private static final String ALARM_ADDRESS = "bus11_alarm";
    private static final String ANNOUNCEMENT_ADDRESS = "bus12_announcement";
    private static final String CONFIG_1_ALL_ADDRESS = "bus100_all";

    private static final int TEST_ZONE_ID = 1;
    private static final int TEST_ZONE_CONFIG_ID_0 = 0;
    private static final int TEST_ZONE_CONFIG_ID_1 = 1;

    private static final AudioAttributes TEST_MEDIA_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA);
    private static final AudioAttributes TEST_ALARM_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ALARM);
    private static final AudioAttributes TEST_ASSISTANT_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ASSISTANT);
    private static final AudioAttributes TEST_NAVIGATION_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);

    private static final CarAudioContext TEST_CAR_AUDIO_CONTEXT =
            new CarAudioContext(CarAudioContext.getAllContextsInfo(),
                    /* useCoreAudioRouting= */ false);

    @Mock
    private CarVolumeGroup mMockMusicGroup0;
    @Mock
    private CarVolumeGroup mMockNavGroup0;
    @Mock
    private CarVolumeGroup mMockVoiceGroup0;
    @Mock
    private CarVolumeGroup mMockGroup1;

    @Mock
    private CarAudioZoneConfig mMockZoneConfig0;
    @Mock
    private CarAudioZoneConfig mMockZoneConfig1;

    private CarAudioZone mTestAudioZone;

    @AudioContext
    private static final int TEST_MEDIA_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_MEDIA_ATTRIBUTE);
    @AudioContext
    private static final int TEST_ALARM_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_ALARM_ATTRIBUTE);
    @AudioContext
    private static final int TEST_ASSISTANT_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_ASSISTANT_ATTRIBUTE);
    @AudioContext
    private static final int TEST_NAVIGATION_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_NAVIGATION_ATTRIBUTE);

    @Before
    public void setUp() {
        mMockMusicGroup0 = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS).build();
        mMockNavGroup0 = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, NAV_ADDRESS).build();
        mMockVoiceGroup0 = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_ASSISTANT_CONTEXT, VOICE_ADDRESS)
                .build();
        mMockGroup1 = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, CONFIG_1_ALL_ADDRESS)
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, CONFIG_1_ALL_ADDRESS)
                .addDeviceAddressAndContexts(TEST_ASSISTANT_CONTEXT, CONFIG_1_ALL_ADDRESS)
                .build();

        mMockZoneConfig0 = new TestCarAudioZoneConfigBuilder(TEST_ZONE_ID, TEST_ZONE_CONFIG_ID_0)
                .setIsDefault(true).addVolumeGroup(mMockMusicGroup0)
                .addVolumeGroup(mMockNavGroup0).addVolumeGroup(mMockVoiceGroup0).build();
        mMockZoneConfig1 = new TestCarAudioZoneConfigBuilder(TEST_ZONE_ID, TEST_ZONE_CONFIG_ID_1)
                .addVolumeGroup(mMockGroup1).build();
        mTestAudioZone = new CarAudioZone(TEST_CAR_AUDIO_CONTEXT, "Secondary zone",
                TEST_ZONE_ID);
    }

    @Test
    public void getAddressForContext_throwsOnInvalidContext() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mTestAudioZone.getAddressForContext(CarAudioContext.getInvalidContext()));

        expectWithMessage("Invalid context exception").that(thrown).hasMessageThat()
                .contains("is invalid");
    }

    @Test
    public void getAddressForContext_throwsOnNonExistentContext() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mTestAudioZone.getAddressForContext(TEST_ALARM_CONTEXT));

        expectWithMessage("Non-existing context exception").that(thrown).hasMessageThat()
                .contains("Could not find output device in zone");
    }

    @Test
    public void findActiveAudioAttributesFromPlaybackConfigurations_withNullConfig_fails() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> mTestAudioZone.findActiveAudioAttributesFromPlaybackConfigurations(
                        /* configurations= */ null));

        expectWithMessage("Null playback configuration exception").that(thrown)
                .hasMessageThat().contains("Audio playback configurations can not be null");
    }

    @Test
    public void findActiveAudioAttributesFromPlaybackConfigurations_returnsAllActiveAttributes() {
        AudioPlaybackConfiguration mediaConfiguration = new AudioPlaybackConfigurationBuilder()
                .setUsage(USAGE_MEDIA).setDeviceAddress(MUSIC_ADDRESS).build();
        AudioPlaybackConfiguration navConfiguration = new AudioPlaybackConfigurationBuilder()
                .setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setDeviceAddress(NAV_ADDRESS)
                .build();
        List<AudioPlaybackConfiguration> activeConfigurations = List.of(mediaConfiguration,
                navConfiguration);
        when(mMockZoneConfig0.isAudioDeviceInfoValidForZone(mediaConfiguration
                .getAudioDeviceInfo())).thenReturn(true);
        when(mMockZoneConfig0.isAudioDeviceInfoValidForZone(navConfiguration
                .getAudioDeviceInfo())).thenReturn(true);
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);

        List<AudioAttributes> activeAttributes = mTestAudioZone
                .findActiveAudioAttributesFromPlaybackConfigurations(activeConfigurations);

        expectWithMessage("Active playback audio attributes").that(activeAttributes)
                .containsExactly(TEST_MEDIA_ATTRIBUTE, TEST_NAVIGATION_ATTRIBUTE);
    }

    @Test
    public void findActiveAudioAttributesFromPlaybackConfigurations_returnsNoMatchingAttributes() {
        AudioPlaybackConfiguration assistantConfiguration = new AudioPlaybackConfigurationBuilder()
                .setUsage(USAGE_ASSISTANT).setDeviceAddress(ANNOUNCEMENT_ADDRESS).build();
        AudioPlaybackConfiguration alarmConfiguration = new AudioPlaybackConfigurationBuilder()
                .setUsage(USAGE_ALARM).setDeviceAddress(ALARM_ADDRESS).build();
        List<AudioPlaybackConfiguration> activeConfigurations = List.of(assistantConfiguration,
                alarmConfiguration);
        when(mMockZoneConfig0.isAudioDeviceInfoValidForZone(assistantConfiguration
                .getAudioDeviceInfo())).thenReturn(false);
        when(mMockZoneConfig0.isAudioDeviceInfoValidForZone(alarmConfiguration
                .getAudioDeviceInfo())).thenReturn(false);
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);

        List<AudioAttributes> activeAttributes = mTestAudioZone
                .findActiveAudioAttributesFromPlaybackConfigurations(activeConfigurations);

        expectWithMessage("Non matching active playback audio attributes")
                .that(activeAttributes).isEmpty();
    }

    @Test
    public void findActiveAudioAttributesFromPlaybackConfigurations_withMultipleZoneConfigs() {
        AudioPlaybackConfiguration mediaConfiguration = new AudioPlaybackConfigurationBuilder()
                .setUsage(USAGE_MEDIA).setDeviceAddress(CONFIG_1_ALL_ADDRESS).build();
        AudioPlaybackConfiguration navConfiguration = new AudioPlaybackConfigurationBuilder()
                .setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setDeviceAddress(NAV_ADDRESS)
                .build();
        List<AudioPlaybackConfiguration> activeConfigurations = List.of(mediaConfiguration,
                navConfiguration);
        when(mMockZoneConfig0.isAudioDeviceInfoValidForZone(mediaConfiguration
                .getAudioDeviceInfo())).thenReturn(false);
        when(mMockZoneConfig0.isAudioDeviceInfoValidForZone(navConfiguration
                .getAudioDeviceInfo())).thenReturn(true);
        when(mMockZoneConfig1.isAudioDeviceInfoValidForZone(mediaConfiguration
                .getAudioDeviceInfo())).thenReturn(true);
        when(mMockZoneConfig1.isAudioDeviceInfoValidForZone(navConfiguration
                .getAudioDeviceInfo())).thenReturn(false);
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        List<AudioAttributes> activeAttributes = mTestAudioZone
                .findActiveAudioAttributesFromPlaybackConfigurations(activeConfigurations);

        expectWithMessage("Matching active playback audio attributes for current zone config")
                .that(activeAttributes).containsExactly(TEST_NAVIGATION_ATTRIBUTE);
    }

    @Test
    public void
            findActiveAudioAttributesFromPlaybackConfigurations_onEmptyConfigurations_retEmpty() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);

        List<AudioAttributes> activeAttributes = mTestAudioZone
                .findActiveAudioAttributesFromPlaybackConfigurations(Collections.emptyList());

        expectWithMessage("Empty active playback audio attributes")
                .that(activeAttributes).isEmpty();
    }

    private static final class AudioPlaybackConfigurationBuilder {
        @AudioAttributes.AttributeUsage private int mUsage = USAGE_MEDIA;
        private boolean mIsActive = true;
        private String mDeviceAddress = "";

        AudioPlaybackConfigurationBuilder setUsage(@AudioAttributes.AttributeUsage int usage) {
            mUsage = usage;
            return this;
        }

        AudioPlaybackConfigurationBuilder setDeviceAddress(String deviceAddress) {
            mDeviceAddress = deviceAddress;
            return this;
        }

        AudioPlaybackConfiguration build() {
            AudioPlaybackConfiguration configuration = mock(AudioPlaybackConfiguration.class);
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(mUsage).build();
            AudioDeviceInfo outputDevice = generateOutAudioDeviceInfo(mDeviceAddress);
            when(configuration.getAudioAttributes()).thenReturn(attributes);
            when(configuration.getAudioDeviceInfo()).thenReturn(outputDevice);
            when(configuration.isActive()).thenReturn(mIsActive);
            return configuration;
        }

        private AudioDeviceInfo generateOutAudioDeviceInfo(String address) {
            AudioDeviceInfo audioDeviceInfo = mock(AudioDeviceInfo.class);
            when(audioDeviceInfo.getAddress()).thenReturn(address);
            when(audioDeviceInfo.getType()).thenReturn(AudioDeviceInfo.TYPE_BUS);
            when(audioDeviceInfo.isSource()).thenReturn(false);
            when(audioDeviceInfo.isSink()).thenReturn(true);
            when(audioDeviceInfo.getInternalType()).thenReturn(AudioDeviceInfo
                    .convertDeviceTypeToInternalInputDevice(AudioDeviceInfo.TYPE_BUS));
            return audioDeviceInfo;
        }
    }

    private static final class TestCarAudioZoneConfigBuilder {
        private List<CarVolumeGroup> mCarVolumeGroups = new ArrayList<>();
        int mConfigId;
        int mZoneId;
        boolean mIsDefault;

        TestCarAudioZoneConfigBuilder(int zoneId, int configId) {
            mZoneId = zoneId;
            mConfigId = configId;
        }

        TestCarAudioZoneConfigBuilder setIsDefault(boolean isDefault) {
            mIsDefault = isDefault;
            return this;
        }

        TestCarAudioZoneConfigBuilder addVolumeGroup(CarVolumeGroup volumeGroup) {
            mCarVolumeGroups.add(volumeGroup);
            return this;
        }

        CarAudioZoneConfig build() {
            CarAudioZoneConfig zoneConfig = mock(CarAudioZoneConfig.class);
            when(zoneConfig.getZoneConfigId()).thenReturn(mConfigId);
            when(zoneConfig.getZoneId()).thenReturn(mZoneId);
            when(zoneConfig.isDefault()).thenReturn(mIsDefault);
            for (int groupIndex = 0; groupIndex < mCarVolumeGroups.size(); groupIndex++) {
                when(zoneConfig.getVolumeGroup(groupIndex))
                        .thenReturn(mCarVolumeGroups.get(groupIndex));
            }
            when(zoneConfig.getVolumeGroups())
                    .thenReturn(mCarVolumeGroups.toArray(new CarVolumeGroup[0]));
            when(zoneConfig.validateVolumeGroups(eq(TEST_CAR_AUDIO_CONTEXT), anyBoolean()))
                    .thenReturn(true);
            return zoneConfig;
        }
    }
}
