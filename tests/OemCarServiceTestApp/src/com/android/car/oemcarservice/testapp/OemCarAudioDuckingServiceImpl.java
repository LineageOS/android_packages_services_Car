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

package com.android.car.oemcarservice.testapp;

import android.car.oem.OemCarAudioDuckingService;
import android.car.oem.OemCarAudioVolumeRequest;
import android.media.AudioAttributes;
import android.util.Log;
import android.util.Slog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.oem.ducking.DuckingInteractions;

import java.io.PrintWriter;
import java.util.List;

final class OemCarAudioDuckingServiceImpl implements OemCarAudioDuckingService {

    private static final String TAG = "OemCarAudioDuckingSrv";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final DuckingInteractions mDuckingInteractions;

    /**
     * Constructs a {@link DuckingInteractions} with the given ducking priorities, if
     * the given ducking priorities is null it will default to
     * {@link DuckingInteractions.DUCKED_PRIORITIES}
     *
     * @param duckingPriorities A list of ducking priorities from highest priority to lowest
     *        priority
     */
    OemCarAudioDuckingServiceImpl(@Nullable List<AudioAttributes> duckingPriorities) {
        List<AudioAttributes> duckingPriorityList = duckingPriorities;
        if (duckingPriorityList == null) {
            duckingPriorityList = DuckingInteractions.DUCKED_PRIORITIES;
        }
        mDuckingInteractions = new DuckingInteractions(duckingPriorityList);
        if (DEBUG) {
            Slog.d(TAG, "constructor");
        }
    }

    @NonNull
    @Override
    public List<AudioAttributes> evaluateAttributesToDuck(
            @NonNull OemCarAudioVolumeRequest requestInfo) {
        return mDuckingInteractions.getDuckedAudioAttributes(requestInfo);
    }

    @Override
    public void init() {
        if (DEBUG) {
            Slog.d(TAG, "init");
        }
    }

    @Override
    public void release() {
        if (DEBUG) {
            Slog.d(TAG, "release");
        }
    }

    @Override
    public void onCarServiceReady() {
        if (DEBUG) {
            Slog.d(TAG, "onCarServiceReady");
        }
        // Do any CarService calls
    }

    @Override
    public void dump(PrintWriter writer, String[] args) {
        if (DEBUG) {
            Slog.d(TAG, "dump");
        }
        writer.println("  OemCarAudioDuckingServiceImpl");
        mDuckingInteractions.dump(writer, "  ");
    }
}
