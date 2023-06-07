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

import android.annotation.Nullable;
import android.car.VehiclePropertyIds;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseLongArray;

import java.time.Duration;

/**
 * Tracks when the next {@link CarPropertyEvent} can be sent for each area ID.
 * This class is not thread-safe.
 *
 * @param <T> The type of key for each list of area IDs.
 *
 * @hide
 */
public final class CarPropertyEventTracker<T> {
    private static final String TAG = "CarPropertyEventTracker";
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);
    private static final float NANOSECONDS_PER_SECOND = Duration.ofSeconds(1).toNanos();

    private final ArrayMap<T, SparseLongArray> mKeyToAreaIdToNextUpdateTimeNanos = new ArrayMap<>();

    /** Add {@code key} and {@code value} pair to map. */
    public SparseLongArray put(T key, SparseLongArray value) {
        return mKeyToAreaIdToNextUpdateTimeNanos.put(key, value);
    }

    /** Remove {@code key} from map. */
    public SparseLongArray remove(T key) {
        return mKeyToAreaIdToNextUpdateTimeNanos.remove(key);
    }

    /**
     * Returns true if the timestamp of the event is greater than timestamp of
     * the next update timestamp for a key and area ID pair.
     */
    public boolean shouldCallbackBeInvoked(T key, CarPropertyEvent carPropertyEvent,
            @Nullable Float updateRateHz) {
        CarPropertyValue<?> carPropertyValue = carPropertyEvent.getCarPropertyValue();
        int propertyId = carPropertyValue.getPropertyId();
        int areaId = carPropertyValue.getAreaId();
        SparseLongArray areaIdToNextUpdateTimeNanos = mKeyToAreaIdToNextUpdateTimeNanos.get(key);
        if (areaIdToNextUpdateTimeNanos == null || updateRateHz == null) {
            Slogf.w(TAG, "shouldCallbackBeInvoked: callback was not found for propertyId=%s,"
                            + " areaId=0x%x, timestampNanos=%d",
                    VehiclePropertyIds.toString(propertyId), areaId,
                    carPropertyValue.getTimestamp());
            return false;
        }

        long nextUpdateTimeNanos = areaIdToNextUpdateTimeNanos.get(areaId,
                /* valueIfKeyNotFound= */ 0L);

        if (carPropertyValue.getTimestamp() >= nextUpdateTimeNanos) {
            long updatePeriodNanos = updateRateHz > 0
                    ? ((long) ((1.0 / updateRateHz) * NANOSECONDS_PER_SECOND))
                    : 0L;
            areaIdToNextUpdateTimeNanos.put(areaId,
                    carPropertyValue.getTimestamp() + updatePeriodNanos);
            return true;
        }

        if (DBG) {
            Slogf.d(TAG, "shouldCallbackBeInvokedLocked: Dropping carPropertyEvent - propertyId=%s,"
                            + " areaId=0x%x, because getTimestamp()=%d < nextUpdateTimeNanos=%d",
                    VehiclePropertyIds.toString(propertyId), areaId,
                    carPropertyValue.getTimestamp(), nextUpdateTimeNanos);
        }
        return false;
    }
}
