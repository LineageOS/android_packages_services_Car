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
import static com.android.car.internal.common.CommonConstants.EMPTY_BYTE_ARRAY;
import static com.android.car.internal.property.VehiclePropertyIdDebugUtils.isDefined;
import static com.android.car.internal.property.VehiclePropertyIdDebugUtils.toDebugString;

import android.annotation.SuppressLint;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.VehicleHalStatusCode;
import android.car.hardware.property.VehicleHalStatusCode.VehicleHalStatusCodeInt;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.util.Collection;

/**
 * Helper class for CarPropertyService/CarPropertyManager.
 *
 * @hide
 */
public final class CarPropertyHelper {
    /**
     * Status indicating no error.
     *
     * <p>This is not exposed to the client as this will be used only for deciding
     * {@link GetPropertyCallback#onSuccess} or {@link GetPropertyCallback#onFailure} is called.
     */
    public static final int STATUS_OK = 0;

    /**
     * Error indicating that too many sync operation is ongoing, caller should try again after
     * some time.
     */
    public static final int SYNC_OP_LIMIT_TRY_AGAIN = -1;

    // These are the same values as defined in VHAL interface.
    private static final int VEHICLE_PROPERTY_GROUP_MASK = 0xf0000000;
    private static final int VEHICLE_PROPERTY_GROUP_VENDOR = 0x20000000;
    private static final int VEHICLE_PROPERTY_GROUP_BACKPORTED = 0x30000000;

    private static final int SYSTEM_ERROR_CODE_MASK = 0xffff;
    private static final int VENDOR_ERROR_CODE_SHIFT = 16;

    /**
     * CarPropertyHelper only contains static fields and methods and must never be instantiated.
     */
    @ExcludeFromCodeCoverageGeneratedReport(reason = PRIVATE_CONSTRUCTOR)
    private CarPropertyHelper() {
        throw new UnsupportedOperationException("Must never be called");
    }

    /**
     * Returns whether the property ID is supported by the current Car Service version.
     */
    public static boolean isSupported(int propertyId) {
        return isSystemProperty(propertyId) || isVendorOrBackportedProperty(propertyId);
    }

    /**
     * Gets a user-friendly representation of a list of properties.
     */
    public static String propertyIdsToString(Collection<Integer> propertyIds) {
        String names = "[";
        boolean first = true;
        for (int propertyId : propertyIds) {
            if (first) {
                first = false;
            } else {
                names += ", ";
            }
            names += toDebugString(propertyId);
        }
        return names + "]";
    }

    /**
     * Returns the system error code contained in the error code returned from VHAL.
     */
    @SuppressLint("WrongConstant")
    public static @VehicleHalStatusCodeInt int getVhalSystemErrorCode(int vhalErrorCode) {
        return vhalErrorCode & SYSTEM_ERROR_CODE_MASK;
    }

    /**
     * Returns the vendor error code contained in the error code returned from VHAL.
     */
    public static int getVhalVendorErrorCode(int vhalErrorCode) {
        return vhalErrorCode >>> VENDOR_ERROR_CODE_SHIFT;
    }

    /**
     * Returns whether the property ID is defined as a system property.
     */
    public static boolean isSystemProperty(int propertyId) {
        return propertyId != VehiclePropertyIds.INVALID && isDefined(propertyId);
    }

    /**
     * Returns {@code true} if {@code vehicleHalStatusCode} is one of the not available
     * {@link VehicleHalStatusCode} values}. Otherwise returns {@code false}.
     */
    public static boolean isNotAvailableVehicleHalStatusCode(
            @VehicleHalStatusCodeInt int vehicleHalStatusCode) {
        switch (vehicleHalStatusCode) {
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_POOR_VISIBILITY:
            case VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns whether the property ID is defined as a vendor property or a backported property.
     */
    public static boolean isVendorOrBackportedProperty(int propertyId) {
        return isVendorProperty(propertyId) || isBackportedProperty(propertyId);
    }

    /**
     * Returns whether the property ID is defined as a vendor property.
     */
    public static boolean isVendorProperty(int propertyId) {
        return (propertyId & VEHICLE_PROPERTY_GROUP_MASK) == VEHICLE_PROPERTY_GROUP_VENDOR;
    }

    /**
     * Returns whether the property ID is defined as a backported property.
     */
    public static boolean isBackportedProperty(int propertyId) {
        return (propertyId & VEHICLE_PROPERTY_GROUP_MASK) == VEHICLE_PROPERTY_GROUP_BACKPORTED;
    }

    /**
     * Gets the default value for a {@link CarPropertyValue} class type.
     */
    public static <T> T getDefaultValue(Class<T> clazz) {
        if (clazz.equals(Boolean.class)) {
            return (T) Boolean.FALSE;
        }
        if (clazz.equals(Integer.class)) {
            return (T) Integer.valueOf(0);
        }
        if (clazz.equals(Long.class)) {
            return (T) Long.valueOf(0);
        }
        if (clazz.equals(Float.class)) {
            return (T) Float.valueOf(0f);
        }
        if (clazz.equals(Integer[].class)) {
            return (T) new Integer[0];
        }
        if (clazz.equals(Long[].class)) {
            return (T) new Long[0];
        }
        if (clazz.equals(Float[].class)) {
            return (T) new Float[0];
        }
        if (clazz.equals(byte[].class)) {
            return (T) EMPTY_BYTE_ARRAY;
        }
        if (clazz.equals(Object[].class)) {
            return (T) new Object[0];
        }
        if (clazz.equals(String.class)) {
            return (T) "";
        }
        throw new IllegalArgumentException("Unexpected class: " + clazz);
    }
}
