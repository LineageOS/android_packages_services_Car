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

import static com.android.car.audio.CarAudioService.DEFAULT_AUDIO_CONTEXT;

import android.media.AudioAttributes.AttributeUsage;
import android.media.AudioPlaybackConfiguration;
import android.telephony.Annotation.CallState;
import android.telephony.TelephonyManager;
import android.util.SparseIntArray;

import com.android.car.audio.CarAudioContext.AudioContext;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CarVolume is responsible for determining which audio contexts to prioritize when adjusting volume
 */
final class CarVolume {
    private static final String TAG = CarVolume.class.getSimpleName();
    private static final int CONTEXT_HEIGHEST_PRIORITY = 0;
    private static final int CONTEXT_NOT_PRIORITIZED = -1;

    private static final int[] AUDIO_CONTEXT_VOLUME_PRIORITY = {
            CarAudioContext.NAVIGATION,
            CarAudioContext.CALL,
            CarAudioContext.MUSIC,
            CarAudioContext.ANNOUNCEMENT,
            CarAudioContext.VOICE_COMMAND,
            CarAudioContext.CALL_RING,
            CarAudioContext.SYSTEM_SOUND,
            CarAudioContext.SAFETY,
            CarAudioContext.ALARM,
            CarAudioContext.NOTIFICATION,
            CarAudioContext.VEHICLE_STATUS,
            CarAudioContext.EMERGENCY,
            // CarAudioContext.INVALID is intentionally not prioritized as it is not routed by
            // CarAudioService and is not expected to be used.
    };

    private static final SparseIntArray VOLUME_PRIORITY_BY_AUDIO_CONTEXT = new SparseIntArray();

    static {
        for (int priority = CONTEXT_HEIGHEST_PRIORITY;
                priority < AUDIO_CONTEXT_VOLUME_PRIORITY.length; priority++) {
            VOLUME_PRIORITY_BY_AUDIO_CONTEXT.append(AUDIO_CONTEXT_VOLUME_PRIORITY[priority],
                    priority);
        }
    }

    /**
     * Suggests a {@link AudioContext} that should be adjusted based on the current
     * {@link AudioPlaybackConfiguration}s, {@link CallState}, and active HAL usages
     */
    static @AudioContext int getSuggestedAudioContext(
            List<AudioPlaybackConfiguration> configurations,
            @CallState int callState, @AttributeUsage int[] activeHalUsages) {
        int currentContext = DEFAULT_AUDIO_CONTEXT;
        int currentPriority = CONTEXT_HEIGHEST_PRIORITY + AUDIO_CONTEXT_VOLUME_PRIORITY.length;

        Set<Integer> contexts = new HashSet<>();

        if (callState == TelephonyManager.CALL_STATE_RINGING) {
            currentContext = CarAudioContext.CALL_RING;
            currentPriority = VOLUME_PRIORITY_BY_AUDIO_CONTEXT.get(CarAudioContext.CALL_RING);
        } else if (callState == TelephonyManager.CALL_STATE_OFFHOOK) {
            currentContext = CarAudioContext.CALL;
            currentPriority = VOLUME_PRIORITY_BY_AUDIO_CONTEXT.get(CarAudioContext.CALL);
        }

        for (AudioPlaybackConfiguration configuration : configurations) {
            if (!configuration.isActive()) {
                continue;
            }
            contexts.add(CarAudioContext
                    .getContextForUsage(configuration.getAudioAttributes().getSystemUsage()));
        }

        for (int index = 0; index < activeHalUsages.length; index++) {
            contexts.add(CarAudioContext.getContextForUsage(activeHalUsages[index]));
        }

        for (@AudioContext int context : contexts) {
            int priority = VOLUME_PRIORITY_BY_AUDIO_CONTEXT.get(context, CONTEXT_NOT_PRIORITIZED);
            if (priority == CONTEXT_NOT_PRIORITIZED) {
                continue;
            }

            if (priority < currentPriority) {
                currentContext = context;
                currentPriority = priority;
                // If the highest priority has been found, return early.
                if (currentPriority == CONTEXT_HEIGHEST_PRIORITY) {
                    return currentContext;
                }
            }
        }

        return currentContext;
    }
}
