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

package com.android.car.logging;

import android.annotation.FloatRange;
import android.annotation.IntRange;

import com.android.modules.expresslog.Histogram;

/**
 * Interface for creating a {@link com.android.modules.expresslog.Histogram}.
 *
 * This interface allows faking the implementation in unit tests.
 */
public interface HistogramFactoryInterface {
    /**
     * Creates a new histogram using uniform (linear) sized bins.
     *
     * @param metricId          to log, logging will be no-op if metricId is not defined in the TeX
     *                          catalog
     * @param binCount          amount of histogram bins. 2 bin indexes will be calculated
     *                          automatically to represent underflow & overflow bins
     * @param minValue          is included in the first bin, values less than minValue
     *                          go to underflow bin
     * @param exclusiveMaxValue is included in the overflow bucket. For accurate
     *                          measure up to kMax, then exclusiveMaxValue
     *                          should be set to kMax + 1
     */
    Histogram newUniformHistogram(String metricId, @IntRange(from = 1) int binCount,
            float minValue, float exclusiveMaxValue);

    /**
     * Creates a new histogram using scaled range bins.
     *
     * @param metricId      to log, logging will be no-op if metricId is not defined in the TeX
     *                      catalog
     * @param binCount      amount of histogram bins. 2 bin indexes will be calculated
     *                      automatically to represent underflow & overflow bins
     * @param minValue      is included in the first bin, values less than minValue
     *                      go to underflow bin
     * @param firstBinWidth used to represent first bin width and as a reference to calculate
     *                      width for consecutive bins
     * @param scaleFactor   used to calculate width for consecutive bins
     */
    Histogram newScaledRangeHistogram(String metricId, @IntRange(from = 1) int binCount,
            int minValue, @FloatRange(from = 1.f) float firstBinWidth,
            @FloatRange(from = 1.f) float scaleFactor);
}
