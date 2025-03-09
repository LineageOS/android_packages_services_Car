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

import static android.hardware.automotive.audiocontrol.RoutingDeviceConfiguration.CONFIGURABLE_AUDIO_ENGINE_ROUTING;
import static android.hardware.automotive.audiocontrol.VolumeInvocationType.ON_BOOT;
import static android.hardware.automotive.audiocontrol.VolumeInvocationType.ON_PLAYBACK_CHANGED;
import static android.hardware.automotive.audiocontrol.VolumeInvocationType.ON_SOURCE_CHANGED;
import static android.media.AudioDeviceAttributes.ROLE_OUTPUT;

import static com.android.car.audio.CarAudioUtils.DEFAULT_ACTIVATION_VOLUME;
import static com.android.car.audio.CarAudioUtils.isInvalidActivationPercentage;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.PRIVATE_CONSTRUCTOR;

import android.annotation.Nullable;
import android.car.builtin.media.AudioManagerHelper;
import android.car.builtin.util.Slogf;
import android.car.oem.CarAudioFadeConfiguration;
import android.hardware.automotive.audiocontrol.AudioDeviceConfiguration;
import android.hardware.automotive.audiocontrol.AudioFadeConfiguration;
import android.hardware.automotive.audiocontrol.AudioZoneContext;
import android.hardware.automotive.audiocontrol.AudioZoneContextInfo;
import android.hardware.automotive.audiocontrol.DeviceToContextEntry;
import android.hardware.automotive.audiocontrol.FadeConfiguration;
import android.hardware.automotive.audiocontrol.FadeState;
import android.hardware.automotive.audiocontrol.TransientFadeConfigurationEntry;
import android.hardware.automotive.audiocontrol.VolumeActivationConfiguration;
import android.hardware.automotive.audiocontrol.VolumeActivationConfigurationEntry;
import android.hardware.automotive.audiocontrol.VolumeGroupConfig;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.FadeManagerConfiguration;
import android.media.audio.common.AudioDevice;
import android.media.audio.common.AudioDeviceDescription;
import android.media.audio.common.AudioDeviceType;
import android.media.audio.common.AudioPort;
import android.media.audio.common.AudioPortExt;
import android.media.audio.common.AudioSource;
import android.util.ArrayMap;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Utility class for general audio control conversion methods
 */
final class AudioControlZoneConverterUtils {

    private static final String TAG = AudioControlZoneConverterUtils.class.getSimpleName();

    @ExcludeFromCodeCoverageGeneratedReport(reason = PRIVATE_CONSTRUCTOR)
    private AudioControlZoneConverterUtils() {
        throw new UnsupportedOperationException();
    }

    static int convertToAudioDeviceInfoType(@AudioDeviceType int type, String connection) {
        switch (type) {
            case AudioDeviceType.OUT_SPEAKER:
                if (connection.equals(AudioDeviceDescription.CONNECTION_BT_A2DP)) {
                    return AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
                }
                if (connection.equals(AudioDeviceDescription.CONNECTION_BT_LE)) {
                    return AudioDeviceInfo.TYPE_BLE_SPEAKER;
                }
                return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            case AudioDeviceType.OUT_HEADPHONE:
                if (connection.equals(AudioDeviceDescription.CONNECTION_ANALOG)) {
                    return AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
                }
                return AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
            case AudioDeviceType.OUT_HEADSET:
                return switch (connection) {
                    case AudioDeviceDescription.CONNECTION_ANALOG ->
                            AudioDeviceInfo.TYPE_WIRED_HEADSET;
                    case AudioDeviceDescription.CONNECTION_BT_LE ->
                            AudioDeviceInfo.TYPE_BLE_HEADSET;
                    case AudioDeviceDescription.CONNECTION_BT_SCO ->
                            AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
                    default -> AudioDeviceInfo.TYPE_USB_HEADSET;
                };
            case AudioDeviceType.OUT_ACCESSORY:
                return AudioDeviceInfo.TYPE_USB_ACCESSORY;
            case AudioDeviceType.OUT_LINE_AUX:
                return AudioDeviceInfo.TYPE_AUX_LINE;
            case AudioDeviceType.OUT_BROADCAST:
                return AudioDeviceInfo.TYPE_BLE_BROADCAST;
            case AudioDeviceType.OUT_HEARING_AID:
                return AudioDeviceInfo.TYPE_HEARING_AID;
            case AudioDeviceType.OUT_DEVICE: // OUT_BUS and OUT_DEVICE are mapped to the same enum
            default:
                return switch (connection) {
                    case AudioDeviceDescription.CONNECTION_BT_A2DP ->
                            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
                    case AudioDeviceDescription.CONNECTION_IP_V4 -> AudioDeviceInfo.TYPE_IP;
                    case AudioDeviceDescription.CONNECTION_HDMI_ARC ->
                            AudioDeviceInfo.TYPE_HDMI_ARC;
                    case AudioDeviceDescription.CONNECTION_HDMI_EARC ->
                            AudioDeviceInfo.TYPE_HDMI_EARC;
                    case AudioDeviceDescription.CONNECTION_HDMI -> AudioDeviceInfo.TYPE_HDMI;
                    case AudioDeviceDescription.CONNECTION_ANALOG ->
                            AudioDeviceInfo.TYPE_LINE_ANALOG;
                    case AudioDeviceDescription.CONNECTION_USB -> AudioDeviceInfo.TYPE_USB_DEVICE;
                    case AudioDeviceDescription.CONNECTION_SPDIF ->
                            AudioDeviceInfo.TYPE_LINE_DIGITAL;
                    default -> AudioDeviceInfo.TYPE_BUS;
                };
        }
    }

    static CarActivationVolumeConfig convertVolumeActivationConfig(
            VolumeActivationConfiguration activationConfiguration) {
        if (activationConfiguration == null) {
            return DEFAULT_ACTIVATION_VOLUME;
        }
        List<VolumeActivationConfigurationEntry> entries =
                activationConfiguration.volumeActivationEntries;
        if (entries == null || entries.isEmpty()) {
            Slogf.e(TAG, "Empty volume activation entry for activation config "
                    + activationConfiguration.name);
            return DEFAULT_ACTIVATION_VOLUME;
        }
        //Currently car only supports a single configuration entry
        VolumeActivationConfigurationEntry entry =
                activationConfiguration.volumeActivationEntries.get(0);
        if (isInvalidActivationPercentage(entry.maxActivationVolumePercentage)) {
            Slogf.e(TAG, "Invalid volume max activation value, value must be between 0 and 100");
            return DEFAULT_ACTIVATION_VOLUME;
        }
        if (isInvalidActivationPercentage(entry.minActivationVolumePercentage)) {
            Slogf.e(TAG, "Invalid volume min activation value, value must be between 0 and 100");
            return DEFAULT_ACTIVATION_VOLUME;
        }
        int maxActivation = entry.maxActivationVolumePercentage;
        int minActivation = entry.minActivationVolumePercentage;
        if (maxActivation <= minActivation) {
            Slogf.e(TAG, "Invalid volume activation values, min = "
                    + minActivation + " greater than max = " + maxActivation
                    + " for activation config " + activationConfiguration.name);
            return DEFAULT_ACTIVATION_VOLUME;
        }
        int activationType;
        switch (entry.type) {
            case ON_PLAYBACK_CHANGED:
                activationType = CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT
                        | CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_SOURCE_CHANGED
                        | CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED;
                break;
            case ON_SOURCE_CHANGED:
                activationType = CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT
                        | CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_SOURCE_CHANGED;
                break;
            case ON_BOOT:
                activationType = CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT;
                break;
            default:
                Slogf.e(TAG, "Invalid activation type " + entry.type + " for "
                        + activationConfiguration.name);
                return DEFAULT_ACTIVATION_VOLUME;
        }
        return new CarActivationVolumeConfig(activationType, minActivation, maxActivation);
    }

    @Nullable
    static CarAudioContext convertCarAudioContext(AudioZoneContext audioZoneContext,
                                                  AudioDeviceConfiguration deviceConfiguration) {
        if (audioZoneContext == null) {
            Slogf.e(TAG, "Audio zone context can not be null");
            return null;
        }
        if (audioZoneContext.audioContextInfos == null
                || audioZoneContext.audioContextInfos.isEmpty()) {
            Slogf.e(TAG, "Audio zone context must have valid audio zone context infos");
            return null;
        }
        if (deviceConfiguration == null) {
            Slogf.e(TAG, "Audio device configuration can not be null");
            return null;
        }
        List<CarAudioContextInfo> infos =
                new ArrayList<>(audioZoneContext.audioContextInfos.size());
        int nextValidId = CarAudioContext.getInvalidContext() + 1;
        for (int c = 0; c < audioZoneContext.audioContextInfos.size(); c++) {
            var contextInfo = convertCarAudioContextInfo(audioZoneContext.audioContextInfos.get(c),
                    deviceConfiguration, nextValidId);
            if (contextInfo == null) {
                return null;
            }
            infos.add(contextInfo);
            if (contextInfo.getId() == nextValidId) {
                nextValidId++;
            }
        }
        return new CarAudioContext(infos, deviceConfiguration.routingConfig
                == CONFIGURABLE_AUDIO_ENGINE_ROUTING);
    }

    static AudioAttributes convertAudioAttributes(
            android.media.audio.common.AudioAttributes audioAttributes) {
        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        if (AudioAttributes.isSystemUsage(audioAttributes.usage)) {
            builder.setSystemUsage(audioAttributes.usage);
        } else {
            builder.setUsage(audioAttributes.usage);
        }
        convertAudioTags(audioAttributes.tags, builder);
        builder.setFlags(audioAttributes.flags);
        builder.setContentType(audioAttributes.contentType);
        if (audioAttributes.source != AudioSource.DEFAULT) {
            builder.setCapturePreset(audioAttributes.source);
        }
        return builder.build();
    }

    @Nullable
    static CarAudioDeviceInfo convertAudioDevicePort(AudioPort port,
            AudioManagerWrapper audioManager,
            ArrayMap<String, CarAudioDeviceInfo> deviceAddressToCarDeviceInfo) {
        if (port == null ||  port.ext == null || port.ext.getTag() != AudioPortExt.device
                || port.ext.getDevice() == null) {
            return null;
        }
        AudioDevice device = port.ext.getDevice().device;
        if (device == null) {
            return null;
        }
        String address = device.address.getId();
        String connection = device.type.connection;
        if (requiresDeviceAddress(device.type.type, connection)) {
            return findDeviceByAddress(address, deviceAddressToCarDeviceInfo);
        }
        int  externalType = convertToAudioDeviceInfoType(device.type.type, connection);
        address = address == null ? "" : address;
        return new CarAudioDeviceInfo(audioManager,
                new AudioDeviceAttributes(ROLE_OUTPUT, externalType, address));
    }

    static boolean verifyVolumeGroupName(String groupName, AudioDeviceConfiguration configuration) {
        return !configuration.useCoreAudioVolume || (groupName != null && !groupName.isEmpty());
    }

    static boolean convertAudioContextEntry(CarVolumeGroupFactory factory,
            DeviceToContextEntry entry, CarAudioDeviceInfo info,
            ArrayMap<String, Integer> contextNameToId) {
        if (factory == null || entry == null || info == null || contextNameToId == null) {
            return false;
        }
        for (int c = 0; c < entry.contextNames.size(); c++) {
            String contextName = entry.contextNames.get(c);
            if (contextName == null || contextName.isEmpty()) {
                return false;
            }
            int id = contextNameToId.getOrDefault(contextName,
                    AudioZoneContextInfo.UNASSIGNED_CONTEXT_ID);
            if (id == AudioZoneContextInfo.UNASSIGNED_CONTEXT_ID) {
                return false;
            }
            factory.setDeviceInfoForContext(id, info);
        }
        return !entry.contextNames.isEmpty();
    }

    static String convertVolumeGroupConfig(CarVolumeGroupFactory factory,
            VolumeGroupConfig volumeGroupConfig, AudioManagerWrapper audioManager,
            ArrayMap<String, CarAudioDeviceInfo> addressToCarDeviceInfo,
            ArrayMap<String, Integer> contextNameToId) {
        Objects.requireNonNull(factory, "Volume group factory can no be null");
        Objects.requireNonNull(volumeGroupConfig, "Volume group config can not be null");
        Objects.requireNonNull(audioManager, "Audio manager can not be null");
        Objects.requireNonNull(addressToCarDeviceInfo,
                "Address to car audio device info map can not be null");
        Objects.requireNonNull(contextNameToId, "Context name to id map can not be null");
        if (volumeGroupConfig.carAudioRoutes.isEmpty()) {
            return "Skipped volume group " + volumeGroupConfig.name + " with id "
                    + volumeGroupConfig.id + " empty car audio routes";
        }
        for (int c = 0; c < volumeGroupConfig.carAudioRoutes.size(); c++) {
            var entry = volumeGroupConfig.carAudioRoutes.get(c);
            var info = convertAudioDevicePort(entry.device, audioManager, addressToCarDeviceInfo);
            if (info == null) {
                return "Skipped volume group " + volumeGroupConfig.name + " with id "
                        + volumeGroupConfig.id + " could not find device info for device "
                        + entry.device;
            }
            if (!convertAudioContextEntry(factory, entry, info, contextNameToId)) {
                return "Skipped volume group " + volumeGroupConfig.name + " with id "
                        + volumeGroupConfig.id + " could not parse audio context entry";
            }
        }
        return "";
    }

    static CarAudioFadeConfiguration convertAudioFadeConfiguration(
            AudioFadeConfiguration configuration) {
        Objects.requireNonNull(configuration, "Audio fade configuration can not be null");
        long fadeOutDuration = configuration.fadeOutDurationMs;
        long fadeInDuration = configuration.fadeInDurationMs;
        FadeManagerConfiguration.Builder fadeManagerBuilder;
        if (fadeOutDuration > 0 && fadeInDuration > 0) {
            fadeManagerBuilder = new FadeManagerConfiguration.Builder(fadeOutDuration,
                    fadeInDuration);
        } else {
            fadeManagerBuilder = new FadeManagerConfiguration.Builder();
        }
        // If the fade configuration is not set or empty then the default configurations will be
        // set internally.
        if (isFadeableUsagesNotEmpty(configuration)) {
            fadeManagerBuilder.setFadeableUsages(convertAudioUsages(configuration.fadeableUsages));
        }
        fadeManagerBuilder.setFadeState(convertFadeState(configuration.fadeState));
        if (configuration.unfadeableContentTypes != null) {
            fadeManagerBuilder.setUnfadeableContentTypes(
                    convertAudioContentTypes(configuration.unfadeableContentTypes));
        }
        var convertedAudioAttributes =
                toMediaAudioAttributes(configuration.unfadableAudioAttributes);
        fadeManagerBuilder.setUnfadeableAudioAttributes(Arrays.asList(convertedAudioAttributes));
        convertFadeOutConfiguration(configuration.fadeOutConfigurations, fadeManagerBuilder);
        convertFadeInConfiguration(configuration.fadeInConfigurations, fadeManagerBuilder);
        long delayForOffenders = configuration.fadeInDelayedForOffendersMs;
        if (delayForOffenders >= 0) {
            fadeManagerBuilder.setFadeInDelayForOffenders(delayForOffenders);
        }
        var fadeConfigBuilder = new CarAudioFadeConfiguration.Builder(fadeManagerBuilder.build());
        if (configuration.name != null) {
            fadeConfigBuilder.setName(configuration.name);
        }
        return fadeConfigBuilder.build();
    }

    private static boolean isFadeableUsagesNotEmpty(AudioFadeConfiguration configuration) {
        return configuration.fadeableUsages != null && configuration.fadeableUsages.length != 0;
    }

    static TransientFadeConfig convertTransientFadeConfiguration(
            TransientFadeConfigurationEntry entry) {
        Objects.requireNonNull(entry, "Transient fade configuration can not be null");
        Objects.requireNonNull(entry.transientFadeConfiguration,
                "Fade configuration in transient fade configuration can not be null");
        Objects.requireNonNull(entry.transientUsages,
                "Audio attribute usages in transient fade configuration can not be null");
        Preconditions.checkArgument(entry.transientUsages.length != 0,
                "Audio attribute usages in transient fade configuration can not be empty");
        CarAudioFadeConfiguration carFadeConfig =
                convertAudioFadeConfiguration(entry.transientFadeConfiguration);
        List<AudioAttributes> audioAttributes = new ArrayList<>(entry.transientUsages.length);
        for (int usage : entry.transientUsages) {
            audioAttributes.add(convertHALAudioUsageToAttribute(usage));
        }
        return new TransientFadeConfig(carFadeConfig, audioAttributes);
    }

    private static AudioAttributes convertHALAudioUsageToAttribute(int halUsage) {
        var audioAttribute = new android.media.audio.common.AudioAttributes();
        audioAttribute.usage = halUsage;
        return convertAudioAttributes(audioAttribute);
    }

    private static void convertFadeOutConfiguration(List<FadeConfiguration> fadeOutConfigurations,
            FadeManagerConfiguration.Builder fadeManagerBuilder) {
        convertFadeConfiguration(fadeOutConfigurations,
                fadeManagerBuilder::setFadeOutDurationForUsage,
                fadeManagerBuilder::setFadeOutDurationForAudioAttributes);
    }

    private static void convertFadeInConfiguration(List<FadeConfiguration> fadeInConfigurations,
            FadeManagerConfiguration.Builder fadeManagerBuilder) {
        convertFadeConfiguration(fadeInConfigurations,
                fadeManagerBuilder::setFadeInDurationForUsage,
                fadeManagerBuilder::setFadeInDurationForAudioAttributes);
    }

    private static void convertFadeConfiguration(List<FadeConfiguration> fadeConfigurations,
            AudioUsageFadeDelayConsumer usageConsumer,
            AudioAttributeFadeDelayConsumer attributeConsumer) {
        if (fadeConfigurations == null) {
            return;
        }
        for (int c = 0; c < fadeConfigurations.size(); c++) {
            var fadeOutConfig = fadeConfigurations.get(c);
            long duration = fadeOutConfig.fadeDurationMillis;
            var attributeOrUsage = fadeOutConfig.audioAttributesOrUsage;
            if (attributeOrUsage.getTag() == FadeConfiguration.AudioAttributesOrUsage.usage) {
                usageConsumer.apply(attributeOrUsage.getUsage(), duration);
                continue;
            }
            attributeConsumer.apply(convertAudioAttributes(
                    attributeOrUsage.getFadeAttribute()), duration);
        }
    }

    private static List<Integer> convertAudioContentTypes(int[] types) {
        if (types == null) {
            return Collections.EMPTY_LIST;
        }
        var contentTypes = new ArrayList<Integer>(types.length);
        for (int type : types) {
            contentTypes.add(type);
        }
        return contentTypes;
    }

    private static List<Integer> convertAudioUsages(int[] usages) {
        if (usages == null) {
            return Collections.EMPTY_LIST;
        }
        var contentTypes = new ArrayList<Integer>(usages.length);
        for (int usage : usages) {
            contentTypes.add(usage);
        }
        return contentTypes;
    }

    private static int convertFadeState(int fadeState) {
        if (fadeState == FadeState.FADE_STATE_DISABLED) {
            return FadeManagerConfiguration.FADE_STATE_DISABLED;
        }
        return FadeManagerConfiguration.FADE_STATE_ENABLED_DEFAULT;
    }

    private static boolean requiresDeviceAddress(int type, String connection) {
        return type == AudioDeviceType.OUT_BUS && (connection == null || connection.isEmpty()
                || connection.equals(AudioDeviceDescription.CONNECTION_BUS));
    }

    private static CarAudioDeviceInfo findDeviceByAddress(String address, ArrayMap<String,
            CarAudioDeviceInfo> deviceAddressToCarDeviceInfo) {
        return deviceAddressToCarDeviceInfo.get(address);
    }

    private static void convertAudioTags(String[] tags, AudioAttributes.Builder builder) {
        if (tags == null || tags.length == 0) {
            return;
        }
        for (String tag : tags) {
            AudioManagerHelper.addTagToAudioAttributes(builder, tag);
        }
    }

    @Nullable
    private static CarAudioContextInfo convertCarAudioContextInfo(AudioZoneContextInfo info,
            AudioDeviceConfiguration deviceConfiguration, int nextValidId) {
        if (info == null) {
            Slogf.e(TAG, "Audio zone context info can not be null");
            return null;
        }
        if (info.audioAttributes == null || info.audioAttributes.isEmpty()) {
            Slogf.e(TAG, "Audio zone context info missing audio attributes");
            return null;
        }
        String contextName = info.name;
        int contextId = getValidContextInfoId(contextName, deviceConfiguration,
                info.id, nextValidId);
        String name = getValidContextName(info.name, contextId);
        return new CarAudioContextInfo(toMediaAudioAttributes(info.audioAttributes),
                name, contextId);
    }

    private static AudioAttributes[] toMediaAudioAttributes(
            List<android.media.audio.common.AudioAttributes> audioAttributes) {
        if (audioAttributes == null) {
            return new AudioAttributes[0];
        }
        AudioAttributes[] mediaAttributes = new AudioAttributes[audioAttributes.size()];
        for (int c = 0; c < audioAttributes.size(); c++) {
            mediaAttributes[c] = convertAudioAttributes(audioAttributes.get(c));
        }
        return mediaAttributes;
    }

    private static int getValidContextInfoId(String contextName,
            AudioDeviceConfiguration deviceConfiguration, int contextId, int nextValidId) {
        if (contextId != AudioZoneContextInfo.UNASSIGNED_CONTEXT_ID) {
            return contextId;
        }
        int strategyId = nextValidId;
        if (deviceConfiguration.routingConfig == CONFIGURABLE_AUDIO_ENGINE_ROUTING) {
            strategyId = CoreAudioHelper.getStrategyForContextName(contextName);
            if (strategyId == CoreAudioHelper.INVALID_STRATEGY) {
                throw new IllegalArgumentException("Can not find product strategy id for context "
                        + contextName);
            }
        }
        return strategyId;
    }

    private static String getValidContextName(String name, int contextId) {
        return name != null && !name.isEmpty() ? name : ("Context " + contextId);
    }

    private interface AudioUsageFadeDelayConsumer {
        void apply(int usage, long durationMs);
    }

    private interface AudioAttributeFadeDelayConsumer {
        void apply(AudioAttributes attributes, long durationMs);
    }

    record TransientFadeConfig(CarAudioFadeConfiguration configuration,
                                      List<AudioAttributes> audioAttributes) {
        CarAudioFadeConfiguration getCarAudioFadeConfiguration() {
            return configuration;
        }

        List<AudioAttributes> getAudioAttributes() {
            return audioAttributes;
        }
    }
}
