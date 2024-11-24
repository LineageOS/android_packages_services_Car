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

import static com.android.car.audio.AudioControlZoneConverterUtils.convertAudioFadeConfiguration;
import static com.android.car.audio.AudioControlZoneConverterUtils.convertCarAudioContext;
import static com.android.car.audio.AudioControlZoneConverterUtils.convertTransientFadeConfiguration;
import static com.android.car.audio.AudioControlZoneConverterUtils.convertVolumeActivationConfig;
import static com.android.car.audio.AudioControlZoneConverterUtils.convertVolumeGroupConfig;
import static com.android.car.audio.AudioControlZoneConverterUtils.verifyVolumeGroupName;

import android.annotation.Nullable;
import android.car.builtin.util.Slogf;
import android.hardware.automotive.audiocontrol.AudioDeviceConfiguration;
import android.hardware.automotive.audiocontrol.AudioZone;
import android.hardware.automotive.audiocontrol.AudioZoneConfig;
import android.hardware.automotive.audiocontrol.AudioZoneFadeConfiguration;
import android.hardware.automotive.audiocontrol.TransientFadeConfigurationEntry;
import android.hardware.automotive.audiocontrol.VolumeGroupConfig;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.ArrayMap;

import com.android.car.internal.util.LocalLog;
import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Class to convert audio control zone to car audio service zone.
 */
final class AudioControlZoneConverter {

    private static final String TAG = AudioControlZoneConverter.class.getSimpleName();

    private final LocalLog mCarServiceLocalLog;
    private final AudioManagerWrapper mAudioManager;
    private final CarAudioSettings mCarAudioSettings;
    private final ArrayMap<String, CarAudioDeviceInfo> mAddressToCarAudioDeviceInfo;
    private final boolean mUseFadeManagerConfiguration;

    AudioControlZoneConverter(AudioManagerWrapper audioManager, CarAudioSettings settings,
                              LocalLog serviceLog, boolean useFadeManagerConfiguration) {
        mAudioManager = Objects.requireNonNull(audioManager, "Audio manager can no be null");
        mCarAudioSettings = Objects.requireNonNull(settings, "Car audio settings can not be null");
        mCarServiceLocalLog = Objects.requireNonNull(serviceLog,
                "Local car service logs can not be null");
        mAddressToCarAudioDeviceInfo = new ArrayMap<>();
        mUseFadeManagerConfiguration = useFadeManagerConfiguration;
        var infos = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo info : infos) {
            if (info.getAddress().isEmpty() && info.isSource()) {
                continue;
            }
            CarAudioDeviceInfo carInfo = new CarAudioDeviceInfo(mAudioManager,
                    new AudioDeviceAttributes(info));
            carInfo.setAudioDeviceInfo(info);
            mAddressToCarAudioDeviceInfo.put(info.getAddress(), carInfo);
        }
    }

    @Nullable
    CarAudioZone convertAudioZone(AudioZone zone, AudioDeviceConfiguration deviceConfiguration) {
        Objects.requireNonNull(zone, "Audio zone can not be null");
        Objects.requireNonNull(deviceConfiguration, "Audio device configuration can not be null");
        Objects.requireNonNull(zone.audioZoneContext, "Audio zone context can not be null");
        Objects.requireNonNull(zone.audioZoneContext.audioContextInfos,
                "Audio zone context infos can not be null");
        Preconditions.checkArgument(!zone.audioZoneContext.audioContextInfos.isEmpty(),
                "Audio zone context infos can not be empty");
        var carAudioContext = convertCarAudioContext(zone.audioZoneContext, deviceConfiguration);
        if (carAudioContext == null || carAudioContext.getContextsInfo() == null
                || carAudioContext.getContextsInfo().isEmpty()) {
            String message = "Could not parse audio control HAL context";
            Slogf.e(TAG, message);
            mCarServiceLocalLog.log(message);
            return null;
        }
        var contextInfos = carAudioContext.getContextsInfo();
        var contextNameToId = new ArrayMap<String, Integer>(contextInfos.size());
        for (int index = 0; index < contextInfos.size(); index++) {
            CarAudioContextInfo info = carAudioContext.getContextsInfo().get(index);
            contextNameToId.put(info.getName(), info.getId());
        }
        var carAudioZone = new CarAudioZone(carAudioContext, zone.name, zone.id);
        int nextConfigId = 0;
        for (int c = 0; c < zone.audioZoneConfigs.size(); c++) {
            var config = zone.audioZoneConfigs.get(c);
            var builder = new CarAudioZoneConfig.Builder(config.name, zone.id, nextConfigId,
                    config.isDefault);
            if (!convertAudioZoneConfig(builder, config, carAudioContext, deviceConfiguration,
                    contextNameToId)) {
                String message = "Failed to parse configuration " + config.name + " in zone "
                        + zone.id + ", exiting audio control HAL configuration";
                Slogf.e(TAG, message);
                mCarServiceLocalLog.log(message);
                return null;
            }
            carAudioZone.addZoneConfig(builder.build());
            nextConfigId++;
        }
        return carAudioZone;
    }

    private boolean convertAudioZoneConfig(CarAudioZoneConfig.Builder builder,
            AudioZoneConfig config, CarAudioContext carAudioContext,
            AudioDeviceConfiguration deviceConfiguration,
            ArrayMap<String, Integer> contextNameToId) {
        for (int c = 0; c < config.volumeGroups.size(); c++) {
            var groupConfig = config.volumeGroups.get(c);
            if (convertVolumeGroup(builder, groupConfig, carAudioContext, deviceConfiguration,
                    contextNameToId, c)) {
                continue;
            }
            String message = "Failed to parse volume group " + groupConfig.name + " with id "
                    + groupConfig.id + " in audio zone config " + config.name;
            Slogf.e(TAG, message);
            mCarServiceLocalLog.log(message);
            return false;
        }
        if (!convertAudioZoneFadeConfiguration(builder, config.fadeConfiguration)) {
            return false;
        }
        Slogf.i(TAG, "Successfully converted audio zone config %s in zone %s",
                builder.getZoneConfigId(), builder.getZoneId());
        return true;
    }

    private boolean convertAudioZoneFadeConfiguration(CarAudioZoneConfig.Builder builder,
            AudioZoneFadeConfiguration fadeConfiguration) {
        if (!mUseFadeManagerConfiguration || fadeConfiguration == null) {
            return true;
        }
        if (fadeConfiguration.defaultConfiguration == null) {
            String message = "Failed to parse default fade configuration in zone config "
                    + builder.getZoneConfigId() + " in zone " + builder.getZoneId();
            Slogf.e(TAG, message);
            mCarServiceLocalLog.log(message);
            return false;
        }
        var defaultConfig = convertAudioFadeConfiguration(fadeConfiguration.defaultConfiguration);
        builder.setDefaultCarAudioFadeConfiguration(defaultConfig);
        var transientFadeConfigs = fadeConfiguration.transientConfiguration;
        if (transientFadeConfigs == null || transientFadeConfigs.isEmpty()) {
            return true;
        }
        for (int c = 0; c < transientFadeConfigs.size(); c++) {
            if (convertTransientFadeConfigurationEntry(builder, transientFadeConfigs.get(c))) {
                continue;
            }
            return false;
        }
        builder.setFadeManagerConfigurationEnabled(mUseFadeManagerConfiguration);
        return true;
    }

    private boolean convertTransientFadeConfigurationEntry(CarAudioZoneConfig.Builder builder,
            TransientFadeConfigurationEntry transientFadeConfig) {
        if (isInvalidTransientFadeConfig(transientFadeConfig)) {
            String message = "Failed to parse transient fade configuration entry in zone"
                    + " config " + builder.getZoneConfigId() + " in zone " + builder.getZoneId();
            Slogf.e(TAG, message);
            mCarServiceLocalLog.log(message);
            return false;
        }
        var convertedTransientConfig = convertTransientFadeConfiguration(transientFadeConfig);
        for (int i = 0; i < convertedTransientConfig.audioAttributes().size(); i++) {
            var audioAttribute = convertedTransientConfig.audioAttributes().get(i);
            builder.setCarAudioFadeConfigurationForAudioAttributes(audioAttribute,
                    convertedTransientConfig.getCarAudioFadeConfiguration());
        }
        return true;
    }

    private static boolean isInvalidTransientFadeConfig(TransientFadeConfigurationEntry config) {
        return config == null || config.transientUsages == null
                || config.transientUsages.length == 0 || config.transientFadeConfiguration == null;
    }

    private boolean convertVolumeGroup(CarAudioZoneConfig.Builder builder,
            VolumeGroupConfig volumeConfig, CarAudioContext carAudioContext,
            AudioDeviceConfiguration deviceConfiguration,
            ArrayMap<String, Integer> contextNameToId, int groupId) {
        if (!verifyVolumeGroupName(volumeConfig.name, deviceConfiguration)) {
            String message = "Found empty volume group name while relying on core volume for config"
                    + " id " + builder.getZoneConfigId() + " and zone id " + builder.getZoneId();
            Slogf.e(TAG, message);
            mCarServiceLocalLog.log(message);
            return false;
        }
        if (volumeConfig.id != VolumeGroupConfig.UNASSIGNED_ID) {
            groupId = volumeConfig.id;
        }
        String volumeGroupName = volumeConfig.name != null ? volumeConfig.name : "";
        if (volumeGroupName.isEmpty()) {
            volumeGroupName = "config " + builder.getZoneConfigId() + " group " + groupId;
        }

        var activationVolumeConfig =
                convertVolumeActivationConfig(volumeConfig.activationConfiguration);
        var factory = new CarVolumeGroupFactory(mAudioManager, mCarAudioSettings, carAudioContext,
                builder.getZoneId(), builder.getZoneConfigId(), groupId, volumeGroupName,
                deviceConfiguration.useCarVolumeGroupMuting, activationVolumeConfig);
        String failureMessage = convertVolumeGroupConfig(factory, volumeConfig, mAudioManager,
                mAddressToCarAudioDeviceInfo, contextNameToId);
        if (!failureMessage.isEmpty()) {
            Slogf.e(TAG, failureMessage);
            mCarServiceLocalLog.log(failureMessage);
            return false;
        }
        builder.addVolumeGroup(factory.getCarVolumeGroup(deviceConfiguration.useCoreAudioVolume));
        return true;
    }
}
