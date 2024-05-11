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
package com.android.car.audio.hal;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.PRIVATE_CONSTRUCTOR;

import android.media.audio.common.AudioDevice;
import android.media.audio.common.AudioDeviceAddress;
import android.media.audio.common.AudioDeviceDescription;
import android.media.audio.common.AudioGain;
import android.media.audio.common.AudioPort;
import android.media.audio.common.AudioPortDeviceExt;
import android.media.audio.common.AudioPortExt;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

@ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
public final class CarAudioHalTestUtils {

    @ExcludeFromCodeCoverageGeneratedReport(reason = PRIVATE_CONSTRUCTOR)
    private CarAudioHalTestUtils() {
        throw new UnsupportedOperationException();
    }

    static AudioPortDeviceExt createAudioPortDeviceExt(int type, String connection,
                                                               String address) {
        AudioPortDeviceExt deviceExt = new AudioPortDeviceExt();
        deviceExt.device = new AudioDevice();
        deviceExt.device.type = new AudioDeviceDescription();
        deviceExt.device.type.type = type;
        deviceExt.device.type.connection = connection;
        deviceExt.device.address = AudioDeviceAddress.id(address);
        return deviceExt;
    }

    static AudioPort createAudioPort(int id, String name, AudioGain[] gains,
                                             AudioPortDeviceExt deviceExt) {
        AudioPort audioPort = new AudioPort();
        audioPort.id = id;
        audioPort.name = name;
        audioPort.gains = gains;
        audioPort.ext = AudioPortExt.device(deviceExt);
        return audioPort;
    }
}
