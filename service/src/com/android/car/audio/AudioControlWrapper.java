/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.hardware.automotive.audiocontrol.V2_0.ICloseHandle;
import android.hardware.automotive.audiocontrol.V2_0.IFocusListener;
import android.media.AudioAttributes;
import android.media.AudioAttributes.AttributeUsage;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.car.audio.CarAudioContext.AudioContext;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * AudioControlWrapper wraps IAudioControl HAL interface, handling version specific support so that
 * the rest of CarAudioService doesn't need to know about it.
 */
final class AudioControlWrapper {
    private static final String TAG = AudioControlWrapper.class.getSimpleName();
    @Nullable
    private final android.hardware.automotive.audiocontrol.V1_0.IAudioControl mAudioControlV1;
    @Nullable
    private final android.hardware.automotive.audiocontrol.V2_0.IAudioControl mAudioControlV2;
    private ICloseHandle mCloseHandle;

    static AudioControlWrapper newAudioControl() {
        android.hardware.automotive.audiocontrol.V1_0.IAudioControl audioControlV1 = null;
        android.hardware.automotive.audiocontrol.V2_0.IAudioControl audioControlV2 = null;
        try {
            audioControlV2 = android.hardware.automotive.audiocontrol.V2_0.IAudioControl
                    .getService(true);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to get IAudioControl V2 service", e);
        } catch (NoSuchElementException e) {
            Log.d(TAG, "IAudioControl@V2.0 not in the manifest");
        }

        try {
            audioControlV1 = android.hardware.automotive.audiocontrol.V1_0.IAudioControl
                    .getService(true);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to get IAudioControl V1 service", e);
        } catch (NoSuchElementException e) {
            Log.d(TAG, "IAudioControl@V1.0 not in the manifest");
        }

        return new AudioControlWrapper(audioControlV1, audioControlV2);
    }

    void unregisterFocusListener() {
        if (mCloseHandle != null) {
            try {
                mCloseHandle.close();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to close focus listener", e);
            } finally {
                mCloseHandle = null;
            }
        }
    }

    @VisibleForTesting
    AudioControlWrapper(
            @Nullable android.hardware.automotive.audiocontrol.V1_0.IAudioControl audioControlV1,
            @Nullable android.hardware.automotive.audiocontrol.V2_0.IAudioControl audioControlV2) {
        checkAudioControls(audioControlV1, audioControlV2);
        mAudioControlV1 = audioControlV1;
        mAudioControlV2 = audioControlV2;

    }

    boolean supportsHalAudioFocus() {
        return mAudioControlV2 != null;
    }

    void registerFocusListener(IFocusListener focusListener) {
        Objects.requireNonNull(mAudioControlV2,
                "IAudioControl V2.0 is required for HAL audio focus requests");

        Log.d(TAG, "Registering focus listener on AudioControl HAL");
        try {
            mCloseHandle = mAudioControlV2.registerFocusListener(focusListener);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register focus listener");
            throw new IllegalStateException("Failed to query IAudioControl#registerFocusListener",
                    e);
        }
    }

    void onAudioFocusChange(@AttributeUsage int usage, int zoneId, int focusChange) {
        Objects.requireNonNull(mAudioControlV2,
                "IAudioControl V2.0 is required for HAL audio focus requests");
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onAudioFocusChange: usage " + AudioAttributes.usageToString(usage)
                    + ", zoneId " + zoneId + ", focusChange " + focusChange);
        }
        try {
            mAudioControlV2.onAudioFocusChange(usage, zoneId, focusChange);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to query IAudioControl#onAudioFocusChange", e);
        }
    }

    /**
     * dumps the current state of the AudioControlWrapper
     *
     * @param indent indent to append to each new line
     * @param writer stream to write current state
     */
    public void dump(String indent, PrintWriter writer) {
        writer.printf("%s*AudioControlWrapper*\n", indent);

        String innerIndent = "\t" + indent;
        writer.printf("%sIAudioControl HAL\n", innerIndent);
        if (mAudioControlV2 != null) {
            writer.printf("%s\t- V2.0\n", innerIndent);
        }
        if (mAudioControlV1 != null) {
            writer.printf("%s\t- V1.0\n", innerIndent);
        }

        writer.printf("%sFocus listener registered on HAL? %b", innerIndent,
                (mCloseHandle != null));
    }

    void setFadeTowardFront(float value) {
        try {
            if (mAudioControlV2 != null) {
                mAudioControlV2.setFadeTowardFront(value);
            } else {
                mAudioControlV1.setFadeTowardFront(value);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "setFadeTowardFront failed", e);
        }
    }

    void setBalanceTowardRight(float value) {
        try {
            if (mAudioControlV2 != null) {
                mAudioControlV2.setBalanceTowardRight(value);
            } else {
                mAudioControlV1.setBalanceTowardRight(value);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "setBalanceTowardRight failed", e);
        }
    }

    /**
     * Gets the bus associated with CarAudioContext
     *
     * <p>This API is used along with car_volume_groups.xml to configure volume groups and routing.
     *
     * @param audioContext CarAudioContext to get a context for
     * @return int bus number. Should be part of the prefix for the device's address. For example,
     * bus001_media would be bus 1.
     * @deprecated Volume and routing configuration has been replaced by
     * car_audio_configuration.xml. Starting with IAudioControl@V2.0, getBusForContext is no longer
     * supported.
     */
    @Deprecated
    int getBusForContext(@AudioContext int audioContext) {
        Preconditions.checkState(mAudioControlV2 == null,
                "IAudioControl#getBusForContext no longer supported beyond V1.0");

        try {
            return mAudioControlV1.getBusForContext(audioContext);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query IAudioControl HAL to get bus for context", e);
            throw new IllegalStateException("Failed to query IAudioControl#getBusForContext", e);
        }
    }

    private void checkAudioControls(
            @Nullable android.hardware.automotive.audiocontrol.V1_0.IAudioControl audioControlV1,
            @Nullable android.hardware.automotive.audiocontrol.V2_0.IAudioControl audioControlV2) {
        if (audioControlV2 != null && audioControlV1 != null) {
            Log.w(TAG, "Both versions of IAudioControl are present, defaulting to V2.0");
        } else if (audioControlV2 == null && audioControlV1 == null) {
            throw new IllegalStateException("No version of AudioControl HAL in the manifest");
        } else if (audioControlV1 != null) {
            Log.w(TAG, "IAudioControl V1.0 is deprecated. Consider upgrading to V2.0");
        } else {
            Log.d(TAG, "Using IAudioControl V2.0");
        }
    }
}
