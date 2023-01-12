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

import android.car.oem.OemCarAudioFocusService;
import android.media.AudioFocusInfo;
import android.util.Log;

import java.io.PrintWriter;
import java.util.List;

public final class OemCarAudioFocusServiceImpl implements OemCarAudioFocusService {

    private static final String TAG = OemCarAudioFocusServiceImpl.class.getSimpleName();

    public OemCarAudioFocusServiceImpl() {
        Log.d(TAG, "constructor");
    }

    @Override
    public void init() {
        Log.d(TAG, "init");

    }

    @Override
    public void release() {
        Log.d(TAG, "release");
    }

    @Override
    public void onCarServiceReady() {
        Log.d(TAG, "onCarServiceReady");
        // Do any CarService calls
    }

    @Override
    public void dump(PrintWriter writer, String[] args) {
        Log.d(TAG, "dump");
        writer.println("Dump OemCarAudioFocusServiceImpl");
    }

    @Override
    public void audioFocusChanged(List<AudioFocusInfo> currentFocusHolders,
            List<AudioFocusInfo> currentFocusLosers, int zoneId) {
        Log.d(TAG, "OemCarAudioFocusServiceImpl audioFocusChanged called");
    }

}
