/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static android.media.AudioAttributes.USAGE_CALL_ASSISTANT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING;

import android.car.builtin.util.Slogf;
import android.media.AudioAttributes;
import android.util.ArraySet;

import com.android.car.CarLog;
import com.android.car.internal.util.DebugUtils;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Class to manage audio focus enforcement in cars
 */
final class CarAudioFocusEnforcement {

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArraySet<AudioAttributes> mEnforceableAttributes = new ArraySet<>();
    @GuardedBy("mLock")
    private final ArraySet<AudioAttributes> mDoNotSilenceAttributes = new ArraySet<>();

    private static boolean isCriticalAudioUsage(int usage) {
        return usage == USAGE_EMERGENCY || usage == USAGE_SAFETY || usage == USAGE_CALL_ASSISTANT
                || usage == USAGE_VOICE_COMMUNICATION
                || usage == USAGE_VOICE_COMMUNICATION_SIGNALLING;
    }

    CarAudioFocusEnforcement() {
    }

    void setEnforceableAttributes(List<AudioAttributes> enforceableAttributes) {
        Objects.requireNonNull(enforceableAttributes,
                "Enforceable audio attributes can not be null");
        synchronized (mLock) {
            mEnforceableAttributes.clear();
            for (int c = 0; c < enforceableAttributes.size(); c++) {
                var audioAttribute = enforceableAttributes.get(c);
                if (isCriticalAudioUsage(audioAttribute.getSystemUsage())) {
                    throw new IllegalArgumentException(
                            "Car Audio focus enforcement does not support usage "
                                    + DebugUtils.constantToString(AudioAttributes.class,
                                    "USAGE_", audioAttribute.getSystemUsage()));
                }
                if (!mEnforceableAttributes.add(audioAttribute)) {
                    Slogf.w(CarLog.TAG_AUDIO, "Silence audio attribute %s repeats",
                            audioAttribute);
                }
            }
        }

    }

    List<AudioAttributes> getEnforceableAttributes() {
        synchronized (mLock) {
            return new ArrayList<>(mEnforceableAttributes);
        }
    }

    void setDoNotSilenceAttributes(List<AudioAttributes> doNotSilenceAttributes) {
        Objects.requireNonNull(doNotSilenceAttributes,
                "Do not silence audio attributes can not be null");
        synchronized (mLock) {
            mDoNotSilenceAttributes.clear();
            for (int c = 0; c < doNotSilenceAttributes.size(); c++) {
                var audioAttribute = doNotSilenceAttributes.get(c);
                if (!mDoNotSilenceAttributes.add(audioAttribute)) {
                    Slogf.w(CarLog.TAG_AUDIO, "Do not silence audio attribute %s repeats",
                            audioAttribute);
                }
            }
        }
    }

    List<AudioAttributes> getDoNotSilenceAttributes() {
        synchronized (mLock) {
            return new ArrayList<>(mDoNotSilenceAttributes);
        }
    }
}
