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

package android.support.car.media;

import android.media.AudioAttributes;

/**
 * Binder interface for {@link android.support.car.media.CarAudioManager}.
 * Check {@link android.support.car.media.CarAudioManager} APIs for expected behavior of each calls.
 *
 * {@CompatibilityApi}
 */
interface ICarAudio {
    int getVersion() = 0;
    AudioAttributes getAudioAttributesForCarUsage(int carUsage) = 1;
}
