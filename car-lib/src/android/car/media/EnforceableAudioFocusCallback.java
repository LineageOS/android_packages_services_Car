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
package android.car.media;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.car.feature.Flags;

import java.util.List;

/**
 * Callback interface to be notified when the audio focus enforcement state
 * changes for specific apps.
 *
 * <p>This callback provides information about which client (app/service) are currently being
 * (un)silenced due to audio focus enforcement.
 *
 * @hide
 */
@TestApi
@FlaggedApi(Flags.FLAG_AUDIO_FOCUS_ENFORCEMENT)
public interface EnforceableAudioFocusCallback {
    /**
     * Called when the audio focus enforcement state changes for one or more
     * apps.
     *
     * <p>This method is invoked whenever there's a change in which applications are having their
     * audio silenced or unsilenced due to audio focus enforcement.
     *
     * @param info A {@link List} of {@link EnforcedAudioFocusInfo} each representing an app whose
     *  audio focus enforcement state has changed.
     */
    void onEnforcedAudioFocusChanged(@NonNull List<EnforcedAudioFocusInfo> info);
}
