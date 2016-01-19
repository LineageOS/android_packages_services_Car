/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.car.hardware.radio;

import com.android.car.hardware.radio.CarRadioPreset;
import com.android.car.hardware.radio.ICarRadioEventListener;

/** {@CompatibilityApi} */
interface ICarRadio {
    /**
     * Returns the version number for this implementation of service.
     */
    int getVersion() = 0;
    /**
     * Returns the number of hard presets on the radio unit that may be programmed.
     */
    int getPresetCount() = 1;

    /**
     * Registers the client for updates to radio changes.
     */
    void registerListener(in ICarRadioEventListener listener, int version) = 2;

    /**
     * Unregisters the client for updates to radio changes.
     */
    void unregisterListener(in ICarRadioEventListener listener) = 3;

    /**
     * Gets the preset values stored for a particular preset number.
     */
    CarRadioPreset getPreset(int presetNumber) = 4;

    /**
     * Sets a specified preset (hard button) in the car. In order to check for success listen to
     * events using {@link registerOrUpdateRadioListener}.
     */
    boolean setPreset(in CarRadioPreset preset) = 5;
}
