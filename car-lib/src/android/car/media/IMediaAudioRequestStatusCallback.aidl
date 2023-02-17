/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.car.CarOccupantZoneManager.OccupantZoneInfo;

/**
 * Binder interface to listen for audio media request results
 *
 * @hide
 */
oneway interface IMediaAudioRequestStatusCallback {
    /**
     * Called on media request status changes
     *
     * @param info Occupant zone of the original request source
     * @param requestId Request id whose status has changed
     * @param status New status of the request
     */
    void onMediaAudioRequestStatusChanged(in OccupantZoneInfo info, long requestId, int status);
}