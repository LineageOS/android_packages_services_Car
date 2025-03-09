/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.car.hardware.property;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.car.feature.Flags;

/**
 * A structure contains min/max supported value.
 *
 * @param <T> the type for the property value, must be one of Object, Boolean, Float, Integer,
 *      Long, Float[], Integer[], Long[], String, byte[], Object[]
 */
@FlaggedApi(Flags.FLAG_CAR_PROPERTY_SUPPORTED_VALUE)
public class MinMaxSupportedValue<T> {
    private final @Nullable T mMinValue;
    private final @Nullable T mMaxValue;

    /**
     * A structure containing one optional min value (might be null) and one optional max value
     * (might be null).
     *
     * @hide
     */
    public MinMaxSupportedValue(@Nullable T minValue, @Nullable T maxValue) {
        mMinValue = minValue;
        mMaxValue = maxValue;
    }

    /**
     * Gets the currently supported min value.
     *
     * @return The currently supported min value, or {@code null} if not specified.
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SUPPORTED_VALUE)
    public @Nullable T getMinValue() {
        return mMinValue;
    }
     /**
     * Gets the currently supported max value.
     *
     * @return The currently supported max value, or {@code null} if not specified.
     */
    @FlaggedApi(Flags.FLAG_CAR_PROPERTY_SUPPORTED_VALUE)
    public @Nullable T getMaxValue() {
        return mMaxValue;
    }
}
