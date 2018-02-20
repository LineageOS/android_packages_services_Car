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

package com.android.car;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.media.CarAudioManager;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioDevicePort;
import android.provider.Settings;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.util.Preconditions;

import java.util.Arrays;

/**
 * A class encapsulates a volume group in car.
 *
 * Volume in a car is controlled by group. A group holds one or more car audio contexts.
 * Call {@link CarAudioManager#getVolumeGroupCount()} to get the count of {@link CarVolumeGroup}
 * supported in a car.
 */
/* package */ final class CarVolumeGroup {

    private final ContentResolver mContentResolver;
    private final int mId;
    private final int[] mContexts;
    private final SparseIntArray mContextToBus = new SparseIntArray();
    private final SparseArray<CarAudioDeviceInfo> mBusToCarAudioDeviceInfos = new SparseArray<>();

    private int mGroupMaxGainIndex = Integer.MIN_VALUE;
    private int mGroupMinGainIndex = Integer.MAX_VALUE;
    private int mCurrentGainIndex;

    CarVolumeGroup(Context context, int id, @NonNull int[] contexts) {
        mContentResolver = context.getContentResolver();
        mId = id;
        mContexts = contexts;

        mCurrentGainIndex = Settings.Global.getInt(mContentResolver,
                CarAudioManager.getVolumeSettingsKeyForGroup(mId), -1);;
    }

    int getId() {
        return mId;
    }

    int[] getContexts() {
        return mContexts;
    }

    int[] getBusNumbers() {
        final int[] busNumbers = new int[mBusToCarAudioDeviceInfos.size()];
        for (int i = 0; i < busNumbers.length; i++) {
            busNumbers[i] = mBusToCarAudioDeviceInfos.keyAt(i);
        }
        return busNumbers;
    }

    /**
     * Binds the context number to physical bus number and audio device port information.
     *
     * @param contextNumber Context number as defined in audio control HAL
     * @param busNumber Physical bus number for the audio device port
     * @param info {@link CarAudioDeviceInfo} instance relates to the physical bus
     */
    void bind(int contextNumber, int busNumber, CarAudioDeviceInfo info) {
        if (mBusToCarAudioDeviceInfos.size() > 0) {
            final int stepValue =
                    mBusToCarAudioDeviceInfos.valueAt(0).getAudioGain().stepValue();
            Preconditions.checkArgument(
                    info.getAudioGain().stepValue() == stepValue,
                    "Gain controls within one group should have same step value");
        }
        mContextToBus.put(contextNumber, busNumber);
        mBusToCarAudioDeviceInfos.put(busNumber, info);
        if (info.getMaxGainIndex() > mGroupMaxGainIndex) {
            mGroupMaxGainIndex = info.getMaxGainIndex();
        }
        if (info.getMinGainIndex() < mGroupMinGainIndex) {
            mGroupMinGainIndex = info.getMinGainIndex();
        }
        if (mCurrentGainIndex < 0) {
            // TODO: check the boundary of each gain control.
            // It's possible that each bus has different current gain index. If it's due to minimum
            // allowed, the current should be set to Math.min(currents), if it's due to maximum
            // allowed, the current should be set to Math.max(currents). Otherwise, the current
            // should be identical among the buses.
            mCurrentGainIndex = info.getCurrentGainIndex();
        }
    }

    int getMaxGainIndex() {
        return mGroupMaxGainIndex;
    }

    int getMinGainIndex() {
        return mGroupMinGainIndex;
    }

    int getCurrentGainIndex() {
        return mCurrentGainIndex;
    }

    void setCurrentGainIndex(int gainIndex) {
        Preconditions.checkArgument(
                gainIndex >= mGroupMinGainIndex && gainIndex <= mGroupMaxGainIndex,
                "Invalid gain index: " + gainIndex);

        for (int i = 0; i < mBusToCarAudioDeviceInfos.size(); i++) {
            CarAudioDeviceInfo info = mBusToCarAudioDeviceInfos.valueAt(i);
            // It's possible that each CarAudioDeviceInfo has different boundary than the group.
            if (gainIndex < info.getMinGainIndex()) {
                info.setCurrentGainIndex(info.getMinGainIndex());
            } else if (gainIndex > info.getMaxGainIndex()) {
                info.setCurrentGainIndex(info.getMaxGainIndex());
            } else {
                info.setCurrentGainIndex(gainIndex);
            }
        }

        mCurrentGainIndex = gainIndex;
        Settings.Global.putInt(mContentResolver,
                CarAudioManager.getVolumeSettingsKeyForGroup(mId), gainIndex);
    }

    @Nullable
    AudioDevicePort getAudioDevicePortForContext(int contextNumber) {
        final int busNumber = mContextToBus.get(contextNumber,
                android.hardware.automotive.audiocontrol.V1_0.ContextNumber.INVALID);
        if (busNumber == android.hardware.automotive.audiocontrol.V1_0.ContextNumber.INVALID
                || mBusToCarAudioDeviceInfos.get(busNumber) == null) {
            return null;
        }
        return mBusToCarAudioDeviceInfos.get(busNumber).getAudioDevicePort();
    }

    @Override
    public String toString() {
        return "CarVolumeGroup id: " + mId
                + " currentGainIndex: " + mCurrentGainIndex
                + " contexts: " + Arrays.toString(mContexts)
                + " buses: " + Arrays.toString(getBusNumbers());
    }
}
