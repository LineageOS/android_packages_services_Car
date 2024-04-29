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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.media.AudioDeviceInfo;
import android.media.AudioGain;

public final class AudioDeviceInfoBuilder {

    private String mAddressName;
    private AudioGain[] mAudioGains;
    private int mDeviceType = AudioDeviceInfo.TYPE_BUS;
    private boolean mIsSource;
    private boolean mBuilt = false;

    /**
     * Sets the audio gains.
     */
    public AudioDeviceInfoBuilder setAudioGains(AudioGain ... audioGains) {
        mAudioGains = audioGains;
        return this;
    }

    /**
     * Sets the address name.
     */
    public AudioDeviceInfoBuilder setAddressName(String addressName) {
        mAddressName = addressName;
        return this;
    }

    /**
     * Sets the device type.
     */
    public AudioDeviceInfoBuilder setType(int deviceType) {
        mDeviceType = deviceType;
        return this;
    }

    /**
     * Sets whether is source.
     */
    public AudioDeviceInfoBuilder setIsSource(boolean isSource) {
        mIsSource = isSource;
        return this;
    }

    /**
     * Builds the audio device info.
     */
    public AudioDeviceInfo build() {
        if (mBuilt) {
            throw new IllegalStateException("A builder is only supposed to be built once");
        }
        mBuilt = true;
        AudioDeviceInfo audioDeviceInfo = mock(AudioDeviceInfo.class);
        when(audioDeviceInfo.getAddress()).thenReturn(mAddressName);
        when(audioDeviceInfo.isSource()).thenReturn(mIsSource);
        return audioDeviceInfo;
    }
}
