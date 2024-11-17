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
import android.hardware.automotive.audiocontrol.AudioDeviceConfiguration;
import android.hardware.automotive.audiocontrol.AudioZoneContext;
import android.hardware.automotive.audiocontrol.AudioZoneContextInfo;
import android.hardware.automotive.audiocontrol.DeviceToContextEntry;
import android.hardware.automotive.audiocontrol.VolumeActivationConfiguration;
import android.hardware.automotive.audiocontrol.VolumeActivationConfigurationEntry;
import android.hardware.automotive.audiocontrol.VolumeGroupConfig;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.audio.common.AudioDevice;
import android.media.audio.common.AudioDeviceDescription;
import android.media.audio.common.AudioDeviceType;
import android.media.audio.common.AudioPort;
import android.media.audio.common.AudioPortExt;
import android.media.audio.common.AudioSource;
import android.util.ArrayMap;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Utility class for general audio control conversion methods
 */
class AudioControlZoneConverterUtils {

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
                activationType = CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED;
                break;
            case ON_SOURCE_CHANGED:
                activationType = CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_SOURCE_CHANGED;
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

    static CarAudioContext convertCarAudioContext(AudioZoneContext audioZoneContext,
                                                  AudioDeviceConfiguration deviceConfiguration) {
        List<CarAudioContextInfo> infos =
                new ArrayList<>(audioZoneContext.audioContextInfos.size());
        int nextValidId = CarAudioContext.getInvalidContext() + 1;
        for (int c = 0; c < audioZoneContext.audioContextInfos.size(); c++) {
            Slogf.d(TAG, "Context " + audioZoneContext.audioContextInfos.get(c));
            var contextInfo = convertCarAudioContextInfo(audioZoneContext.audioContextInfos.get(c),
                    deviceConfiguration, nextValidId);
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

    private static CarAudioContextInfo convertCarAudioContextInfo(AudioZoneContextInfo info,
            AudioDeviceConfiguration deviceConfiguration, int nextValidId) {
        String contextName = info.name;
        int contextId = getValidContextInfoId(contextName, deviceConfiguration,
                info.id, nextValidId);
        String name = getValidContextName(info.name, contextId);
        return new CarAudioContextInfo(toMediaAudioAttributes(info.audioAttributes),
                name, contextId);
    }

    private static AudioAttributes[] toMediaAudioAttributes(
            List<android.media.audio.common.AudioAttributes> audioAttributes) {
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
}
