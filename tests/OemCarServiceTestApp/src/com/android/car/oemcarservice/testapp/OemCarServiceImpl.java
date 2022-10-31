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

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public final class OemCarServiceImpl extends OemCarService {

    private static final String TAG = OemCarServiceImpl.class.getSimpleName();
    private static final CarVersion SUPPORTED_CAR_VERSION =
            CarVersion.VERSION_CODES.TIRAMISU_2;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private OemCarAudioFocusServiceImpl mOemCarAudioFocusServiceImpl;

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");

        // Initialize all subcomponents.
        synchronized (mLock) {
            mOemCarAudioFocusServiceImpl = new OemCarAudioFocusServiceImpl();
        }
        super.onCreate();
    }


    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        // Releases resource from subcomponents.
        synchronized (mLock) {
            mOemCarAudioFocusServiceImpl = null;
        }
        super.onDestroy();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        Log.d(TAG, "dump");
        writer.println("Dump OemCarServiceImpl");
        writer.println("SUPPORTED_CAR_VERSION:" + SUPPORTED_CAR_VERSION);

    }

    @Override
    public OemCarAudioFocusService getOemAudioFocusService() {
        synchronized (mLock) {
            Log.d(TAG, "getOemAudioFocusService returned " + mOemCarAudioFocusServiceImpl);
            return mOemCarAudioFocusServiceImpl;
        }
    }

    @Override
    public void onCarServiceReady() {
        Log.d(TAG, "onCarServiceReady");
    }

    @Override
    public CarVersion getSupportedCarVersion() {
        Log.d(TAG, "OemCarServiceImpl getSupportedCarVersion called");
        return SUPPORTED_CAR_VERSION;
    }

}
