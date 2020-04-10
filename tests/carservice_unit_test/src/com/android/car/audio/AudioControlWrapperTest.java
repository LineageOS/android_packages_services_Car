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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.hardware.automotive.audiocontrol.V2_0.IFocusListener;
import android.media.AudioAttributes;
import android.media.AudioManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public class AudioControlWrapperTest {
    private static final float FADE_VALUE = 5;
    private static final float BALANCE_VALUE = 6;
    private static final int CONTEXT_NUMBER = 3;
    private static final int USAGE = AudioAttributes.USAGE_MEDIA;
    private static final int ZONE_ID = 2;
    private static final int FOCUS_GAIN = AudioManager.AUDIOFOCUS_GAIN;

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    android.hardware.automotive.audiocontrol.V1_0.IAudioControl mAudioControlV1;
    @Mock
    android.hardware.automotive.audiocontrol.V2_0.IAudioControl mAudioControlV2;

    @Test
    public void constructor_throwsIfBothVersionsAreNull() throws Exception {
        assertThrows(IllegalStateException.class, () -> new AudioControlWrapper(null, null));
    }

    @Test
    public void constructor_succeedsWithOneVersion() throws Exception {
        new AudioControlWrapper(null, mAudioControlV2);
    }

    @Test
    public void setFadeTowardFront_withBothVersions_defaultsToV2() throws Exception {
        AudioControlWrapper audioControlWrapper = new AudioControlWrapper(mAudioControlV1,
                mAudioControlV2);
        audioControlWrapper.setFadeTowardFront(FADE_VALUE);

        verify(mAudioControlV2).setFadeTowardFront(FADE_VALUE);
        verify(mAudioControlV1, never()).setFadeTowardFront(anyFloat());
    }

    @Test
    public void setFadeTowardFront_withJustV1_succeeds() throws Exception {
        AudioControlWrapper audioControlWrapper = new AudioControlWrapper(mAudioControlV1, null);
        audioControlWrapper.setFadeTowardFront(FADE_VALUE);

        verify(mAudioControlV1).setFadeTowardFront(FADE_VALUE);
    }

    @Test
    public void setBalanceTowardRight_withBothVersions_defaultsToV2() throws Exception {
        AudioControlWrapper audioControlWrapper = new AudioControlWrapper(mAudioControlV1,
                mAudioControlV2);
        audioControlWrapper.setBalanceTowardRight(BALANCE_VALUE);

        verify(mAudioControlV2).setBalanceTowardRight(BALANCE_VALUE);
        verify(mAudioControlV1, never()).setBalanceTowardRight(anyFloat());
    }

    @Test
    public void setBalanceTowardRight_withJustV1_succeeds() throws Exception {
        AudioControlWrapper audioControlWrapper = new AudioControlWrapper(mAudioControlV1, null);
        audioControlWrapper.setBalanceTowardRight(BALANCE_VALUE);

        verify(mAudioControlV1).setBalanceTowardRight(BALANCE_VALUE);
    }

    @Test
    public void getBusForContext_withV2Present_throws() {
        AudioControlWrapper audioControlWrapper = new AudioControlWrapper(mAudioControlV1,
                mAudioControlV2);
        assertThrows(IllegalStateException.class,
                () -> audioControlWrapper.getBusForContext(CONTEXT_NUMBER));
    }

    @Test
    public void getBusForContext_withJustV1_returnsBusNumber() throws Exception {
        AudioControlWrapper audioControlWrapper = new AudioControlWrapper(mAudioControlV1, null);
        int busNumber = 1;
        when(mAudioControlV1.getBusForContext(CONTEXT_NUMBER)).thenReturn(busNumber);

        int actualBus = audioControlWrapper.getBusForContext(CONTEXT_NUMBER);
        assertThat(actualBus).isEqualTo(busNumber);
    }

    @Test
    public void supportsHalAudioFocus_withV2_returnsTrue() {
        AudioControlWrapper audioControlWrapper = new AudioControlWrapper(mAudioControlV1,
                mAudioControlV2);

        assertThat(audioControlWrapper.supportsHalAudioFocus()).isTrue();
    }

    @Test
    public void supportsHalAudioFocus_withJustV1_returnsFalse() {
        AudioControlWrapper audioControlWrapper = new AudioControlWrapper(mAudioControlV1, null);

        assertThat(audioControlWrapper.supportsHalAudioFocus()).isFalse();
    }

    @Test
    public void registerFocusListener_withJustV1_throws() {
        AudioControlWrapper audioControlWrapper = new AudioControlWrapper(mAudioControlV1, null);
        IFocusListener mockListener = mock(IFocusListener.class);

        assertThrows(NullPointerException.class,
                () -> audioControlWrapper.registerFocusListener(mockListener));
    }

    @Test
    public void registerFocusListener_withV2_succeeds() throws Exception {
        AudioControlWrapper audioControlWrapper = new AudioControlWrapper(mAudioControlV1,
                mAudioControlV2);
        IFocusListener mockListener = mock(IFocusListener.class);
        audioControlWrapper.registerFocusListener(mockListener);

        verify(mAudioControlV2).registerFocusListener(mockListener);
    }

    @Test
    public void onAudioFocusChange_withJustV1_throws() {
        AudioControlWrapper audioControlWrapper = new AudioControlWrapper(mAudioControlV1, null);

        assertThrows(NullPointerException.class,
                () -> audioControlWrapper.onAudioFocusChange(USAGE, ZONE_ID, FOCUS_GAIN));
    }

    @Test
    public void onAudioFocusChange_withV2_succeeds() throws Exception {
        AudioControlWrapper audioControlWrapper = new AudioControlWrapper(mAudioControlV1,
                mAudioControlV2);
        IFocusListener mockListener = mock(IFocusListener.class);
        audioControlWrapper.onAudioFocusChange(USAGE, ZONE_ID, FOCUS_GAIN);

        verify(mAudioControlV2).onAudioFocusChange(USAGE, ZONE_ID, FOCUS_GAIN);
    }
}
