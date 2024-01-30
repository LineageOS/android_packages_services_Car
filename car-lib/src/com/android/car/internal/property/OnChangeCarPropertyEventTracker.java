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
import android.util.Log;

/**
 * A {@link CarPropertyEventTracker} implementation for on-change property
 *
 * @hide
 */
public final class OnChangeCarPropertyEventTracker implements CarPropertyEventTracker {
    private static final String TAG = "OnChangeCarPropertyEventTracker";
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);
    private static final float INVALID_UPDATE_RATE_HZ = 0f;

    private long mPreviousEventTimeNanos;

    @Override
    public float getUpdateRateHz() {
        return INVALID_UPDATE_RATE_HZ;
    }

    /** Returns true if the client needs to be updated for this event. */
    @Override
    public boolean hasUpdate(CarPropertyValue<?> carPropertyValue) {
        if (carPropertyValue.getTimestamp() < mPreviousEventTimeNanos) {
            if (DBG) {
                Slogf.d(TAG, "hasUpdate: Dropping carPropertyValue: %s, "
                        + "because getTimestamp()=%d < previousEventTimeNanos=%d",
                        carPropertyValue, carPropertyValue.getTimestamp(), mPreviousEventTimeNanos);
            }
            return false;
        }
        mPreviousEventTimeNanos = carPropertyValue.getTimestamp();
        return true;
    }
}
