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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
public final class CarZonesAudioFocusUsageTest extends CarZonesAudioFocusTestBase {
    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    private final AudioClientInfo mAudioClientInfo;

    public CarZonesAudioFocusUsageTest(AudioClientInfo audioClientInfo) {
        mAudioClientInfo = audioClientInfo;
    }

    @Parameterized.Parameters
    public static Collection provideParams() {
        return Arrays.asList(
                new Object[][] {
                        {INVALID_SOUND_INFO_1},
                        {MEDIA_INFO_1},
                        {NAVIGATION_INFO},
                        {VOICE_COMMAND_INFO},
                        {CALL_RING_INFO},
                        {CALL_INFO},
                        {ALARM_INFO},
                        {NOTIFICATION_INFO},
                        {SYSTEM_SOUND_INFO},
                        {EMERGENCY_INFO},
                        {SAFETY_INFO},
                        {VEHICLE_STATUS_INFO},
                        {ANNOUNCEMENT_INFO_1}
                });
    }

    @Test
    public void requestAndAbandonFocus_forEveryUsage_requestAndAbandonSucceed() {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();

        when(mCarAudioService.getZoneIdForUid(mAudioClientInfo.getClientUid()))
                .thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo audioFocusClient =
                new AudioFocusInfoBuilder().setUsage(mAudioClientInfo.getUsage())
                        .setGainRequest(AUDIOFOCUS_GAIN)
                        .setClientId(mAudioClientInfo.getClientId())
                        .setClientUid(mAudioClientInfo.getClientUid()).createAudioFocusInfo();

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, audioFocusClient);

        carZonesAudioFocus.onAudioFocusAbandon(audioFocusClient);

        verify(mMockAudioManager, never()).dispatchAudioFocusChange(eq(audioFocusClient),
                anyInt(), eq(mAudioPolicy));
    }
}
