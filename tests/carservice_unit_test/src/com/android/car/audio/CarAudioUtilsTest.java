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
import static android.media.AudioDeviceInfo.TYPE_FM_TUNER;
import static android.media.AudioDeviceInfo.TYPE_HDMI;
import static android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY;
import static android.media.AudioDeviceInfo.TYPE_USB_DEVICE;
import static android.media.AudioDeviceInfo.TYPE_USB_HEADSET;
import static android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
import static android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET;

import static com.android.car.audio.CarAudioUtils.hasExpired;
import static com.android.car.audio.CarAudioUtils.isMicrophoneInputDevice;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.car.test.AbstractExpectableTestCase;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.internal.util.DebugUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CarAudioUtilsTest extends AbstractExpectableTestCase {

    public static final String TEST_ADDRESS_1 = "test_address_1";
    public static final String TEST_ADDRESS_2 = "test_address_2";
    public static final String TEST_NOT_AVAILABLE_ADDRESS = "test_not_available_address";

    @Test
    public void hasExpired_forCurrentTimeBeforeTimeout() {
        expectWithMessage("Unexpired state").that(hasExpired(/*startTimeMs= */ 0,
                /*currentTimeMs= */ 100, /*timeoutMs= */ 200)).isFalse();
    }

    @Test
    public void hasExpired_forCurrentTimeAfterTimeout() {
        expectWithMessage("Expired state").that(hasExpired(/*startTimeMs= */ 0,
                /*currentTimeMs= */ 300, /*timeoutMs= */ 200)).isTrue();
    }

    @Test
    public void isMicrophoneInputDevice_forMicrophoneDevice() {
        AudioDeviceInfo deviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(deviceInfo.getType()).thenReturn(TYPE_BUILTIN_MIC);
        expectWithMessage("Microphone device").that(isMicrophoneInputDevice(deviceInfo)).isTrue();
    }

    @Test
    public void isMicrophoneInputDevice_forNonMicrophoneDevice() {
        AudioDeviceInfo deviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(deviceInfo.getType()).thenReturn(TYPE_FM_TUNER);
        expectWithMessage("Non microphone device")
                .that(isMicrophoneInputDevice(deviceInfo)).isFalse();
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

        expectWithMessage("Audio device info").that(info).isEqualTo(info1);
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

        expectWithMessage("Not available audio device info").that(info).isNull();
    }

    @Test
    public void isDynamicDeviceType_forDynamicDevices() {
        List<Integer> dynamicDevices = List.of(TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES,
                TYPE_BLUETOOTH_A2DP, TYPE_HDMI, TYPE_USB_ACCESSORY, TYPE_USB_DEVICE,
                TYPE_USB_HEADSET, TYPE_AUX_LINE, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER,
                TYPE_BLE_BROADCAST);

        for (int dynamicDeviceType : dynamicDevices) {
            expectWithMessage("Dynamic Audio device type %s", DebugUtils.constantToString(
                    AudioDeviceInfo.class, /* prefix= */ "TYPE_", dynamicDeviceType))
                    .that(CarAudioUtils.isDynamicDeviceType(dynamicDeviceType)).isTrue();
        }
    }

    @Test
    public void isDynamicDeviceType_forNonDynamicDevice() {
        List<Integer> dynamicDevices = List.of(TYPE_BUILTIN_SPEAKER, TYPE_BUS);

        for (int dynamicDeviceType : dynamicDevices) {
            expectWithMessage("Non dynamic audio device type %s", DebugUtils.constantToString(
                    AudioDeviceInfo.class, /* prefix= */ "TYPE_", dynamicDeviceType))
                    .that(CarAudioUtils.isDynamicDeviceType(dynamicDeviceType)).isFalse();
        }
    }

    private static AudioDeviceInfo getTestAudioDeviceInfo(String address) {
        AudioDeviceInfo deviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(deviceInfo.getAddress()).thenReturn(address);
        return deviceInfo;
    }
}
