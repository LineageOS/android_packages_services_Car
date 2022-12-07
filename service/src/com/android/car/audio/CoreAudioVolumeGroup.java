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
package com.android.car.audio;

import static com.android.car.CarLog.TAG_AUDIO;
import static com.android.car.audio.CarVolumeEventFlag.VolumeEventFlags;
import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.builtin.media.AudioManagerHelper;
import android.car.builtin.util.Slogf;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;

/**
 * A class encapsulates a volume group in car.
 *
 * Volume in a car is controlled by group. A group holds one or more car audio contexts.
 * Call {@link CarAudioManager#getVolumeGroupCount()} to get the count of {@link CarVolumeGroup}
 * supported in a car.
 */
final class CoreAudioVolumeGroup extends CarVolumeGroup {
    static final String TAG = TAG_AUDIO + ".CoreAudioVolumeGroup";
    /**
     * For all volume operations, attributes are required
     */
    private final AudioAttributes mAudioAttributes;
    private final AudioManager mAudioManager;
    @GuardedBy("mLock")
    private int mAmCurrentGainIndex = UNINITIALIZED;
    private final int mAmId;
    private final int mDefaultGainIndex;
    private final int mMaxGainIndex;
    private final int mMinGainIndex;

    CoreAudioVolumeGroup(AudioManager audioManager, CarAudioContext carAudioContext,
            CarAudioSettings settingsManager,
            SparseArray<String> contextToAddress, ArrayMap<String,
            CarAudioDeviceInfo> addressToCarAudioDeviceInfo, int zoneId, int volumeGroupId,
            String name, boolean useCarVolumeGroupMute) {
        super(carAudioContext, settingsManager, contextToAddress, addressToCarAudioDeviceInfo,
                        zoneId, volumeGroupId, name, useCarVolumeGroupMute);
        mAudioManager = audioManager;
        mAudioAttributes = CoreAudioHelper.selectAttributesForVolumeGroupName(name);
        mAmId = CoreAudioHelper.getVolumeGroupIdForAudioAttributes(mAudioAttributes);
        mAmCurrentGainIndex = getAmCurrentGainIndex();
        mMinGainIndex = mAudioManager.getMinVolumeIndexForAttributes(mAudioAttributes);
        mMaxGainIndex = mAudioManager.getMaxVolumeIndexForAttributes(mAudioAttributes);
        // Unfortunately core groups do not have defaults
        mDefaultGainIndex = (mMaxGainIndex - mMinGainIndex) / 3 + mMinGainIndex;
        mLimitedGainIndex = mMaxGainIndex;
    }

    @Override
    public int getMaxGainIndex() {
        return mMaxGainIndex;
    }

    @Override
    public int getMinGainIndex() {
        synchronized (mLock) {
            return mMinGainIndex;
        }
    }

    int getAmCurrentGainIndexFromCache() {
        synchronized (mLock) {
            return mAmCurrentGainIndex;
        }
    }

    int getAmCurrentGainIndex() {
        synchronized (mLock) {
            return mAudioManager.getVolumeIndexForAttributes(mAudioAttributes);
        }
    }

    int getAmLastAudibleIndex() {
        return AudioManagerHelper.getLastAudibleVolumeGroupVolume(mAudioManager, mAmId);
    }

    @Override
    @GuardedBy("mLock")
    @SuppressWarnings("GuardedBy")
    protected void setCurrentGainIndexLocked(int gainIndex) {
        setCurrentGainIndexIntLocked(gainIndex);
        super.setMuteLocked(gainIndex == 0);
    }

    @GuardedBy("mLock")
    @SuppressWarnings("GuardedBy")
    private void setCurrentGainIndexIntLocked(int gainIndex) {
        mAudioManager.setVolumeIndexForAttributes(mAudioAttributes, gainIndex, /* flags= */ 0);
        super.setCurrentGainIndexLocked(gainIndex);
    }

    @Override
    @GuardedBy("mLock")
    @SuppressWarnings("GuardedBy")
    protected boolean isValidGainIndexLocked(int gainIndex) {
        return gainIndex >= mMinGainIndex && gainIndex <= mMaxGainIndex;
    }

    /**
     * As per AudioService logic, a mutable group is a group which min index is zero
     * @return true if group is mutable, false otherwise
     */
    private boolean isMutable() {
        return mMinGainIndex == 0;
    }

    @GuardedBy("mLock")
    private boolean isMutedByVolumeZeroLocked() {
        return getCurrentGainIndexLocked() == 0;
    }

    @Override
    @GuardedBy("mLock")
    @SuppressWarnings("GuardedBy")
    protected void setMuteLocked(boolean mute) {
        if (!isMutable()) {
            return;
        }
        if (mute) {
            AudioManagerHelper.adjustVolumeGroupVolume(mAudioManager, mAmId,
                    AudioManager.ADJUST_MUTE, /* flags= */ 0);
        } else {
            if (isMutedByVolumeZeroLocked()
                    && AudioManagerHelper.isVolumeGroupMuted(mAudioManager, mAmId)) {
                // Unmuting but volume at 0 -> use unmute API to sync AudioManager mute stat
                AudioManagerHelper.adjustVolumeGroupVolume(mAudioManager, mAmId,
                        AudioManager.ADJUST_UNMUTE, /* flags= */ 0);
            } else {
                // Restore current gain from cache
                setCurrentGainIndexIntLocked(getCurrentGainIndexLocked());
            }
        }
        super.setMuteLocked(mute);
    }

    /**
     * Allowing using {@link AudioManager#setVolumeIndexForAudioAttributes} implies that some volume
     * change may not be originated by CarAudioManager itself.
     * Hence, it requires synchronization of the indexes.
     */
    @Override
    @SuppressWarnings("GuardedBy")
    public int onAudioVolumeGroupChanged(int flags) {
        int returnedFlags = 0;
        int newAmIndex = getAmCurrentGainIndex();
        int amCachedIndex = getAmCurrentGainIndexFromCache();
        int previousIndex = getCurrentGainIndex();
        synchronized (mLock) {
            boolean isAmGroupMuted = AudioManagerHelper.isVolumeGroupMuted(mAudioManager, mAmId);
            // Update AM cached index
            mAmCurrentGainIndex = newAmIndex;
            boolean volumeChanged = previousIndex != newAmIndex;
            boolean amVolumeChanged = amCachedIndex != newAmIndex;
            boolean amMuteStateChanged = isMuted() != isAmGroupMuted;

            if (isBlockedLocked())  {
                Slogf.i(TAG, "onAudioVolumeGroupChanged group(%s) volume change not "
                        + "permitted while blocked changes reported by Gain callback", getId());
                returnedFlags |= CarVolumeEventFlag.FLAG_EVENT_VOLUME_BLOCKED;
                return returnedFlags;
            }
            if (!amMuteStateChanged && !amVolumeChanged
                    && (!volumeChanged || hasPendingAttenuationReasonsLocked())) {
                Slogf.i(TAG, "onAudioVolumeGroupChanged group(%s) changed(%b) and/or "
                        + "attenuation pending(%b), bailing out", getName(), volumeChanged,
                        hasPendingAttenuationReasonsLocked());
                return returnedFlags;
            }
            // check first if need to align mute state with AudioService
            returnedFlags |= syncMuteState(newAmIndex, isAmGroupMuted);
            // If muted or volume change handled, bailing out.
            // Otherwise update of the cache in respect of limitation, blocking or attenuation.
            if (isMuted() || (returnedFlags & CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE) != 0) {
                return returnedFlags;
            }
            returnedFlags |= syncGainIndex(newAmIndex);
        }
        return returnedFlags;
    }

    @SuppressWarnings("GuardedBy")
    @VolumeEventFlags int syncMuteState(int newAmIndex, boolean isAmGroupMuted) {
        int returnedFlags = 0;
        synchronized (mLock) {
            // Current Core volume group can only be muted by volume 0, or ackownlegdge our request
            boolean isAmGroupMutedByZero = (newAmIndex == 0) && (AudioManagerHelper
                    .getLastAudibleVolumeGroupVolume(mAudioManager, mAmId) == 0);
            if (isMuted() != isAmGroupMuted) {
                Slogf.i(TAG, "syncMuteState group(%s) muted(%b) synced from AudioService",
                        getName(), isAmGroupMuted);
                super.setMuteLocked(isAmGroupMuted);
                returnedFlags |= CarVolumeEventFlag.FLAG_EVENT_VOLUME_MUTE;
            }

            if (isAmGroupMutedByZero) {
                // Muted or Unmuted at volume zero (this is possible on AudioService),
                // set cache index to zero to be aligned with AudioServer.
                Slogf.i(TAG, "syncMuteState group(%s) muted by 0 sync from AudioService",
                        getName());
                if (mCurrentGainIndex != newAmIndex) {
                    mCurrentGainIndex = newAmIndex;
                    returnedFlags |= CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE;
                }
                return returnedFlags;
            }
        }
        return returnedFlags;
    }

    @VolumeEventFlags int syncGainIndex(int newAmIndex) {
        int returnedFlags = 0;
        synchronized (mLock) {
            // check if a limitation or ducking is active to prevent restoring am index
            if (isOverLimitLocked(newAmIndex)) {
                Slogf.i(TAG, "syncGainIndex group(%s) index(%d) over limit(%d)", getName(),
                        newAmIndex,
                        mLimitedGainIndex);
                if (mCurrentGainIndex != mLimitedGainIndex) {
                    mCurrentGainIndex = mLimitedGainIndex;
                    returnedFlags |= CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE;
                }
                returnedFlags |= CarVolumeEventFlag.FLAG_EVENT_VOLUME_LIMITED;
            } else if (isAttenuatedLocked() && (newAmIndex != mAttenuatedGainIndex)) {
                Slogf.i(TAG, "syncGainIndex group(%s) index(%d) reset attenuation(%d)", getName(),
                        newAmIndex, mAttenuatedGainIndex);
                // reset attenuation
                resetAttenuationLocked();
                // Refresh the current gain with new AudioManager index
                mCurrentGainIndex = newAmIndex;
                returnedFlags |= CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE;
            } else if (mCurrentGainIndex != newAmIndex) {
                Slogf.i(TAG, "syncGainIndex group(%s) index(%d) synced from AudioService",
                        getName(),
                        newAmIndex);
                // Refresh the current gain with new AudioManager index
                mCurrentGainIndex = newAmIndex;
                returnedFlags |= CarVolumeEventFlag.FLAG_EVENT_VOLUME_CHANGE;
            } else {
                Slogf.i(TAG, "syncGainIndex group(%s) index(%d) ack from AudioService", getName(),
                        newAmIndex);
            }
        }
        return returnedFlags;
    }

    @Override
    protected int getDefaultGainIndex() {
        return mDefaultGainIndex;
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    @GuardedBy("mLock")
    protected void dumpLocked(IndentingPrintWriter writer) {
        writer.printf("AudioManager Gain index (current): %d\n", mAmCurrentGainIndex);
    }
}
