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

import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;

import android.car.media.CarAudioManager;
import android.media.AudioAttributes;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class CarAudioPlaybackMonitor {

    private final CarAudioService mCarAudioService;
    private final TelephonyManager mTelephonyManager;
    private final Executor mExecutor;
    private final CarCallStateListener mCallStateCallback;

    CarAudioPlaybackMonitor(CarAudioService carAudioService, TelephonyManager telephonyManager) {
        mCarAudioService = Objects.requireNonNull(carAudioService,
                "Car audio service can not be null");
        mTelephonyManager = Objects.requireNonNull(telephonyManager,
                "Telephony manager can not be null");
        mExecutor = Executors.newSingleThreadExecutor();
        mCallStateCallback = new CarCallStateListener();
        mTelephonyManager.registerTelephonyCallback(mExecutor, mCallStateCallback);
    }

    /**
     * Reset car audio playback monitor
     *
     * <p>Once reset, car audio playback monitor cannot be reused since the listener to
     * {@link TelephonyManager} has been unregistered.</p>
     */
    void reset() {
        mTelephonyManager.unregisterTelephonyCallback(mCallStateCallback);
    }

    /**
     * Informs {@link CarAudioService} that newly active playbacks are received and min/max
     * activation volume should be applied if needed.
     *
     * @param newActivePlaybackAttributes List of {@link AudioAttributes} of the newly active
     *                                    {@link android.media.AudioPlaybackConfiguration}s
     * @param zoneId Zone Id of thr newly active playbacks
     */
    public void onActiveAudioPlaybackAttributesAdded(
            List<AudioAttributes> newActivePlaybackAttributes, int zoneId) {
        if (newActivePlaybackAttributes == null || newActivePlaybackAttributes.isEmpty()) {
            return;
        }
        mCarAudioService.handleActivationVolumeWithAudioAttributes(newActivePlaybackAttributes,
                zoneId);
    }

    private class CarCallStateListener extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            int usage;
            switch (state) {
                case TelephonyManager.CALL_STATE_RINGING:
                    usage = USAGE_NOTIFICATION_RINGTONE;
                    break;
                case TelephonyManager.CALL_STATE_OFFHOOK:
                    usage = USAGE_VOICE_COMMUNICATION;
                    break;
                default:
                    return;
            }
            AudioAttributes audioAttributes = CarAudioContext.getAudioAttributeFromUsage(usage);
            mCarAudioService.handleActivationVolumeWithAudioAttributes(List.of(audioAttributes),
                    CarAudioManager.PRIMARY_AUDIO_ZONE);
        }
    }
}
