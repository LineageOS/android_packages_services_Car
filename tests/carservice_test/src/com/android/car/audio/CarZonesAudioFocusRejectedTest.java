/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.media.AudioManager.AUDIOFOCUS_GAIN;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED;
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.AudioFocusInfo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public final class CarZonesAudioFocusRejectedTest extends CarZonesAudioFocusTestBase {
    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    private final AudioClientInfo mAcceptedAudioClientInfo;
    private final AudioClientInfo mRejectedAudioClientInfo;

    public CarZonesAudioFocusRejectedTest(AudioClientInfo acceptedAudioClientInfo,
            AudioClientInfo rejectedAudioClientInfo) {
        mAcceptedAudioClientInfo = acceptedAudioClientInfo;
        mRejectedAudioClientInfo = rejectedAudioClientInfo;

    }

    @Parameterized.Parameters
    public static Collection provideParams() {
        return Arrays.asList(
                new Object[][] {
                        {INVALID_SOUND_INFO_1, INVALID_SOUND_INFO_2},
                        {INVALID_SOUND_INFO_1, MEDIA_INFO},
                        {INVALID_SOUND_INFO_1, NAVIGATION_INFO},
                        {INVALID_SOUND_INFO_1, VOICE_COMMAND_INFO},
                        {INVALID_SOUND_INFO_1, CALL_RING_INFO},
                        {INVALID_SOUND_INFO_1, CALL_INFO},
                        {INVALID_SOUND_INFO_1, ALARM_INFO},
                        {INVALID_SOUND_INFO_1, NOTIFICATION_INFO},
                        {INVALID_SOUND_INFO_1, SYSTEM_SOUND_INFO},
                        {INVALID_SOUND_INFO_1, VEHICLE_STATUS_INFO},
                        {INVALID_SOUND_INFO_1, ANNOUNCEMENT_INFO},

                        {MEDIA_INFO, INVALID_SOUND_INFO_1},

                        {NAVIGATION_INFO, INVALID_SOUND_INFO_1},

                        {VOICE_COMMAND_INFO, INVALID_SOUND_INFO_1},
                        {VOICE_COMMAND_INFO, NAVIGATION_INFO},
                        {VOICE_COMMAND_INFO, NOTIFICATION_INFO},
                        {VOICE_COMMAND_INFO, SYSTEM_SOUND_INFO},
                        {VOICE_COMMAND_INFO, ANNOUNCEMENT_INFO},

                        {CALL_RING_INFO, INVALID_SOUND_INFO_1},
                        {CALL_RING_INFO, MEDIA_INFO},
                        {CALL_RING_INFO, ALARM_INFO},
                        {CALL_RING_INFO, NOTIFICATION_INFO},
                        {CALL_RING_INFO, ANNOUNCEMENT_INFO},

                        {CALL_INFO, INVALID_SOUND_INFO_1},
                        {CALL_INFO, MEDIA_INFO},
                        {CALL_INFO, VOICE_COMMAND_INFO},
                        {CALL_INFO, SYSTEM_SOUND_INFO},
                        {CALL_INFO, ANNOUNCEMENT_INFO},

                        {ALARM_INFO, INVALID_SOUND_INFO_1},
                        {ALARM_INFO, ANNOUNCEMENT_INFO},

                        {NOTIFICATION_INFO, INVALID_SOUND_INFO_1},

                        {SYSTEM_SOUND_INFO, INVALID_SOUND_INFO_1},

                        {EMERGENCY_INFO, INVALID_SOUND_INFO_1},
                        {EMERGENCY_INFO, MEDIA_INFO},
                        {EMERGENCY_INFO, NAVIGATION_INFO},
                        {EMERGENCY_INFO, VOICE_COMMAND_INFO},
                        {EMERGENCY_INFO, CALL_RING_INFO},
                        {EMERGENCY_INFO, ALARM_INFO},
                        {EMERGENCY_INFO, NOTIFICATION_INFO},
                        {EMERGENCY_INFO, SYSTEM_SOUND_INFO},
                        {EMERGENCY_INFO, VEHICLE_STATUS_INFO},
                        {EMERGENCY_INFO, ANNOUNCEMENT_INFO},

                        {SAFETY_INFO, INVALID_SOUND_INFO_1},

                        {VEHICLE_STATUS_INFO, INVALID_SOUND_INFO_1},

                        {ANNOUNCEMENT_INFO, INVALID_SOUND_INFO_1}
                });
    }

    @Test
    public void rejectedInteractionsFocusTest() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();

        when(mCarAudioService.getZoneIdForUid(mAcceptedAudioClientInfo.getClientUid()))
                .thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo acceptedFocusInfo =
                new AudioFocusInfoBuilder().setUsage(mAcceptedAudioClientInfo.getUsage())
                        .setGainRequest(AUDIOFOCUS_GAIN)
                        .setClientId(mAcceptedAudioClientInfo.getClientId())
                        .setClientUid(mAcceptedAudioClientInfo.getClientUid())
                        .createAudioFocusInfo();

        when(mCarAudioService.getZoneIdForUid(mRejectedAudioClientInfo.getClientUid()))
                .thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo rejectedFocusInfo =
                new AudioFocusInfoBuilder().setUsage(mRejectedAudioClientInfo.getUsage())
                        .setGainRequest(AUDIOFOCUS_GAIN)
                        .setClientId(mRejectedAudioClientInfo.getClientId())
                        .setClientUid(mRejectedAudioClientInfo.getClientUid())
                        .createAudioFocusInfo();

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, acceptedFocusInfo);

        carZonesAudioFocus
                .onAudioFocusRequest(rejectedFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        verify(mMockAudioManager).setFocusRequestResult(rejectedFocusInfo,
                AUDIOFOCUS_REQUEST_FAILED, mAudioPolicy);
    }
}
