/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.internal.util;

import static java.lang.Integer.toHexString;

import android.annotation.Nullable;
import android.car.VehicleAreaDoor;
import android.car.VehicleAreaMirror;
import android.car.VehicleAreaSeat;
import android.car.VehicleAreaType;
import android.car.VehicleAreaWheel;
import android.car.VehicleAreaWindow;
import android.car.VehiclePropertyIds;
import android.car.feature.Flags;
import android.util.Slog;

import com.android.car.internal.property.CarPropertyHelper;
import com.android.car.internal.property.PropIdAreaId;

import java.util.List;

// Copied from frameworks/base and kept only used codes
/**
 * <p>Various utilities for debugging and logging.</p>
 */
public final class DebugUtils {
    public static final String TAG = DebugUtils.class.getSimpleName();

    private DebugUtils() {
    }

    /**
     * Gets human-readable representation of constants (static final values).
     *
     * @see #constantToString(Class, String, int)
     */
    public static String constantToString(Class<?> clazz, int value) {
        return constantToString(clazz, "", value);
    }

    /**
     * Use prefixed constants (static final values) on given class to turn value
     * into human-readable string.
     */
    public static String constantToString(Class<?> clazz, String prefix, int value) {
        String constantString = ConstantDebugUtils.toName(clazz, prefix, value);
        return constantString != null ? constantString : prefix + value;
    }

    /**
     * Use prefixed constants (public static final int values) on a given class to turn flags into
     * human-readable string.
     */
    public static String flagsToString(Class<?> bitFlagClazz, String prefix, int flagsToConvert) {
        String flagsString = flagsToOptionalString(bitFlagClazz, prefix, flagsToConvert);
        return flagsString != null ? flagsString : "0x" + Integer.toHexString(flagsToConvert);
    }

    /**
     * Use constants (public static final int values) on given class to turn flags into
     * human-readable string if possible. If no conversion found, returns {@code null}.
     */
    public static @Nullable String flagsToOptionalString(Class<?> bitFlagClazz,
            int flagsToConvert) {
        return flagsToOptionalString(bitFlagClazz, "", flagsToConvert);
    }

    /**
     * Use prefixed constants (public static final int values) on a given class to turn flags into
     * human-readable string if possible. If no conversion found, returns {@code null}.
     */
    public static @Nullable String flagsToOptionalString(Class<?> bitFlagClazz, String prefix,
            int flagsToConvert) {
        boolean inputFlagsWasZero = flagsToConvert == 0;
        int flagsToConvertCopy = flagsToConvert;
        final StringBuilder result = new StringBuilder();

        List<Integer> bitFlags = ConstantDebugUtils.getValues(bitFlagClazz, prefix);
        for (int i = 0; i < bitFlags.size(); i++) {
            int bitFlag = bitFlags.get(i);

            if (bitFlag == 0 && inputFlagsWasZero) {
                return ConstantDebugUtils.toName(bitFlagClazz, prefix, bitFlag);
            }
            if (bitFlag != 0 && (flagsToConvertCopy & bitFlag) == bitFlag) {
                flagsToConvertCopy &= ~bitFlag;
                result.append(ConstantDebugUtils.toName(bitFlagClazz, prefix, bitFlag)).append('|');
            }
        }

        if (result.isEmpty()) {
            return null;
        } else if (flagsToConvertCopy != 0) {
            result.append("0x").append(Integer.toHexString(flagsToConvertCopy));
        } else {
            result.deleteCharAt(result.length() - 1);
        }

        return result.toString();
    }

    /**
     * Gets a user-friendly string representation of an {@code areaId} for the given
     * {@code propertyId}.
     */
    public static String toAreaIdString(int propertyId, int areaId) {
        int areaType;
        try {
            areaType = CarPropertyHelper.getAreaType(propertyId);
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Property ID: " + VehiclePropertyIds.toString(propertyId)
                    + " has invalid area type for area ID: " + areaId, e);
            areaType = -1;
        }

        if (Flags.androidVicVehicleProperties()
                && areaType == VehicleAreaType.VEHICLE_AREA_TYPE_VENDOR) {
            return "VENDOR_AREA_ID(0x" + toHexString(areaId) + ")";
        }

        switch (areaType) {
            case VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL -> {
                if (areaId == 0) {
                    return "GLOBAL";
                }
                return "INVALID_GLOBAL_AREA_ID(0x" + toHexString(areaId) + ")";
            }
            case VehicleAreaType.VEHICLE_AREA_TYPE_DOOR -> {
                return areaIdToString(VehicleAreaDoor.class, "DOOR_", areaId);
            }
            case VehicleAreaType.VEHICLE_AREA_TYPE_MIRROR -> {
                return areaIdToString(VehicleAreaMirror.class, "MIRROR_", areaId);
            }
            case VehicleAreaType.VEHICLE_AREA_TYPE_SEAT -> {
                return areaIdToString(VehicleAreaSeat.class, "SEAT_", areaId);
            }
            case VehicleAreaType.VEHICLE_AREA_TYPE_WHEEL -> {
                return areaIdToString(VehicleAreaWheel.class, "WHEEL_", areaId);
            }
            case VehicleAreaType.VEHICLE_AREA_TYPE_WINDOW -> {
                return areaIdToString(VehicleAreaWindow.class, "WINDOW_", areaId);
            }
            default -> {
                return "UNKNOWN_AREA_TYPE_AREA_ID(0x" + toHexString(areaId) + ")";
            }
        }
    }

    /**
     * Gets human-readable representation of a {@code PropIdAreaId} structure.
     */
    public static String toDebugString(PropIdAreaId propIdAreaId) {
        return "PropIdAreaId{propId=" + VehiclePropertyIds.toString(propIdAreaId.propId)
            + ", areaId=" + toAreaIdString(propIdAreaId.propId, propIdAreaId.areaId) + "}";
    }

    /**
     * Gets human-readable representation of a list of {@code PropIdAreaId} structure.
     */
    public static String toDebugString(List<PropIdAreaId> propIdAreaIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("propIdAreaIds: [");
        boolean first = true;
        for (int i = 0; i < propIdAreaIds.size(); i++) {
            var propIdAreaId = propIdAreaIds.get(i);
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append(toDebugString(propIdAreaId));
        }
        return sb.append("]").toString();
    }

    private static String areaIdToString(Class<?> areaTypeClazz, String prefix, int areaId) {
        String areaIdString = flagsToOptionalString(areaTypeClazz, prefix, areaId);
        if (areaIdString != null) {
            return areaIdString;
        }
        return "UNKNOWN_" + prefix + "AREA_ID(0x" + toHexString(areaId) + ")";
    }
}
