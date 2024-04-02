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

import static com.android.car.audio.CarActivationVolumeConfig.ActivationVolumeInvocationType;

import android.annotation.NonNull;
import android.car.builtin.util.Slogf;
import android.media.AudioAttributes;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CarAudioPlaybackMonitor {

    private static final int INVALID_UID = -1;

    private final Object mLock = new Object();
    private final CarAudioService mCarAudioService;
    private final SparseArray<CarAudioZone> mCarAudioZones;
    @GuardedBy("mLock")
    private final SparseArray<SparseIntArray> mZoneIdGroupIdToUidMap;

    CarAudioPlaybackMonitor(@NonNull CarAudioService carAudioService,
                            @NonNull SparseArray<CarAudioZone> carAudioZones) {
        mCarAudioService = Objects.requireNonNull(carAudioService,
                "Car audio service can not be null");
        mCarAudioZones = Objects.requireNonNull(carAudioZones,
                "Car audio zones can not be null");
        mZoneIdGroupIdToUidMap = new SparseArray<>();
    }

    void resetActivationTypesForZone(int zoneId) {
        synchronized (mLock) {
            mZoneIdGroupIdToUidMap.remove(zoneId);
        }
    }

    /**
     * Informs {@link CarAudioService} that newly active playbacks are received and min/max
     * activation volume should be applied if needed.
     *
     * @param newActivePlaybackAttributesWithUid List of pairs of {@link AudioAttributes} and
     *                                           client Uid of the newly active
     *                                          {@link android.media.AudioPlaybackConfiguration}s
     * @param zoneId Zone Id of thr newly active playbacks
     */
    public void onActiveAudioPlaybackAttributesAdded(
            List<Pair<AudioAttributes, Integer>> newActivePlaybackAttributesWithUid, int zoneId) {
        if (newActivePlaybackAttributesWithUid == null
                || newActivePlaybackAttributesWithUid.isEmpty()) {
            return;
        }
        CarAudioZoneConfig currentZoneConfig = mCarAudioZones.get(zoneId)
                .getCurrentCarAudioZoneConfig();
        List<ActivationInfo> newActivationVolumeGroupsWithType =
                new ArrayList<>();
        for (int index = 0; index < newActivePlaybackAttributesWithUid.size(); index++) {
            Pair<AudioAttributes, Integer> newActivePlaybackAttributesWithUidEntry =
                    newActivePlaybackAttributesWithUid.get(index);
            ActivationInfo activationVolumeGroupWithInvocationType =
                    getActivationInfo(
                            newActivePlaybackAttributesWithUidEntry, currentZoneConfig);
            if (activationVolumeGroupWithInvocationType == null) {
                continue;
            }
            newActivationVolumeGroupsWithType.add(activationVolumeGroupWithInvocationType);
        }
        if (newActivationVolumeGroupsWithType.isEmpty()) {
            return;
        }
        mCarAudioService.handleActivationVolumeWithActivationInfos(
                newActivationVolumeGroupsWithType, zoneId, currentZoneConfig.getZoneConfigId());
    }

    private ActivationInfo getActivationInfo(
            Pair<AudioAttributes, Integer> attributesIntegerPair,
            CarAudioZoneConfig currentZoneConfig) {
        int zoneId = currentZoneConfig.getZoneId();
        CarVolumeGroup volumeGroup = currentZoneConfig.getVolumeGroupForAudioAttributes(
                attributesIntegerPair.first);
        if (volumeGroup == null) {
            Slogf.w(CarLog.TAG_AUDIO, "Audio attributes %s is not found in zone %d config %d",
                    attributesIntegerPair.first, zoneId, currentZoneConfig.getZoneConfigId());
            return null;
        }
        int groupId = volumeGroup.getId();
        int uid = attributesIntegerPair.second;
        int activationVolumeInvocationType;
        synchronized (mLock) {
            // For a playback with a uid that does not exist for a given zone id and volume group
            // id, it is the playback after boot or zone configuration change for activation volume.
            if (!mZoneIdGroupIdToUidMap.contains(zoneId)) {
                mZoneIdGroupIdToUidMap.put(zoneId, new SparseIntArray());
                activationVolumeInvocationType =
                        CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT;
            } else if (mZoneIdGroupIdToUidMap.get(zoneId).get(groupId, INVALID_UID)
                    == INVALID_UID) {
                activationVolumeInvocationType =
                        CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_BOOT;
            } else {
                // For a playback with a uid existing for the given zone id and volume group id,
                // whether it changes the source from the previous playback is determines by
                // whether it has the same uid.
                int prevUid = mZoneIdGroupIdToUidMap.get(zoneId).get(groupId);
                activationVolumeInvocationType = uid == prevUid
                        ? CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_PLAYBACK_CHANGED
                        : CarActivationVolumeConfig.ACTIVATION_VOLUME_ON_SOURCE_CHANGED;
            }
            mZoneIdGroupIdToUidMap.get(zoneId).put(groupId, uid);
        }
        return new ActivationInfo(volumeGroup.getId(),
                activationVolumeInvocationType);
    }

    static final class ActivationInfo {
        final int mGroupId;
        @ActivationVolumeInvocationType
        final int mInvocationType;

        ActivationInfo(int groupId, @ActivationVolumeInvocationType int type) {
            mGroupId = groupId;
            mInvocationType = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ActivationInfo)) {
                return false;
            }
            ActivationInfo other = (ActivationInfo) o;
            return other.mGroupId == mGroupId && other.mInvocationType == mInvocationType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mGroupId, mInvocationType);
        }

        public String toString() {
            return "ActivationInfo { volume group id: " + mGroupId + ", invocation type = "
                    + mInvocationType + "}";
        }
    }
}
