/*
 * Copyright (C) 2020 The Android Open Source Project
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
import static android.media.AudioAttributes.USAGE_ASSISTANT;
import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_VIRTUAL_SOURCE;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;
import static android.telephony.TelephonyManager.CALL_STATE_IDLE;
import static android.telephony.TelephonyManager.CALL_STATE_OFFHOOK;
import static android.telephony.TelephonyManager.CALL_STATE_RINGING;

import static com.android.car.audio.CarAudioContext.ALARM;
import static com.android.car.audio.CarAudioContext.CALL;
import static com.android.car.audio.CarAudioContext.CALL_RING;
import static com.android.car.audio.CarAudioContext.MUSIC;
import static com.android.car.audio.CarAudioContext.NAVIGATION;
import static com.android.car.audio.CarAudioContext.VOICE_COMMAND;
import static com.android.car.audio.CarAudioService.DEFAULT_AUDIO_CONTEXT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

import android.media.AudioAttributes;
import android.media.AudioAttributes.AttributeUsage;
import android.media.AudioPlaybackConfiguration;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.audio.CarAudioContext.AudioContext;

import com.google.common.collect.ImmutableList;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CarVolumeTest {

    public static final @CarVolume.CarVolumeListVersion int VERSION_ZERO = 0;
    public static final @CarVolume.CarVolumeListVersion int VERSION_ONE = 1;
    public static final @CarVolume.CarVolumeListVersion int VERSION_TWO = 2;
    public static final @CarVolume.CarVolumeListVersion int VERSION_THREE = 3;

    @Test
    public void createCarVolume_withVersionLessThanOne_failsTooLow() {
        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () -> {
            new CarVolume(VERSION_ZERO);
        });

        assertThat(thrown).hasMessageThat().contains("too low");
    }

    @Test
    public void createCarVolume_withVersionGreaterThanTwo_failsTooHigh() {
        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () -> {
            new CarVolume(VERSION_THREE);
        });

        assertThat(thrown).hasMessageThat().contains("too high");
    }

    @Test
    public void getSuggestedAudioContext_withNullConfigurations_fails() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);

        assertThrows(NullPointerException.class, () -> carVolume.getSuggestedAudioContext(
                null, CALL_STATE_IDLE, new int[0]));
    }

    @Test
    public void getSuggestedAudioContext_withNullHallUsages_fails() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);

        assertThrows(NullPointerException.class, () -> carVolume.getSuggestedAudioContext(
                new ArrayList<>(), CALL_STATE_IDLE, null));
    }

    @Test
    public void getSuggestedAudioContext_withNoConfigurationsAndIdleTelephony_returnsDefault() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(new ArrayList<>(),
                CALL_STATE_IDLE, new int[0]);

        assertThat(suggestedContext).isEqualTo(CarAudioService.DEFAULT_AUDIO_CONTEXT);
    }

    @Test
    public void getSuggestedAudioContext_withOneConfiguration_returnsAssociatedContext() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ASSISTANT).build()
        );

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(configurations,
                CALL_STATE_IDLE, new int[0]);

        assertThat(suggestedContext).isEqualTo(VOICE_COMMAND);
    }

    @Test
    public void getSuggestedAudioContext_withCallStateOffHook_returnsCallContext() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(new ArrayList<>(),
                CALL_STATE_OFFHOOK, new int[0]);

        assertThat(suggestedContext).isEqualTo(CALL);
    }

    @Test

    public void getSuggestedAudioContext_withV1AndCallStateRinging_returnsCallRingContext() {
        CarVolume carVolume = new CarVolume(VERSION_ONE);

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(new ArrayList<>(),
                CALL_STATE_RINGING, new int[0]);

        assertThat(suggestedContext).isEqualTo(CALL_RING);
    }

    @Test
    public void getSuggestedAudioContext_withConfigurations_returnsHighestPriorityContext() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ALARM).build(),
                new Builder().setUsage(USAGE_VOICE_COMMUNICATION).build(),
                new Builder().setUsage(USAGE_NOTIFICATION).build()
        );

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(configurations,
                CALL_STATE_IDLE, new int[0]);

        assertThat(suggestedContext).isEqualTo(CALL);
    }

    @Test
    public void getSuggestedAudioContext_ignoresInactiveConfigurations() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ASSISTANT).build(),
                new Builder().setUsage(USAGE_VOICE_COMMUNICATION).setInactive().build(),
                new Builder().setUsage(USAGE_NOTIFICATION).build()
        );

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(configurations,
                CALL_STATE_IDLE, new int[0]);

        assertThat(suggestedContext).isEqualTo(VOICE_COMMAND);
    }

    @Test
    public void getSuggestedAudioContext_withLowerPriorityConfigurationsAndCall_returnsCall() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ALARM).build(),
                new Builder().setUsage(USAGE_NOTIFICATION).build()
        );

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(configurations,
                CALL_STATE_OFFHOOK, new int[0]);

        assertThat(suggestedContext).isEqualTo(CALL);
    }

    @Test
    public void getSuggestedAudioContext_withV1AndNavigationConfigurationAndCall_returnsNav() {
        CarVolume carVolume = new CarVolume(VERSION_ONE);
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()
        );

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(configurations,
                CALL_STATE_OFFHOOK, new int[0]);

        assertThat(suggestedContext).isEqualTo(NAVIGATION);
    }

    @Test
    public void getSuggestedAudioContext_withV2AndNavigationConfigurationAndCall_returnsCall() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()
        );

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(configurations,
                CALL_STATE_OFFHOOK, new int[0]);

        assertThat(suggestedContext).isEqualTo(CALL);
    }

    @Test
    public void getSuggestedAudioContext_withUnprioritizedUsage_returnsDefault() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_VIRTUAL_SOURCE).build()
        );

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(configurations,
                CALL_STATE_IDLE, new int[0]);

        assertThat(suggestedContext).isEqualTo(DEFAULT_AUDIO_CONTEXT);
    }

    @Test
    public void getSuggestedAudioContext_withHalActiveUsage_returnsHalActive() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);
        int[] activeHalUsages = new int[] {USAGE_ASSISTANT};

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(new ArrayList<>(),
                CALL_STATE_IDLE, activeHalUsages);

        assertThat(suggestedContext).isEqualTo(VOICE_COMMAND);
    }

    @Test
    public void getSuggestedAudioContext_withHalUnprioritizedUsage_returnsDefault() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);
        int[] activeHalUsages = new int[] {USAGE_VIRTUAL_SOURCE};

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(new ArrayList<>(),
                CALL_STATE_IDLE, activeHalUsages);

        assertThat(suggestedContext).isEqualTo(DEFAULT_AUDIO_CONTEXT);
    }

    @Test
    public void getSuggestedAudioContext_withConfigAndHalActiveUsage_returnsConfigActive() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);
        int[] activeHalUsages = new int[] {USAGE_ASSISTANT};
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_MEDIA).build()
        );

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(configurations,
                CALL_STATE_IDLE, activeHalUsages);

        assertThat(suggestedContext).isEqualTo(MUSIC);
    }

    @Test
    public void getSuggestedAudioContext_withConfigAndHalActiveUsage_returnsHalActive() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);
        int[] activeHalUsages = new int[] {USAGE_MEDIA};
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ASSISTANT).build()
        );

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(configurations,
                CALL_STATE_IDLE, activeHalUsages);

        assertThat(suggestedContext).isEqualTo(MUSIC);
    }

    @Test
    public void getSuggestedAudioContext_withHalActiveUsageAndActiveCall_returnsCall() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);
        int[] activeHalUsages = new int[] {USAGE_MEDIA};
        List<AudioPlaybackConfiguration> configurations = new ArrayList<>();

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(configurations,
                CALL_STATE_OFFHOOK, activeHalUsages);

        assertThat(suggestedContext).isEqualTo(CALL);
    }

    @Test
    public void getSuggestedAudioContext_withMultipleHalActiveUsages_returnsMusic() {
        CarVolume carVolume = new CarVolume(VERSION_TWO);
        int[] activeHalUsages = new int[] {USAGE_MEDIA, USAGE_ANNOUNCEMENT, USAGE_ASSISTANT};
        List<AudioPlaybackConfiguration> configurations = new ArrayList<>();

        @AudioContext int suggestedContext = carVolume.getSuggestedAudioContext(configurations,
                CALL_STATE_IDLE, activeHalUsages);

        assertThat(suggestedContext).isEqualTo(MUSIC);
    }


    @Test
    public void isAnyContextActive_withOneConfigurationAndMatchedContext_returnsTrue() {
        @AudioContext int[] activeContexts = {VOICE_COMMAND};
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ASSISTANT).build()
        );

        assertThat(CarVolume.isAnyContextActive(activeContexts, configurations, CALL_STATE_IDLE,
                new int[0])).isTrue();
    }

    @Test
    public void isAnyContextActive_withOneConfigurationAndMismatchedContext_returnsFalse() {
        @AudioContext int[] activeContexts = {ALARM};
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ASSISTANT).build()
        );

        assertThat(CarVolume.isAnyContextActive(activeContexts, configurations, CALL_STATE_IDLE,
                new int[0])).isFalse();
    }

    @Test
    public void isAnyContextActive_withOneConfigurationAndMultipleContexts_returnsTrue() {
        @AudioContext int[] activeContexts = {ALARM, MUSIC, VOICE_COMMAND};
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_ASSISTANT).build()
        );

        assertThat(CarVolume.isAnyContextActive(activeContexts, configurations, CALL_STATE_IDLE,
                new int[0])).isTrue();
    }

    @Test
    public void isAnyContextActive_withOneConfigurationAndMultipleContexts_returnsFalse() {
        @AudioContext int[] activeContexts = {ALARM, MUSIC, VOICE_COMMAND};
        List<AudioPlaybackConfiguration> configurations = ImmutableList.of(
                new Builder().setUsage(USAGE_NOTIFICATION).build()
        );

        assertThat(CarVolume.isAnyContextActive(activeContexts, configurations, CALL_STATE_IDLE,
                new int[0])).isFalse();
    }

    @Test
    public void isAnyContextActive_withActiveHalUsagesAndMatchedContext_returnsTrue() {
        @AudioContext int[] activeContexts = {VOICE_COMMAND};
        @AttributeUsage int[] activeHalUsages = {USAGE_MEDIA, USAGE_ANNOUNCEMENT, USAGE_ASSISTANT};
        List<AudioPlaybackConfiguration> configurations = new ArrayList<>();

        assertThat(CarVolume.isAnyContextActive(activeContexts, configurations, CALL_STATE_IDLE,
                activeHalUsages)).isTrue();
    }

    @Test
    public void isAnyContextActive_withActiveHalUsagesAndMismatchedContext_returnsFalse() {
        @AudioContext int[] activeContexts = {ALARM};
        @AttributeUsage int[] activeHalUsages = {USAGE_MEDIA, USAGE_ANNOUNCEMENT, USAGE_ASSISTANT};
        List<AudioPlaybackConfiguration> configurations = new ArrayList<>();

        assertThat(CarVolume.isAnyContextActive(activeContexts, configurations, CALL_STATE_IDLE,
                activeHalUsages)).isFalse();
    }

    @Test
    public void isAnyContextActive_withActiveCallAndMatchedContext_returnsTrue() {
        @AudioContext int[] activeContexts = {CALL};
        @AttributeUsage int[] activeHalUsages = {};
        List<AudioPlaybackConfiguration> configurations = new ArrayList<>();

        assertThat(CarVolume.isAnyContextActive(activeContexts, configurations, CALL_STATE_OFFHOOK,
                activeHalUsages)).isTrue();
    }

    @Test
    public void isAnyContextActive_withActiveCallAndMismatchedContext_returnsFalse() {
        @AudioContext int[] activeContexts = {VOICE_COMMAND};
        @AttributeUsage int[] activeHalUsages = {};
        List<AudioPlaybackConfiguration> configurations = new ArrayList<>();

        assertThat(CarVolume.isAnyContextActive(activeContexts, configurations, CALL_STATE_OFFHOOK,
                activeHalUsages)).isFalse();
    }

    @Test
    public void isAnyContextActive_withNullContexts_fails() {
        @AudioContext int[] activeContexts = null;
        @AttributeUsage int[] activeHalUsages = {};
        List<AudioPlaybackConfiguration> configurations = new ArrayList<>();

        assertThrows(NullPointerException.class,
                () -> CarVolume.isAnyContextActive(activeContexts,
                configurations, CALL_STATE_OFFHOOK, activeHalUsages));
    }

    @Test
    public void isAnyContextActive_withEmptyContexts_fails() {
        @AudioContext int[] activeContexts = {};
        @AttributeUsage int[] activeHalUsages = {};
        List<AudioPlaybackConfiguration> configurations = new ArrayList<>();

        assertThrows(IllegalArgumentException.class,
                () -> CarVolume.isAnyContextActive(activeContexts,
                configurations, CALL_STATE_OFFHOOK, activeHalUsages));
    }

    @Test
    public void isAnyContextActive_withNullConfigurations_fails() {
        @AudioContext int[] activeContexts = {ALARM};
        @AttributeUsage int[] activeHalUsages = {};
        List<AudioPlaybackConfiguration> configurations = null;

        assertThrows(NullPointerException.class,
                () -> CarVolume.isAnyContextActive(activeContexts,
                        configurations, CALL_STATE_OFFHOOK, activeHalUsages));
    }

    @Test
    public void isAnyContextActive_withNullHalUsages_fails() {
        @AudioContext int[] activeContexts = {ALARM};
        @AttributeUsage int[] activeHalUsages = null;
        List<AudioPlaybackConfiguration> configurations = new ArrayList<>();

        assertThrows(NullPointerException.class,
                () -> CarVolume.isAnyContextActive(activeContexts,
                        configurations, CALL_STATE_OFFHOOK, activeHalUsages));
    }

    private static class Builder {
        private @AttributeUsage int mUsage = USAGE_MEDIA;
        private boolean mIsActive = true;

        Builder setUsage(@AttributeUsage int usage) {
            mUsage = usage;
            return this;
        }

        Builder setInactive() {
            mIsActive = false;
            return this;
        }

        AudioPlaybackConfiguration build() {
            AudioPlaybackConfiguration configuration = mock(AudioPlaybackConfiguration.class);
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(mUsage).build();
            when(configuration.getAudioAttributes()).thenReturn(attributes);
            when(configuration.isActive()).thenReturn(mIsActive);
            return configuration;
        }
    }
}
