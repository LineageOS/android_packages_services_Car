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

package com.android.car.hal;

import android.annotation.Nullable;
import android.hardware.automotive.vehicle.VehicleProperty;

import com.android.car.internal.property.CarPropertyHelper;
import com.android.car.internal.util.ConstantDebugUtils;

/**
 * Utility class for converting {@link VehicleProperty} IDs and names.
 */
public final class HalPropertyIdDebugUtils {
    /**
     * HalPropertyIdDebugUtils only contains static fields and methods and must never be
     * instantiated.
     */
    private HalPropertyIdDebugUtils() {
        throw new UnsupportedOperationException("Must never be called");
    }

    /**
     * Gets the HAL property's name based on the ID.
     */
    @Nullable
    private static String toName(int propertyId) {
        return ConstantDebugUtils.toName(VehicleProperty.class, propertyId);
    }

    /**
     * Gets a user-friendly representation string representation of {@code propertyId}.
     */
    public static String toDebugString(int propertyId) {
        if (isDefined(propertyId)) {
            return toName(propertyId);
        } else if (CarPropertyHelper.isVendorProperty(propertyId)) {
            return "VENDOR_PROPERTY(0x" + Integer.toHexString(propertyId) + ")";
        }
        return "0x" + Integer.toHexString(propertyId);
    }

    /**
     * Gets the HAL property's ID based on the passed name.
     */
    @Nullable
    public static Integer toId(String propertyName) {
        return ConstantDebugUtils.toValue(VehicleProperty.class, propertyName);
    }

    /**
     * Returns {@code true} if {@code propertyId} is defined in {@link VehicleProperty}.
     * {@code false} otherwise.
     */
    private static boolean isDefined(int propertyId) {
        return toName(propertyId) != null;
    }
}
