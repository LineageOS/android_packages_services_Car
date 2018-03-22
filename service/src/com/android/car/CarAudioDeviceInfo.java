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

import android.media.AudioDeviceInfo;
import android.media.AudioDevicePort;
import android.media.AudioFormat;
import android.media.AudioGain;
import android.media.AudioGainConfig;
import android.media.AudioManager;
import android.media.AudioPort;
import android.media.AudioPortConfig;
import android.util.Log;

import com.android.internal.util.Preconditions;

/**
 * A helper class wraps {@link AudioDeviceInfo}, and helps get/set the gain on a specific port
 * in terms of millibels.
 * Note to the reader.  For whatever reason, it seems that AudioGain contains only configuration
 * information (min/max/step, etc) while the ironicly named AudioGainConfig class contains the
 * actual currently active gain value(s).
 */
/* package */ class CarAudioDeviceInfo {

    private final AudioDeviceInfo mAudioDeviceInfo;
    private final int mBusNumber;
    private final int mSampleRate;
    private final int mChannelCount;
    private final int mDefaultGain;
    private final int mMaxGain;
    private final int mMinGain;

    CarAudioDeviceInfo(AudioDeviceInfo audioDeviceInfo) {
        mAudioDeviceInfo = audioDeviceInfo;
        mBusNumber = parseDeviceAddress(audioDeviceInfo.getAddress());
        mSampleRate = getMaxSampleRate(audioDeviceInfo);
        mChannelCount = getMaxChannels(audioDeviceInfo);
        final AudioGain audioGain = Preconditions.checkNotNull(
                getAudioGain(), "No audio gain on device port " + audioDeviceInfo);
        mDefaultGain = audioGain.defaultValue();
        mMaxGain = audioGain.maxValue();
        mMinGain = audioGain.minValue();
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

    int getDefaultGain() {
        return mDefaultGain;
    }

    int getMaxGain() {
        return mMaxGain;
    }

    int getMinGain() {
        return mMinGain;
    }

    // Return value is in millibels
    int getCurrentGain() {
        final AudioDevicePort audioPort = getAudioDevicePort();
        final AudioPortConfig activePortConfig = audioPort.activeConfig();
        if (activePortConfig != null) {
            final AudioGainConfig activeGainConfig = activePortConfig.gain();
            if (activeGainConfig != null
                    && activeGainConfig.values() != null
                    && activeGainConfig.values().length > 0) {
                // Since we are always in MODE_JOINT, we can just look at the first channel
                return activeGainConfig.values()[0];
            }
        }

        Log.e(CarLog.TAG_AUDIO, "Incomplete port configuration while fetching gain");
        return -1;
    }

    int getSampleRate() {
        return mSampleRate;
    }

    int getChannelCount() {
        return mChannelCount;
    }

    // Input is in millibels
    void setCurrentGain(int gainInMillibels) {
        // Clamp the incoming value to our valid range.  Out of range values ARE legal input
        if (gainInMillibels < mMinGain) {
            gainInMillibels = mMinGain;
        } else if (gainInMillibels > mMaxGain) {
            gainInMillibels = mMaxGain;
        }

        // Push the new gain value down to our underlying port which will cause it to show up
        // at the HAL.
        AudioGain audioGain = getAudioGain();
        if (audioGain == null) {
            Log.e(CarLog.TAG_AUDIO, "getAudioGain() returned null.");
        } else {
            // size of gain values is 1 in MODE_JOINT
            AudioGainConfig audioGainConfig = audioGain.buildConfig(
                    AudioGain.MODE_JOINT,
                    audioGain.channelMask(),
                    new int[] { gainInMillibels },
                    0);
            if (audioGainConfig == null) {
                Log.e(CarLog.TAG_AUDIO, "Failed to construct AudioGainConfig");
            } else {
                int r = AudioManager.setAudioPortGain(getAudioDevicePort(), audioGainConfig);
                if (r != AudioManager.SUCCESS) {
                    Log.e(CarLog.TAG_AUDIO, "Failed to setAudioPortGain: " + r);
                }
            }
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
     * @return {@link AudioGain} with {@link AudioGain#MODE_JOINT} on a given {@link AudioPort}.
     * This is useful for inspecting the configuration data associated with this gain controller
     * (min/max/step/default).
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

    @Override
    public String toString() {
        return "device: " + mAudioDeviceInfo.getAddress()
                + " currentGain: " + getCurrentGain()
                + " maxGain: " + mMaxGain
                + " minGain: " + mMinGain;
    }
}
