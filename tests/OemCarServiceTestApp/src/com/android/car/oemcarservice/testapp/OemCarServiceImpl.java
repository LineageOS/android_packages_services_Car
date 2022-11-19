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

import android.car.CarVersion;
import android.car.oem.OemCarAudioFocusService;
import android.car.oem.OemCarService;
import android.util.Log;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public final class OemCarServiceImpl extends OemCarService {

    private static final String TAG = OemCarServiceImpl.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final CarVersion SUPPORTED_CAR_VERSION =
            CarVersion.VERSION_CODES.TIRAMISU_2;


    private final OemCarAudioFocusServiceImpl mOemCarAudioFocusServiceImpl =
            new OemCarAudioFocusServiceImpl();

    @Override
    public void onCreate() {
        if (DEBUG) {
            Slog.d(TAG, "onCreate");
        }

        super.onCreate();
    }


    @Override
    public void onDestroy() {
        if (DEBUG) {
            Slog.d(TAG, "onDestroy");
        }
        // Releases resource from subcomponents.
        super.onDestroy();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (DEBUG) {
            Slog.d(TAG, "dump");
        }
        writer.println("Dump OemCarServiceImpl");
        writer.printf("\tSUPPORTED_CAR_VERSION: %s", SUPPORTED_CAR_VERSION);
        mOemCarAudioFocusServiceImpl.dump(writer, args);
    }

    @Override
    public OemCarAudioFocusService getOemAudioFocusService() {
        if (DEBUG) {
            Slog.d(TAG, "getOemAudioFocusService returned " + mOemCarAudioFocusServiceImpl);
        }
        return mOemCarAudioFocusServiceImpl;
    }

    @Override
    public void onCarServiceReady() {
        if (DEBUG) {
            Slog.d(TAG, "onCarServiceReady");
        }
        mOemCarAudioFocusServiceImpl.onCarServiceReady();
    }

    @Override
    public CarVersion getSupportedCarVersion() {
        if (DEBUG) {
            Slog.d(TAG, "OemCarServiceImpl getSupportedCarVersion called");
        }
        return SUPPORTED_CAR_VERSION;
    }

}
