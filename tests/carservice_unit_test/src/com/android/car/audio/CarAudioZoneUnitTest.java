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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.media.CarAudioManager;
import android.car.media.CarAudioZoneConfigInfo;
import android.car.test.AbstractExpectableTestCase;
import android.hardware.automotive.audiocontrol.AudioGainConfigInfo;
import android.hardware.automotive.audiocontrol.Reasons;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
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

    private static final String TEST_ZONE_NAME = "Secondary zone";
    private static final String TEST_ZONE_CONFIG_NAME_0 = "Zone Config 0";
    private static final String TEST_ZONE_CONFIG_NAME_1 = "Zone Config 1";

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

    private List<CarVolumeGroup> mZoneConfig0VolumeGroups;

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

        mZoneConfig0VolumeGroups = List.of(mMockMusicGroup0, mMockNavGroup0, mMockVoiceGroup0);

        mMockZoneConfig0 = new TestCarAudioZoneConfigBuilder(TEST_ZONE_ID, TEST_ZONE_CONFIG_ID_0,
                TEST_ZONE_CONFIG_NAME_0).setIsDefault(true).addVolumeGroup(mMockMusicGroup0)
                .addVolumeGroup(mMockNavGroup0).addVolumeGroup(mMockVoiceGroup0).build();
        mMockZoneConfig1 = new TestCarAudioZoneConfigBuilder(TEST_ZONE_ID, TEST_ZONE_CONFIG_ID_1,
                TEST_ZONE_CONFIG_NAME_1).addVolumeGroup(mMockGroup1).build();
        mTestAudioZone = new CarAudioZone(TEST_CAR_AUDIO_CONTEXT, TEST_ZONE_NAME,
                TEST_ZONE_ID);
    }

    @Test
    public void init_allZoneConfigsInitialized() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        mTestAudioZone.init();

        verify(mMockZoneConfig0).synchronizeCurrentGainIndex();
        verify(mMockZoneConfig1).synchronizeCurrentGainIndex();
    }

    @Test
    public void getId() {
        expectWithMessage("Zone id").that(mTestAudioZone.getId()).isEqualTo(TEST_ZONE_ID);
    }

    @Test
    public void getName() {
        expectWithMessage("Zone name").that(mTestAudioZone.getName()).isEqualTo(TEST_ZONE_NAME);
    }

    @Test
    public void isPrimaryZone_forPrimaryZone_returnsTrue() {
        CarAudioZone primaryZone = new CarAudioZone(TEST_CAR_AUDIO_CONTEXT,
                /* name= */ "primary zone", CarAudioManager.PRIMARY_AUDIO_ZONE);

        expectWithMessage("Primary zone").that(primaryZone.isPrimaryZone()).isTrue();
    }

    @Test
    public void isPrimaryZone_forNonPrimaryZone_returnsFalse() {
        expectWithMessage("Non-primary zone").that(mTestAudioZone.isPrimaryZone()).isFalse();
    }

    @Test
    public void getCurrentCarAudioZoneConfig() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        expectWithMessage("Current zone configuration")
                .that(mTestAudioZone.getCurrentCarAudioZoneConfig()).isEqualTo(mMockZoneConfig0);
    }

    @Test
    public void getAllCarAudioZoneConfigs() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        expectWithMessage("All zone configurations")
                .that(mTestAudioZone.getAllCarAudioZoneConfigs())
                .containsExactly(mMockZoneConfig0, mMockZoneConfig1);
    }

    @Test
    public void isCurrentZoneConfig_forCurrentConfig_returnsTrue() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);
        CarAudioZoneConfigInfo currentZoneConfigInfo = mTestAudioZone
                .getCurrentCarAudioZoneConfig().getCarAudioZoneConfigInfo();

        expectWithMessage("Current zone config info")
                .that(mTestAudioZone.isCurrentZoneConfig(currentZoneConfigInfo))
                .isTrue();
    }

    @Test
    public void isCurrentZoneConfig_forCurrentConfig_returnsFalse() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);
        CarAudioZoneConfigInfo nonCurrentZoneConfigInfo = getNonCurrentZoneConfigInfo();

        expectWithMessage("Non-current zone config info")
                .that(mTestAudioZone.isCurrentZoneConfig(nonCurrentZoneConfigInfo))
                .isFalse();
    }

    @Test
    public void setCurrentCarZoneConfig() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);
        CarAudioZoneConfigInfo currentZoneConfigInfoToSwitch = getNonCurrentZoneConfigInfo();

        mTestAudioZone.setCurrentCarZoneConfig(currentZoneConfigInfoToSwitch);

        expectWithMessage("Current zone config info after switching zone configuration")
                .that(mTestAudioZone.isCurrentZoneConfig(currentZoneConfigInfoToSwitch))
                .isTrue();
    }

    @Test
    public void getCurrentVolumeGroup_withGroupId() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);
        int groupId = 1;

        expectWithMessage("Current volume group with id %s", groupId)
                .that(mTestAudioZone.getCurrentVolumeGroup(groupId)).isEqualTo(mMockNavGroup0);
    }

    @Test
    public void getCurrentVolumeGroup_withName() {
        String groupName0 = "Group Name 0";
        when(mMockZoneConfig0.getVolumeGroup(groupName0)).thenReturn(mMockVoiceGroup0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        expectWithMessage("Current volume group with name %s", groupName0)
                .that(mTestAudioZone.getCurrentVolumeGroup(groupName0))
                .isEqualTo(mMockVoiceGroup0);
    }

    @Test
    public void getCurrentVolumeGroupCount() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        expectWithMessage("Current volume group count")
                .that(mTestAudioZone.getCurrentVolumeGroupCount())
                .isEqualTo(mZoneConfig0VolumeGroups.size());
    }

    @Test
    public void getCurrentVolumeGroups() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        expectWithMessage("Current volume groups")
                .that(mTestAudioZone.getCurrentVolumeGroups()).asList()
                .containsExactlyElementsIn(mZoneConfig0VolumeGroups).inOrder();
    }

    @Test
    public void validateZoneConfigs_withValidConfigs_returnsTrue() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        expectWithMessage("Valid zone configurations")
                .that(mTestAudioZone.validateZoneConfigs(/* useCoreAudioRouting= */ false))
                .isTrue();
    }

    @Test
    public void validateZoneConfigs_withoutConfigs_returnsFalse() {
        expectWithMessage("Invalid zone without zone configurations")
                .that(mTestAudioZone.validateZoneConfigs(/* useCoreAudioRouting= */ false))
                .isFalse();
    }

    @Test
    public void validateZoneConfigs_withoutInvalidDefaultZoneConfigId_returnsFalse() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        expectWithMessage("Invalid zone with invalid default zone configuration id")
                .that(mTestAudioZone.validateZoneConfigs(/* useCoreAudioRouting= */ false))
                .isFalse();
    }

    @Test
    public void validateZoneConfigs_withWrongZoneIdInZoneConfigs_returnsFalse() {
        CarAudioZoneConfig zoneConfig2 = new TestCarAudioZoneConfigBuilder(TEST_ZONE_ID + 1,
                /* configId= */ 2, TEST_ZONE_CONFIG_NAME_1).build();
        mTestAudioZone.addZoneConfig(zoneConfig2);

        expectWithMessage("Invalid zone with wrong zone id in zone configurations")
                .that(mTestAudioZone.validateZoneConfigs(/* useCoreAudioRouting= */ false))
                .isFalse();
    }

    @Test
    public void validateZoneConfigs_withoutDefaultZoneConfig_returnsFalse() {
        CarAudioZoneConfig zoneConfig2 = new TestCarAudioZoneConfigBuilder(TEST_ZONE_ID,
                /* configId= */ 2, TEST_ZONE_CONFIG_NAME_1).build();
        mTestAudioZone.addZoneConfig(zoneConfig2);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        expectWithMessage("Invalid zone without default zone configuration")
                .that(mTestAudioZone.validateZoneConfigs(/* useCoreAudioRouting= */ false))
                .isFalse();
    }

    @Test
    public void validateZoneConfigs_withMultipleDefaultZoneConfigs_returnsFalse() {
        CarAudioZoneConfig zoneConfig2 = new TestCarAudioZoneConfigBuilder(TEST_ZONE_ID,
                /* configId= */ 2, TEST_ZONE_CONFIG_NAME_1).setIsDefault(true).build();
        mTestAudioZone.addZoneConfig(zoneConfig2);
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);

        expectWithMessage("Invalid zone with multiple default zone configurations")
                .that(mTestAudioZone.validateZoneConfigs(/* useCoreAudioRouting= */ false))
                .isFalse();
    }

    @Test
    public void validateZoneConfigs_withInvalidVolumeGroupsInZoneConfigs_returnsFalse() {
        boolean useCoreAudioRouting = true;
        CarAudioZoneConfig zoneConfig2 = new TestCarAudioZoneConfigBuilder(TEST_ZONE_ID,
                /* configId= */ 2, TEST_ZONE_CONFIG_NAME_1).build();
        when(zoneConfig2.validateVolumeGroups(any(), eq(useCoreAudioRouting))).thenReturn(false);
        mTestAudioZone.addZoneConfig(zoneConfig2);
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);

        expectWithMessage("Invalid zone with invalid volume groups in zone configurations")
                .that(mTestAudioZone.validateZoneConfigs(useCoreAudioRouting))
                .isFalse();
    }

    @Test
    public void validateCanUseDynamicMixRouting() {
        when(mMockZoneConfig0.validateCanUseDynamicMixRouting(
                /* useCoreAudioRouting= */ false)).thenReturn(true);
        when(mMockZoneConfig1.validateCanUseDynamicMixRouting(
                /* useCoreAudioRouting= */ false)).thenReturn(false);
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        expectWithMessage("Using dynamic mix routing validated")
                .that(mTestAudioZone.validateCanUseDynamicMixRouting(
                        /* useCoreAudioRouting= */ false)).isTrue();
    }

    @Test
    public void getAddressForContext_returnsExpectedDeviceAddress() {
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        String musicAddress = mTestAudioZone.getAddressForContext(TEST_MEDIA_CONTEXT);
        String navAddress = mTestAudioZone.getAddressForContext(TEST_NAVIGATION_CONTEXT);
        String voiceAddress = mTestAudioZone.getAddressForContext(TEST_ASSISTANT_CONTEXT);

        expectWithMessage("Music volume group address")
                .that(musicAddress).isEqualTo(MUSIC_ADDRESS);
        expectWithMessage("Navigation volume group address")
                .that(navAddress).matches(NAV_ADDRESS);
        expectWithMessage("Assistant volume group address")
                .that(voiceAddress).matches(VOICE_ADDRESS);
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
    public void getInputDevices() {
        AudioDeviceAttributes mockInputDevice1 = mock(AudioDeviceAttributes.class);
        AudioDeviceAttributes mockInputDevice2 = mock(AudioDeviceAttributes.class);
        mTestAudioZone.addInputAudioDevice(mockInputDevice1);
        mTestAudioZone.addInputAudioDevice(mockInputDevice2);

        expectWithMessage("Input devices").that(mTestAudioZone.getInputAudioDevices())
                .containsExactly(mockInputDevice1, mockInputDevice2);
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

    @Test
    public void getCurrentAudioDeviceInfos() {
        AudioDeviceInfo audioDeviceInfo0 = mock(AudioDeviceInfo.class);
        AudioDeviceInfo audioDeviceInfo1 = mock(AudioDeviceInfo.class);
        when(mMockZoneConfig0.getAudioDeviceInfos()).thenReturn(List.of(audioDeviceInfo0));
        when(mMockZoneConfig1.getAudioDeviceInfos()).thenReturn(List.of(audioDeviceInfo1));
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        expectWithMessage("Current device infos")
                .that(mTestAudioZone.getCurrentAudioDeviceInfos())
                .containsExactly(audioDeviceInfo0);
    }

    @Test
    public void getCurrentAudioDeviceInfosSupportingDynamicMix() {
        AudioDeviceInfo audioDeviceInfo0 = mock(AudioDeviceInfo.class);
        AudioDeviceInfo audioDeviceInfo1 = mock(AudioDeviceInfo.class);
        when(mMockZoneConfig0.getAudioDeviceInfosSupportingDynamicMix())
                .thenReturn(List.of(audioDeviceInfo0));
        when(mMockZoneConfig1.getAudioDeviceInfosSupportingDynamicMix())
                .thenReturn(List.of(audioDeviceInfo1));
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        expectWithMessage("Current device infos supporting dynamic mix")
                .that(mTestAudioZone.getCurrentAudioDeviceInfosSupportingDynamicMix())
                .containsExactly(audioDeviceInfo0);
    }

    @Test
    public void isAudioDeviceInfoValidForZone() {
        AudioDeviceInfo audioDeviceInfo = mock(AudioDeviceInfo.class);
        when(mMockZoneConfig0.isAudioDeviceInfoValidForZone(audioDeviceInfo)).thenReturn(false);
        when(mMockZoneConfig1.isAudioDeviceInfoValidForZone(audioDeviceInfo)).thenReturn(true);
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        expectWithMessage("Invalid device for current zone configuration")
                .that(mTestAudioZone.isAudioDeviceInfoValidForZone(audioDeviceInfo)).isFalse();
    }

    @Test
    public void onAudioGainChanged_withDeviceAddressesInZone() {
        List<Integer> reasons = List.of(Reasons.REMOTE_MUTE, Reasons.NAV_DUCKING);
        AudioGainConfigInfo musicGainInfo = new AudioGainConfigInfo();
        musicGainInfo.zoneId = TEST_ZONE_ID;
        musicGainInfo.devicePortAddress = CONFIG_1_ALL_ADDRESS;
        musicGainInfo.volumeIndex = 666;
        CarAudioGainConfigInfo carMusicGainInfo = new CarAudioGainConfigInfo(musicGainInfo);
        AudioGainConfigInfo navGainInfo = new AudioGainConfigInfo();
        navGainInfo.zoneId = TEST_ZONE_ID;
        navGainInfo.devicePortAddress = NAV_ADDRESS;
        navGainInfo.volumeIndex = 999;
        CarAudioGainConfigInfo carNavGainInfo = new CarAudioGainConfigInfo(navGainInfo);
        List<CarAudioGainConfigInfo> carGains = List.of(carMusicGainInfo, carNavGainInfo);
        mTestAudioZone.addZoneConfig(mMockZoneConfig0);
        mTestAudioZone.addZoneConfig(mMockZoneConfig1);

        mTestAudioZone.onAudioGainChanged(reasons, carGains);

        verify(mMockMusicGroup0, never()).onAudioGainChanged(any(), any());
        verify(mMockNavGroup0).onAudioGainChanged(any(), any());
        verify(mMockVoiceGroup0, never()).onAudioGainChanged(any(), any());
        verify(mMockGroup1).onAudioGainChanged(any(), any());
    }

    @Test
    public void onAudioGainChanged_withoutAnyDeviceAddressInZone() {
        List<Integer> reasons = List.of(Reasons.REMOTE_MUTE, Reasons.NAV_DUCKING);
        AudioGainConfigInfo navGainInfo = new AudioGainConfigInfo();
        navGainInfo.zoneId = TEST_ZONE_ID;
        navGainInfo.devicePortAddress = NAV_ADDRESS;
        navGainInfo.volumeIndex = 999;
        CarAudioGainConfigInfo carNavGainInfo = new CarAudioGainConfigInfo(navGainInfo);
        CarAudioZoneConfig zoneConfig = new TestCarAudioZoneConfigBuilder(TEST_ZONE_ID,
                TEST_ZONE_CONFIG_ID_0, "zone config test").setIsDefault(true)
                .addVolumeGroup(mMockMusicGroup0).build();
        mTestAudioZone.addZoneConfig(zoneConfig);

        mTestAudioZone.onAudioGainChanged(reasons, List.of(carNavGainInfo));

        verify(mMockMusicGroup0, never()).onAudioGainChanged(any(), any());
        verify(mMockNavGroup0, never()).onAudioGainChanged(any(), any());
        verify(mMockVoiceGroup0, never()).onAudioGainChanged(any(), any());
    }

    @Test
    public void getCarAudioContext() {
        expectWithMessage("Audio context in audio zone")
                .that(mTestAudioZone.getCarAudioContext()).isEqualTo(TEST_CAR_AUDIO_CONTEXT);
    }

    private CarAudioZoneConfigInfo getNonCurrentZoneConfigInfo() {
        CarAudioZoneConfigInfo currentZoneConfigInfo = mTestAudioZone
                .getCurrentCarAudioZoneConfig().getCarAudioZoneConfigInfo();
        List<CarAudioZoneConfigInfo> zoneConfigInfoList = mTestAudioZone
                .getCarAudioZoneConfigInfos();
        for (int index = 0; index < zoneConfigInfoList.size(); index++) {
            CarAudioZoneConfigInfo zoneConfigInfo = zoneConfigInfoList.get(index);
            if (!currentZoneConfigInfo.equals(zoneConfigInfo)) {
                return zoneConfigInfo;
            }
        }
        return null;
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
        String mName;
        boolean mIsDefault;

        TestCarAudioZoneConfigBuilder(int zoneId, int configId, String name) {
            mZoneId = zoneId;
            mConfigId = configId;
            mName = name;
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
            when(zoneConfig.getName()).thenReturn(mName);
            when(zoneConfig.isDefault()).thenReturn(mIsDefault);
            for (int groupIndex = 0; groupIndex < mCarVolumeGroups.size(); groupIndex++) {
                when(zoneConfig.getVolumeGroup(groupIndex))
                        .thenReturn(mCarVolumeGroups.get(groupIndex));
            }
            when(zoneConfig.getVolumeGroupCount()).thenReturn(mCarVolumeGroups.size());
            when(zoneConfig.getVolumeGroups())
                    .thenReturn(mCarVolumeGroups.toArray(new CarVolumeGroup[0]));
            when(zoneConfig.validateVolumeGroups(eq(TEST_CAR_AUDIO_CONTEXT), anyBoolean()))
                    .thenReturn(true);
            doAnswer(invocation -> {
                List<Integer> halReasons = (List<Integer>) invocation.getArguments()[0];
                CarAudioGainConfigInfo gainInfo =
                        (CarAudioGainConfigInfo) invocation.getArguments()[1];
                for (int groupIndex = 0; groupIndex < mCarVolumeGroups.size(); groupIndex++) {
                    CarVolumeGroup group = mCarVolumeGroups.get(groupIndex);
                    if (group.getAddresses().contains(gainInfo.getDeviceAddress())) {
                        group.onAudioGainChanged(halReasons, gainInfo);
                        return true;
                    }
                }
                return false;
            }).when(zoneConfig).onAudioGainChanged(anyList(), any(CarAudioGainConfigInfo.class));
            when(zoneConfig.getCarAudioZoneConfigInfo())
                    .thenReturn(new CarAudioZoneConfigInfo(mName, mZoneId, mConfigId));
            return zoneConfig;
        }
    }
}
