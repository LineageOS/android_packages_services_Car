/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.hardware.automotive.audiocontrol.MutingInfo;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class CarVolumeGroupMuting {

    private static final String TAG = CarLog.tagFor(CarVolumeGroupMuting.class);

    private final SparseArray<CarAudioZone> mCarAudioZones;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private List<MutingInfo> mLastMutingInformation;

    CarVolumeGroupMuting(@NonNull SparseArray<CarAudioZone> carAudioZones) {
        Objects.requireNonNull(carAudioZones, "Car Audio Zones can not be null");
        Preconditions.checkArgument(carAudioZones.size() != 0,
                "At least one car audio zone must be present.");
        mCarAudioZones = carAudioZones;
        mLastMutingInformation = new ArrayList<>();
    }

    /**
     * Signal that mute has changed.
     */
    public void carMuteChanged() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, "carMuteChanged");
        }
        List<MutingInfo> mutingInfo = generateMutingInfo();
        setLastMutingInfo(mutingInfo);
        // TODO(175732501): Add AudioControl HAL mute communication
    }

    private void setLastMutingInfo(List<MutingInfo> mutingInfo) {
        synchronized (mLock) {
            mLastMutingInformation = mutingInfo;
        }
    }

    @VisibleForTesting
    List<MutingInfo> getLastMutingInformation() {
        synchronized (mLock) {
            return mLastMutingInformation;
        }
    }

    private List<MutingInfo> generateMutingInfo() {
        List<MutingInfo> mutingInformation = new ArrayList<>(mCarAudioZones.size());
        for (int index = 0; index < mCarAudioZones.size(); index++) {
            mutingInformation.add(generateMutingInfoFromZone(mCarAudioZones.valueAt(index)));
        }

        return mutingInformation;
    }

    /**
     * Dumps internal state
     */
    public void dump(IndentingPrintWriter writer) {
        writer.println(TAG);
        writer.increaseIndent();
        synchronized (mLock) {
            for (int index = 0; index < mLastMutingInformation.size(); index++) {
                dumpCarMutingInfo(writer, mLastMutingInformation.get(index));

            }
        }
        writer.decreaseIndent();
    }

    private void dumpCarMutingInfo(IndentingPrintWriter writer, MutingInfo info) {
        writer.printf("Zone ID: %d\n", info.zoneId);

        writer.println("Muted Devices:");
        writer.increaseIndent();
        dumpDeviceAddresses(writer, info.deviceAddressesToMute);
        writer.decreaseIndent();

        writer.println("Un-muted Devices:");
        writer.increaseIndent();
        dumpDeviceAddresses(writer, info.deviceAddressesToMute);
        writer.decreaseIndent();
    }

    private static void dumpDeviceAddresses(IndentingPrintWriter writer, String[] devices) {
        for (int index = 0; index < devices.length; index++) {
            writer.printf("%d %s\n", index, devices[index]);
        }
    }

    @VisibleForTesting
    static MutingInfo generateMutingInfoFromZone(CarAudioZone audioZone) {
        MutingInfo mutingInfo = new MutingInfo();
        mutingInfo.zoneId = audioZone.getId();

        List<String> mutedDevices = new ArrayList<>();
        List<String> unMutedDevices = new ArrayList<>();
        CarVolumeGroup[] groups = audioZone.getVolumeGroups();

        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
            CarVolumeGroup group = groups[groupIndex];
            if (group.isMuted()) {
                mutedDevices.addAll(group.getAddresses());
                continue;
            }
            unMutedDevices.addAll(group.getAddresses());
        }

        mutingInfo.deviceAddressesToMute = mutedDevices.toArray(new String[mutedDevices.size()]);
        mutingInfo.deviceAddressesToUnmute =
                unMutedDevices.toArray(new String[unMutedDevices.size()]);

        return mutingInfo;
    }
}
