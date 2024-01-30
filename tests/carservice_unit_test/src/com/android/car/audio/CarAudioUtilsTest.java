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

import static android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC;
import static android.media.AudioDeviceInfo.TYPE_FM_TUNER;

import static com.android.car.audio.CarAudioUtils.hasExpired;
import static com.android.car.audio.CarAudioUtils.isMicrophoneInputDevice;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@RunWith(AndroidJUnit4.class)
public class CarAudioUtilsTest {

    public static final String TEST_ADDRESS_1 = "test_address_1";
    public static final String TEST_ADDRESS_2 = "test_address_2";
    public static final String TEST_NOT_AVAILABLE_ADDRESS = "test_not_available_address";

    @Test
    public void hasExpired_forCurrentTimeBeforeTimeout_returnsFalse() {
        assertThat(hasExpired(0, 100, 200)).isFalse();
    }

    @Test
    public void hasExpired_forCurrentTimeAfterTimeout_returnsFalse() {
        assertThat(hasExpired(0, 300, 200)).isTrue();
    }

    @Test
    public void isMicrophoneInputDevice_forMicrophoneDevice_returnsTrue() {
        AudioDeviceInfo deviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(deviceInfo.getType()).thenReturn(TYPE_BUILTIN_MIC);
        assertThat(isMicrophoneInputDevice(deviceInfo)).isTrue();
    }

    @Test
    public void isMicrophoneInputDevice_forNonMicrophoneDevice_returnsFalse() {
        AudioDeviceInfo deviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(deviceInfo.getType()).thenReturn(TYPE_FM_TUNER);
        assertThat(isMicrophoneInputDevice(deviceInfo)).isFalse();
    }

    @Test
    public void getAudioDeviceInfo() {
        AudioDeviceInfo info1 = getTestAudioDeviceInfo(TEST_ADDRESS_1);
        AudioDeviceInfo info2 = getTestAudioDeviceInfo(TEST_ADDRESS_2);
        AudioManager audioManager = Mockito.mock(AudioManager.class);
        when(audioManager.getDevices(anyInt())).thenReturn(new AudioDeviceInfo[]{info2, info1});
        AudioDeviceAttributes attributes =
                new AudioDeviceAttributes(TYPE_BLUETOOTH_A2DP, TEST_ADDRESS_1);

        AudioDeviceInfo info = CarAudioUtils.getAudioDeviceInfo(attributes, audioManager);

        assertWithMessage("Audio device info").that(info).isEqualTo(info1);
    }

    @Test
    public void getAudioDeviceInfo_withDeviceNotAvailable() {
        AudioDeviceInfo info1 = getTestAudioDeviceInfo(TEST_ADDRESS_1);
        AudioDeviceInfo info2 = getTestAudioDeviceInfo(TEST_ADDRESS_2);
        AudioManager audioManager = Mockito.mock(AudioManager.class);
        when(audioManager.getDevices(anyInt())).thenReturn(new AudioDeviceInfo[]{info2, info1});
        AudioDeviceAttributes attributes =
                new AudioDeviceAttributes(TYPE_BLUETOOTH_A2DP, TEST_NOT_AVAILABLE_ADDRESS);

        AudioDeviceInfo info = CarAudioUtils.getAudioDeviceInfo(attributes, audioManager);

        assertWithMessage("Not available audio device info").that(info).isNull();
    }

    private static AudioDeviceInfo getTestAudioDeviceInfo(String address) {
        AudioDeviceInfo deviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(deviceInfo.getAddress()).thenReturn(address);
        return deviceInfo;
    }
}
