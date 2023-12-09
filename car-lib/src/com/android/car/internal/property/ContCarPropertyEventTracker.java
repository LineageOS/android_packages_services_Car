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

package com.android.car.internal.property;

import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.CarPropertyValue.PropertyStatus;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.time.Duration;
import java.util.Objects;

/**
 * A {@link CarPropertyEventTracker} implementation for continuous property
 *
 * @hide
 */
public final class ContCarPropertyEventTracker implements CarPropertyEventTracker{
    private static final String TAG = "ContCarPropertyEventTracker";
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);
    private static final float NANOSECONDS_PER_SECOND = Duration.ofSeconds(1).toNanos();
    // Add a margin so that if an event timestamp is within
    // 5% of the next timestamp, it will not be dropped.
    private static final float UPDATE_PERIOD_OFFSET = 0.95f;

    private final boolean mEnableVur;
    private final float mUpdateRateHz;
    private final long mUpdatePeriodNanos;
    private long mNextUpdateTimeNanos;
    private Object mCurrentValue;
    private @PropertyStatus int mCurrentStatus;

    public ContCarPropertyEventTracker(float updateRateHz, boolean enableVur) {
        if (DBG) {
            Slogf.d(TAG, "new continuous car property event tracker, updateRateHz: %f, "
                    + ", enableVur: %b", updateRateHz, enableVur);
        }
        // updateRateHz should be sanitized before.
        Preconditions.checkArgument(updateRateHz > 0, "updateRateHz must be a positive number");
        mUpdateRateHz = updateRateHz;
        mUpdatePeriodNanos =
                (long) ((1.0 / mUpdateRateHz) * NANOSECONDS_PER_SECOND * UPDATE_PERIOD_OFFSET);
        mEnableVur = enableVur;
    }

    @Override
    public float getUpdateRateHz() {
        return mUpdateRateHz;
    }

    /** Returns true if the client needs to be updated for this event. */
    @Override
    public boolean hasUpdate(CarPropertyValue<?> carPropertyValue) {
        if (carPropertyValue.getTimestamp() < mNextUpdateTimeNanos) {
            if (DBG) {
                Slogf.d(TAG, "hasUpdate: Dropping carPropertyValue: %s, "
                        + "because getTimestamp()=%d < nextUpdateTimeNanos=%d",
                        carPropertyValue, carPropertyValue.getTimestamp(), mNextUpdateTimeNanos);
            }
            return false;
        }
        mNextUpdateTimeNanos = carPropertyValue.getTimestamp() + mUpdatePeriodNanos;
        if (mEnableVur) {
            int status = carPropertyValue.getStatus();
            Object value = carPropertyValue.getValue();
            if (status == mCurrentStatus && Objects.deepEquals(value, mCurrentValue)) {
                if (DBG) {
                    Slogf.d(TAG, "hasUpdate: Dropping carPropertyValue: %s, "
                            + "because VUR is enabled and value is the same",
                            carPropertyValue);
                }
                return false;
            }
            mCurrentStatus = status;
            mCurrentValue = value;
        }
        return true;
    }
}
