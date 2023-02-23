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

import static android.media.AudioAttributes.USAGE_ALARM;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
import static android.media.AudioAttributes.USAGE_ASSISTANT;
import static android.media.AudioAttributes.USAGE_MEDIA;

import static com.android.car.audio.CarAudioContext.AudioContext;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.media.CarAudioManager;
import android.car.media.CarVolumeGroupInfo;
import android.car.test.AbstractExpectableTestCase;
import android.hardware.automotive.audiocontrol.AudioGainConfigInfo;
import android.hardware.automotive.audiocontrol.Reasons;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioPlaybackConfiguration;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(MockitoJUnitRunner.class)
public class CarAudioZoneTest extends AbstractExpectableTestCase {
    private static final String MUSIC_ADDRESS = "bus0_music";
    private static final String NAV_ADDRESS = "bus1_nav";
    private static final String VOICE_ADDRESS = "bus3_voice";
    private static final String ASSISTANT_ADDRESS = "bus10_assistant";
    private static final String ALARM_ADDRESS = "bus11_alarm";
    private static final String ANNOUNCEMENT_ADDRESS = "bus12_announcement";

    private static final int TEST_ZONE_CONFIG_ID = 0;
    private static final String TEST_ZONE_CONFIG_NAME = "Config 0";

    private static final AudioAttributes TEST_MEDIA_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA);
    private static final AudioAttributes TEST_ALARM_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ALARM);
    private static final AudioAttributes TEST_ASSISTANT_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ASSISTANT);
    private static final AudioAttributes TEST_NAVIGATION_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
    private static final AudioAttributes TEST_SYSTEM_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ASSISTANCE_SONIFICATION);

    private static final CarAudioContext TEST_CAR_AUDIO_CONTEXT =
            new CarAudioContext(CarAudioContext.getAllContextsInfo(),
                    /* useCoreAudioRouting= */ false);

    @Rule
    public final Expect expect = Expect.create();

    @Mock
    private CarVolumeGroup mMockMusicGroup;
    @Mock
    private CarVolumeGroup mMockNavGroup;
    @Mock
    private CarVolumeGroup mMockVoiceGroup;

    private CarAudioZoneConfig.Builder mTestAudioZoneConfigBuilder;
    private CarAudioZone mTestAudioZone;

    private static final @AudioContext int TEST_MEDIA_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_MEDIA_ATTRIBUTE);
    private static final  @AudioContext int TEST_ALARM_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_ALARM_ATTRIBUTE);
    private static final  @AudioContext int TEST_ASSISTANT_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_ASSISTANT_ATTRIBUTE);
    private static final  @AudioContext int TEST_NAVIGATION_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_NAVIGATION_ATTRIBUTE);

    @Before
    public void setUp() {
        mTestAudioZoneConfigBuilder = new CarAudioZoneConfig.Builder(TEST_ZONE_CONFIG_NAME,
                CarAudioManager.PRIMARY_AUDIO_ZONE, TEST_ZONE_CONFIG_ID, /* isDefault= */ true);
        mTestAudioZone = new CarAudioZone(TEST_CAR_AUDIO_CONTEXT, "Primary zone",
                CarAudioManager.PRIMARY_AUDIO_ZONE);

        mMockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS).build();

        mMockNavGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, NAV_ADDRESS).build();

        mMockVoiceGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_ASSISTANT_CONTEXT, VOICE_ADDRESS).build();
    }

    @Test
    public void forbidUseDynamicRouting_addressSharedAmongGroup() {
        CarAudioDeviceInfo musicCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo)
                .build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo)
                .build();

        CarAudioZoneConfig defaultZoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic));
        mTestAudioZone.addZoneConfig(defaultZoneConfig);

        mTestAudioZone.validateCanUseDynamicMixRouting(/* useCoreAudioRouting= */ true);

        verify(musicCarAudioDeviceInfo).resetCanBeRoutedWithDynamicPolicyMix();
    }

    @Test
    public void validateZoneWhenUsageSharedAmongContext_usingCoreRouting_forbidUseDynamicRouting() {
        CarAudioDeviceInfo musicCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        CarAudioDeviceInfo navCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        when(musicCarAudioDeviceInfo.getAddress()).thenReturn(MUSIC_ADDRESS);
        when(navCarAudioDeviceInfo.getAddress()).thenReturn(NAV_ADDRESS);

        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .addDeviceAddressAndUsages(USAGE_MEDIA, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo)
                .build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, NAV_ADDRESS)
                .addDeviceAddressAndUsages(USAGE_MEDIA, NAV_ADDRESS)
                .addCarAudioDeviceInfoMock(navCarAudioDeviceInfo)
                .build();

        CarAudioZoneConfig defaultZoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic));
        mTestAudioZone.addZoneConfig(defaultZoneConfig);

        expect.withMessage("Zone valides when using core routing and usage is shared amont context")
                .that(mTestAudioZone.validateCanUseDynamicMixRouting(
                        /* useCoreAudioRouting= */ true))
                .isTrue();
        verify(musicCarAudioDeviceInfo).resetCanBeRoutedWithDynamicPolicyMix();
        verify(navCarAudioDeviceInfo).resetCanBeRoutedWithDynamicPolicyMix();
    }

    @Test
    public void validateZoneWhenUsageSharedAmongContext_notUsingCoreRouting_fails() {
        CarAudioDeviceInfo musicCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        CarAudioDeviceInfo navCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        when(musicCarAudioDeviceInfo.getAddress()).thenReturn(MUSIC_ADDRESS);
        when(navCarAudioDeviceInfo.getAddress()).thenReturn(NAV_ADDRESS);

        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .addDeviceAddressAndUsages(USAGE_MEDIA, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo)
                .build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, NAV_ADDRESS)
                .addDeviceAddressAndUsages(USAGE_MEDIA, NAV_ADDRESS)
                .addCarAudioDeviceInfoMock(navCarAudioDeviceInfo)
                .build();

        CarAudioZoneConfig defaultZoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic));
        mTestAudioZone.addZoneConfig(defaultZoneConfig);

        expect.withMessage("Zone does not validate when not using core routing and usage is shared "
                        + "among contexts")
                .that(mTestAudioZone.validateCanUseDynamicMixRouting(
                        /* useCoreAudioRouting= */ false))
                .isFalse();
        verify(musicCarAudioDeviceInfo, never()).resetCanBeRoutedWithDynamicPolicyMix();
        verify(navCarAudioDeviceInfo, never()).resetCanBeRoutedWithDynamicPolicyMix();
    }

    @Test
    public void validateZoneConfigs_success() {
        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, NAV_ADDRESS)
                .build();
        CarVolumeGroup mockAllOtherContextsGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(CarAudioContext.VOICE_COMMAND, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.CALL_RING, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.CALL, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.ALARM, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.NOTIFICATION, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.SYSTEM_SOUND, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.EMERGENCY, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.SAFETY, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.VEHICLE_STATUS, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.ANNOUNCEMENT, VOICE_ADDRESS)
                .build();

        CarAudioZoneConfig defaultZoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic, mockAllOtherContextsGroup));
        mTestAudioZone.addZoneConfig(defaultZoneConfig);

        expect.withMessage("Volume group validates when using core audio routing")
                .that(mTestAudioZone.validateZoneConfigs(/* useCoreAudioRouting= */ true))
                .isTrue();
        expect.withMessage("Volume group validates when not using core audio routing")
                .that(mTestAudioZone.validateZoneConfigs(/* useCoreAudioRouting= */ false))
                .isTrue();
    }

    @Test
    public void validateZoneConfigs_addressSharedAmongGroup() {
        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, MUSIC_ADDRESS)
                .build();
        CarVolumeGroup mockAllOtherContextsGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(CarAudioContext.VOICE_COMMAND, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.CALL_RING, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.CALL, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.ALARM, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.NOTIFICATION, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.SYSTEM_SOUND, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.EMERGENCY, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.SAFETY, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.VEHICLE_STATUS, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.ANNOUNCEMENT, VOICE_ADDRESS)
                .build();

        CarAudioZoneConfig defaultZoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic, mockAllOtherContextsGroup));
        mTestAudioZone.addZoneConfig(defaultZoneConfig);

        expect.withMessage("Volume group validates when using core audio routing")
                .that(mTestAudioZone.validateZoneConfigs(/* useCoreAudioRouting= */ true))
                .isTrue();
        expect.withMessage("Volume group does not validate when not using core audio routing")
                .that(mTestAudioZone.validateZoneConfigs(/* useCoreAudioRouting= */ false))
                .isFalse();
    }

    @Test
    public void getCurrentAudioDeviceInfos() {
        AudioDeviceInfo musicAudioDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        AudioDeviceInfo navAudioDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        CarAudioDeviceInfo musicCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        CarAudioDeviceInfo navCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        when(musicCarAudioDeviceInfo.getAddress()).thenReturn(MUSIC_ADDRESS);
        when(navCarAudioDeviceInfo.getAddress()).thenReturn(NAV_ADDRESS);
        when(musicCarAudioDeviceInfo.getAudioDeviceInfo()).thenReturn(musicAudioDeviceInfo);
        when(navCarAudioDeviceInfo.getAudioDeviceInfo()).thenReturn(navAudioDeviceInfo);
        when(navCarAudioDeviceInfo.canBeRoutedWithDynamicPolicyMix()).thenReturn(false);
        when(musicCarAudioDeviceInfo.canBeRoutedWithDynamicPolicyMix()).thenReturn(true);

        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo)
                .build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, NAV_ADDRESS)
                .addCarAudioDeviceInfoMock(navCarAudioDeviceInfo)
                .build();

        CarAudioZoneConfig defaultZoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic));
        mTestAudioZone.addZoneConfig(defaultZoneConfig);

        expect.withMessage("Zone's groups devices")
                .that(mTestAudioZone.getCurrentAudioDeviceInfos())
                .containsExactlyElementsIn(List.of(musicAudioDeviceInfo, navAudioDeviceInfo));
    }

    @Test
    public void getFilteredAudioDeviceSupportingDynamicMix() {
        AudioDeviceInfo musicAudioDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        CarAudioDeviceInfo musicCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        CarAudioDeviceInfo navCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        when(musicCarAudioDeviceInfo.getAddress()).thenReturn(MUSIC_ADDRESS);
        when(navCarAudioDeviceInfo.getAddress()).thenReturn(NAV_ADDRESS);
        when(musicCarAudioDeviceInfo.getAudioDeviceInfo()).thenReturn(musicAudioDeviceInfo);
        when(navCarAudioDeviceInfo.canBeRoutedWithDynamicPolicyMix()).thenReturn(false);
        when(musicCarAudioDeviceInfo.canBeRoutedWithDynamicPolicyMix()).thenReturn(true);

        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo)
                .build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, NAV_ADDRESS)
                .addCarAudioDeviceInfoMock(navCarAudioDeviceInfo)
                .build();

        CarAudioZoneConfig defaultZoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic));
        mTestAudioZone.addZoneConfig(defaultZoneConfig);

        expect.withMessage("Filtered out non dynamic mix ready devices")
                .that(mTestAudioZone.getCurrentAudioDeviceInfosSupportingDynamicMix())
                .containsExactly(musicAudioDeviceInfo);
    }

    @Test
    public void getAddressForContext_returnsExpectedDeviceAddress() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);

        String musicAddress = mTestAudioZone.getAddressForContext(
                TEST_MEDIA_CONTEXT);
        expectWithMessage("Music volume group address")
                .that(musicAddress).isEqualTo(MUSIC_ADDRESS);

        String navAddress = mTestAudioZone.getAddressForContext(
                TEST_NAVIGATION_CONTEXT);
        expectWithMessage("Navigation volume group address")
                .that(navAddress).matches(NAV_ADDRESS);
    }

    @Test
    public void getAddressForContext_throwsOnInvalidContext() {
        mTestAudioZone.addZoneConfig(mTestAudioZoneConfigBuilder.build());

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class,
                        () -> mTestAudioZone.getAddressForContext(CarAudioContext
                                .getInvalidContext()));

        expectWithMessage("Invalid context exception").that(thrown).hasMessageThat()
                .contains("is invalid");
    }

    @Test
    public void getAddressForContext_throwsOnNonExistentContext() {
        mTestAudioZone.addZoneConfig(mTestAudioZoneConfigBuilder.build());

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class,
                        () -> mTestAudioZone.getAddressForContext(
                                TEST_MEDIA_CONTEXT));

        expectWithMessage("Non-existing context exception").that(thrown).hasMessageThat()
                .contains("Could not find output device in zone");
    }

    @Test
    public void findActiveAudioAttributesFromPlaybackConfigurations_withNullConfig_fails() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup)
                .addVolumeGroup(mMockVoiceGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);

        NullPointerException thrown =
                assertThrows(NullPointerException.class,
                        () -> mTestAudioZone
                                .findActiveAudioAttributesFromPlaybackConfigurations(null));

        expectWithMessage("Null playback configuration exception").that(thrown)
                .hasMessageThat().contains("Audio playback configurations can not be null");
    }

    @Test
    public void findActiveAudioAttributesFromPlaybackConfigurations_returnsAllActiveAttributes() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup)
                .addVolumeGroup(mMockVoiceGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);
        List<AudioPlaybackConfiguration> activeConfigurations = ImmutableList.of(
                new Builder().setUsage(USAGE_MEDIA).setDeviceAddress(MUSIC_ADDRESS).build(),
                new Builder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setDeviceAddress(NAV_ADDRESS).build()
        );

        List<AudioAttributes> activeAttributes = mTestAudioZone
                .findActiveAudioAttributesFromPlaybackConfigurations(activeConfigurations);

        expectWithMessage("Active playback audio attributes").that(activeAttributes)
                .containsExactly(TEST_MEDIA_ATTRIBUTE, TEST_NAVIGATION_ATTRIBUTE);
    }

    @Test
    public void findActiveAudioAttributesFromPlaybackConfigurations_returnsNoMatchingAttributes() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup)
                .addVolumeGroup(mMockVoiceGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);
        List<AudioPlaybackConfiguration> activeConfigurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ASSISTANT)
                        .setDeviceAddress(ANNOUNCEMENT_ADDRESS).build(),
                new Builder().setUsage(USAGE_ALARM)
                        .setDeviceAddress(ALARM_ADDRESS).build()
        );

        List<AudioAttributes> activeAttributes = mTestAudioZone
                .findActiveAudioAttributesFromPlaybackConfigurations(activeConfigurations);

        expectWithMessage("Non matching active playback audio attributes")
                .that(activeAttributes).isEmpty();
    }

    @Test
    public void findActiveAudioAttributesFromPlaybackConfigurations_returnAllAttributes() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup)
                .addVolumeGroup(new VolumeGroupBuilder()
                        .addDeviceAddressAndContexts(TEST_ASSISTANT_CONTEXT, ASSISTANT_ADDRESS)
                        .addDeviceAddressAndContexts(TEST_ALARM_CONTEXT, ALARM_ADDRESS).build())
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);
        List<AudioPlaybackConfiguration> activeConfigurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ASSISTANT)
                        .setDeviceAddress(ASSISTANT_ADDRESS).build(),
                new Builder().setUsage(USAGE_ALARM)
                        .setDeviceAddress(ALARM_ADDRESS).build()
        );

        List<AudioAttributes> activeAttributes = mTestAudioZone
                .findActiveAudioAttributesFromPlaybackConfigurations(activeConfigurations);

        expectWithMessage("Single volume group active playback audio attributes")
                .that(activeAttributes)
                .containsExactly(TEST_ASSISTANT_ATTRIBUTE, TEST_ALARM_ATTRIBUTE);
    }

    @Test
    public void findActiveAudioAttributesFromPlaybackConfigurations_missingAddress_retAttribute() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(new VolumeGroupBuilder()
                        .addDeviceAddressAndContexts(TEST_ASSISTANT_CONTEXT, ASSISTANT_ADDRESS)
                        .addDeviceAddressAndContexts(TEST_ALARM_CONTEXT, ASSISTANT_ADDRESS)
                        .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, ASSISTANT_ADDRESS)
                        .build())
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);
        List<AudioPlaybackConfiguration> activeConfigurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ALARM)
                        .setDeviceAddress(ASSISTANT_ADDRESS).build(),
                new Builder().setUsage(USAGE_MEDIA)
                        .setDeviceAddress(MUSIC_ADDRESS).build()
        );

        List<AudioAttributes> activeAttributes = mTestAudioZone
                .findActiveAudioAttributesFromPlaybackConfigurations(activeConfigurations);

        expectWithMessage("Non matching address active playback audio attributes")
                .that(activeAttributes).containsExactly(TEST_ALARM_ATTRIBUTE);
    }

    @Test
    public void
            findActiveAudioAttributesFromPlaybackConfigurations_withNonMatchContext_retAttr() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup)
                .addVolumeGroup(new VolumeGroupBuilder()
                        .addDeviceAddressAndContexts(TEST_ASSISTANT_CONTEXT, ASSISTANT_ADDRESS)
                        .build())
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);
        List<AudioPlaybackConfiguration> activeConfigurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ASSISTANCE_SONIFICATION)
                        .setDeviceAddress(ASSISTANT_ADDRESS).build()
        );

        List<AudioAttributes> activeAttributes = mTestAudioZone
                .findActiveAudioAttributesFromPlaybackConfigurations(activeConfigurations);

        expectWithMessage("Non matching context active playback audio attributes")
                .that(activeAttributes).containsExactly(TEST_SYSTEM_ATTRIBUTE);
    }

    @Test
    public void findActiveAudioAttributesFromPlaybackConfigurations_withMultiGroupMatch() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup)
                .addVolumeGroup(new VolumeGroupBuilder()
                        .addDeviceAddressAndContexts(TEST_ASSISTANT_CONTEXT, ASSISTANT_ADDRESS)
                        .addDeviceAddressAndContexts(TEST_ALARM_CONTEXT, ALARM_ADDRESS)
                        .build())
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);
        List<AudioPlaybackConfiguration> activeConfigurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ALARM)
                        .setDeviceAddress(ALARM_ADDRESS).build(),
                new Builder().setUsage(USAGE_MEDIA)
                        .setDeviceAddress(MUSIC_ADDRESS).build()
        );

        List<AudioAttributes> activeContexts = mTestAudioZone
                .findActiveAudioAttributesFromPlaybackConfigurations(activeConfigurations);

        expectWithMessage("Multi group match active playback audio attributes")
                .that(activeContexts).containsExactly(TEST_ALARM_ATTRIBUTE, TEST_MEDIA_ATTRIBUTE);
    }

    @Test
    public void
            findActiveAudioAttributesFromPlaybackConfigurations_onEmptyConfigurations_retEmpty() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup)
                .addVolumeGroup(mMockVoiceGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);
        List<AudioPlaybackConfiguration> activeConfigurations = ImmutableList.of();

        List<AudioAttributes> activeAttributes = mTestAudioZone
                .findActiveAudioAttributesFromPlaybackConfigurations(activeConfigurations);

        expectWithMessage("Empty active playback audio attributes")
                .that(activeAttributes).isEmpty();
    }

    @Test
    public void findActiveAudioAttributesFromPlaybackConfigurations_onNullConfigurations_fails() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup)
                .addVolumeGroup(mMockVoiceGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);
        List<AudioPlaybackConfiguration> activeConfigurations = null;

        assertThrows(NullPointerException.class,
                () -> mTestAudioZone
                        .findActiveAudioAttributesFromPlaybackConfigurations(activeConfigurations));
    }

    @Test
    public void isAudioDeviceInfoValidForZone_withNullAudioDeviceInfo_returnsFalse() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);

        expectWithMessage("Null audio device info")
                .that(mTestAudioZone.isAudioDeviceInfoValidForZone(null)).isFalse();
    }

    @Test
    public void isAudioDeviceInfoValidForZone_withNullDeviceAddress_returnsFalse() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);
        AudioDeviceInfo nullAddressDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(nullAddressDeviceInfo.getAddress()).thenReturn(null);

        expectWithMessage("Invalid audio device info").that(
                mTestAudioZone.isAudioDeviceInfoValidForZone(nullAddressDeviceInfo)).isFalse();
    }

    @Test
    public void isAudioDeviceInfoValidForZone_withEmptyDeviceAddress_returnsFalse() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);
        AudioDeviceInfo nullAddressDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(nullAddressDeviceInfo.getAddress()).thenReturn("");

        expectWithMessage("Device info with invalid address").that(
                mTestAudioZone.isAudioDeviceInfoValidForZone(nullAddressDeviceInfo)).isFalse();
    }

    @Test
    public void isAudioDeviceInfoValidForZone_withDeviceAddressNotInZone_returnsFalse() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);
        AudioDeviceInfo nullAddressDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(nullAddressDeviceInfo.getAddress()).thenReturn(VOICE_ADDRESS);

        expectWithMessage("Non zone audio device info").that(
                mTestAudioZone.isAudioDeviceInfoValidForZone(nullAddressDeviceInfo)).isFalse();
    }

    @Test
    public void isAudioDeviceInfoValidForZone_withDeviceAddressInZone_returnsTrue() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);
        AudioDeviceInfo nullAddressDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(nullAddressDeviceInfo.getAddress()).thenReturn(MUSIC_ADDRESS);

        expectWithMessage("Valid audio device info").that(
                mTestAudioZone.isAudioDeviceInfoValidForZone(nullAddressDeviceInfo)).isTrue();
    }

    @Test
    public void onAudioGainChanged_withDeviceAddressesInZone() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);

        List<Integer> reasons = List.of(Reasons.REMOTE_MUTE, Reasons.NAV_DUCKING);

        AudioGainConfigInfo musicGainInfo = new AudioGainConfigInfo();
        musicGainInfo.zoneId = CarAudioManager.PRIMARY_AUDIO_ZONE;
        musicGainInfo.devicePortAddress = MUSIC_ADDRESS;
        musicGainInfo.volumeIndex = 666;
        CarAudioGainConfigInfo carMusicGainInfo = new CarAudioGainConfigInfo(musicGainInfo);
        AudioGainConfigInfo navGainInfo = new AudioGainConfigInfo();
        navGainInfo.zoneId = CarAudioManager.PRIMARY_AUDIO_ZONE;
        navGainInfo.devicePortAddress = NAV_ADDRESS;
        navGainInfo.volumeIndex = 999;
        CarAudioGainConfigInfo carNavGainInfo = new CarAudioGainConfigInfo(navGainInfo);

        List<CarAudioGainConfigInfo> carGains = List.of(carMusicGainInfo, carNavGainInfo);

        mTestAudioZone.onAudioGainChanged(reasons, carGains);

        verify(mMockMusicGroup).onAudioGainChanged(eq(reasons), eq(carMusicGainInfo));
        verify(mMockNavGroup).onAudioGainChanged(eq(reasons), eq(carNavGainInfo));
        verify(mMockVoiceGroup, never()).onAudioGainChanged(any(), any());
    }

    @Test
    public void onAudioGainChanged_withoutAnyDeviceAddressInZone() {
        mTestAudioZone.addZoneConfig(mTestAudioZoneConfigBuilder.build());
        List<Integer> reasons = List.of(Reasons.REMOTE_MUTE, Reasons.NAV_DUCKING);

        AudioGainConfigInfo musicGainInfo = new AudioGainConfigInfo();
        musicGainInfo.zoneId = CarAudioManager.PRIMARY_AUDIO_ZONE;
        musicGainInfo.devicePortAddress = MUSIC_ADDRESS;
        musicGainInfo.volumeIndex = 666;
        CarAudioGainConfigInfo carMusicGainInfo = new CarAudioGainConfigInfo(musicGainInfo);
        AudioGainConfigInfo navGainInfo = new AudioGainConfigInfo();
        navGainInfo.zoneId = CarAudioManager.PRIMARY_AUDIO_ZONE;
        navGainInfo.devicePortAddress = NAV_ADDRESS;
        navGainInfo.volumeIndex = 999;
        CarAudioGainConfigInfo carNavGainInfo = new CarAudioGainConfigInfo(navGainInfo);
        AudioGainConfigInfo voiceGainInfo = new AudioGainConfigInfo();
        voiceGainInfo.zoneId = CarAudioManager.PRIMARY_AUDIO_ZONE;
        voiceGainInfo.devicePortAddress = VOICE_ADDRESS;
        voiceGainInfo.volumeIndex = 777;
        CarAudioGainConfigInfo carVoiceGainInfo = new CarAudioGainConfigInfo(voiceGainInfo);

        List<CarAudioGainConfigInfo> carGains =
                List.of(carMusicGainInfo, carNavGainInfo, carVoiceGainInfo);

        mTestAudioZone.onAudioGainChanged(reasons, carGains);

        verify(mMockMusicGroup, never()).onAudioGainChanged(any(), any());
        verify(mMockNavGroup, never()).onAudioGainChanged(any(), any());
        verify(mMockVoiceGroup, never()).onAudioGainChanged(any(), any());
    }

    @Test
    public void getCarVolumeGroupInfos() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup)
                .addVolumeGroup(mMockVoiceGroup)
                .build();
        mTestAudioZone.addZoneConfig(zoneConfig);

        List<CarVolumeGroupInfo> infos = mTestAudioZone.getCurrentVolumeGroupInfos();

        expectWithMessage("Car volume group infos").that(infos).hasSize(3);
    }

    private CarAudioZoneConfig buildZoneConfig(List<CarVolumeGroup> volumeGroups) {
        CarAudioZoneConfig.Builder carAudioZoneConfigBuilder =
                new CarAudioZoneConfig.Builder("Primary zone config 0",
                        CarAudioManager.PRIMARY_AUDIO_ZONE, /* zoneConfigId= */ 0,
                        /* isDefault= */ true);
        for (int index = 0; index < volumeGroups.size(); index++) {
            carAudioZoneConfigBuilder =
                    carAudioZoneConfigBuilder.addVolumeGroup(volumeGroups.get(index));
        }
        return carAudioZoneConfigBuilder.build();
    }

    private static class VolumeGroupBuilder {
        private SparseArray<String> mDeviceAddresses = new SparseArray<>();
        private CarAudioDeviceInfo mCarAudioDeviceInfoMock;
        private ArrayMap<String, List<Integer>> mUsagesDeviceAddresses = new ArrayMap<>();

        VolumeGroupBuilder addDeviceAddressAndContexts(@AudioContext int context, String address) {
            mDeviceAddresses.put(context, address);
            return this;
        }

        VolumeGroupBuilder addDeviceAddressAndUsages(int usage, String address) {
            if (!mUsagesDeviceAddresses.containsKey(address)) {
                mUsagesDeviceAddresses.put(address, new ArrayList<>());
            }
            mUsagesDeviceAddresses.get(address).add(usage);
            return this;
        }

        VolumeGroupBuilder addCarAudioDeviceInfoMock(CarAudioDeviceInfo infoMock) {
            mCarAudioDeviceInfoMock = infoMock;
            return this;
        }

        CarVolumeGroup build() {
            CarVolumeGroup carVolumeGroup = mock(CarVolumeGroup.class);
            Map<String, ArrayList<Integer>> addressToContexts = new HashMap<>();
            @AudioContext int[] contexts = new int[mDeviceAddresses.size()];

            for (int index = 0; index < mDeviceAddresses.size(); index++) {
                @AudioContext int context = mDeviceAddresses.keyAt(index);
                String address = mDeviceAddresses.get(context);
                when(carVolumeGroup.getAddressForContext(context)).thenReturn(address);
                if (!addressToContexts.containsKey(address)) {
                    addressToContexts.put(address, new ArrayList<>());
                }
                addressToContexts.get(address).add(context);
                contexts[index] = context;
            }

            for (int index = 0; index < mUsagesDeviceAddresses.size(); index++) {
                String address = mUsagesDeviceAddresses.keyAt(index);
                List<Integer> usagesForAddress = mUsagesDeviceAddresses.get(address);
                when(carVolumeGroup.getAllSupportedUsagesForAddress(eq(address)))
                        .thenReturn(usagesForAddress);
            }

            when(carVolumeGroup.getContexts()).thenReturn(contexts);

            for (String address : addressToContexts.keySet()) {
                when(carVolumeGroup.getContextsForAddress(address))
                        .thenReturn(ImmutableList.copyOf(addressToContexts.get(address)));
            }
            when(carVolumeGroup.getAddresses())
                    .thenReturn(ImmutableList.copyOf(addressToContexts.keySet()));

            when(carVolumeGroup.getCarAudioDeviceInfoForAddress(any()))
                    .thenReturn(mCarAudioDeviceInfoMock);

            return carVolumeGroup;
        }

    }

    private static class Builder {
        private @AudioAttributes.AttributeUsage int mUsage = USAGE_MEDIA;
        private boolean mIsActive = true;
        private String mDeviceAddress = "";

        Builder setUsage(@AudioAttributes.AttributeUsage int usage) {
            mUsage = usage;
            return this;
        }

        Builder setDeviceAddress(String deviceAddress) {
            mDeviceAddress = deviceAddress;
            return this;
        }

        Builder setInactive() {
            mIsActive = false;
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
}
