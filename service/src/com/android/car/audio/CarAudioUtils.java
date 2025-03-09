/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.media.AudioDeviceInfo.TYPE_AUX_LINE;
import static android.media.AudioDeviceInfo.TYPE_BLE_BROADCAST;
import static android.media.AudioDeviceInfo.TYPE_BLE_HEADSET;
import static android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER;
import static android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
import static android.media.AudioDeviceInfo.TYPE_BUS;
import static android.media.AudioDeviceInfo.TYPE_HDMI;
import static android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY;
import static android.media.AudioDeviceInfo.TYPE_USB_DEVICE;
import static android.media.AudioDeviceInfo.TYPE_USB_HEADSET;
import static android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
import static android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET;
import static android.media.AudioManager.GET_DEVICES_OUTPUTS;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.PRIVATE_CONSTRUCTOR;

import static java.util.Collections.EMPTY_LIST;

import android.annotation.Nullable;
import android.car.feature.Flags;
import android.car.media.CarAudioZoneConfigInfo;
import android.car.media.CarVolumeGroupEvent;
import android.car.media.CarVolumeGroupInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.text.TextUtils;
import android.util.ArrayMap;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class CarAudioUtils {

    static final int ACTIVATION_VOLUME_PERCENTAGE_MIN = 0;
    static final int ACTIVATION_VOLUME_PERCENTAGE_MAX = 100;
    static final int ACTIVATION_VOLUME_INVOCATION_TYPE =
            CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT
                    | CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_SOURCE_CHANGED
                    | CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED;

    static final CarActivationVolumeConfig DEFAULT_ACTIVATION_VOLUME =
            new CarActivationVolumeConfig(ACTIVATION_VOLUME_INVOCATION_TYPE,
                    ACTIVATION_VOLUME_PERCENTAGE_MIN, ACTIVATION_VOLUME_PERCENTAGE_MAX);

    @ExcludeFromCodeCoverageGeneratedReport(reason = PRIVATE_CONSTRUCTOR)
    private CarAudioUtils() {
        throw new UnsupportedOperationException();
    }

    static boolean hasExpired(long startTimeMs, long currentTimeMs, int timeoutMs) {
        return (currentTimeMs - startTimeMs) > timeoutMs;
    }

    static boolean isMicrophoneInputDevice(AudioDeviceInfo device) {
        return device.getType() == TYPE_BUILTIN_MIC;
    }

    static CarVolumeGroupEvent convertVolumeChangeToEvent(CarVolumeGroupInfo info, int flags,
            int eventTypes) {
        List<Integer> extraInfos = CarVolumeGroupEvent.convertFlagsToExtraInfo(flags,
                eventTypes);
        return convertVolumeChangesToEvents(List.of(info), eventTypes, extraInfos);
    }

    static CarVolumeGroupEvent convertVolumeChangesToEvents(List<CarVolumeGroupInfo> infoList,
                                                            int eventTypes,
                                                            List<Integer> extraInfos) {
        return new CarVolumeGroupEvent.Builder(infoList, eventTypes, extraInfos).build();
    }

    @Nullable
    static AudioDeviceInfo getAudioDeviceInfo(AudioDeviceAttributes audioDeviceAttributes,
            AudioManagerWrapper audioManager) {
        AudioDeviceInfo[] infos = audioManager.getDevices(GET_DEVICES_OUTPUTS);
        for (int c = 0; c < infos.length; c++) {
            if (!infos[c].getAddress().equals(audioDeviceAttributes.getAddress())) {
                continue;
            }
            return infos[c];
        }
        return null;
    }

    static boolean isDynamicDeviceType(int type) {
        switch (type) {
            case TYPE_WIRED_HEADSET: // fallthrough
            case TYPE_WIRED_HEADPHONES: // fallthrough
            case TYPE_BLUETOOTH_A2DP: // fallthrough
            case TYPE_HDMI: // fallthrough
            case TYPE_USB_ACCESSORY: // fallthrough
            case TYPE_USB_DEVICE: // fallthrough
            case TYPE_USB_HEADSET: // fallthrough
            case TYPE_AUX_LINE: // fallthrough
            case TYPE_BLE_HEADSET: // fallthrough
            case TYPE_BLE_SPEAKER: // fallthrough
            case TYPE_BLE_BROADCAST:
                return true;
            case TYPE_BUILTIN_SPEAKER: // fallthrough
            case TYPE_BUS:  // fallthrough
            default:
                return false;
        }
    }

    static List<AudioDeviceInfo> getDynamicDevicesInConfig(CarAudioZoneConfigInfo zoneConfig,
            AudioManagerWrapper manager) {
        return Flags.carAudioDynamicDevices()
                ? getDynamicAudioDevices(zoneConfig.getConfigVolumeGroups(), manager) : EMPTY_LIST;
    }

    static boolean excludesDynamicDevices(CarAudioZoneConfigInfo zoneConfig) {
        if (!Flags.carAudioDynamicDevices()) {
            return true;
        }
        List<CarVolumeGroupInfo> carVolumeInfos = zoneConfig.getConfigVolumeGroups();
        for (int c = 0; c < carVolumeInfos.size(); c++) {
            if (excludesDynamicDevices(carVolumeInfos.get(c).getAudioDeviceAttributes())) {
                continue;
            }
            return false;
        }
        return true;
    }

    static boolean isInvalidActivationPercentage(int activationValue) {
        return activationValue < ACTIVATION_VOLUME_PERCENTAGE_MIN
                || activationValue > ACTIVATION_VOLUME_PERCENTAGE_MAX;
    }

    private static boolean excludesDynamicDevices(List<AudioDeviceAttributes> devices) {
        for (int c = 0; c < devices.size(); c++) {
            if (!isDynamicDeviceType(devices.get(c).getType())) {
                continue;
            }
            return false;
        }
        return true;
    }

    static List<AudioAttributes> getAudioAttributesForDynamicDevices(CarAudioZoneConfigInfo info) {
        List<AudioAttributes> audioAttributes = new ArrayList<>();
        if (!Flags.carAudioDynamicDevices()) {
            return audioAttributes;
        }
        List<CarVolumeGroupInfo> groups = info.getConfigVolumeGroups();
        for (int c = 0; c < groups.size(); c++) {
            CarVolumeGroupInfo groupInfo = groups.get(c);
            if (excludesDynamicDevices(groupInfo.getAudioDeviceAttributes())) {
                continue;
            }
            audioAttributes.addAll(groupInfo.getAudioAttributes());
        }
        return audioAttributes;
    }

    static ArrayMap<String, CarAudioDeviceInfo> generateAddressToCarAudioDeviceInfoMap(
            List<CarAudioDeviceInfo> carAudioDeviceInfos) {
        Objects.requireNonNull(carAudioDeviceInfos, "Car audio device infos can not be null");
        var addressToCarInfo = new ArrayMap<String, CarAudioDeviceInfo>();
        for (int i = 0; i < carAudioDeviceInfos.size(); i++) {
            CarAudioDeviceInfo info = carAudioDeviceInfos.get(i);
            if (!TextUtils.isEmpty(info.getAddress())) {
                addressToCarInfo.put(info.getAddress(), info);
            }
        }
        return addressToCarInfo;
    }

    static ArrayMap<String, AudioDeviceInfo> generateAddressToInputAudioDeviceInfoMap(
            AudioDeviceInfo[] deviceInfos) {
        Objects.requireNonNull(deviceInfos, "Input audio device infos can not be null");
        var addressToInputDevice = new ArrayMap<String, AudioDeviceInfo>();
        for (AudioDeviceInfo info : deviceInfos) {
            if (info.isSource() && !TextUtils.isEmpty(info.getAddress())) {
                addressToInputDevice.put(info.getAddress(), info);
            }
        }
        return addressToInputDevice;
    }

    static List<CarAudioDeviceInfo> generateCarAudioDeviceInfos(AudioManagerWrapper audioManager) {
        Objects.requireNonNull(audioManager, "Audio manager can not be null");
        AudioDeviceInfo[] deviceInfos = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        List<CarAudioDeviceInfo> carInfos = new ArrayList<>();
        for (int index = 0; index < deviceInfos.length; index++) {
            if (!isValidDeviceType(deviceInfos[index].getType())) {
                continue;
            }
            AudioDeviceInfo info = deviceInfos[index];
            AudioDeviceAttributes attributes = new AudioDeviceAttributes(info);
            CarAudioDeviceInfo carInfo = new CarAudioDeviceInfo(audioManager, attributes);
            carInfo.setAudioDeviceInfo(info);
            carInfos.add(carInfo);
        }
        return carInfos;
    }

    /*
     * Currently only BUS and BUILT_SPEAKER devices are valid static devices.
     */
    private static boolean isValidDeviceType(int type) {
        return type == AudioDeviceInfo.TYPE_BUS || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
    }

    private static List<AudioDeviceInfo> getDynamicAudioDevices(
            List<CarVolumeGroupInfo> volumeGroups, AudioManagerWrapper manager) {
        List<AudioDeviceInfo> dynamicDevices = new ArrayList<>();
        for (int c = 0; c < volumeGroups.size(); c++) {
            dynamicDevices.addAll(getDynamicDevices(volumeGroups.get(c), manager));
        }
        return dynamicDevices;
    }

    private static List<AudioDeviceInfo> getDynamicDevices(CarVolumeGroupInfo carVolumeGroupInfo,
            AudioManagerWrapper manager) {
        List<AudioDeviceInfo> dynamicDevices = new ArrayList<>();
        List<AudioDeviceAttributes> devices = carVolumeGroupInfo.getAudioDeviceAttributes();
        for (int c = 0; c < devices.size(); c++) {
            if (!isDynamicDeviceType(devices.get(c).getType())) {
                continue;
            }
            AudioDeviceInfo info = getAudioDeviceInfo(devices.get(c), manager);
            if (info == null) {
                continue;
            }
            dynamicDevices.add(info);
        }
        return dynamicDevices;
    }
}
