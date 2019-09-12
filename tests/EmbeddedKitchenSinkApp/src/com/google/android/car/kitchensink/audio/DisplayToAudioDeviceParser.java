/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.google.android.car.kitchensink.audio;

import android.util.Log;
import android.util.SparseArray;

public class DisplayToAudioDeviceParser {
    private static final String TAG = "DisplayToAudioDeviceParser";

    public static SparseArray<String> parseDisplayToDeviceMapping(String[] displayToDeviceArray) {
        SparseArray<String> displayToDeviceMapping = new SparseArray<>();
        for (String displayToDevicePair : displayToDeviceArray) {
            parseDisplayAndAudioDevice(displayToDevicePair, displayToDeviceMapping);
        }
        return displayToDeviceMapping;
    }

    private static void parseDisplayAndAudioDevice(String displayToDevicePair,
            SparseArray<String> displayToDeviceMapping) {
        String[] displayAndDevice = displayToDevicePair.split(",");
        if (displayAndDevice.length != 2) {
            Log.e(TAG, "Invalid display to audio device pair format: " + displayToDevicePair);
        }
        try {
            int display = Integer.parseInt(displayAndDevice[0]);
            String device = displayAndDevice[1];
            displayToDeviceMapping.put(display, device);
            Log.d(TAG, "display: " + display + " device: " + device);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid display to audio device pair format: "
                    + displayToDevicePair);
        }
    }
}
