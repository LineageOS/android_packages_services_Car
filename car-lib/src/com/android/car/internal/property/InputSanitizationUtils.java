/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.PRIVATE_CONSTRUCTOR;
import static com.android.car.internal.util.ArrayUtils.convertToIntArray;

import android.car.VehiclePropertyIds;
import android.car.feature.FeatureFlags;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.property.CarPropertyManager;
import android.util.Log;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * Common utility functions used by {@link CarPropertyManager} stack to sanitize input arguments.
 *
 * @hide
 */
public final class InputSanitizationUtils {

    private static final String TAG = InputSanitizationUtils.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    @ExcludeFromCodeCoverageGeneratedReport(reason = PRIVATE_CONSTRUCTOR)
    private InputSanitizationUtils() {
    }

    /**
     * Sanitizes the {@code updateRateHz} passed to {@link
     * CarPropertyManager#registerCallback(CarPropertyManager.CarPropertyEventCallback, int, float)}
     * and similar functions.
     */
    public static float sanitizeUpdateRateHz(
            CarPropertyConfig<?> carPropertyConfig, float updateRateHz) {
        float sanitizedUpdateRateHz = updateRateHz;
        if (carPropertyConfig.getChangeMode()
                != CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
            sanitizedUpdateRateHz = CarPropertyManager.SENSOR_RATE_ONCHANGE;
        } else if (sanitizedUpdateRateHz > carPropertyConfig.getMaxSampleRate()) {
            sanitizedUpdateRateHz = carPropertyConfig.getMaxSampleRate();
        } else if (sanitizedUpdateRateHz < carPropertyConfig.getMinSampleRate()) {
            sanitizedUpdateRateHz = carPropertyConfig.getMinSampleRate();
        }
        return sanitizedUpdateRateHz;
    }

    /**
     * Returns whether VUR feature is enabled and property is continuous.
     *
     * Need to be public even though InputSanitizationUtilsUnitTest is in the same package because
     * in CarServiceUnitTest, it is loaded using a different class loader.
     */
    @VisibleForTesting
    public static boolean isVURAllowed(FeatureFlags featureFlags,
            CarPropertyConfig<?> carPropertyConfig) {
        if (!featureFlags.variableUpdateRate()) {
            if (DBG) {
                Log.d(TAG, "VUR feature is not enabled, VUR is always off");
            }
            return false;
        }
        if (carPropertyConfig.getChangeMode()
                != CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
            if (DBG) {
                Log.d(TAG, "VUR is always off for non-continuous property");
            }
            return false;
        }
        return true;
    }

    /**
     * Sanitizes the enableVUR option in {@code CarSubscribeOption}.
     *
     * <p>Overwrite it to false if:
     *
     * <ul>
     * <li>VUR feature is disabled.</li>
     * <li>Property is not continuous.</li>
     * <li>VUR is not supported for the specific area.</li>
     * </ul>
     *
     * @return a list of sanitized subscribe options. We may return more than one option because
     * part of the areaIds support VUR, the rest does not.
     */
    public static List<CarSubscribeOption> sanitizeEnableVariableUpdateRate(
            FeatureFlags featureFlags, CarPropertyConfig<?> carPropertyConfig,
            CarSubscribeOption inputOption) throws IllegalArgumentException {
        int[] areaIds = inputOption.areaIds;
        Preconditions.checkArgument(areaIds != null && areaIds.length != 0,
                "areaIds must not be empty for property: "
                + VehiclePropertyIds.toString(inputOption.propertyId));
        List<CarSubscribeOption> sanitizedOptions = new ArrayList<>();
        if (!inputOption.enableVariableUpdateRate) {
            // We will only overwrite enableVUR to off.
            sanitizedOptions.add(inputOption);
            return sanitizedOptions;
        }
        // If VUR feature is disabled, overwrite the VUR option to false.
        if (!isVURAllowed(featureFlags, carPropertyConfig)) {
            inputOption.enableVariableUpdateRate = false;
            sanitizedOptions.add(inputOption);
            return sanitizedOptions;
        }
        List<Integer> enabledAreaIds = new ArrayList<>();
        List<Integer> disabledAreaIds = new ArrayList<>();
        for (int areaId : areaIds) {
            try {
                if (carPropertyConfig.getAreaIdConfig(areaId)
                        .isVariableUpdateRateSupported()) {
                    enabledAreaIds.add(areaId);
                    continue;
                }
            } catch (IllegalArgumentException e) {
                // Do nothing.
            }
            if (DBG) {
                Log.d(TAG, "VUR is enabled but not supported for areaId: " + areaId);
            }
            disabledAreaIds.add(areaId);
        }
        CarSubscribeOption disabledVUROption = new CarSubscribeOption();
        disabledVUROption.propertyId = inputOption.propertyId;
        disabledVUROption.areaIds = convertToIntArray(disabledAreaIds);
        disabledVUROption.updateRateHz = inputOption.updateRateHz;
        disabledVUROption.enableVariableUpdateRate = false;

        CarSubscribeOption enabledVUROption = new CarSubscribeOption();
        enabledVUROption.propertyId = inputOption.propertyId;
        enabledVUROption.areaIds = convertToIntArray(enabledAreaIds);
        enabledVUROption.updateRateHz = inputOption.updateRateHz;
        enabledVUROption.enableVariableUpdateRate = true;

        sanitizedOptions.add(enabledVUROption);
        sanitizedOptions.add(disabledVUROption);
        return sanitizedOptions;
    }
}
