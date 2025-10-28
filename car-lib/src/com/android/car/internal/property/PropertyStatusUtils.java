/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.car.hardware.CarPropertyValue;
import android.car.hardware.CarPropertyValue.CarPropertyStatus;
import android.car.hardware.property.VehicleHalStatusCode;
import android.util.SparseIntArray;

import java.util.Set;

/** Utils class for {@link CarPropertyStatus}. */
public class PropertyStatusUtils {

    private PropertyStatusUtils() {
        throw new UnsupportedOperationException("PropertyStatusUtils is a static class");
    }

    private static final SparseIntArray NOT_AVAILABLE_STATUS_CODE_TO_PROPERTY_STATUS =
            new SparseIntArray();

    static {
        NOT_AVAILABLE_STATUS_CODE_TO_PROPERTY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE,
                CarPropertyValue.STATUS_NOT_AVAILABLE_GENERAL);
        NOT_AVAILABLE_STATUS_CODE_TO_PROPERTY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED,
                CarPropertyValue.STATUS_NOT_AVAILABLE_DISABLED);
        NOT_AVAILABLE_STATUS_CODE_TO_PROPERTY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW,
                CarPropertyValue.STATUS_NOT_AVAILABLE_SPEED_LOW);
        NOT_AVAILABLE_STATUS_CODE_TO_PROPERTY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH,
                CarPropertyValue.STATUS_NOT_AVAILABLE_SPEED_HIGH);
        NOT_AVAILABLE_STATUS_CODE_TO_PROPERTY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_POOR_VISIBILITY,
                CarPropertyValue.STATUS_NOT_AVAILABLE_POOR_VISIBILITY);
        NOT_AVAILABLE_STATUS_CODE_TO_PROPERTY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY,
                CarPropertyValue.STATUS_NOT_AVAILABLE_SAFETY);
        NOT_AVAILABLE_STATUS_CODE_TO_PROPERTY_STATUS.put(
                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED,
                CarPropertyValue.STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED);
    }

    /** All property status that represents a not_available status. */
    public static final Set<Integer> NOT_AVAILABLE_PROPERTY_STATUS_LIST =
            Set.of(
                    CarPropertyValue.STATUS_NOT_AVAILABLE_GENERAL,
                    CarPropertyValue.STATUS_NOT_AVAILABLE_DISABLED,
                    CarPropertyValue.STATUS_NOT_AVAILABLE_SPEED_LOW,
                    CarPropertyValue.STATUS_NOT_AVAILABLE_SPEED_HIGH,
                    CarPropertyValue.STATUS_NOT_AVAILABLE_POOR_VISIBILITY,
                    CarPropertyValue.STATUS_NOT_AVAILABLE_SAFETY,
                    CarPropertyValue.STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED);

    /**
     * Returns whether the property status is an error status.
     */
    public static boolean isPropertyStatusError(int propertyStatus) {
        return propertyStatus == CarPropertyValue.STATUS_ERROR;
    }

    /**
     * Returns whether the property status is a not_available status.
     */
    public static boolean isPropertyStatusNotAvailable(int propertyStatus) {
        return NOT_AVAILABLE_PROPERTY_STATUS_LIST.contains(propertyStatus);
    }

    /**
     * Returns whether the property status is available.
     */
    public static boolean isPropertyStatusAvailable(int propertyStatus) {
        return propertyStatus == CarPropertyValue.STATUS_AVAILABLE;
    }

    /** Returns the equivalent not available property status given a VHAL StatusCode. */
    public static @CarPropertyStatus int getNotAvailablePropertyStatusFromStatusCode(
            int statusCode) {
        int propertyStatusIndex =
                NOT_AVAILABLE_STATUS_CODE_TO_PROPERTY_STATUS.indexOfKey(statusCode);
        if (propertyStatusIndex < 0) {
            throw new IllegalArgumentException("Not a not-available StatusCode: " + statusCode);
        }
        return NOT_AVAILABLE_STATUS_CODE_TO_PROPERTY_STATUS.valueAt(propertyStatusIndex);
    }
}
