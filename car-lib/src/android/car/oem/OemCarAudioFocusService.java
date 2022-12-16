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
package android.car.oem;

import android.annotation.NonNull;
import android.car.annotation.ApiRequirements;
import android.media.AudioFocusInfo;

import java.util.List;

/*
 * OemCarAudioFocusServiceInterface would expose all the method from IOemCarAudioFocusService. It
 * should always be in sync with IOemCarAudioFocusService. Oem will implement
 * OemCarAudioFocusServiceInterface which would be used by OemCarAudioFocusService.
 */
/**
 * Interface for Audio focus for OEM Service.
 *
 * @hide
 */
public interface OemCarAudioFocusService extends OemCarServiceComponent {
    /**
     * Updates audio focus change. It is one way call for OEM Service.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_2,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    void audioFocusChanged(@NonNull List<AudioFocusInfo> currentFocusHolders,
            @NonNull List<AudioFocusInfo> currentFocusLosers, int zoneId);
}
