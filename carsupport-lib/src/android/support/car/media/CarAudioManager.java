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
import android.support.car.CarManagerBase;

public class CarAudioManager implements CarManagerBase {

    /**
     * TODO Fix this. Needs new type or custom range in AudioAttribures.
     */
    public static final int AUDIO_ATTRIBUTES_USAGE_RADIO = AudioAttributes.USAGE_VIRTUAL_SOURCE;

    private final ICarAudio mService;

    @Override
    public void onCarDisconnected() {
        // TODO Auto-generated method stub
    }

    /** @hide */
    public CarAudioManager(ICarAudio service) {
        mService = service;
    }
}
