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
import static android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.AudioFocusInfo;
import android.media.AudioManager;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public final class CarZonesAudioFocusExclusiveTest extends CarZonesAudioFocusTestBase {
    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    private final AudioClientInfo mExcludedAudioClientInfo;
    private final AudioClientInfo mAcceptedAudioClientInfo;

    public CarZonesAudioFocusExclusiveTest(AudioClientInfo excludedAudioClientInfo,
            AudioClientInfo acceptedAudioClientInfo) {
        mExcludedAudioClientInfo = excludedAudioClientInfo;
        mAcceptedAudioClientInfo = acceptedAudioClientInfo;
    }

    @Parameterized.Parameters
    public static Collection provideParams() {
        return Arrays.asList(
                new Object[][]{
                        {INVALID_SOUND_INFO_1, EMERGENCY_INFO},
                        {INVALID_SOUND_INFO_1, SAFETY_INFO},

                        {MEDIA_INFO_1, MEDIA_INFO_2},
                        {MEDIA_INFO_1, VOICE_COMMAND_INFO},
                        {MEDIA_INFO_1, CALL_RING_INFO},
                        {MEDIA_INFO_1, CALL_INFO},
                        {MEDIA_INFO_1, ALARM_INFO},
                        {MEDIA_INFO_1, EMERGENCY_INFO},
                        {MEDIA_INFO_1, ANNOUNCEMENT_INFO_1},

                        {NAVIGATION_INFO, VOICE_COMMAND_INFO},
                        {NAVIGATION_INFO, CALL_INFO},
                        {NAVIGATION_INFO, EMERGENCY_INFO},

                        {VOICE_COMMAND_INFO, CALL_RING_INFO},
                        {VOICE_COMMAND_INFO, CALL_INFO},
                        {VOICE_COMMAND_INFO, EMERGENCY_INFO},

                        {CALL_RING_INFO, EMERGENCY_INFO},

                        {ALARM_INFO, VOICE_COMMAND_INFO},
                        {ALARM_INFO, CALL_RING_INFO},
                        {ALARM_INFO, CALL_INFO},
                        {ALARM_INFO, EMERGENCY_INFO},

                        {NOTIFICATION_INFO, VOICE_COMMAND_INFO},
                        {NOTIFICATION_INFO, CALL_RING_INFO},
                        {NOTIFICATION_INFO, CALL_INFO},
                        {NOTIFICATION_INFO, EMERGENCY_INFO},

                        {SYSTEM_SOUND_INFO, VOICE_COMMAND_INFO},
                        {SYSTEM_SOUND_INFO, CALL_RING_INFO},
                        {SYSTEM_SOUND_INFO, CALL_INFO},
                        {SYSTEM_SOUND_INFO, EMERGENCY_INFO},

                        {VEHICLE_STATUS_INFO, EMERGENCY_INFO},

                        {ANNOUNCEMENT_INFO_1, MEDIA_INFO_1},
                        {ANNOUNCEMENT_INFO_1, VOICE_COMMAND_INFO},
                        {ANNOUNCEMENT_INFO_1, CALL_RING_INFO},
                        {ANNOUNCEMENT_INFO_1, CALL_INFO},
                        {ANNOUNCEMENT_INFO_1, ALARM_INFO},
                        {ANNOUNCEMENT_INFO_1, EMERGENCY_INFO},
                        {ANNOUNCEMENT_INFO_1, ANNOUNCEMENT_INFO_2}
                });
    }

    @Test
    public void exclusiveInteractionsForFocusGainNoPause_RequestGrantedAndFocusLossSent() {
        testExclusiveNonTransientInteractions(/* pauseForDucking= */ false);
    }

    @Test
    public void exclusiveInteractionsForFocusGainPause_RequestGrantedAndFocusLossSent() {
        testExclusiveNonTransientInteractions(/* pauseForDucking= */ true);
    }

    @Test
    public void exclusiveInteractionsTransientNoPause_RequestGrantedAndFocusLossSent() {
        testExclusiveTransientInteractions(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                /* pauseForDucking= */ false);
    }

    @Test
    public void exclusiveInteractionsTransientPause_RequestGrantedAndFocusLossSent() {
        testExclusiveTransientInteractions(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                /* pauseForDucking= */ true);
    }

    @Test
    public void exclusiveInteractionsTransientMayDuckNoPause_RequestGrantedAndFocusLossSent() {
        testExclusiveTransientInteractions(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                /* pauseForDucking= */ false);
    }

    @Test
    public void exclusiveInteractionsTransientMayDuckPause_RequestGrantedAndFocusLossSent() {
        testExclusiveTransientInteractions(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                /* pauseForDucking= */ true);
    }

    private void testExclusiveNonTransientInteractions(boolean pauseForDucking) {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();

        when(mCarAudioService.getZoneIdForUid(mExcludedAudioClientInfo.getClientUid()))
                .thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo excludedAudioFocusInfo =
                new AudioFocusInfoBuilder().setUsage(mExcludedAudioClientInfo.getUsage())
                        .setGainRequest(AUDIOFOCUS_GAIN)
                        .setClientId(mExcludedAudioClientInfo.getClientId())
                        .setClientUid(mExcludedAudioClientInfo.getClientUid())
                        .setPausesOnDuckRequestEnable(pauseForDucking).createAudioFocusInfo();

        when(mCarAudioService.getZoneIdForUid(mAcceptedAudioClientInfo.getClientUid()))
                .thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo acceptedAudioFocusInfo =
                new AudioFocusInfoBuilder().setUsage(mAcceptedAudioClientInfo.getUsage())
                        .setGainRequest(AUDIOFOCUS_GAIN)
                        .setClientId(mAcceptedAudioClientInfo.getClientId())
                        .setClientUid(mAcceptedAudioClientInfo.getClientUid())
                        .setPausesOnDuckRequestEnable(pauseForDucking).createAudioFocusInfo();

        carZonesAudioFocus
                .onAudioFocusRequest(excludedAudioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, acceptedAudioFocusInfo);
        verify(mMockAudioManager).dispatchAudioFocusChange(excludedAudioFocusInfo,
                AudioManager.AUDIOFOCUS_LOSS, mAudioPolicy);

        when(mMockAudioManager.dispatchAudioFocusChange(excludedAudioFocusInfo,
                AUDIOFOCUS_GAIN, mAudioPolicy)).thenReturn(AUDIOFOCUS_REQUEST_GRANTED);
        carZonesAudioFocus.onAudioFocusAbandon(acceptedAudioFocusInfo);

        verify(mMockAudioManager, never()).dispatchAudioFocusChange(eq(acceptedAudioFocusInfo),
                anyInt(), eq(mAudioPolicy));
    }

    private void testExclusiveTransientInteractions(int gainType, boolean pauseForDucking) {
        CarZonesAudioFocus carZonesAudioFocus = getCarZonesAudioFocus();

        when(mCarAudioService.getZoneIdForUid(mExcludedAudioClientInfo.getClientUid()))
                .thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo excludedAudioFocusInfo =
                new AudioFocusInfoBuilder().setUsage(mExcludedAudioClientInfo.getUsage())
                        .setGainRequest(gainType)
                        .setClientId(mExcludedAudioClientInfo.getClientId())
                        .setClientUid(mExcludedAudioClientInfo.getClientUid())
                        .setPausesOnDuckRequestEnable(pauseForDucking).createAudioFocusInfo();

        when(mCarAudioService.getZoneIdForUid(mAcceptedAudioClientInfo.getClientUid()))
                .thenReturn(PRIMARY_ZONE_ID);
        AudioFocusInfo acceptedAudioFocusInfo =
                new AudioFocusInfoBuilder().setUsage(mAcceptedAudioClientInfo.getUsage())
                        .setGainRequest(gainType)
                        .setClientId(mAcceptedAudioClientInfo.getClientId())
                        .setClientUid(mAcceptedAudioClientInfo.getClientUid())
                        .setPausesOnDuckRequestEnable(pauseForDucking).createAudioFocusInfo();

        carZonesAudioFocus
                .onAudioFocusRequest(excludedAudioFocusInfo, AUDIOFOCUS_REQUEST_GRANTED);

        requestFocusAndAssertIfRequestNotGranted(carZonesAudioFocus, acceptedAudioFocusInfo);
        verify(mMockAudioManager).dispatchAudioFocusChange(excludedAudioFocusInfo,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, mAudioPolicy);

        when(mMockAudioManager.dispatchAudioFocusChange(excludedAudioFocusInfo,
                AUDIOFOCUS_GAIN, mAudioPolicy)).thenReturn(AUDIOFOCUS_REQUEST_GRANTED);
        carZonesAudioFocus.onAudioFocusAbandon(acceptedAudioFocusInfo);

        verify(mMockAudioManager, never()).dispatchAudioFocusChange(eq(acceptedAudioFocusInfo),
                anyInt(), eq(mAudioPolicy));
        verify(mMockAudioManager).dispatchAudioFocusChange(excludedAudioFocusInfo,
                AUDIOFOCUS_GAIN, mAudioPolicy);
    }
}
