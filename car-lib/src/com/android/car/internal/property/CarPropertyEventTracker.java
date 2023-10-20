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

import android.car.VehiclePropertyIds;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyValue;
import android.util.Log;

import java.time.Duration;

/**
 * Tracks the next update timestamp for vehicle property listeners.
 * This class is not thread-safe.
 *
 * @hide
 */
public final class CarPropertyEventTracker {
    private static final String TAG = "CarPropertyEventTracker";
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);
    private static final float NANOSECONDS_PER_SECOND = Duration.ofSeconds(1).toNanos();
    // Add a margin so that if an event timestamp is within
    // 5% of the next timestamp, it will not be dropped.
    private static final float UPDATE_PERIOD_OFFSET = 0.95f;

    private final float mUpdateRateHz;
    private final long mUpdatePeriodNanos;
    private long mNextUpdateTimeNanos = 0L;

    public CarPropertyEventTracker(float updateRateHz) {
        mUpdateRateHz = updateRateHz;
        mUpdatePeriodNanos = mUpdateRateHz > 0
                ? ((long) ((1.0 / mUpdateRateHz) * NANOSECONDS_PER_SECOND * UPDATE_PERIOD_OFFSET))
                : 0L;
    }

    public float getUpdateRateHz() {
        return mUpdateRateHz;
    }

    /** Returns true if the timestamp of the event is greater than the next update timestamp. */
    public boolean hasNextUpdateTimeArrived(CarPropertyValue<?> carPropertyValue) {
        int propertyId = carPropertyValue.getPropertyId();
        if (carPropertyValue.getTimestamp() < mNextUpdateTimeNanos) {
            if (DBG) {
                Slogf.d(TAG, "hasNextUpdateTimeArrived: Dropping carPropertyEvent - propertyId=%s,"
                        + " areaId=0x%x, because getTimestamp()=%d < nextUpdateTimeNanos=%d",
                        VehiclePropertyIds.toString(propertyId), carPropertyValue.getAreaId(),
                        carPropertyValue.getTimestamp(), mNextUpdateTimeNanos);
            }
            return false;
        }
        mNextUpdateTimeNanos = carPropertyValue.getTimestamp() + mUpdatePeriodNanos;
        return true;
    }
}
