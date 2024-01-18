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

import static android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC;
import static android.media.AudioManager.GET_DEVICES_OUTPUTS;

import android.annotation.Nullable;
import android.car.media.CarVolumeGroupEvent;
import android.car.media.CarVolumeGroupInfo;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import java.util.List;

final class CarAudioUtils {

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
        List<Integer> extraInfos = CarVolumeGroupEvent.convertFlagsToExtraInfo(flags, eventTypes);
        return new CarVolumeGroupEvent.Builder(List.of(info), eventTypes, extraInfos).build();
    }

    @Nullable
    static AudioDeviceInfo getAudioDeviceInfo(AudioDeviceAttributes audioDeviceAttributes,
            AudioManager audioManager) {
        AudioDeviceInfo[] infos = audioManager.getDevices(GET_DEVICES_OUTPUTS);
        for (int c = 0; c < infos.length; c++) {
            if (!infos[c].getAddress().equals(audioDeviceAttributes.getAddress())) {
                continue;
            }
            return infos[c];
        }
        return null;
    }
}
