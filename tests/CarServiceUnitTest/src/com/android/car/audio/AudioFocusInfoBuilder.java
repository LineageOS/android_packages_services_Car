/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.BOILERPLATE_CODE;

import android.car.builtin.media.AudioManagerHelper;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.util.ArrayList;
import java.util.List;

@ExcludeFromCodeCoverageGeneratedReport(reason = BOILERPLATE_CODE)
public final class AudioFocusInfoBuilder {
    private int mUsage;
    private int mClientUid;
    private String mClientId;
    private int mGainRequest;
    private String mPackageName = "com.android.car.audio";
    private Bundle mBundle = null;
    private int mLossReceived = AudioManager.AUDIOFOCUS_NONE;
    private int mSdk = Build.VERSION.SDK_INT;
    private boolean mDelayedFocusRequestEnabled;
    private boolean mPausesOnDuckRequestEnabled;
    private AudioAttributes mAudioAttributes;
    private final List<String> mTags = new ArrayList<>();

    /**
     * Set audio focus info usage
     */
    public AudioFocusInfoBuilder setUsage(int usage) {
        mUsage = usage;
        return this;
    }

    /**
     * Add tag to audio focus info
     */
    public AudioFocusInfoBuilder addTag(String tag) {
        mTags.add(tag);
        return this;
    }

    /**
     * Set audio focus attributes.
     *
     * <p>Note: if this method is used, other attributes (usage, tags, bundle) set by other methods
     * are ignored.
     */
    public AudioFocusInfoBuilder setAudioAttributes(AudioAttributes attributes) {
        mAudioAttributes = attributes;
        return this;
    }

    /**
     * Set client UID info usage
     */
    public AudioFocusInfoBuilder setClientUid(int clientUid) {
        mClientUid = clientUid;
        return this;
    }

    /**
     * Set audio focus info client ID
     */
    public AudioFocusInfoBuilder setClientId(String clientId) {
        mClientId = clientId;
        return this;
    }

    /**
     * Set audio focus info package name
     */
    public AudioFocusInfoBuilder setPackageName(String packageName) {
        mPackageName = packageName;
        return this;
    }

    /**
     * Set audio focus info gain request
     */
    public AudioFocusInfoBuilder setGainRequest(int gainRequest) {
        mGainRequest = gainRequest;
        return this;
    }

    /**
     * Set audio focus info loss request
     */
    public AudioFocusInfoBuilder setLossReceived(int lossReceived) {
        mLossReceived = lossReceived;
        return this;
    }

    /**
     * Set audio focus info sdk
     */
    public AudioFocusInfoBuilder setSdk(int sdk) {
        mSdk = sdk;
        return this;
    }

    /**
     * Set audio focus info bundle
     */
    public AudioFocusInfoBuilder setBundle(Bundle bundle) {
        mBundle = bundle;
        return this;
    }

    /**
     * Set whether delayed focus request should be enabled in audio focus info
     */
    public AudioFocusInfoBuilder setDelayedFocusRequestEnable(boolean delayedFocusRequestEnabled) {
        mDelayedFocusRequestEnabled = delayedFocusRequestEnabled;
        return this;
    }

    /**
     * Set whether pause on duck should be enabled in audio focus info
     */
    public AudioFocusInfoBuilder setPausesOnDuckRequestEnable(boolean pausesOnDuckRequestEnabled) {
        mPausesOnDuckRequestEnabled = pausesOnDuckRequestEnabled;
        return this;
    }


    /**
     * Build audio focus info
     */
    public AudioFocusInfo createAudioFocusInfo() {
        AudioAttributes attributes = mAudioAttributes;
        if (attributes == null) {
            AudioAttributes.Builder builder = new AudioAttributes.Builder();
            if (AudioAttributes.isSystemUsage(mUsage)) {
                builder.setSystemUsage(mUsage);
            } else {
                builder.setUsage(mUsage);
            }

            for (int i = 0; i < mTags.size(); i++) {
                AudioManagerHelper.addTagToAudioAttributes(builder, mTags.get(i));
            }

            if (mBundle != null) {
                builder = builder.addBundle(mBundle);
            }
            attributes = builder.build();
        }

        int flags = 0;
        if (mDelayedFocusRequestEnabled) {
            flags |= AudioManager.AUDIOFOCUS_FLAG_DELAY_OK;
        }

        if (mPausesOnDuckRequestEnabled) {
            flags |= AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS;
        }

        return new AudioFocusInfo(attributes, mClientUid, mClientId,
                mPackageName, mGainRequest, mLossReceived, flags, mSdk);
    }
}
