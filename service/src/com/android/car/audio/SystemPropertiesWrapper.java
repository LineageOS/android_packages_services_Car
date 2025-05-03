/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.os.SystemProperties;

/**
 * Class to wrap system properties used in car audio service.
 */
// Not final since will create fake in car audio service unit tests
class SystemPropertiesWrapper {

    //Property to determine if audio patches are available from car audio service as configured by
    // OEM/Vendors
    static final String PROPERTY_RO_ENABLE_AUDIO_PATCH = "ro.android.car.audio.enableaudiopatch";

    /**
     * Returns {@code true} if audio patch APIs are supported in car audio service,
     * {@code false} otherwise
     */
    public boolean areAudioPatchAPIsEnabled() {
        return SystemProperties.getBoolean(PROPERTY_RO_ENABLE_AUDIO_PATCH, /* default= */ false);
    }
}
