/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.IntDef;

import com.android.internal.util.Preconditions;

final class CarActivationVolumeConfig {

    /**
     * Activation volume type invoked for the first time after boot or switching
     * {@link CarAudioZoneConfig} or user.
     */
    static final int ACTIVATION_VOLUME_ON_BOOT = 1;
    /**
     * Activation volume type invoked when the source (represented bu uid) of the newly active
     * {@link android.media.AudioPlaybackConfiguration} for each {@link CarVolumeGroup} is changed
     */
    static final int ACTIVATION_VOLUME_ON_SOURCE_CHANGED = 1 << 1;
    /**
     * Activation volume type invoked for every newly active
     * {@link android.media.AudioPlaybackConfiguration} for each {@link CarVolumeGroup}
     */
    static final int ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED = 1 << 2;

    private final int mInvocationType;
    private final int mMinActivationVolumePercentage;
    private final int mMaxActivationVolumePercentage;

    @IntDef(flag = true, value = {
            ACTIVATION_VOLUME_ON_BOOT,
            ACTIVATION_VOLUME_ON_SOURCE_CHANGED,
            ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED
    })
    @interface ActivationVolumeInvocationType {}

    CarActivationVolumeConfig(int invocationType, int minActivationVolumePercentage,
                              int maxActivationVolumePercentage) {
        Preconditions.checkArgument(minActivationVolumePercentage < maxActivationVolumePercentage,
                "Min activation volume percentage can not be higher than max");
        mInvocationType = invocationType;
        mMinActivationVolumePercentage = minActivationVolumePercentage;
        mMaxActivationVolumePercentage = maxActivationVolumePercentage;
    }

    int getInvocationType() {
        return mInvocationType;
    }

    int getMinActivationVolumePercentage() {
        return mMinActivationVolumePercentage;
    }

    int getMaxActivationVolumePercentage() {
        return mMaxActivationVolumePercentage;
    }
}
