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

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.car.builtin.media.AudioManagerHelper;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioManager.AudioPlaybackCallback;
import android.media.AudioManager.AudioServerStateCallback;
import android.media.AudioPlaybackConfiguration;
import android.media.FadeManagerConfiguration;
import android.media.audiopolicy.AudioPolicy;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.os.Handler;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

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

    void setMasterMute(boolean mute, int flags) {
        AudioManagerHelper.setMasterMute(mAudioManager, mute, flags);
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

    void registerVolumeGroupCallback(Executor executor,
            AudioManager.VolumeGroupCallback coreAudioVolumeGroupCallback) {
        mAudioManager.registerVolumeGroupCallback(executor, coreAudioVolumeGroupCallback);
    }

    void unregisterVolumeGroupCallback(
            AudioManager.VolumeGroupCallback coreAudioVolumeGroupCallback) {
        mAudioManager.unregisterVolumeGroupCallback(coreAudioVolumeGroupCallback);
    }

    boolean isAudioServerRunning() {
        return mAudioManager.isAudioServerRunning();
    }

    void setAudioServerStateCallback(Executor executor, AudioServerStateCallback callback) {
        mAudioManager.setAudioServerStateCallback(executor, callback);
    }

    @SuppressLint("WrongConstant")
    void setSupportedSystemUsages(int[] systemUsages) {
        mAudioManager.setSupportedSystemUsages(systemUsages);
    }

    void registerAudioDeviceCallback(AudioDeviceCallback callback, Handler handler) {
        mAudioManager.registerAudioDeviceCallback(callback, handler);
    }

    void unregisterAudioDeviceCallback(AudioDeviceCallback callback) {
        mAudioManager.unregisterAudioDeviceCallback(callback);
    }

    void clearAudioServerStateCallback() {
        mAudioManager.clearAudioServerStateCallback();
    }

    void unregisterAudioPolicy(AudioPolicy policy) {
        mAudioManager.unregisterAudioPolicy(policy);
    }

    void unregisterAudioPolicyAsync(AudioPolicy policy) {
        mAudioManager.unregisterAudioPolicyAsync(policy);
    }

    int registerAudioPolicy(AudioPolicy policy) {
        return mAudioManager.registerAudioPolicy(policy);
    }

    void setStreamVolume(int stream, int index, int flags) {
        mAudioManager.setStreamVolume(stream, index, flags);
    }

    int getStreamMaxVolume(int stream) {
        return mAudioManager.getStreamMaxVolume(stream);
    }

    int getStreamMinVolume(int stream) {
        return mAudioManager.getStreamMinVolume(stream);
    }

    int getStreamVolume(int stream) {
        return mAudioManager.getStreamVolume(stream);
    }

    void setParameters(String parameters) {
        mAudioManager.setParameters(parameters);
    }

    AudioDeviceInfo[] getDevices(int flags) {
        return mAudioManager.getDevices(flags);
    }

    void registerAudioPlaybackCallback(AudioPlaybackCallback callback, @Nullable Handler handler) {
        mAudioManager.registerAudioPlaybackCallback(callback, handler);
    }

    void unregisterAudioPlaybackCallback(AudioPlaybackCallback callback) {
        mAudioManager.unregisterAudioPlaybackCallback(callback);
    }

    boolean releaseAudioPatch(AudioManagerHelper.AudioPatchInfo audioPatchInfo) {
        return AudioManagerHelper.releaseAudioPatch(mAudioManager, audioPatchInfo);
    }

    List<AudioPlaybackConfiguration> getActivePlaybackConfigurations() {
        return mAudioManager.getActivePlaybackConfigurations();
    }

    static List<AudioProductStrategy> getAudioProductStrategies() {
        return AudioManager.getAudioProductStrategies();
    }

    static List<AudioVolumeGroup> getAudioVolumeGroups() {
        return AudioManager.getAudioVolumeGroups();
    }
}
