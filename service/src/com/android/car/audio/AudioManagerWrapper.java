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

import android.car.builtin.media.AudioManagerHelper;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.FadeManagerConfiguration;
import android.media.audiopolicy.AudioPolicy;
import android.media.audiopolicy.AudioProductStrategy;

import java.util.List;
import java.util.Objects;

/**
 * Class to wrap audio manager. This makes it easier to call to audio manager without the need
 * for actually using an audio manager.
 */
public final class AudioManagerWrapper {

    private final AudioManager mAudioManager;

    AudioManagerWrapper(AudioManager audioManager) {
        mAudioManager = Objects.requireNonNull(audioManager, "Audio manager can not be null");
    }

    int getMinVolumeIndexForAttributes(AudioAttributes audioAttributes) {
        return mAudioManager.getMinVolumeIndexForAttributes(audioAttributes);
    }

    int getMaxVolumeIndexForAttributes(AudioAttributes audioAttributes) {
        return mAudioManager.getMaxVolumeIndexForAttributes(audioAttributes);
    }

    int getVolumeIndexForAttributes(AudioAttributes audioAttributes) {
        return mAudioManager.getVolumeIndexForAttributes(audioAttributes);
    }

    boolean isVolumeGroupMuted(int groupId) {
        return AudioManagerHelper.isVolumeGroupMuted(mAudioManager, groupId);
    }

    int getLastAudibleVolumeForVolumeGroup(int groupId) {
        return AudioManagerHelper.getLastAudibleVolumeGroupVolume(mAudioManager, groupId);
    }

    void setVolumeGroupVolumeIndex(int groupId, int gainIndex, int flags) {
        mAudioManager.setVolumeGroupVolumeIndex(groupId, gainIndex, flags);
    }

    void adjustVolumeGroupVolume(int groupId, int adjustment, int flags) {
        AudioManagerHelper.adjustVolumeGroupVolume(mAudioManager, groupId, adjustment, flags);
    }

    void setPreferredDeviceForStrategy(AudioProductStrategy strategy,
            AudioDeviceAttributes audioDeviceAttributes) {
        mAudioManager.setPreferredDeviceForStrategy(strategy, audioDeviceAttributes);
    }

    boolean setAudioDeviceGain(String address, int gain, boolean isOutput) {
        return AudioManagerHelper.setAudioDeviceGain(mAudioManager, address, gain, isOutput);
    }

    /**
     * {@link AudioManager#abandonAudioFocusRequest(AudioFocusRequest)}
     */
    public int abandonAudioFocusRequest(AudioFocusRequest audioFocusRequest) {
        return mAudioManager.abandonAudioFocusRequest(audioFocusRequest);
    }

    /**
     * {@link AudioManager#requestAudioFocus(AudioFocusRequest)}
     */
    public int requestAudioFocus(AudioFocusRequest audioFocusRequest) {
        return mAudioManager.requestAudioFocus(audioFocusRequest);
    }

    boolean isMasterMuted() {
        return AudioManagerHelper.isMasterMute(mAudioManager);
    }

    int dispatchAudioFocusChange(AudioFocusInfo info, int focusChange, AudioPolicy policy) {
        return mAudioManager.dispatchAudioFocusChange(info, focusChange, policy);
    }

    int dispatchAudioFocusChangeWithFade(AudioFocusInfo info, int changeType,
            AudioPolicy policy, List<AudioFocusInfo> activeAfis,
            FadeManagerConfiguration fadeConfig) {
        return mAudioManager.dispatchAudioFocusChangeWithFade(info, changeType, policy, activeAfis,
                fadeConfig);
    }

    void setFocusRequestResult(AudioFocusInfo info, int response, AudioPolicy policy) {
        mAudioManager.setFocusRequestResult(info, response, policy);
    }
}
