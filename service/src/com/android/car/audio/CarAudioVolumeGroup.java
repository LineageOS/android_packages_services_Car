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

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

/**
 * A class encapsulates a volume group in car.
 *
 * Volume in a car is controlled by group. A group holds one or more car audio contexts.
 * Call {@link CarAudioManager#getVolumeGroupCount()} to get the count of {@link CarVolumeGroup}
 * supported in a car.
 */
final class CarAudioVolumeGroup extends CarVolumeGroup {
    private final int mDefaultGain;
    private final int mMaxGain;
    private final int mMinGain;
    private final int mStepSize;

    CarAudioVolumeGroup(CarAudioContext carAudioContext,
            CarAudioSettings settingsManager,
            SparseArray<String> contextToAddress, ArrayMap<String,
            CarAudioDeviceInfo> addressToCarAudioDeviceInfo, int zoneId, int volumeGroupId,
            String name, int stepSize, int defaultGain, int minGain, int maxGain,
            boolean useCarVolumeGroupMute) {
        super(carAudioContext, settingsManager, contextToAddress, addressToCarAudioDeviceInfo,
                zoneId, volumeGroupId, name, useCarVolumeGroupMute);
        Preconditions.checkArgument(stepSize != 0, "Step Size must not be zero");
        mStepSize = stepSize;
        mDefaultGain = defaultGain;
        mMinGain = minGain;
        mMaxGain = maxGain;
        mLimitedGainIndex = getIndexForGainLocked(mMaxGain);
    }

    @Override
    public int getMaxGainIndex() {
        synchronized (mLock) {
            return getIndexForGainLocked(mMaxGain);
        }
    }

    @Override
    public int getMinGainIndex() {
        synchronized (mLock) {
            return getIndexForGainLocked(mMinGain);
        }
    }

    @Override
    @GuardedBy("mLock")
    @SuppressWarnings("GuardedBy")
    protected void setCurrentGainIndexLocked(int gainIndex) {
        int gainInMillibels = getGainForIndexLocked(gainIndex);
        for (int index = 0; index < mAddressToCarAudioDeviceInfo.size(); index++) {
            CarAudioDeviceInfo info = mAddressToCarAudioDeviceInfo.valueAt(index);
            info.setCurrentGain(gainInMillibels);
        }
        super.setCurrentGainIndexLocked(gainIndex);
    }

    @Override
    @GuardedBy("mLock")
    protected boolean isValidGainIndexLocked(int gainIndex) {
        return gainIndex >= getIndexForGainLocked(mMinGain)
                && gainIndex <= getIndexForGainLocked(mMaxGain);
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    @GuardedBy("mLock")
    protected void dumpLocked(IndentingPrintWriter writer) {
        writer.printf("Step size: %d\n", mStepSize);
        writer.printf("Gain values (min / max / default/ current): %d %d %d %d\n", mMinGain,
                mMaxGain, mDefaultGain, getGainForIndexLocked(mCurrentGainIndex));
    }

    @Override
    protected int getDefaultGainIndex() {
        synchronized (mLock) {
            return getIndexForGainLocked(mDefaultGain);
        }
    }

    @GuardedBy("mLock")
    private int getGainForIndexLocked(int gainIndex) {
        return mMinGain + gainIndex * mStepSize;
    }

    @GuardedBy("mLock")
    private int getIndexForGainLocked(int gainInMillibel) {
        return (gainInMillibel - mMinGain) / mStepSize;
    }
}
