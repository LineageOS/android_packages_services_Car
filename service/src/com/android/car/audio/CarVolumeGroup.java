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
import android.car.media.CarAudioManager;
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.automotive.audiocontrol.V1_0.ContextNumber;
import android.media.AudioDevicePort;
import android.provider.Settings;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
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

    private final ContentResolver mContentResolver;
    private final int mZoneId;
    private final int mId;
    private final SparseArray<String> mContextToAddress = new SparseArray<>();
    private final Map<String, CarAudioDeviceInfo> mAddressToCarAudioDeviceInfo = new HashMap<>();

    private int mDefaultGain = Integer.MIN_VALUE;
    private int mMaxGain = Integer.MIN_VALUE;
    private int mMinGain = Integer.MAX_VALUE;
    private int mStepSize = 0;
    private int mStoredGainIndex;
    private int mCurrentGainIndex = -1;

    /**
     * Constructs a {@link CarVolumeGroup} instance
     * @param context {@link Context} instance
     * @param zoneId Audio zone this volume group belongs to
     * @param id ID of this volume group
     */
    CarVolumeGroup(Context context, int zoneId, int id) {
        mContentResolver = context.getContentResolver();
        mZoneId = zoneId;
        mId = id;
        mStoredGainIndex = Settings.Global.getInt(mContentResolver,
                CarAudioService.getVolumeSettingsKeyForGroup(mZoneId, mId), -1);
    }

    /**
     * Constructs a {@link CarVolumeGroup} instance
     * @param context {@link Context} instance
     * @param zoneId Audio zone this volume group belongs to
     * @param id ID of this volume group
     * @param contexts Pre-populated array of car contexts, for legacy car_volume_groups.xml only
     * @deprecated In favor of {@link #CarVolumeGroup(Context, int, int)}
     */
    @Deprecated
    CarVolumeGroup(Context context, int zoneId, int id, @NonNull int[] contexts) {
        this(context, zoneId, id);
        // Deal with the pre-populated car audio contexts
        for (int audioContext : contexts) {
            mContextToAddress.put(audioContext, null);
        }
    }

    /**
     * @param address Physical address for the audio device
     * @return {@link CarAudioDeviceInfo} associated with a given address
     */
    CarAudioDeviceInfo getCarAudioDeviceInfoForAddress(String address) {
        return mAddressToCarAudioDeviceInfo.get(address);
    }

    /**
     * @return Array of context numbers in this {@link CarVolumeGroup}
     */
    int[] getContexts() {
        final int[] contextNumbers = new int[mContextToAddress.size()];
        for (int i = 0; i < contextNumbers.length; i++) {
            contextNumbers[i] = mContextToAddress.keyAt(i);
        }
        return contextNumbers;
    }

    /**
     * @param address Physical address for the audio device
     * @return Array of context numbers assigned to a given address
     */
    int[] getContextsForAddress(@NonNull String address) {
        List<Integer> contextNumbers = new ArrayList<>();
        for (int i = 0; i < mContextToAddress.size(); i++) {
            String value = mContextToAddress.valueAt(i);
            if (address.equals(value)) {
                contextNumbers.add(mContextToAddress.keyAt(i));
            }
        }
        return contextNumbers.stream().mapToInt(i -> i).toArray();
    }

    /**
     * @return Array of addresses in this {@link CarVolumeGroup}
     */
    List<String> getAddresses() {
        return new ArrayList<>(mAddressToCarAudioDeviceInfo.keySet());
    }

    /**
     * Binds the context number to physical address and audio device port information.
     * Because this may change the groups min/max values, thus invalidating an index computed from
     * a gain before this call, all calls to this function must happen at startup before any
     * set/getGainIndex calls.
     *
     * @param contextNumber Context number as defined in audio control HAL
     * @param info {@link CarAudioDeviceInfo} instance relates to the physical address
     */
    void bind(int contextNumber, CarAudioDeviceInfo info) {
        if (mAddressToCarAudioDeviceInfo.size() == 0) {
            mStepSize = info.getStepValue();
        } else {
            Preconditions.checkArgument(
                    info.getStepValue() == mStepSize,
                    "Gain controls within one group must have same step value");
        }

        mAddressToCarAudioDeviceInfo.put(info.getAddress(), info);
        mContextToAddress.put(contextNumber, info.getAddress());

        if (info.getDefaultGain() > mDefaultGain) {
            // We're arbitrarily selecting the highest device default gain as the group's default.
            mDefaultGain = info.getDefaultGain();
        }
        if (info.getMaxGain() > mMaxGain) {
            mMaxGain = info.getMaxGain();
        }
        if (info.getMinGain() < mMinGain) {
            mMinGain = info.getMinGain();
        }
        if (mStoredGainIndex < getMinGainIndex() || mStoredGainIndex > getMaxGainIndex()) {
            // We expected to load a value from last boot, but if we didn't (perhaps this is the
            // first boot ever?), then use the highest "default" we've seen to initialize
            // ourselves.
            mCurrentGainIndex = getIndexForGain(mDefaultGain);
        } else {
            // Just use the gain index we stored last time the gain was set (presumably during our
            // last boot cycle).
            mCurrentGainIndex = mStoredGainIndex;
        }
    }

    private int getDefaultGainIndex() {
        return getIndexForGain(mDefaultGain);
    }

    int getMaxGainIndex() {
        return getIndexForGain(mMaxGain);
    }

    int getMinGainIndex() {
        return getIndexForGain(mMinGain);
    }

    int getCurrentGainIndex() {
        return mCurrentGainIndex;
    }

    /**
     * Sets the gain on this group, gain will be set on all devices within volume group.
     * @param gainIndex The gain index
     */
    void setCurrentGainIndex(int gainIndex) {
        int gainInMillibels = getGainForIndex(gainIndex);

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
        Settings.Global.putInt(mContentResolver,
                CarAudioService.getVolumeSettingsKeyForGroup(mZoneId, mId), gainIndex);
    }

    // Given a group level gain index, return the computed gain in millibells
    // TODO (randolphs) If we ever want to add index to gain curves other than lock-stepped
    // linear, this would be the place to do it.
    private int getGainForIndex(int gainIndex) {
        return mMinGain + gainIndex * mStepSize;
    }

    // TODO (randolphs) if we ever went to a non-linear index to gain curve mapping, we'd need to
    // revisit this as it assumes (at the least) that getGainForIndex is reversible.  Luckily,
    // this is an internal implementation details we could factor out if/when necessary.
    private int getIndexForGain(int gainInMillibel) {
        return (gainInMillibel - mMinGain) / mStepSize;
    }

    /**
     * Gets {@link AudioDevicePort} from a context number
     */
    @Nullable
    AudioDevicePort getAudioDevicePortForContext(int contextNumber) {
        final String address = mContextToAddress.get(contextNumber);
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

    /** Writes to dumpsys output */
    void dump(String indent, PrintWriter writer) {
        writer.printf("%sCarVolumeGroup(%d)\n", indent, mId);
        writer.printf("%sGain values (min / max / default/ current): %d %d %d %d\n",
                indent, mMinGain, mMaxGain,
                mDefaultGain, getGainForIndex(mCurrentGainIndex));
        writer.printf("%sGain indexes (min / max / default / current): %d %d %d %d\n",
                indent, getMinGainIndex(), getMaxGainIndex(),
                getDefaultGainIndex(), mCurrentGainIndex);
        for (int i = 0; i < mContextToAddress.size(); i++) {
            writer.printf("%sContext: %s -> Address: %s\n", indent,
                    ContextNumber.toString(mContextToAddress.keyAt(i)),
                    mContextToAddress.valueAt(i));
        }
        mAddressToCarAudioDeviceInfo.keySet().stream()
                .map(mAddressToCarAudioDeviceInfo::get)
                .forEach((info -> info.dump(indent, writer)));

        // Empty line for comfortable reading
        writer.println();
    }
}
