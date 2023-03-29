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

import static android.car.media.CarVolumeGroupEvent.EVENT_TYPE_VOLUME_GAIN_INDEX_CHANGED;
import static android.car.media.CarVolumeGroupEvent.EVENT_TYPE_VOLUME_MAX_INDEX_CHANGED;
import static android.car.media.CarVolumeGroupEvent.EVENT_TYPE_VOLUME_MIN_INDEX_CHANGED;

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
    private static final int UNSET_STEP_SIZE = -1;
    @GuardedBy("mLock")
    private int mDefaultGain = Integer.MAX_VALUE;
    @GuardedBy("mLock")
    private int mMaxGain = Integer.MIN_VALUE;
    @GuardedBy("mLock")
    private int mMinGain = Integer.MAX_VALUE;
    @GuardedBy("mLock")
    private int mStepSize = UNSET_STEP_SIZE;

    CarAudioVolumeGroup(CarAudioContext carAudioContext,
            CarAudioSettings settingsManager,
            SparseArray<String> contextToAddress, ArrayMap<String,
            CarAudioDeviceInfo> addressToCarAudioDeviceInfo, int zoneId, int configId,
            int volumeGroupId, String name, int stepSize, int defaultGain, int minGain, int maxGain,
            boolean useCarVolumeGroupMute) {
        super(carAudioContext, settingsManager, contextToAddress, addressToCarAudioDeviceInfo,
                zoneId, configId, volumeGroupId, name, useCarVolumeGroupMute);
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

    @Override
    int calculateNewGainStageFromDeviceInfos() {
        int minGain = Integer.MAX_VALUE;
        int maxGain = Integer.MIN_VALUE;
        int defaultGain = Integer.MAX_VALUE;
        int stepSize = UNSET_STEP_SIZE;

        // compute the new volume group gain stage from scratch
        for (int index = 0; index < mAddressToCarAudioDeviceInfo.size(); index++) {
            CarAudioDeviceInfo info = mAddressToCarAudioDeviceInfo.valueAt(index);
            if (stepSize == UNSET_STEP_SIZE) {
                stepSize = info.getStepValue();
            } else {
                Preconditions.checkArgument(
                        info.getStepValue() == stepSize,
                        "Gain stages within one group must have same step value");
            }
            if (info.getDefaultGain() < defaultGain) {
                // We're arbitrarily selecting the lowest
                // device default gain as the group's default.
                defaultGain = info.getDefaultGain();
            }
            if (info.getMaxGain() > maxGain) {
                maxGain = info.getMaxGain();
            }
            if (info.getMinGain() < minGain) {
                minGain = info.getMinGain();
            }
        }

        // update the new gain stage and return event types so that callback can be triggered.
        int eventType = 0;
        synchronized (mLock) {
            // get the curret gain in mb before updating the volume bounds
            int extrapolateGainInMb = getGainForIndexLocked(getCurrentGainIndexLocked());

            if (minGain != mMinGain) {
                mMinGain = minGain;
                eventType |= (EVENT_TYPE_VOLUME_GAIN_INDEX_CHANGED
                        | EVENT_TYPE_VOLUME_MIN_INDEX_CHANGED);
            }
            if (maxGain != mMaxGain) {
                mMaxGain = maxGain;
                eventType |= (EVENT_TYPE_VOLUME_GAIN_INDEX_CHANGED
                        | EVENT_TYPE_VOLUME_MAX_INDEX_CHANGED);
            }
            if (defaultGain != mDefaultGain) {
                mDefaultGain = defaultGain;
                // if only default gain changes, no impact to volume gain stages (i.e. no event).
            }
            if (stepSize != mStepSize) {
                mStepSize = stepSize;
                eventType |= (EVENT_TYPE_VOLUME_GAIN_INDEX_CHANGED
                        | EVENT_TYPE_VOLUME_MAX_INDEX_CHANGED);
            }


            if ((eventType & EVENT_TYPE_VOLUME_GAIN_INDEX_CHANGED)
                    == EVENT_TYPE_VOLUME_GAIN_INDEX_CHANGED)  {
                // if min/max/step values change, we shall try to maintain the same volume level
                // through simple extrapolation i.e. the new  gain index is calculated from the
                // previous 'current gain in millibels'
                // caution: before updating, check if the previous gain is out of bound in the
                //          new gain stage. If yes, use the safe value (i.e default gain)
                //          provided by the hal implementations.
                if (extrapolateGainInMb < mMinGain || extrapolateGainInMb > mMaxGain) {
                    setCurrentGainIndexLocked(getIndexForGainLocked(mDefaultGain));
                } else {
                    setCurrentGainIndexLocked(getIndexForGainLocked(extrapolateGainInMb));
                }
            }
        }
        return eventType;
    }
}
