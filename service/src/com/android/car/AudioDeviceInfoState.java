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

import android.car.media.CarAudioManager;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioDevicePort;
import android.media.AudioFormat;
import android.media.AudioGain;
import android.provider.Settings;

import com.android.internal.util.Preconditions;

/**
 * This class holds the min and max gain index translated from min / max / step values in
 * gain control. Also tracks the current gain index on a certain {@link AudioDevicePort}.
 */
/* package */ class AudioDeviceInfoState {
    private final ContentResolver mContentResolver;
    private final AudioDeviceInfo mAudioDeviceInfo;
    private final int mBusNumber;
    private final int mMaxGainIndex;
    private final int mMinGainIndex;
    private final int mSampleRate;
    private final int mChannelCount;

    private int mCurrentGainIndex;

    AudioDeviceInfoState(Context context, AudioDeviceInfo audioDeviceInfo) {
        mContentResolver = context.getContentResolver();
        mAudioDeviceInfo = audioDeviceInfo;
        mBusNumber = parseDeviceAddress(mAudioDeviceInfo.getAddress());
        mSampleRate = getMaxSampleRate(audioDeviceInfo);
        mChannelCount = getMaxChannels(audioDeviceInfo);
        final AudioGain audioGain = Preconditions.checkNotNull(
                CarAudioService.getAudioGain(audioDeviceInfo.getPort()),
                "No audio gain on device port " + audioDeviceInfo);
        mMaxGainIndex = CarAudioService.gainToIndex(audioGain, audioGain.maxValue());
        mMinGainIndex = CarAudioService.gainToIndex(audioGain, audioGain.minValue());

        // Get the current gain index from persistent storage and fallback to default.
        mCurrentGainIndex = Settings.Global.getInt(mContentResolver,
                CarAudioManager.getVolumeSettingsKeyForBus(mBusNumber), -1);
        if (mCurrentGainIndex < 0) {
            mCurrentGainIndex = CarAudioService.gainToIndex(audioGain, audioGain.defaultValue());
        }
    }

    AudioDeviceInfo getAudioDeviceInfo() {
        return mAudioDeviceInfo;
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
        Settings.Global.putInt(mContentResolver,
                CarAudioManager.getVolumeSettingsKeyForBus(mBusNumber), gainIndex);
        mCurrentGainIndex = gainIndex;
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

    @Override
    public String toString() {
        return "device: " + mAudioDeviceInfo.getAddress()
                + " currentGain: " + mCurrentGainIndex
                + " maxGain: " + mMaxGainIndex
                + " minGain: " + mMinGainIndex;
    }
}
