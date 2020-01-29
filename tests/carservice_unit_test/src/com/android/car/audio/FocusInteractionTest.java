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

import static com.android.car.audio.CarAudioContext.AudioContext;
import static com.android.car.audio.FocusInteraction.INTERACTION_CONCURRENT;
import static com.android.car.audio.FocusInteraction.INTERACTION_EXCLUSIVE;
import static com.android.car.audio.FocusInteraction.INTERACTION_REJECT;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.media.AudioManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.audio.CarAudioFocus.FocusEntry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class FocusInteractionTest {

    private static final int UNDEFINED_CONTEXT_VALUE = -10;
    private final List<FocusEntry> mLosers = new ArrayList<>();

    @Test
    public void getInteractionMatrix_returnsNByNMatrix() {
        int n = CarAudioContext.CONTEXTS.length + 1; // One extra for CarAudioContext.INVALID
        int[][] interactionMatrix = FocusInteraction.getInteractionMatrix();

        assertThat(interactionMatrix.length).isEqualTo(n);
        for (int i = 0; i < n; i++) {
            assertWithMessage("Row %s is not of length %s", i, n)
                    .that(interactionMatrix[i].length).isEqualTo(n);
        }
    }

    @Test
    public void getInteractionMatrix_hasValidInteractionValues() {
        List<Integer> supportedInteractions = Arrays.asList(INTERACTION_REJECT,
                INTERACTION_EXCLUSIVE, INTERACTION_CONCURRENT);
        int[][] interactionMatrix = FocusInteraction.getInteractionMatrix();

        for (int i = 0; i < interactionMatrix.length; i++) {
            for (int j = 0; j < interactionMatrix[i].length; j++) {
                assertWithMessage("Row %s column %s has unexpected value %s", i, j,
                        interactionMatrix[i][j]).that(
                        interactionMatrix[i][j]).isIn(supportedInteractions);
            }
        }
    }

    @Test
    public void evaluateResult_forRejectPair_returnsFailed() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.INVALID);

        int result = FocusInteraction.evaluateRequest(CarAudioContext.INVALID, focusEntry, mLosers,
                false);

        assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
    }

    @Test
    public void evaluateResult_forRejectPair_doesNotAddToLosers() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.INVALID);

        FocusInteraction.evaluateRequest(CarAudioContext.INVALID, focusEntry, mLosers, false);

        assertThat(mLosers).isEmpty();
    }

    @Test
    public void evaluateRequest_forExclusivePair_returnsGranted() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.MUSIC);

        int result = FocusInteraction.evaluateRequest(CarAudioContext.MUSIC, focusEntry, mLosers,
                false);

        assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    }

    @Test
    public void evaluateRequest_forExclusivePair_addsEntryToLosers() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.MUSIC);

        FocusInteraction.evaluateRequest(CarAudioContext.MUSIC, focusEntry, mLosers, false);

        assertThat(mLosers).containsExactly(focusEntry);
    }

    @Test
    public void evaluateResult_forConcurrentPair_returnsGranted() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.NAVIGATION);

        int result = FocusInteraction.evaluateRequest(CarAudioContext.MUSIC, focusEntry, mLosers,
                false);

        assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    }

    @Test
    public void evaluateResult_forConcurrentPair_andNoDucking_addsToLosers() {
        FocusEntry focusEntry = newMockFocusEntryWithDuckingBehavior(false, false);

        FocusInteraction.evaluateRequest(CarAudioContext.MUSIC, focusEntry, mLosers, false);

        assertThat(mLosers).containsExactly(focusEntry);
    }

    @Test
    public void evaluateResult_forConcurrentPair_andWantsPauseInsteadOfDucking_addsToLosers() {
        FocusEntry focusEntry = newMockFocusEntryWithDuckingBehavior(true, false);

        FocusInteraction.evaluateRequest(CarAudioContext.MUSIC, focusEntry, mLosers, true);

        assertThat(mLosers).containsExactly(focusEntry);
    }

    @Test
    public void evaluateResult_forConcurrentPair_andReceivesDuckEvents_addsToLosers() {
        FocusEntry focusEntry = newMockFocusEntryWithDuckingBehavior(false, true);

        FocusInteraction.evaluateRequest(CarAudioContext.MUSIC, focusEntry, mLosers, true);

        assertThat(mLosers).containsExactly(focusEntry);
    }

    @Test
    public void evaluateResult_forConcurrentPair_andDucking_doesAddsToLosers() {
        FocusEntry focusEntry = newMockFocusEntryWithDuckingBehavior(false, true);

        FocusInteraction.evaluateRequest(CarAudioContext.MUSIC, focusEntry, mLosers, true);

        assertThat(mLosers).containsExactly(focusEntry);
    }

    @Test
    public void evaluateResult_forUndefinedContext_throws() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.NAVIGATION);

        assertThrows(IllegalArgumentException.class,
                () -> FocusInteraction.evaluateRequest(UNDEFINED_CONTEXT_VALUE, focusEntry, mLosers,
                        false));
    }

    @Test
    public void evaluateResult_forUndefinedFocusHolderContext_throws() {
        FocusEntry focusEntry = newMockFocusEntryWithContext(UNDEFINED_CONTEXT_VALUE);

        assertThrows(IllegalArgumentException.class,
                () -> FocusInteraction.evaluateRequest(CarAudioContext.MUSIC, focusEntry,
                        mLosers, false));
    }

    private FocusEntry newMockFocusEntryWithContext(@AudioContext int audioContext) {
        FocusEntry focusEntry = mock(FocusEntry.class);
        when(focusEntry.getAudioContext()).thenReturn(audioContext);
        return focusEntry;
    }

    private FocusEntry newMockFocusEntryWithDuckingBehavior(boolean pauseInsteadOfDucking,
            boolean receivesDuckingEvents) {
        FocusEntry focusEntry = newMockFocusEntryWithContext(CarAudioContext.NAVIGATION);
        when(focusEntry.wantsPauseInsteadOfDucking()).thenReturn(pauseInsteadOfDucking);
        when(focusEntry.receivesDuckEvents()).thenReturn(receivesDuckingEvents);
        return focusEntry;
    }
}
