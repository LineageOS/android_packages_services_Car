/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.car.builtin.media.AudioManagerHelper.adjustToString;
import static android.car.builtin.media.AudioManagerHelper.isMasterMute;
import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.media.AudioManager.ADJUST_LOWER;
import static android.media.AudioManager.ADJUST_MUTE;
import static android.media.AudioManager.ADJUST_RAISE;
import static android.media.AudioManager.ADJUST_SAME;
import static android.media.AudioManager.ADJUST_TOGGLE_MUTE;
import static android.media.AudioManager.ADJUST_UNMUTE;
import static android.media.AudioManager.FLAG_FROM_KEY;
import static android.media.AudioManager.FLAG_SHOW_UI;
import static android.util.Log.DEBUG;
import static android.util.Log.VERBOSE;

import static com.android.car.CarLog.TAG_AUDIO;

import android.annotation.NonNull;
import android.car.builtin.util.Slogf;
import android.media.AudioManager;
import android.media.audiopolicy.AudioPolicy;
import android.media.audiopolicy.AudioPolicy.Builder;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

final class CarAudioPolicyVolumeCallback extends AudioPolicy.AudioPolicyVolumeCallback{
    private final AudioManager mAudioManager;
    private final boolean mUseCarVolumeGroupMuting;
    private final CarVolumeInfoWrapper mCarVolumeInfo;

    static void addVolumeCallbackToPolicy(@NonNull Builder policyBuilder,
            @NonNull AudioManager audioManager, CarVolumeInfoWrapper carVolumeInfo,
            boolean useCarVolumeGroupMuting) {
        Objects.requireNonNull(policyBuilder, "AudioPolicy.Builder cannot be null");
        policyBuilder.setAudioPolicyVolumeCallback(
                new CarAudioPolicyVolumeCallback(audioManager, carVolumeInfo,
                        useCarVolumeGroupMuting));
        if (Slogf.isLoggable(TAG_AUDIO, DEBUG)) {
            Slogf.d(TAG_AUDIO, "Registered car audio policy volume callback");
        }
    }

    @VisibleForTesting
    CarAudioPolicyVolumeCallback(@NonNull AudioManager audioManager,
            CarVolumeInfoWrapper carVolumeInfo, boolean useCarVolumeGroupMuting) {
        mAudioManager = Objects.requireNonNull(audioManager, "AudioManager cannot be null");
        mCarVolumeInfo = Objects.requireNonNull(carVolumeInfo,
                "CarVolumeInfoWrapper cannot be null");
        mUseCarVolumeGroupMuting = useCarVolumeGroupMuting;
    }

    @Override
    public void onVolumeAdjustment(int adjustment) {
        int zoneId = PRIMARY_AUDIO_ZONE;
        int groupId = mCarVolumeInfo.getVolumeGroupIdForAudioZone(zoneId);
        boolean isMuted = isMuted(zoneId, groupId);

        if (Slogf.isLoggable(TAG_AUDIO, VERBOSE)) {
            Slogf.v(TAG_AUDIO,
                    "onVolumeAdjustment: %s suggested volume group: %s is muted: %b",
                    adjustToString(adjustment),
                    groupId, isMuted);
        }

        int currentVolume = mCarVolumeInfo.getGroupVolume(zoneId, groupId);
        int flags = FLAG_FROM_KEY | FLAG_SHOW_UI;
        int minGain = mCarVolumeInfo.getGroupMinVolume(zoneId, groupId);
        switch (adjustment) {
            case ADJUST_LOWER:
                int minValue = Math.max(currentVolume - 1, minGain);
                if (isMuted)  {
                    minValue = minGain;
                }
                mCarVolumeInfo.setGroupVolume(zoneId, groupId, minValue, flags);
                break;
            case ADJUST_RAISE:
                int maxValue = Math.min(currentVolume + 1,
                        mCarVolumeInfo.getGroupMaxVolume(zoneId, groupId));
                if (isMuted)  {
                    maxValue = minGain;
                }
                mCarVolumeInfo.setGroupVolume(zoneId, groupId, maxValue, flags);
                break;
            case ADJUST_MUTE:
            case ADJUST_UNMUTE:
                setMute(adjustment == ADJUST_MUTE, groupId, flags);
                break;
            case ADJUST_TOGGLE_MUTE:
                setMute(!isMuted, groupId, flags);
                break;
            case ADJUST_SAME:
            default:
                break;
        }
    }

    private boolean isMuted(int zoneId, int groupId) {
        if (mUseCarVolumeGroupMuting) {
            return mCarVolumeInfo.isVolumeGroupMuted(zoneId, groupId);
        }
        return isMasterMute(mAudioManager);
    }

    private void setMute(boolean mute, int groupId, int flags) {
        if (mUseCarVolumeGroupMuting) {
            mCarVolumeInfo.setVolumeGroupMute(PRIMARY_AUDIO_ZONE, groupId, mute, flags);
            return;
        }
        mCarVolumeInfo.setMasterMute(mute, flags);
    }
}
