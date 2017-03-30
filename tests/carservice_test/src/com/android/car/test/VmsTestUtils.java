package com.android.car.test;

import android.car.annotation.FutureFeature;
import android.util.Log;

import com.android.car.internal.FeatureConfiguration;

@FutureFeature
public class VmsTestUtils {
    public static boolean canRunTest(String tag) {
        if (!FeatureConfiguration.ENABLE_VEHICLE_MAP_SERVICE) {
            Log.i(tag, "Skipping test because ENABLE_VEHICLE_MAP_SERVICE = false");
        }
        return FeatureConfiguration.ENABLE_VEHICLE_MAP_SERVICE;
    }
}
