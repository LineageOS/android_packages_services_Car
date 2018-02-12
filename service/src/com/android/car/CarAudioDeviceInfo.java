/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.car;

import android.annotation.Nullable;
import android.car.media.CarAudioManager;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioDevicePort;
import android.media.AudioFormat;
import android.media.AudioGain;
import android.media.AudioGainConfig;
import android.media.AudioManager;
import android.media.AudioPort;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.util.Preconditions;

/**
 * A helper class wraps {@link AudioDeviceInfo}, translates the min / max/ step gain values
 * in gain controller to gain index values.
 */
/* package */ class CarAudioDeviceInfo {

    private final ContentResolver mContentResolver;
    private final AudioDeviceInfo mAudioDeviceInfo;
    private final int mBusNumber;
    private final int mMaxGainIndex;
    private final int mMinGainIndex;
    private final int mSampleRate;
    private final int mChannelCount;

    private int mCurrentGainIndex;

    CarAudioDeviceInfo(Context context, AudioDeviceInfo audioDeviceInfo) {
        mContentResolver = context.getContentResolver();
        mAudioDeviceInfo = audioDeviceInfo;
        mBusNumber = parseDeviceAddress(mAudioDeviceInfo.getAddress());
        mSampleRate = getMaxSampleRate(audioDeviceInfo);
        mChannelCount = getMaxChannels(audioDeviceInfo);
        final AudioGain audioGain = Preconditions.checkNotNull(
                getAudioGain(), "No audio gain on device port " + audioDeviceInfo);
        mMaxGainIndex = gainToIndex(audioGain, audioGain.maxValue());
        mMinGainIndex = gainToIndex(audioGain, audioGain.minValue());

        // Get the current gain index from persistent storage and fallback to default.
        mCurrentGainIndex = Settings.Global.getInt(mContentResolver,
                CarAudioManager.getVolumeSettingsKeyForBus(mBusNumber), -1);
        if (mCurrentGainIndex < 0) {
            mCurrentGainIndex = gainToIndex(audioGain, audioGain.defaultValue());
        }
    }

    AudioDeviceInfo getAudioDeviceInfo() {
        return mAudioDeviceInfo;
    }

    AudioDevicePort getAudioDevicePort() {
        return mAudioDeviceInfo.getPort();
    }

    int getBusNumber() {
        return mBusNumber;
    }

    int getMaxGainIndex() {
        return mMaxGainIndex;
    }

    int getMinGainIndex() {
        return mMinGainIndex;
    }

    int getCurrentGainIndex() {
        return mCurrentGainIndex;
    }

    int getSampleRate() {
        return mSampleRate;
    }

    int getChannelCount() {
        return mChannelCount;
    }

    void setCurrentGainIndex(int gainIndex) {
        Preconditions.checkArgument(
                gainIndex >= mMinGainIndex && gainIndex <= mMaxGainIndex,
                "Invalid gain index: " + gainIndex);

        // Calls to AudioManager.setAudioPortConfig
        AudioGainConfig audioGainConfig = null;
        AudioGain audioGain = getAudioGain();
        if (audioGain != null) {
            audioGainConfig = getPortGainForIndex(gainIndex);
        }
        if (audioGainConfig != null) {
            int r = AudioManager.setAudioPortGain(getAudioDevicePort(), audioGainConfig);
            if (r == 0) {
                // Updates the setting and internal state only if setAudioPortGain succeeds
                Settings.Global.putInt(mContentResolver,
                        CarAudioManager.getVolumeSettingsKeyForBus(mBusNumber), gainIndex);
                mCurrentGainIndex = gainIndex;
            } else {
                Log.e(CarLog.TAG_AUDIO, "Failed to setAudioPortGain: " + r);
            }
        } else {
            Log.e(CarLog.TAG_AUDIO, "Failed to construct AudioGainConfig");
        }
    }

    /**
     * Parse device address. Expected format is BUS%d_%s, address, usage hint
     * @return valid address (from 0 to positive) or -1 for invalid address.
     */
    private int parseDeviceAddress(String address) {
        String[] words = address.split("_");
        int addressParsed = -1;
        if (words[0].toLowerCase().startsWith("bus")) {
            try {
                addressParsed = Integer.parseInt(words[0].substring(3));
            } catch (NumberFormatException e) {
                //ignore
            }
        }
        if (addressParsed < 0) {
            return -1;
        }
        return addressParsed;
    }

    private int getMaxSampleRate(AudioDeviceInfo info) {
        int[] sampleRates = info.getSampleRates();
        if (sampleRates == null || sampleRates.length == 0) {
            return 48000;
        }
        int sampleRate = sampleRates[0];
        for (int i = 1; i < sampleRates.length; i++) {
            if (sampleRates[i] > sampleRate) {
                sampleRate = sampleRates[i];
            }
        }
        return sampleRate;
    }

    private int getMaxChannels(AudioDeviceInfo info) {
        int[] channelMasks = info.getChannelMasks();
        if (channelMasks == null) {
            return AudioFormat.CHANNEL_OUT_STEREO;
        }
        int channels = AudioFormat.CHANNEL_OUT_MONO;
        int numChannels = 1;
        for (int channelMask : channelMasks) {
            int currentNumChannels = Integer.bitCount(channelMask);
            if (currentNumChannels > numChannels) {
                numChannels = currentNumChannels;
                channels = channelMask;
            }
        }
        return channels;
    }

    /**
     * @return {@link AudioGain} with {@link AudioGain#MODE_JOINT} on a given {@link AudioPort}
     */
    AudioGain getAudioGain() {
        final AudioDevicePort audioPort = getAudioDevicePort();
        if (audioPort != null && audioPort.gains().length > 0) {
            for (AudioGain audioGain : audioPort.gains()) {
                if ((audioGain.mode() & AudioGain.MODE_JOINT) != 0) {
                    return checkAudioGainConfiguration(audioGain);
                }
            }
        }
        return null;
    }

    /**
     * Constraints applied to gain configuration, see also audio_policy_configuration.xml
     */
    private AudioGain checkAudioGainConfiguration(AudioGain audioGain) {
        Preconditions.checkArgument(audioGain.maxValue() >= audioGain.minValue());
        Preconditions.checkArgument((audioGain.defaultValue() >= audioGain.minValue())
                && (audioGain.defaultValue() <= audioGain.maxValue()));
        Preconditions.checkArgument(
                ((audioGain.maxValue() - audioGain.minValue()) % audioGain.stepValue()) == 0);
        Preconditions.checkArgument(
                ((audioGain.defaultValue() - audioGain.minValue()) % audioGain.stepValue()) == 0);
        return audioGain;
    }

    /**
     * @param audioGain {@link AudioGain} on a {@link AudioPort}
     * @param gain Gain value in millibel
     * @return index value depends on max / min / step of a given {@link AudioGain}
     */
    private int gainToIndex(AudioGain audioGain, int gain) {
        gain = checkGainBound(audioGain, gain);
        return (gain - audioGain.minValue()) / audioGain.stepValue();
    }

    /**
     * @param audioGain {@link AudioGain} on a {@link AudioPort}
     * @param index index value depends on max / min / step of a given {@link AudioGain}
     * @return gain value in millibel
     */
    private int indexToGain(AudioGain audioGain, int index) {
        final int gain = index * audioGain.stepValue() + audioGain.minValue();
        return checkGainBound(audioGain, gain);
    }

    private int checkGainBound(AudioGain audioGain, int gain) {
        if (gain < audioGain.minValue() || gain > audioGain.maxValue()) {
            throw new RuntimeException("Gain value out of bound: " + gain);
        }
        return gain;
    }

    @Nullable
    AudioGainConfig getPortGainForIndex(int index) {
        AudioGainConfig audioGainConfig = null;
        AudioGain audioGain = getAudioGain();
        if (audioGain != null) {
            int gainValue = indexToGain(audioGain, index);
            // size of gain values is 1 in MODE_JOINT
            audioGainConfig = audioGain.buildConfig(AudioGain.MODE_JOINT,
                    audioGain.channelMask(), new int[] { gainValue }, 0);
        }
        return audioGainConfig;
    }

    @Override
    public String toString() {
        return "device: " + mAudioDeviceInfo.getAddress()
                + " currentGain: " + mCurrentGainIndex
                + " maxGain: " + mMaxGainIndex
                + " minGain: " + mMinGainIndex;
    }
}
