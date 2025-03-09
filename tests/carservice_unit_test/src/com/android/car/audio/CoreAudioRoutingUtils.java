/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static android.media.audio.common.AudioUsage.ALARM;
import static android.media.audio.common.AudioUsage.ANNOUNCEMENT;
import static android.media.audio.common.AudioUsage.ASSISTANCE_ACCESSIBILITY;
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

import static com.android.car.audio.CarAudioContext.getAudioAttributeFromUsage;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.MEDIA_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.NAVIGATION_TEST_DEVICE;
import static com.android.car.audio.CarAudioDeviceInfoTestUtils.OEM_TEST_DEVICE;
import static com.android.car.audio.CarAudioTestUtils.TEST_ACTIVATION;
import static com.android.car.audio.CarAudioTestUtils.TEST_ALARM_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_ANNOUNCEMENT_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_ASSISTANT_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_CALL_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_EMERGENCY_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_GAME_USAGE_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_MAX_ACTIVATION;
import static com.android.car.audio.CarAudioTestUtils.TEST_MEDIA_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_MIN_ACTIVATION;
import static com.android.car.audio.CarAudioTestUtils.TEST_NOTIFICATION_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_NOTIFICATION_EVENT_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_RINGER_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_SAFETY_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_SYSTEM_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_UNKNOWN_USAGE_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.TEST_VEHICLE_ATTRIBUTE;
import static com.android.car.audio.CarAudioTestUtils.createBusAudioPort;
import static com.android.car.audio.CarAudioTestUtils.createDeviceToContextEntry;
import static com.android.car.audio.CarAudioTestUtils.createListOfHALAudioAttributes;
import static com.android.car.audio.CarAudioTestUtils.createVolumeActivationConfiguration;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import static com.google.common.collect.Sets.newHashSet;

import android.hardware.automotive.audiocontrol.AudioZone;
import android.hardware.automotive.audiocontrol.AudioZoneConfig;
import android.hardware.automotive.audiocontrol.AudioZoneContext;
import android.hardware.automotive.audiocontrol.AudioZoneContextInfo;
import android.hardware.automotive.audiocontrol.DeviceToContextEntry;
import android.hardware.automotive.audiocontrol.VolumeGroupConfig;
import android.media.AudioAttributes;
import android.media.MediaRecorder;
import android.media.audio.common.AudioContentType;
import android.media.audio.common.AudioHalProductStrategy;
import android.media.audio.common.AudioUsage;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.os.Parcel;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.util.ArrayList;
import java.util.List;

@ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
public final class CoreAudioRoutingUtils {

    public static final int MUSIC_MIN_INDEX = 0;
    public static final int MUSIC_MAX_INDEX = 40;
    public static final int MUSIC_AM_INIT_INDEX = 8;
    public static final int NAV_MIN_INDEX = 5;
    public static final int NAV_MAX_INDEX = 35;
    public static final int OEM_MIN_INDEX = 1;
    public static final int OEM_MAX_INDEX = 15;
    public static final int MUSIC_CAR_GROUP_ID = 0;
    public static final int OEM_CAR_GROUP_ID = 2;
    public static final int NAV_CAR_GROUP_ID = 1;
    public static final String MUSIC_DEVICE_ADDRESS = "MUSIC_DEVICE_ADDRESS";
    public static final String NAV_DEVICE_ADDRESS = "NAV_DEVICE_ADDRESS";
    public static final String OEM_DEVICE_ADDRESS = "OEM_DEVICE_ADDRESS";
    public static final List<AudioVolumeGroup> VOLUME_GROUPS;
    public static final List<AudioProductStrategy> PRODUCT_STRATEGIES;

    public static final AudioAttributes MUSIC_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    public static final AudioAttributes MOVIE_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build();
    public static final int MUSIC_STRATEGY_ID = 777;
    public static final String MUSIC_CONTEXT_NAME = "MUSIC_CONTEXT";
    public static final int MUSIC_GROUP_ID = 666;
    public static final String MUSIC_GROUP_NAME = "MUSIC_GROUP";
    public static final AudioProductStrategy MUSIC_STRATEGY;
    public static final AudioVolumeGroup MUSIC_GROUP;
    public static final CarAudioContextInfo MEDIA_CONTEXT_INFO;

    public static final AudioAttributes NAV_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    public static final int NAV_STRATEGY_ID = 99;
    public static final String NAV_CONTEXT_NAME = "NAV_CONTEXT";
    public static final int NAV_GROUP_ID = 8;
    public static final String NAV_GROUP_NAME = "NAV_GROUP";
    public static final AudioProductStrategy NAV_STRATEGY;
    public static final AudioVolumeGroup NAV_GROUP;
    public static final CarAudioContextInfo NAV_CONTEXT_INFO;

    public static final AudioAttributes OEM_ATTRIBUTES;
    public static final int OEM_STRATEGY_ID = 1979;
    public static final String OEM_CONTEXT_NAME = "OEM_CONTEXT";
    public static final int OEM_GROUP_ID = 55;
    public static final String OEM_GROUP_NAME = "OEM_GROUP";
    public static final String OEM_FORMATTED_TAGS = "oem=extension_1979";
    public static final AudioProductStrategy OEM_STRATEGY;
    public static final AudioVolumeGroup OEM_GROUP;
    public static final CarAudioContextInfo OEM_CONTEXT_INFO;

    static final String INVALID_CONTEXT_NAME = "INVALID_CONTEXT";
    static final int INVALID_STRATEGY_ID = 999999;
    static final int INVALID_GROUP_ID = 999999;
    static final String INVALID_GROUP_NAME = "INVALID_GROUP";

    static final int INVALID_STRATEGY = -1;
    public static final String CORE_PRIMARY_ZONE = "Core primary zone";

    static final AudioAttributes UNSUPPORTED_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    public static final int TEST_MEDIA_PORT_ID = 1979;
    public static final int TEST_NAVIGATION_PORT_ID = 867;
    public static final int TEST_OEM_PORT_ID = 5309;

    static {
        OEM_ATTRIBUTES = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .replaceTags(newHashSet(OEM_FORMATTED_TAGS))
                .build();
        // Note: constructors are private, use marshalling public API to generate the mocks
        Parcel parcel = Parcel.obtain();
        // marshall AudioProductStrategy data
        parcel.writeString(MUSIC_CONTEXT_NAME);
        parcel.writeInt(MUSIC_STRATEGY_ID);

        // nb attributes groups
        parcel.writeInt(1);
        parcel.writeInt(MUSIC_GROUP_ID);
        // stream type
        parcel.writeInt(0);
        // nb attributes
        parcel.writeInt(2);

        parcel.writeInt(AudioAttributes.USAGE_MEDIA);
        parcel.writeInt(AudioAttributes.CONTENT_TYPE_MUSIC);
        parcel.writeInt(MediaRecorder.AudioSource.AUDIO_SOURCE_INVALID);
        parcel.writeInt(AudioAttributes.FLAG_MUTE_HAPTIC);
        parcel.writeInt(AudioAttributes.FLATTEN_TAGS);
        // mFormattedTags
        parcel.writeString("");
        // ATTR_PARCEL_IS_NULL_BUNDLE
        parcel.writeInt(-1977);

        parcel.writeInt(AudioAttributes.USAGE_UNKNOWN);
        parcel.writeInt(AudioAttributes.CONTENT_TYPE_UNKNOWN);
        parcel.writeInt(MediaRecorder.AudioSource.AUDIO_SOURCE_INVALID);
        parcel.writeInt(AudioAttributes.FLAG_MUTE_HAPTIC);
        parcel.writeInt(AudioAttributes.FLATTEN_TAGS);
        // mFormattedTags
        parcel.writeString("");
        // ATTR_PARCEL_IS_NULL_BUNDLE
        parcel.writeInt(-1977);

        parcel.setDataPosition(0);
        MUSIC_STRATEGY = AudioProductStrategy.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        parcel = Parcel.obtain();
        // marshall AudioProductStrategy data
        parcel.writeString(NAV_CONTEXT_NAME);
        parcel.writeInt(NAV_STRATEGY_ID);

        // nb attributes groups
        parcel.writeInt(1);
        parcel.writeInt(NAV_GROUP_ID);
        // stream type
        parcel.writeInt(0);
        // nb attributes
        parcel.writeInt(2);

        parcel.writeInt(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
        parcel.writeInt(AudioAttributes.CONTENT_TYPE_SPEECH);
        parcel.writeInt(MediaRecorder.AudioSource.AUDIO_SOURCE_INVALID);
        parcel.writeInt(AudioAttributes.FLAG_MUTE_HAPTIC);
        parcel.writeInt(AudioAttributes.FLATTEN_TAGS);
        // mFormattedTags
        parcel.writeString("");
        // ATTR_PARCEL_IS_NULL_BUNDLE
        parcel.writeInt(-1977);

        parcel.writeInt(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
        parcel.writeInt(AudioAttributes.CONTENT_TYPE_UNKNOWN);
        parcel.writeInt(MediaRecorder.AudioSource.AUDIO_SOURCE_INVALID);
        parcel.writeInt(AudioAttributes.FLAG_MUTE_HAPTIC);
        parcel.writeInt(AudioAttributes.FLATTEN_TAGS);
        // mFormattedTags
        parcel.writeString("");
        // ATTR_PARCEL_IS_NULL_BUNDLE
        parcel.writeInt(-1977);

        parcel.setDataPosition(0);
        NAV_STRATEGY = AudioProductStrategy.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        parcel = Parcel.obtain();
        // marshall AudioProductStrategy data
        parcel.writeString(OEM_CONTEXT_NAME);
        parcel.writeInt(OEM_STRATEGY_ID);

        // nb attributes groups
        parcel.writeInt(1);
        parcel.writeInt(OEM_GROUP_ID);
        // stream type
        parcel.writeInt(0);
        // nb attributes
        parcel.writeInt(1);
        parcel.writeInt(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
        parcel.writeInt(AudioAttributes.CONTENT_TYPE_SPEECH);
        parcel.writeInt(MediaRecorder.AudioSource.AUDIO_SOURCE_INVALID);
        parcel.writeInt(AudioAttributes.FLAG_MUTE_HAPTIC);
        parcel.writeInt(AudioAttributes.FLATTEN_TAGS);
        parcel.writeString(OEM_FORMATTED_TAGS);
        // ATTR_PARCEL_IS_NULL_BUNDLE
        parcel.writeInt(-1977);

        parcel.setDataPosition(0);
        OEM_STRATEGY = AudioProductStrategy.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        // Order matters, put the default in the middle to check default strategy is not selected
        // if not explicitly requested.
        PRODUCT_STRATEGIES = List.of(OEM_STRATEGY, MUSIC_STRATEGY, NAV_STRATEGY);

        parcel = Parcel.obtain();
        // marshall AudioVolumeGroup data
        parcel.writeString(MUSIC_GROUP_NAME);
        parcel.writeInt(MUSIC_GROUP_ID);

        // nb attributes
        parcel.writeInt(2);
        parcel.writeInt(AudioAttributes.USAGE_MEDIA);
        parcel.writeInt(AudioAttributes.CONTENT_TYPE_MUSIC);
        parcel.writeInt(MediaRecorder.AudioSource.AUDIO_SOURCE_INVALID);
        parcel.writeInt(AudioAttributes.FLAG_MUTE_HAPTIC);
        parcel.writeInt(AudioAttributes.FLATTEN_TAGS);
        // mFormattedTags
        parcel.writeString("");
        // ATTR_PARCEL_IS_NULL_BUNDLE
        parcel.writeInt(-1977);

        parcel.writeInt(AudioAttributes.USAGE_UNKNOWN);
        parcel.writeInt(AudioAttributes.CONTENT_TYPE_UNKNOWN);
        parcel.writeInt(MediaRecorder.AudioSource.AUDIO_SOURCE_INVALID);
        parcel.writeInt(AudioAttributes.FLAG_MUTE_HAPTIC);
        parcel.writeInt(AudioAttributes.FLATTEN_TAGS);
        // mFormattedTags
        parcel.writeString("");
        // ATTR_PARCEL_IS_NULL_BUNDLE
        parcel.writeInt(-1977);

        // nb stream types
        parcel.writeInt(0);

        parcel.setDataPosition(0);
        MUSIC_GROUP = AudioVolumeGroup.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        parcel = Parcel.obtain();
        // marshall AudioProductStrategy data
        parcel.writeString(NAV_GROUP_NAME);
        parcel.writeInt(NAV_GROUP_ID);

        // nb attributes
        parcel.writeInt(1);
        parcel.writeInt(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
        parcel.writeInt(AudioAttributes.CONTENT_TYPE_SPEECH);
        parcel.writeInt(MediaRecorder.AudioSource.AUDIO_SOURCE_INVALID);
        parcel.writeInt(AudioAttributes.FLAG_MUTE_HAPTIC);
        parcel.writeInt(AudioAttributes.FLATTEN_TAGS);
        // mFormattedTags
        parcel.writeString("");
        // ATTR_PARCEL_IS_NULL_BUNDLE
        parcel.writeInt(-1977);

        parcel.writeInt(/* nb stream types= */ 0);

        parcel.setDataPosition(0);
        NAV_GROUP = AudioVolumeGroup.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        parcel = Parcel.obtain();
        // marshall AudioProductStrategy data
        parcel.writeString(OEM_GROUP_NAME);
        parcel.writeInt(OEM_GROUP_ID);

        // nb attributes
        parcel.writeInt(1);
        parcel.writeInt(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
        parcel.writeInt(AudioAttributes.CONTENT_TYPE_SPEECH);
        parcel.writeInt(MediaRecorder.AudioSource.AUDIO_SOURCE_INVALID);
        parcel.writeInt(AudioAttributes.FLAG_MUTE_HAPTIC);
        parcel.writeInt(AudioAttributes.FLATTEN_TAGS);
        // mFormattedTags
        parcel.writeString("oem=extension_1979");
        // ATTR_PARCEL_IS_NULL_BUNDLE
        parcel.writeInt(-1977);

        // nb stream types
        parcel.writeInt(0);

        parcel.setDataPosition(0);
        OEM_GROUP = AudioVolumeGroup.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        VOLUME_GROUPS = List.of(MUSIC_GROUP, NAV_GROUP, OEM_GROUP);

        AudioAttributes[] oemAttributesArray = { OEM_ATTRIBUTES };
        AudioAttributes[] musicAttributesArray = {MUSIC_ATTRIBUTES, MOVIE_ATTRIBUTES,
                TEST_NOTIFICATION_ATTRIBUTE, TEST_NOTIFICATION_EVENT_ATTRIBUTE,
                TEST_SYSTEM_ATTRIBUTE, TEST_ALARM_ATTRIBUTE, TEST_CALL_ATTRIBUTE,
                getAudioAttributeFromUsage(AudioAttributes.USAGE_CALL_ASSISTANT),
                getAudioAttributeFromUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING),
                TEST_RINGER_ATTRIBUTE, TEST_UNKNOWN_USAGE_ATTRIBUTE, TEST_GAME_USAGE_ATTRIBUTE,
                TEST_MEDIA_ATTRIBUTE,
                getAudioAttributeFromUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY),
                TEST_ASSISTANT_ATTRIBUTE, TEST_EMERGENCY_ATTRIBUTE, TEST_SAFETY_ATTRIBUTE,
                TEST_VEHICLE_ATTRIBUTE, TEST_ANNOUNCEMENT_ATTRIBUTE};
        AudioAttributes[] navAttributesArray = { NAV_ATTRIBUTES };

        MEDIA_CONTEXT_INFO = new CarAudioContextInfo(musicAttributesArray, MUSIC_CONTEXT_NAME,
                MUSIC_STRATEGY_ID);
        NAV_CONTEXT_INFO = new CarAudioContextInfo(navAttributesArray,  NAV_CONTEXT_NAME,
                NAV_STRATEGY_ID);
        OEM_CONTEXT_INFO = new CarAudioContextInfo(oemAttributesArray, OEM_CONTEXT_NAME,
                OEM_STRATEGY_ID);
    }

    private CoreAudioRoutingUtils() {
        throw new UnsupportedOperationException("CoreAudioRoutingUtils class is non instantiable");
    }

    public static List<AudioVolumeGroup> getVolumeGroups() {
        return VOLUME_GROUPS;
    }

    public static List<AudioProductStrategy> getProductStrategies() {
        return PRODUCT_STRATEGIES;
    }

    public static List<CarAudioContextInfo> getCarAudioContextInfos() {
        List<CarAudioContextInfo> carAudioContextInfos = new ArrayList<>(3);

        carAudioContextInfos.add(MEDIA_CONTEXT_INFO);
        carAudioContextInfos.add(NAV_CONTEXT_INFO);
        carAudioContextInfos.add(OEM_CONTEXT_INFO);

        return carAudioContextInfos;
    }

    /**
     * Returns a car audio control context built using core routing management
     */
    public static CarAudioContext getCoreCarAudioContext() {
        return new CarAudioContext(getCarAudioContextInfos(), true);
    }

    /**
     * Returns an audio control HAL context built using core routing management
     */
    public static AudioZoneContext createCoreHALAudioContext() {
        android.media.audio.common.AudioAttributes oemAttributes =
                new android.media.audio.common.AudioAttributes();
        oemAttributes.usage = AudioUsage.ASSISTANCE_NAVIGATION_GUIDANCE;
        oemAttributes.contentType = AudioContentType.SPEECH;
        oemAttributes.tags = new String[]{OEM_FORMATTED_TAGS};

        AudioZoneContextInfo oemInfo = new AudioZoneContextInfo();
        oemInfo.id = OEM_STRATEGY_ID;
        oemInfo.name = OEM_CONTEXT_NAME;
        oemInfo.audioAttributes = List.of(oemAttributes);

        android.media.audio.common.AudioAttributes navAttributes =
                new android.media.audio.common.AudioAttributes();
        navAttributes.usage = AudioUsage.ASSISTANCE_NAVIGATION_GUIDANCE;
        navAttributes.contentType = AudioContentType.SPEECH;
        AudioZoneContextInfo navInfo = new AudioZoneContextInfo();
        navInfo.id = NAV_STRATEGY_ID;
        navInfo.name = NAV_CONTEXT_NAME;
        navInfo.audioAttributes = List.of(navAttributes);

        android.media.audio.common.AudioAttributes musicAttributes =
                new android.media.audio.common.AudioAttributes();
        musicAttributes.usage = AudioUsage.MEDIA;
        musicAttributes.contentType = AudioContentType.MUSIC;
        android.media.audio.common.AudioAttributes movieAttributes =
                new android.media.audio.common.AudioAttributes();
        movieAttributes.contentType = AudioContentType.MOVIE;
        AudioZoneContextInfo musicInfo = new AudioZoneContextInfo();
        musicInfo.id = MUSIC_STRATEGY_ID;
        musicInfo.name = MUSIC_CONTEXT_NAME;
        var audioAttributes = new ArrayList<android.media.audio.common.AudioAttributes>();
        audioAttributes.add(musicAttributes);
        audioAttributes.add(movieAttributes);
        audioAttributes.addAll(createListOfHALAudioAttributes(new int[]{NOTIFICATION,
                NOTIFICATION_EVENT, ASSISTANCE_SONIFICATION, ALARM, VOICE_COMMUNICATION,
                CALL_ASSISTANT, VOICE_COMMUNICATION_SIGNALLING, NOTIFICATION_TELEPHONY_RINGTONE,
                UNKNOWN, GAME, MEDIA, ASSISTANCE_ACCESSIBILITY, ASSISTANT, EMERGENCY, SAFETY,
                VEHICLE_STATUS, ANNOUNCEMENT}));
        musicInfo.audioAttributes = audioAttributes;

        AudioZoneContext context = new AudioZoneContext();
        context.audioContextInfos = List.of(musicInfo, navInfo, oemInfo);
        return context;
    }

    /**
     * @return zone created with core audio contexts
     */
    public static AudioZone getCoreAudioZone() {
        var coreZone = new AudioZone();
        coreZone.name = CORE_PRIMARY_ZONE;
        coreZone.id = AudioHalProductStrategy.ZoneId.DEFAULT;
        coreZone.audioZoneContext = createCoreHALAudioContext();
        coreZone.audioZoneConfigs = createCoreAudioZoneConfigs();
        return coreZone;
    }

    private static List<AudioZoneConfig> createCoreAudioZoneConfigs() {
        var configs = new ArrayList<AudioZoneConfig>(1);
        configs.add(createCoreAudioZoneConfig());
        return configs;
    }

    private static AudioZoneConfig createCoreAudioZoneConfig() {
        var config = new AudioZoneConfig();
        config.name = "Core audio zone config";
        config.isDefault = true;
        config.volumeGroups = createCoreVolumeGroups();
        return config;
    }

    private static List<VolumeGroupConfig> createCoreVolumeGroups() {
        var coreOemConfig = getVolumeGroupConfig(createOemCoreAudioRoutes(), OEM_GROUP_NAME,
                OEM_GROUP_ID);
        var coreNavConfig = getVolumeGroupConfig(createNavCoreAudioRoutes(), NAV_GROUP_NAME,
                NAV_GROUP_ID);
        var coreMediaConfig = getVolumeGroupConfig(createMediaCoreAudioRoutes(), MUSIC_GROUP_NAME,
                MUSIC_GROUP_ID);
        return List.of(coreMediaConfig, coreNavConfig, coreOemConfig);
    }

    private static VolumeGroupConfig getVolumeGroupConfig(List<DeviceToContextEntry> contextEntries,
            String navGroupName, int navGroupId) {
        var coreNavConfig = new VolumeGroupConfig();
        coreNavConfig.activationConfiguration =
                createVolumeActivationConfiguration(TEST_ACTIVATION, TEST_MIN_ACTIVATION,
                        TEST_MAX_ACTIVATION, ON_BOOT);
        coreNavConfig.carAudioRoutes = contextEntries;
        coreNavConfig.name = navGroupName;
        coreNavConfig.id = navGroupId;
        return coreNavConfig;
    }

    private static List<DeviceToContextEntry> createMediaCoreAudioRoutes() {
        var busPortDevice = createBusAudioPort(MEDIA_TEST_DEVICE, TEST_MEDIA_PORT_ID,
                "core_media_port");
        var mediaContext = createDeviceToContextEntry(busPortDevice, List.of(MUSIC_CONTEXT_NAME));
        return List.of(mediaContext);
    }

    private static List<DeviceToContextEntry> createNavCoreAudioRoutes() {
        var navPortDevice = createBusAudioPort(NAVIGATION_TEST_DEVICE, TEST_NAVIGATION_PORT_ID,
                "core_nav_port");
        var navContext = createDeviceToContextEntry(navPortDevice, List.of(NAV_CONTEXT_NAME));
        return List.of(navContext);
    }

    private static List<DeviceToContextEntry> createOemCoreAudioRoutes() {
        var oemPortDevice = createBusAudioPort(OEM_TEST_DEVICE, TEST_OEM_PORT_ID,
                "core_oem_port");
        var oemContext = createDeviceToContextEntry(oemPortDevice, List.of(OEM_CONTEXT_NAME));
        return List.of(oemContext);
    }
}
