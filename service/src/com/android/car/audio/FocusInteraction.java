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

import android.media.AudioManager;
import android.media.AudioManager.FocusRequestResult;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.car.audio.CarAudioContext.AudioContext;
import com.android.internal.util.Preconditions;

import java.util.List;

/**
 * FocusInteraction is responsible for evaluating how incoming focus requests should be handled
 * based on pre-defined interaction behaviors for each incoming {@link AudioContext} in relation to
 * a {@link AudioContext} that is currently holding focus.
 */
final class FocusInteraction {

    private static final String TAG = FocusInteraction.class.getSimpleName();

    // Values for the internal interaction matrix we use to make focus decisions
    @VisibleForTesting
    static final int INTERACTION_REJECT = 0; // Focus not granted
    @VisibleForTesting
    static final int INTERACTION_EXCLUSIVE = 1; // Focus granted, others loose focus
    @VisibleForTesting
    static final int INTERACTION_CONCURRENT = 2; // Focus granted, others keep focus

    private static final int[][] sInteractionMatrix = {
            // Each Row represents CarAudioContext of current focus holder
            // Each Column represents CarAudioContext of incoming request (labels along the right)
            // Cell value is one of INTERACTION_REJECT, INTERACTION_EXCLUSIVE,
            // or INTERACTION_CONCURRENT

            // Focus holder: INVALID
            {
                    INTERACTION_REJECT, // INVALID
                    INTERACTION_REJECT, // MUSIC
                    INTERACTION_REJECT, // NAVIGATION
                    INTERACTION_REJECT, // VOICE_COMMAND
                    INTERACTION_REJECT, // CALL_RING
                    INTERACTION_REJECT, // CALL
                    INTERACTION_REJECT, // ALARM
                    INTERACTION_REJECT, // NOTIFICATION
                    INTERACTION_REJECT, // SYSTEM_SOUND
            },
            // Focus holder: MUSIC
            {
                    INTERACTION_REJECT, // INVALID
                    INTERACTION_EXCLUSIVE, // MUSIC
                    INTERACTION_CONCURRENT, // NAVIGATION
                    INTERACTION_EXCLUSIVE, // VOICE_COMMAND
                    INTERACTION_EXCLUSIVE, // CALL_RING
                    INTERACTION_EXCLUSIVE, // CALL
                    INTERACTION_EXCLUSIVE, // ALARM
                    INTERACTION_CONCURRENT, // NOTIFICATION
                    INTERACTION_CONCURRENT,  // SYSTEM_SOUND
            },
            // Focus holder: NAVIGATION
            {
                    INTERACTION_REJECT, // INVALID
                    INTERACTION_CONCURRENT, // MUSIC
                    INTERACTION_CONCURRENT, // NAVIGATION
                    INTERACTION_EXCLUSIVE, // VOICE_COMMAND
                    INTERACTION_CONCURRENT, // CALL_RING
                    INTERACTION_EXCLUSIVE, // CALL
                    INTERACTION_CONCURRENT, // ALARM
                    INTERACTION_CONCURRENT, // NOTIFICATION
                    INTERACTION_CONCURRENT,  // SYSTEM_SOUND
            },
            // Focus holder: VOICE_COMMAND
            {
                    INTERACTION_REJECT, // INVALID
                    INTERACTION_CONCURRENT, // MUSIC
                    INTERACTION_REJECT, // NAVIGATION
                    INTERACTION_CONCURRENT, // VOICE_COMMAND
                    INTERACTION_EXCLUSIVE, // CALL_RING
                    INTERACTION_EXCLUSIVE, // CALL
                    INTERACTION_REJECT, // ALARM
                    INTERACTION_REJECT, // NOTIFICATION
                    INTERACTION_REJECT, // SYSTEM_SOUND
            },
            // Focus holder: CALL_RING
            {
                    INTERACTION_REJECT, // INVALID
                    INTERACTION_REJECT, // MUSIC
                    INTERACTION_CONCURRENT, // NAVIGATION
                    INTERACTION_CONCURRENT, // VOICE_COMMAND
                    INTERACTION_CONCURRENT, // CALL_RING
                    INTERACTION_CONCURRENT, // CALL
                    INTERACTION_REJECT, // ALARM
                    INTERACTION_REJECT, // NOTIFICATION
                    INTERACTION_CONCURRENT, // SYSTEM_SOUND
            },
            // Focus holder: CALL
            {
                    INTERACTION_REJECT, // INVALID
                    INTERACTION_REJECT, // MUSIC
                    INTERACTION_CONCURRENT, // NAVIGATION
                    INTERACTION_REJECT, // VOICE_COMMAND
                    INTERACTION_CONCURRENT, // CALL_RING
                    INTERACTION_CONCURRENT, // CALL
                    INTERACTION_CONCURRENT, // ALARM
                    INTERACTION_CONCURRENT, // NOTIFICATION
                    INTERACTION_REJECT, // SYSTEM_SOUND
            },
            // Focus holder: ALARM
            {
                    INTERACTION_REJECT, // INVALID
                    INTERACTION_CONCURRENT, // MUSIC
                    INTERACTION_CONCURRENT, // NAVIGATION
                    INTERACTION_EXCLUSIVE, // VOICE_COMMAND
                    INTERACTION_EXCLUSIVE, // CALL_RING
                    INTERACTION_EXCLUSIVE, // CALL
                    INTERACTION_CONCURRENT, // ALARM
                    INTERACTION_CONCURRENT, // NOTIFICATION
                    INTERACTION_CONCURRENT, // SYSTEM_SOUND
            },
            // Focus holder: NOTIFICATION
            {
                    INTERACTION_REJECT, // INVALID
                    INTERACTION_CONCURRENT, // MUSIC
                    INTERACTION_CONCURRENT, // NAVIGATION
                    INTERACTION_EXCLUSIVE, // VOICE_COMMAND
                    INTERACTION_EXCLUSIVE, // CALL_RING
                    INTERACTION_EXCLUSIVE, // CALL
                    INTERACTION_CONCURRENT, // ALARM
                    INTERACTION_CONCURRENT, // NOTIFICATION
                    INTERACTION_CONCURRENT, // SYSTEM_SOUND
            },
            // Focus holder: SYSTEM_SOUND
            {
                    INTERACTION_REJECT, // INVALID
                    INTERACTION_CONCURRENT, // MUSIC
                    INTERACTION_CONCURRENT, // NAVIGATION
                    INTERACTION_EXCLUSIVE, // VOICE_COMMAND
                    INTERACTION_EXCLUSIVE, // CALL_RING
                    INTERACTION_EXCLUSIVE, // CALL
                    INTERACTION_CONCURRENT, // ALARM
                    INTERACTION_CONCURRENT, // NOTIFICATION
                    INTERACTION_CONCURRENT, // SYSTEM_SOUND
            },
    };

    /**
     * Evaluates interaction between incoming focus {@link AudioContext} and the current focus
     * request based on interaction matrix.
     *
     * <p>Note: In addition to returning the {@link FocusRequestResult}
     * for the incoming request based on this interaction, this method also adds the current {@code
     * focusHolder} to the {@code focusLosers} list when appropriate.
     *
     * @param requestedContext CarAudioContextType of incoming focus request
     * @param focusHolder      {@link FocusEntry} for current focus holder
     * @param focusLosers      Mutable array to add focusHolder to if it should lose focus
     * @return {@link FocusRequestResult} result of focus interaction
     */
    static @FocusRequestResult int evaluateRequest(@AudioContext int requestedContext,
            FocusEntry focusHolder, List<FocusEntry> focusLosers, boolean allowDucking) {
        @AudioContext int holderContext = focusHolder.getAudioContext();
        Preconditions.checkArgumentInRange(holderContext, 0, sInteractionMatrix.length - 1,
                "holderContext");
        int[] holderRow = sInteractionMatrix[holderContext];
        Preconditions.checkArgumentInRange(requestedContext, 0, holderRow.length - 1,
                "requestedContext");

        switch (holderRow[requestedContext]) {
            case INTERACTION_REJECT:
                return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            case INTERACTION_EXCLUSIVE:
                focusLosers.add(focusHolder);
                return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            case INTERACTION_CONCURRENT:
                // If ducking isn't allowed by the focus requester, then everybody else
                // must get a LOSS.
                // If a focus holder has set the AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS flag,
                // they must get a LOSS message even if ducking would otherwise be allowed.
                // If a focus holder holds the RECEIVE_CAR_AUDIO_DUCKING_EVENTS permission,
                // they must receive all audio focus losses.
                if (!allowDucking
                        || focusHolder.wantsPauseInsteadOfDucking()
                        || focusHolder.receivesDuckEvents()) {
                    focusLosers.add(focusHolder);
                }
                return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            default:
                Log.e(TAG, String.format("Unsupported CarAudioContext %d - rejecting request",
                        holderRow[requestedContext]));
                return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
    }

    @VisibleForTesting
    static int[][] getInteractionMatrix() {
        int[][] interactionMatrixClone =
                new int[sInteractionMatrix.length][sInteractionMatrix.length];
        for (int audioContext = 0; audioContext < sInteractionMatrix.length; audioContext++) {
            interactionMatrixClone[audioContext] = sInteractionMatrix[audioContext].clone();
        }
        return interactionMatrixClone;
    }
}
