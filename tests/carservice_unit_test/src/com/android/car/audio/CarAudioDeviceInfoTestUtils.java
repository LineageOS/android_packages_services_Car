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

import static android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC;
import static android.media.AudioDeviceInfo.TYPE_BUS;
import static android.media.AudioDeviceInfo.TYPE_FM_TUNER;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.media.AudioDeviceInfo;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.util.List;

@ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
class CarAudioDeviceInfoTestUtils {

    static final String MEDIA_TEST_DEVICE = "media_bus_device";
    static final String OEM_TEST_DEVICE = "oem_bus_device";
    static final String MIRROR_TEST_DEVICE = "mirror_bus_device";
    static final String NAVIGATION_TEST_DEVICE = "navigation_bus_device";
    static final String CALL_TEST_DEVICE = "call_bus_device";
    static final String NOTIFICATION_TEST_DEVICE = "notification_bus_device";
    static final String VOICE_TEST_DEVICE = "voice_bus_device";
    static final String RING_TEST_DEVICE = "ring_bus_device";
    static final String ALARM_TEST_DEVICE = "alarm_bus_device";
    static final String SYSTEM_BUS_DEVICE = "system_bus_device";
    static final String SECONDARY_TEST_DEVICE_CONFIG_0 = "secondary_zone_bus_100";
    static final String SECONDARY_TEST_DEVICE_CONFIG_1_0 = "secondary_zone_bus_200";
    static final String SECONDARY_TEST_DEVICE_CONFIG_1_1 = "secondary_zone_bus_201";
    static final String TEST_BT_DEVICE = "08:67:53:09";
    static final String TERTIARY_TEST_DEVICE_1 = "tertiary_zone_bus_100";
    static final String TERTIARY_TEST_DEVICE_2 = "tertiary_zone_bus_200";
    static final String QUATERNARY_TEST_DEVICE_1 = "quaternary_zone_bus_1";
    static final String TEST_REAR_ROW_3_DEVICE = "rear_row_three_zone_bus_1";
    static final String TEST_SPEAKER_DEVICE = "speaker";

    static final String ADDRESS_DOES_NOT_EXIST_DEVICE = "bus1000_does_not_exist";

    static final String PRIMARY_ZONE_MICROPHONE_DEVICE = "Built-In Mic";
    static final String PRIMARY_ZONE_FM_TUNER_DEVICE = "fm_tuner";
    static final String SECONDARY_ZONE_BACK_MICROPHONE_DEVICE = "Built-In Back Mic";
    static final String SECONDARY_ZONE_BUS_1000_INPUT_DEVICE = "bus_1000_input";

    final AudioDeviceInfo mMediaOutputDevice;
    final AudioDeviceInfo mNotificationOutputBus;
    final AudioDeviceInfo mNavOutputDevice;
    final AudioDeviceInfo mVoiceOutputBus;
    final AudioDeviceInfo mSecondaryConfig1Group0Device;
    final AudioDeviceInfo mSecondaryConfig1Group1Device;
    final AudioDeviceInfo mBTAudioDeviceInfo;
    final AudioDeviceInfo mSecondaryConfigOutputDevice;

    private final List<AudioDeviceInfo> mAudioOutputDeviceInfos;
    private final List<AudioDeviceInfo> mAudioInputDeviceInfos;

    CarAudioDeviceInfoTestUtils() {
        mMediaOutputDevice = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(MEDIA_TEST_DEVICE)
                .build();
        mNotificationOutputBus = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(NOTIFICATION_TEST_DEVICE)
                .build();
        mNavOutputDevice = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(NAVIGATION_TEST_DEVICE)
                .build();
        mVoiceOutputBus = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(VOICE_TEST_DEVICE)
                .build();
        mBTAudioDeviceInfo = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(TEST_BT_DEVICE)
                .setType(TYPE_BLUETOOTH_A2DP)
                .build();
        AudioDeviceInfo callOutputDevice = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(CALL_TEST_DEVICE)
                .build();
        AudioDeviceInfo systemOutputDevice = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(SYSTEM_BUS_DEVICE)
                .build();
        AudioDeviceInfo ringOutputDevice = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(RING_TEST_DEVICE)
                .build();
        AudioDeviceInfo alarmOutputDevice = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(ALARM_TEST_DEVICE)
                .build();
        mSecondaryConfigOutputDevice = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(SECONDARY_TEST_DEVICE_CONFIG_0)
                .build();
        mSecondaryConfig1Group0Device = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(SECONDARY_TEST_DEVICE_CONFIG_1_0)
                .build();
        mSecondaryConfig1Group1Device = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(SECONDARY_TEST_DEVICE_CONFIG_1_1)
                .build();
        AudioDeviceInfo speakerDevice = new AudioDeviceInfoBuilder()
                .setAudioGains(new GainBuilder().build())
                .setAddressName(TEST_SPEAKER_DEVICE).build();

        mAudioOutputDeviceInfos = List.of(
                mBTAudioDeviceInfo,
                mMediaOutputDevice,
                mNavOutputDevice,
                callOutputDevice,
                systemOutputDevice,
                mNotificationOutputBus,
                mVoiceOutputBus,
                ringOutputDevice,
                alarmOutputDevice,
                mSecondaryConfig1Group0Device,
                mSecondaryConfig1Group1Device,
                mSecondaryConfigOutputDevice,
                speakerDevice,
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new GainBuilder().build())
                        .setAddressName(TERTIARY_TEST_DEVICE_1)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new GainBuilder().build())
                        .setAddressName(TERTIARY_TEST_DEVICE_2)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new GainBuilder().build())
                        .setAddressName(QUATERNARY_TEST_DEVICE_1)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new GainBuilder().build())
                        .setAddressName(OEM_TEST_DEVICE)
                        .build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new GainBuilder().build())
                        .setAddressName(MIRROR_TEST_DEVICE).build(),
                new AudioDeviceInfoBuilder()
                        .setAudioGains(new GainBuilder().build())
                        .setAddressName(TEST_REAR_ROW_3_DEVICE).build()
        );
        mAudioInputDeviceInfos = List.of(
                generateInputAudioDeviceInfo(PRIMARY_ZONE_MICROPHONE_DEVICE, TYPE_BUILTIN_MIC),
                generateInputAudioDeviceInfo(PRIMARY_ZONE_FM_TUNER_DEVICE, TYPE_FM_TUNER),
                generateInputAudioDeviceInfo(SECONDARY_ZONE_BACK_MICROPHONE_DEVICE, TYPE_BUS),
                generateInputAudioDeviceInfo(SECONDARY_ZONE_BUS_1000_INPUT_DEVICE, TYPE_BUILTIN_MIC)
        );
    }

    AudioDeviceInfo[] generateOutputDeviceInfos() {
        return mAudioOutputDeviceInfos.toArray(new AudioDeviceInfo[0]);
    }

    AudioDeviceInfo[] generateInputDeviceInfos() {
        return mAudioInputDeviceInfos.toArray(new AudioDeviceInfo[0]);
    }

    static CarAudioDeviceInfo generateCarAudioDeviceInfo(String address) {
        CarAudioDeviceInfo cadiMock = mock(CarAudioDeviceInfo.class);
        when(cadiMock.getStepValue()).thenReturn(1);
        when(cadiMock.getDefaultGain()).thenReturn(2);
        when(cadiMock.getMaxGain()).thenReturn(5);
        when(cadiMock.getMinGain()).thenReturn(0);
        when(cadiMock.getAddress()).thenReturn(address);
        return cadiMock;
    }

    static AudioDeviceInfo generateInputAudioDeviceInfo(String address, int type) {
        AudioDeviceInfo inputMock = mock(AudioDeviceInfo.class);
        when(inputMock.getAddress()).thenReturn(address);
        when(inputMock.getType()).thenReturn(type);
        when(inputMock.isSource()).thenReturn(true);
        when(inputMock.isSink()).thenReturn(false);
        when(inputMock.getInternalType()).thenReturn(
                AudioDeviceInfo.convertDeviceTypeToInternalInputDevice(type));
        return inputMock;
    }
}
