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

import static android.media.AudioAttributes.USAGE_ALARM;
import static android.media.AudioAttributes.USAGE_ANNOUNCEMENT;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
import static android.media.AudioAttributes.USAGE_ASSISTANT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_GAME;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioAttributes.USAGE_UNKNOWN;
import static android.media.AudioAttributes.USAGE_VEHICLE_STATUS;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;

import static com.android.car.audio.CarAudioContext.getAudioAttributeFromUsage;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.car.builtin.media.AudioManagerHelper;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.audio.common.AudioDevice;
import android.media.audio.common.AudioDeviceAddress;
import android.media.audio.common.AudioDeviceDescription;
import android.media.audio.common.AudioGain;
import android.media.audio.common.AudioPort;
import android.media.audio.common.AudioPortDeviceExt;
import android.media.audio.common.AudioPortExt;
import android.os.Build;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.util.List;


@ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
public final class CarAudioTestUtils {

    private static final String PACKAGE_NAME = "com.android.car.audio";
    private static final int AUDIOFOCUS_FLAG = 0;

    public static final AudioAttributes TEST_UNKNOWN_USAGE_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_UNKNOWN);
    public static final AudioAttributes TEST_GAME_USAGE_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_GAME);
    public static final AudioAttributes TEST_CALL_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_VOICE_COMMUNICATION);
    public static final AudioAttributes TEST_RINGER_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_NOTIFICATION_RINGTONE);
    public static final AudioAttributes TEST_MEDIA_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_MEDIA);
    public static final AudioAttributes TEST_EMERGENCY_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_EMERGENCY);
    public static final AudioAttributes TEST_INVALID_ATTRIBUTE =
            getAudioAttributeFromUsage(AudioManagerHelper
                    .getUsageVirtualSource());
    public static final AudioAttributes TEST_NAVIGATION_ATTRIBUTE =
            getAudioAttributeFromUsage(
                    USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
    public static final AudioAttributes TEST_ASSISTANT_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_ASSISTANT);
    public static final AudioAttributes TEST_ALARM_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_ALARM);
    public static final AudioAttributes TEST_NOTIFICATION_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_NOTIFICATION);
    public static final AudioAttributes TEST_SYSTEM_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_ASSISTANCE_SONIFICATION);
    public static final AudioAttributes TEST_VEHICLE_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_VEHICLE_STATUS);
    public static final AudioAttributes TEST_ANNOUNCEMENT_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_ANNOUNCEMENT);
    public static final AudioAttributes TEST_SAFETY_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_SAFETY);
    public static final AudioAttributes TEST_NOTIFICATION_EVENT_ATTRIBUTE =
            getAudioAttributeFromUsage(USAGE_NOTIFICATION_EVENT);

    private static final CarAudioContextInfo TEST_CONTEXT_INFO_MUSIC =
            new CarAudioContextInfo(new AudioAttributes[] {
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_UNKNOWN),
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_GAME),
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_MEDIA)
            }, "MUSIC", 1);

    private static final CarAudioContextInfo TEST_CONTEXT_INFO_NAVIGATION =
            new CarAudioContextInfo(new AudioAttributes[] {
                    getAudioAttributeFromUsage(AudioAttributes
                            .USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            }, "NAVIGATION", 2);

    private static final CarAudioContextInfo TEST_CONTEXT_INFO_VOICE_COMMAND =
            new CarAudioContextInfo(new AudioAttributes[] {
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY),
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_ASSISTANT)
            }, "VOICE_COMMAND", 3);

    private static final CarAudioContextInfo TEST_CONTEXT_INFO_CALL_RING =
            new CarAudioContextInfo(new AudioAttributes[] {
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            }, "CALL_RING", 4);

    private static final CarAudioContextInfo TEST_CONTEXT_INFO_CALL =
            new CarAudioContextInfo(new AudioAttributes[] {
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION),
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_CALL_ASSISTANT),
                    getAudioAttributeFromUsage(AudioAttributes
                            .USAGE_VOICE_COMMUNICATION_SIGNALLING),
            }, "CALL", 5);

    private static final CarAudioContextInfo TEST_CONTEXT_INFO_ALARM =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_ALARM)
            }, "ALARM", 6);

    private static final CarAudioContextInfo TEST_CONTEXT_INFO_NOTIFICATION =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_NOTIFICATION),
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            }, "NOTIFICATION", 7);

    private static final CarAudioContextInfo TEST_CONTEXT_INFO_SYSTEM_SOUND =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            }, "SYSTEM_SOUND", 8);

    private static final CarAudioContextInfo TEST_CONTEXT_INFO_EMERGENCY =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_EMERGENCY)
            }, "EMERGENCY", 9);

    private static final CarAudioContextInfo TEST_CONTEXT_INFO_SAFETY =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_SAFETY)
            }, "SAFETY", 10);

    private static final CarAudioContextInfo TEST_CONTEXT_INFO_VEHICLE_STATUS =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_VEHICLE_STATUS)
            }, "VEHICLE_STATUS", 11);

    private static final CarAudioContextInfo TEST_CONTEXT_INFO_ANNOUNCEMENT =
            new CarAudioContextInfo(new AudioAttributes[]{
                    getAudioAttributeFromUsage(AudioAttributes.USAGE_ANNOUNCEMENT)
            }, "ANNOUNCEMENT", 12);

    public static final CarAudioContext TEST_CREATED_CAR_AUDIO_CONTEXT =
            new CarAudioContext(List.of(TEST_CONTEXT_INFO_MUSIC, TEST_CONTEXT_INFO_NAVIGATION,
                    TEST_CONTEXT_INFO_VOICE_COMMAND, TEST_CONTEXT_INFO_CALL_RING,
                    TEST_CONTEXT_INFO_CALL, TEST_CONTEXT_INFO_ALARM, TEST_CONTEXT_INFO_NOTIFICATION,
                    TEST_CONTEXT_INFO_SYSTEM_SOUND, TEST_CONTEXT_INFO_EMERGENCY,
                    TEST_CONTEXT_INFO_SAFETY, TEST_CONTEXT_INFO_VEHICLE_STATUS,
                    TEST_CONTEXT_INFO_ANNOUNCEMENT),
                    /* useCoreAudioRouting= */ false);

    public static final CarAudioContext TEST_CAR_AUDIO_CONTEXT =
            new CarAudioContext(CarAudioContext.getAllContextsInfo(),
                    /* useCoreAudioRouting= */ false);

    private CarAudioTestUtils() {
        throw new UnsupportedOperationException();
    }

    static AudioFocusInfo getInfo(AudioAttributes audioAttributes, String clientId, int gainType,
            boolean acceptsDelayedFocus, boolean pauseInsteadOfDucking, int uid) {
        int flags = AUDIOFOCUS_FLAG;
        if (acceptsDelayedFocus) {
            flags |= AudioManager.AUDIOFOCUS_FLAG_DELAY_OK;
        }
        if (pauseInsteadOfDucking) {
            flags |= AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS;
        }
        return new AudioFocusInfo(audioAttributes, uid, clientId, PACKAGE_NAME,
                gainType, AudioManager.AUDIOFOCUS_NONE,
                flags, Build.VERSION.SDK_INT);
    }

    /**
     * Creates an external audio port device
     *
     * @param type Type of audio device (e.g. BUS)
     * @param connection Connection for the audio device
     * @param address Device address
     * @return Created audio device
     */
    public static AudioPortDeviceExt createAudioPortDeviceExt(int type, String connection,
                                                              String address) {
        AudioPortDeviceExt deviceExt = new AudioPortDeviceExt();
        deviceExt.device = new AudioDevice();
        deviceExt.device.type = new AudioDeviceDescription();
        deviceExt.device.type.type = type;
        deviceExt.device.type.connection = connection;
        deviceExt.device.address = AudioDeviceAddress.id(address);
        return deviceExt;
    }

    /**
     * Creates an audio port for testing
     *
     * @param id ID of the audio port device
     * @param name Name of the audio port
     * @param gains Gains of the audio port
     * @param deviceExt External device represented by the port
     *
     * @return Created audio device port
     */
    public static AudioPort createAudioPort(int id, String name, AudioGain[] gains,
                                            AudioPortDeviceExt deviceExt) {
        AudioPort audioPort = new AudioPort();
        audioPort.id = id;
        audioPort.name = name;
        audioPort.gains = gains;
        audioPort.ext = AudioPortExt.device(deviceExt);
        return audioPort;
    }
}
