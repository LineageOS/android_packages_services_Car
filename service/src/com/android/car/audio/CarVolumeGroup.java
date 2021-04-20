/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.car.media.CarAudioManager;
import android.media.AudioDevicePort;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.audio.CarAudioContext.AudioContext;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A class encapsulates a volume group in car.
 *
 * Volume in a car is controlled by group. A group holds one or more car audio contexts.
 * Call {@link CarAudioManager#getVolumeGroupCount()} to get the count of {@link CarVolumeGroup}
 * supported in a car.
 */
/* package */ final class CarVolumeGroup {

    private final boolean mUseCarVolumeGroupMute;
    private final CarAudioSettings mSettingsManager;
    private final int mZoneId;
    private final int mId;
    private final SparseArray<String> mContextToAddress = new SparseArray<>();
    private final Map<String, CarAudioDeviceInfo> mAddressToCarAudioDeviceInfo = new HashMap<>();

    private final Object mLock = new Object();

    private int mDefaultGain = Integer.MIN_VALUE;
    private int mMaxGain = Integer.MIN_VALUE;
    private int mMinGain = Integer.MAX_VALUE;
    private int mStepSize = 0;
    private int mStoredGainIndex;
    private int mCurrentGainIndex = -1;
    private boolean mIsMuted;
    private @UserIdInt int mUserId = UserHandle.USER_CURRENT;

    CarVolumeGroup(int zoneId, int id, CarAudioSettings settings, boolean useCarVolumeGroupMute) {
        mSettingsManager = settings;
        mZoneId = zoneId;
        mId = id;
        mStoredGainIndex = mSettingsManager.getStoredVolumeGainIndexForUser(mUserId, mZoneId, mId);
        mUseCarVolumeGroupMute = useCarVolumeGroupMute;
    }

    /**
     * @deprecated In favor of {@link #CarVolumeGroup(int, int, CarAudioSettings, boolean)}
     * Only used for legacy configuration via IAudioControl@1.0
     */
    @Deprecated
    CarVolumeGroup(CarAudioSettings settings, int zoneId, int id, @NonNull int[] contexts) {
        this(zoneId, id, settings, false);
        // Deal with the pre-populated car audio contexts
        for (int audioContext : contexts) {
            mContextToAddress.put(audioContext, null);
        }
    }

    @Nullable
    CarAudioDeviceInfo getCarAudioDeviceInfoForAddress(String address) {
        return mAddressToCarAudioDeviceInfo.get(address);
    }

    @AudioContext int[] getContexts() {
        final int[] carAudioContexts = new int[mContextToAddress.size()];
        for (int i = 0; i < carAudioContexts.length; i++) {
            carAudioContexts[i] = mContextToAddress.keyAt(i);
        }
        return carAudioContexts;
    }

    /**
     * Returns the devices address for the given context
     * or {@code null} if the context does not exist in the volume group
     */
    @Nullable
    String getAddressForContext(int audioContext) {
        return mContextToAddress.get(audioContext);
    }

    @AudioContext List<Integer> getContextsForAddress(@NonNull String address) {
        List<Integer> carAudioContexts = new ArrayList<>();
        for (int i = 0; i < mContextToAddress.size(); i++) {
            String value = mContextToAddress.valueAt(i);
            if (address.equals(value)) {
                carAudioContexts.add(mContextToAddress.keyAt(i));
            }
        }
        return carAudioContexts;
    }

    List<String> getAddresses() {
        return new ArrayList<>(mAddressToCarAudioDeviceInfo.keySet());
    }

    /**
     * Binds the context number to physical address and audio device port information.
     * Because this may change the groups min/max values, thus invalidating an index computed from
     * a gain before this call, all calls to this function must happen at startup before any
     * set/getGainIndex calls.
     *
     * @param carAudioContext Context to bind audio to {@link CarAudioContext}
     * @param info {@link CarAudioDeviceInfo} instance relates to the physical address
     */
    void bind(int carAudioContext, CarAudioDeviceInfo info) {
        Preconditions.checkArgument(mContextToAddress.get(carAudioContext) == null,
                String.format("Context %s has already been bound to %s",
                        CarAudioContext.toString(carAudioContext),
                        mContextToAddress.get(carAudioContext)));

        synchronized (mLock) {
            if (mAddressToCarAudioDeviceInfo.size() == 0) {
                mStepSize = info.getStepValue();
            } else {
                Preconditions.checkArgument(
                        info.getStepValue() == mStepSize,
                        "Gain controls within one group must have same step value");
            }

            mAddressToCarAudioDeviceInfo.put(info.getAddress(), info);
            mContextToAddress.put(carAudioContext, info.getAddress());

            if (info.getDefaultGain() > mDefaultGain) {
                // We're arbitrarily selecting the highest
                // device default gain as the group's default.
                mDefaultGain = info.getDefaultGain();
            }
            if (info.getMaxGain() > mMaxGain) {
                mMaxGain = info.getMaxGain();
            }
            if (info.getMinGain() < mMinGain) {
                mMinGain = info.getMinGain();
            }
            updateCurrentGainIndexLocked();
        }
    }

    int getMaxGainIndex() {
        synchronized (mLock) {
            return getIndexForGainLocked(mMaxGain);
        }
    }

    int getMinGainIndex() {
        synchronized (mLock) {
            return getIndexForGainLocked(mMinGain);
        }
    }

    int getCurrentGainIndex() {
        synchronized (mLock) {
            return mCurrentGainIndex;
        }
    }

    /**
     * Sets the gain on this group, gain will be set on all devices within volume group.
     * @param gainIndex The gain index
     */
    void setCurrentGainIndex(int gainIndex) {
        synchronized (mLock) {
            int gainInMillibels = getGainForIndexLocked(gainIndex);
            Preconditions.checkArgument(
                    gainInMillibels >= mMinGain && gainInMillibels <= mMaxGain,
                    "Gain out of range ("
                            + mMinGain + ":"
                            + mMaxGain + ") "
                            + gainInMillibels + "index "
                            + gainIndex);

            for (String address : mAddressToCarAudioDeviceInfo.keySet()) {
                CarAudioDeviceInfo info = mAddressToCarAudioDeviceInfo.get(address);
                info.setCurrentGain(gainInMillibels);
            }

            mCurrentGainIndex = gainIndex;

            storeGainIndexForUserLocked(mCurrentGainIndex, mUserId);
        }
    }

    @Nullable
    AudioDevicePort getAudioDevicePortForContext(int carAudioContext) {
        final String address = mContextToAddress.get(carAudioContext);
        if (address == null || mAddressToCarAudioDeviceInfo.get(address) == null) {
            return null;
        }

        return mAddressToCarAudioDeviceInfo.get(address).getAudioDevicePort();
    }

    @Override
    public String toString() {
        return "CarVolumeGroup id: " + mId
                + " currentGainIndex: " + mCurrentGainIndex
                + " contexts: " + Arrays.toString(getContexts())
                + " addresses: " + String.join(", ", getAddresses());
    }

    void dump(IndentingPrintWriter writer) {
        synchronized (mLock) {
            writer.printf("CarVolumeGroup(%d)\n", mId);
            writer.increaseIndent();
            writer.printf("Is Muted(%b)\n", mIsMuted);
            writer.printf("UserId(%d)\n", mUserId);
            writer.printf("Persist Volume Group Mute(%b)\n",
                    mSettingsManager.isPersistVolumeGroupMuteEnabled(mUserId));
            writer.printf("Gain values (min / max / default/ current): %d %d %d %d\n", mMinGain,
                    mMaxGain, mDefaultGain, getGainForIndexLocked(mCurrentGainIndex));
            writer.printf("Gain indexes (min / max / default / current): %d %d %d %d\n",
                    getMinGainIndex(), getMaxGainIndex(), getDefaultGainIndex(), mCurrentGainIndex);
            for (int i = 0; i < mContextToAddress.size(); i++) {
                writer.printf("Context: %s -> Address: %s\n",
                        CarAudioContext.toString(mContextToAddress.keyAt(i)),
                        mContextToAddress.valueAt(i));
            }
            mAddressToCarAudioDeviceInfo.keySet().stream()
                    .map(mAddressToCarAudioDeviceInfo::get)
                    .forEach((info -> info.dump(writer)));

            // Empty line for comfortable reading
            writer.println();
            writer.decreaseIndent();
        }
    }

    void loadVolumesSettingsForUser(@UserIdInt int userId) {
        synchronized (mLock) {
            //Update the volume for the new user
            updateUserIdLocked(userId);
            //Update the current gain index
            updateCurrentGainIndexLocked();
            //Reset devices with current gain index
            updateGroupMuteLocked();
        }
        setCurrentGainIndex(getCurrentGainIndex());
    }

    void setMute(boolean mute) {
        synchronized (mLock) {
            mIsMuted = mute;
            if (mSettingsManager.isPersistVolumeGroupMuteEnabled(mUserId)) {
                mSettingsManager.storeVolumeGroupMuteForUser(mUserId, mZoneId, mId, mute);
            }
        }
    }

    boolean isMuted() {
        synchronized (mLock) {
            return mIsMuted;
        }
    }

    @GuardedBy("mLock")
    private void updateUserIdLocked(@UserIdInt int userId) {
        mUserId = userId;
        mStoredGainIndex = getCurrentGainIndexForUserLocked();
    }

    @GuardedBy("mLock")
    private int getCurrentGainIndexForUserLocked() {
        int gainIndexForUser = mSettingsManager.getStoredVolumeGainIndexForUser(mUserId, mZoneId,
                mId);
        Slog.i(CarLog.TAG_AUDIO, "updateUserId userId " + mUserId
                + " gainIndexForUser " + gainIndexForUser);
        return gainIndexForUser;
    }

    /**
     * Update the current gain index based on the stored gain index
     */
    @GuardedBy("mLock")
    private void updateCurrentGainIndexLocked() {
        if (isValidGainLocked(mStoredGainIndex)) {
            mCurrentGainIndex = mStoredGainIndex;
        } else {
            mCurrentGainIndex = getIndexForGainLocked(mDefaultGain);
        }
    }

    @GuardedBy("mLock")
    private boolean isValidGainLocked(int gain) {
        return gain >= getIndexForGainLocked(mMinGain) && gain <= getIndexForGainLocked(mMaxGain);
    }

    private int getDefaultGainIndex() {
        synchronized (mLock) {
            return getIndexForGainLocked(mDefaultGain);
        }
    }

    @GuardedBy("mLock")
    private void storeGainIndexForUserLocked(int gainIndex, @UserIdInt int userId) {
        mSettingsManager.storeVolumeGainIndexForUser(userId,
                mZoneId, mId, gainIndex);
    }

    private int getGainForIndexLocked(int gainIndex) {
        return mMinGain + gainIndex * mStepSize;
    }

    @GuardedBy("mLock")
    private int getIndexForGainLocked(int gainInMillibel) {
        return (gainInMillibel - mMinGain) / mStepSize;
    }

    @GuardedBy("mLock")
    private void updateGroupMuteLocked() {
        if (!mUseCarVolumeGroupMute) {
            return;
        }
        if (!mSettingsManager.isPersistVolumeGroupMuteEnabled(mUserId)) {
            mIsMuted = false;
            return;
        }
        mIsMuted = mSettingsManager.getVolumeGroupMuteForUser(mUserId, mZoneId, mId);
    }
}
