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

import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;

import static com.android.car.audio.CarAudioUtils.isMicrophoneInputDevice;
import static com.android.car.audio.hal.AudioControlWrapper.AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION;

import android.hardware.automotive.audiocontrol.AudioDeviceConfiguration;
import android.hardware.automotive.audiocontrol.AudioZone;
import android.hardware.automotive.audiocontrol.RoutingDeviceConfiguration;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.car.CarLog;
import com.android.car.audio.hal.AudioControlWrapper;
import com.android.car.internal.util.LocalLog;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Class to get car audio service configuration from audio control HAL
 */
final class CarAudioZonesHelperAudioControlHAL implements CarAudioZonesHelper {

    private static final String TAG = CarAudioZonesHelperAudioControlHAL.class.getSimpleName();

    private final LocalLog mCarAudioLog;
    private final AudioControlWrapper mAudioControl;
    private final AudioControlZoneConverter mZoneConverter;
    private final AudioManagerWrapper mAudioManager;

    private final Object mLock = new Object();
    @GuardedBy("mLock") //Use to guard access
    private final SparseIntArray mAudioZoneIdToOccupantZoneId = new SparseIntArray();
    @GuardedBy("mLock")
    private CarAudioContext mAudioContext;
    @GuardedBy("mLock")
    private AudioDeviceConfiguration mAudioDeviceConfiguration;

    CarAudioZonesHelperAudioControlHAL(AudioControlWrapper wrapper,
            AudioManagerWrapper audioManager, CarAudioSettings settings, LocalLog serviceLog,
            boolean useFadeManagerConfiguration)  {
        mAudioControl = Objects.requireNonNull(wrapper, "Audio control HAL can not be null");
        mAudioManager = Objects.requireNonNull(audioManager, "Audio manager can not be null");
        Objects.requireNonNull(settings, "Car audio settings can not be null");
        mCarAudioLog = Objects.requireNonNull(serviceLog, "Car audio log can not be null");
        mZoneConverter = new AudioControlZoneConverter(audioManager, settings, serviceLog,
                useFadeManagerConfiguration);
        mAudioDeviceConfiguration = new AudioDeviceConfiguration();
    }

    @Override
    public SparseArray<CarAudioZone> loadAudioZones() {
        var deviceConfigs = getAudioDeviceConfiguration();
        if (deviceConfigs.routingConfig == RoutingDeviceConfiguration.DEFAULT_AUDIO_ROUTING) {
            return new SparseArray<>();
        }
        return initCarAudioZones(deviceConfigs);
    }

    private AudioDeviceConfiguration getAudioDeviceConfiguration() {
        if (!mAudioControl.supportsFeature(AUDIOCONTROL_FEATURE_AUDIO_CONFIGURATION)) {
            return getDefaultAudioDeviceConfiguration();
        }
        var deviceConfigs = mAudioControl.getAudioDeviceConfiguration();
        if (deviceConfigs == null) {
            return getDefaultAudioDeviceConfiguration();
        }
        return deviceConfigs;
    }

    private static AudioDeviceConfiguration getDefaultAudioDeviceConfiguration() {
        AudioDeviceConfiguration deviceConfiguration = new AudioDeviceConfiguration();
        deviceConfiguration.routingConfig = RoutingDeviceConfiguration.DEFAULT_AUDIO_ROUTING;
        return deviceConfiguration;
    }

    private SparseArray<CarAudioZone> initCarAudioZones(AudioDeviceConfiguration deviceConfigs) {
        var halAudioZones = mAudioControl.getCarAudioZones();
        if (halAudioZones == null || halAudioZones.isEmpty()) {
            logParsingError("Audio control HAL returned invalid zones");
            return new SparseArray<>();
        }
        var zoneIdToZone = new SparseArray<CarAudioZone>(halAudioZones.size());
        boolean foundErrors = false;
        var zoneIdToOccupantZoneId = new SparseIntArray();
        var usedInputAddresses = new ArraySet<String>();
        for (int c = 0; c < halAudioZones.size(); c++) {
            var halZone = halAudioZones.get(c);
            if (halZone == null) {
                logParsingError("Audio control HAL zones helper found null audio zone");
                foundErrors = true;
                continue;
            }
            var carAudioZone = mZoneConverter.convertAudioZone(halZone, deviceConfigs);
            if (carAudioZone == null) {
                logParsingError("Audio control HAL zones helper failed to parse audio zone "
                        + halZone.id);
                foundErrors = true;
                continue;
            }
            usedInputAddresses.addAll(getInputDeviceAddresses(carAudioZone));
            if (zoneIdToZone.indexOfKey(carAudioZone.getId()) >= 0) {
                logParsingError("Audio control HAL zones helper found a repeating audio zone,"
                        + " zone id " + halZone.id);
                foundErrors = true;
                continue;
            }
            // If found errors continue to parse other zones to get errors
            if (foundErrors) {
                continue;
            }
            zoneIdToZone.put(carAudioZone.getId(), carAudioZone);
            if (halZone.occupantZoneId != AudioZone.UNASSIGNED_OCCUPANT) {
                zoneIdToOccupantZoneId.put(carAudioZone.getId(), halZone.occupantZoneId);
            }
        }
        if (foundErrors) {
            zoneIdToZone.clear();
            zoneIdToOccupantZoneId.clear();
        }
        CarAudioZone primaryZone = zoneIdToZone.get(PRIMARY_AUDIO_ZONE);
        if (primaryZone != null) {
            synchronized (mLock) {
                mAudioContext = primaryZone.getCarAudioContext();
                mAudioDeviceConfiguration = deviceConfigs;
                mAudioZoneIdToOccupantZoneId.clear();
                for (int c = 0; c < zoneIdToOccupantZoneId.size(); c++) {
                    int zoneId = zoneIdToOccupantZoneId.keyAt(c);
                    int occupantId = zoneIdToOccupantZoneId.valueAt(c);
                    mAudioZoneIdToOccupantZoneId.put(zoneId, occupantId);
                }
            }
            addRemainingInputDevicesToZone(primaryZone, usedInputAddresses);
        } else {
            logParsingError("Audio control HAL zones helper could not find primary zone");
            zoneIdToZone.clear();
            zoneIdToOccupantZoneId.clear();
        }

        return zoneIdToZone;
    }

    private void addRemainingInputDevicesToZone(CarAudioZone zone,
            ArraySet<String> usedAddresses) {
        AudioDeviceInfo[] inputDevices = mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (var device : inputDevices) {
            if (usedAddresses.contains(device.getAddress()) || !isMicrophoneInputDevice(device)) {
                continue;
            }
            zone.addInputAudioDevice(new AudioDeviceAttributes(device));
        }
    }

    private static List<String> getInputDeviceAddresses(CarAudioZone carAudioZone) {
        var inputDevices = carAudioZone.getInputAudioDevices();
        var addresses = new ArrayList<String>(inputDevices.size());
        for (int c = 0; c < inputDevices.size(); c++) {
            addresses.add(inputDevices.get(c).getAddress());
        }
        return addresses;
    }

    private void logParsingError(String message) {
        Slog.e(CarLog.TAG_AUDIO, message);
        mCarAudioLog.log(message);
    }

    @Override
    public CarAudioContext getCarAudioContext() {
        synchronized (mLock) {
            return mAudioContext;
        }
    }

    @Override
    public SparseIntArray getCarAudioZoneIdToOccupantZoneIdMapping() {
        synchronized (mLock) {
            return mAudioZoneIdToOccupantZoneId;
        }
    }

    @Override
    public List<CarAudioDeviceInfo> getMirrorDeviceInfos() {
        return mZoneConverter.convertZonesMirroringAudioPorts(
                mAudioControl.getOutputMirroringDevices());
    }

    @Override
    public boolean useCoreAudioRouting() {
        synchronized (mLock) {
            return mAudioDeviceConfiguration.routingConfig
                    == RoutingDeviceConfiguration.CONFIGURABLE_AUDIO_ENGINE_ROUTING;
        }
    }

    @Override
    public boolean useCoreAudioVolume() {
        synchronized (mLock) {
            return mAudioDeviceConfiguration.useCoreAudioVolume;
        }
    }

    @Override
    public boolean useHalDuckingSignalOrDefault(boolean unusedDefaultUseHalDuckingSignal) {
        // Prefer information from HAL over RRO since vendor freeze requires it and this API
        // enables information directly from vendor
        synchronized (mLock) {
            return mAudioDeviceConfiguration.useHalDuckingSignals;
        }
    }

    @Override
    public boolean useVolumeGroupMuting() {
        synchronized (mLock) {
            return mAudioDeviceConfiguration.useCarVolumeGroupMuting;
        }
    }
}
