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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.media.AudioAttributes.AttributeUsage;
import android.media.AudioPlaybackConfiguration;
import android.telephony.Annotation.CallState;
import android.telephony.TelephonyManager;
import android.util.SparseIntArray;

import com.android.car.audio.CarAudioContext.AudioContext;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * CarVolume is responsible for determining which audio contexts to prioritize when adjusting volume
 */
final class CarVolume {
    private static final String TAG = CarVolume.class.getSimpleName();
    private static final int CONTEXT_HEIGHEST_PRIORITY = 0;
    private static final int CONTEXT_NOT_PRIORITIZED = -1;

    private static final int VERSION_ONE = 1;
    private static final int[] AUDIO_CONTEXT_VOLUME_PRIORITY_V1 = {
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

    private static final int VERSION_TWO = 2;
    private static final int[] AUDIO_CONTEXT_VOLUME_PRIORITY_V2 = {
            CarAudioContext.CALL,
            CarAudioContext.MUSIC,
            CarAudioContext.ANNOUNCEMENT,
            CarAudioContext.VOICE_COMMAND,
    };

    private final SparseIntArray mVolumePriorityByAudioContext = new SparseIntArray();

    /**
     * Creates a car volume with the specify context priority
     *
     * @param contextVolumePriority car volume priority of {#code @AudioContext}'s,
     * arranged in priority from highest first to lowest.
     */
    CarVolume(@NonNull @AudioContext int[] contextVolumePriority) {
        Objects.requireNonNull(contextVolumePriority);
        for (int priority = CONTEXT_HEIGHEST_PRIORITY;
                priority < contextVolumePriority.length; priority++) {
            mVolumePriorityByAudioContext.append(contextVolumePriority[priority], priority);
        }
    }

    /**
     * Creates a car volume with the specify context priority version number
     *
     * @param audioVolumeAdjustmentContextsVersion car volume priority list version number.
     */
    CarVolume(@CarVolumeListVersion int audioVolumeAdjustmentContextsVersion) {
        Preconditions.checkArgumentInRange(audioVolumeAdjustmentContextsVersion, 1, 2,
                "audioVolumeAdjustmentContextsVersion");
        @AudioContext int[] contextVolumePriority = AUDIO_CONTEXT_VOLUME_PRIORITY_V1;
        if (audioVolumeAdjustmentContextsVersion == VERSION_TWO) {
            contextVolumePriority = AUDIO_CONTEXT_VOLUME_PRIORITY_V2;
        }
        for (int priority = CONTEXT_HEIGHEST_PRIORITY;
                priority < contextVolumePriority.length; priority++) {
            mVolumePriorityByAudioContext.append(contextVolumePriority[priority], priority);
        }
    }

    /**
     * Suggests a {@link AudioContext} that should be adjusted based on the current
     * {@link AudioPlaybackConfiguration}s, {@link CallState}, and active HAL usages
     */
    @AudioContext int getSuggestedAudioContext(@NonNull List<Integer> activePlaybackContexts,
            @CallState int callState, @NonNull @AttributeUsage int[] activeHalUsages) {
        int currentContext = DEFAULT_AUDIO_CONTEXT;
        int currentPriority = CONTEXT_HEIGHEST_PRIORITY + mVolumePriorityByAudioContext.size();

        Set<Integer> activeContexts = getActiveContexts(activePlaybackContexts, callState,
                activeHalUsages);

        for (@AudioContext int context : activeContexts) {
            int priority = mVolumePriorityByAudioContext.get(context, CONTEXT_NOT_PRIORITIZED);
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

    public static boolean isAnyContextActive(@NonNull @AudioContext int [] contexts,
            @NonNull List<Integer> activePlaybackContext, @CallState int callState,
            @NonNull @AttributeUsage int[] activeHalUsages) {
        Objects.nonNull(contexts);
        Preconditions.checkArgument(contexts.length != 0,
                "contexts can not be empty.");
        Set<Integer> activeContexts = getActiveContexts(activePlaybackContext,
                callState, activeHalUsages);
        for (@AudioContext int context : contexts) {
            if (activeContexts.contains(context)) {
                return true;
            }
        }
        return false;
    }

    private static Set<Integer> getActiveContexts(@NonNull List<Integer> activePlaybackContexts,
            @CallState int callState, @NonNull  @AttributeUsage int[] activeHalUsages) {
        Objects.nonNull(activePlaybackContexts);
        Objects.nonNull(activeHalUsages);
        Set<Integer> contexts = new HashSet<>();
        switch (callState) {
            case TelephonyManager.CALL_STATE_RINGING:
                contexts.add(CarAudioContext.CALL_RING);
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                contexts.add(CarAudioContext.CALL);
                break;
        }

        contexts.addAll(activePlaybackContexts);

        for (int index = 0; index < activeHalUsages.length; index++) {
            contexts.add(CarAudioContext.getContextForUsage(activeHalUsages[index]));
        }

        return contexts;
    }

    @IntDef({
            VERSION_ONE,
            VERSION_TWO
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CarVolumeListVersion {
    }
}
