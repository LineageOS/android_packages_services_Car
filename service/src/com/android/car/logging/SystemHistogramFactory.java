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

import com.android.modules.expresslog.Histogram;

/**
 * A real implementation for {@link com.android.car.internal.logging.HistogramFactoryInterface}.
 */
public class SystemHistogramFactory implements HistogramFactoryInterface {
    @Override
    public Histogram newUniformHistogram(String metricId, int binCount,
            float minValue, float exclusiveMaxValue) {
        return new Histogram(metricId, new Histogram.UniformOptions(
                binCount, minValue, exclusiveMaxValue));
    }

    @Override
    public Histogram newScaledRangeHistogram(String metricId, int binCount,
            int minValue, float firstBinWidth, float scaleFactor) {
        return new Histogram(metricId, new Histogram.ScaledRangeOptions(
                binCount, minValue, firstBinWidth, scaleFactor));
    }
}
