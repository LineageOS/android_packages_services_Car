/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.util.SparseArray;
import android.util.SparseIntArray;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;

/**
 * Interface for loading car audio service configurations
 */
public interface CarAudioZonesHelper {

    /**
     * @return Car audio context which can be used to setup car audio service
     */
    CarAudioContext getCarAudioContext();

    /**
     * @return Car audio zone id to occupant zone id mapping
     */
    SparseIntArray getCarAudioZoneIdToOccupantZoneIdMapping();

    /**
     * @return Mirroring devices to use between audio zones
     */
    List<CarAudioDeviceInfo> getMirrorDeviceInfos();

    /**
     * @return List of audio zones which can be used to configure the car audio service
     */
    SparseArray<CarAudioZone> loadAudioZones() throws IOException, XmlPullParserException;

    /**
     * @return Boolean indicating if core audio routing should be used
     */
    boolean useCoreAudioRouting();

    /**
     * @return Boolean indicating if core audio volume management should be used
     */
    boolean useCoreAudioVolume();

    /**
     * Determines of audio control HAL ducking signals should be use
     *
     * @param defaultUseHalDuckingSignal default value that should be returned of there is no
     *   definition of use HAL ducking defined internally
     *
     * @return Boolean indicating if HAL ducking signal should be use
     */
    boolean useHalDuckingSignalOrDefault(boolean defaultUseHalDuckingSignal);

    /**
     * @return Boolean indicating if audio control HAL muting should be used
     */
    boolean useVolumeGroupMuting();
}
