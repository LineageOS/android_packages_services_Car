/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.car.cluster.sample;

/**
 * Utility functions to convert distance. Assumes US locale - for demo purpose only!
 */
public final class DistanceUtils {

    public static final float MILES_IN_KM = 0.621371f;
    public static final float FEET_PER_METER = 3.2808399f;
    public static final float FEET_PER_MILE = 5280;
    public static final float METERS_PER_FOOT = 1 / FEET_PER_METER;
    public static final float METERS_PER_MILE = FEET_PER_MILE / FEET_PER_METER;

    private DistanceUtils() {}  // Prevent instantiation of utility class.

    public static boolean showDistanceInFeet(int meters) {
        return meters < ((0.1 / MILES_IN_KM) * 1000);
    }

    public static String metersToMilesFormatted(int meters) {
        float miles = meters / METERS_PER_MILE;

        if (miles > 100) {  // Do not show decimal if more than 100 miles.
            return String.valueOf((int) miles);
        } else {
            return String.format("%.1f", miles);
        }
    }

    private static int roundToNearest(int value, int base) {
        return value - value % base;
    }

    public static String metersToFeetFormatted(int meters) {
        int feet = (int) (meters / METERS_PER_FOOT);
        return String.valueOf(roundToNearest(feet, 50));
    }

}
