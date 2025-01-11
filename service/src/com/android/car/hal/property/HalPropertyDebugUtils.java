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

package com.android.car.hal.property;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.PRIVATE_CONSTRUCTOR;
import static com.android.car.internal.util.ConstantDebugUtils.toName;
import static com.android.car.internal.util.DebugUtils.flagsToOptionalString;

import static java.lang.Integer.toHexString;

import android.annotation.Nullable;
import android.hardware.automotive.vehicle.EnumForVehicleProperty;
import android.hardware.automotive.vehicle.UnitsForVehicleProperty;
import android.hardware.automotive.vehicle.VehicleArea;
import android.hardware.automotive.vehicle.VehicleAreaDoor;
import android.hardware.automotive.vehicle.VehicleAreaMirror;
import android.hardware.automotive.vehicle.VehicleAreaSeat;
import android.hardware.automotive.vehicle.VehicleAreaWheel;
import android.hardware.automotive.vehicle.VehicleAreaWindow;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.VehiclePropertyGroup;
import android.hardware.automotive.vehicle.VehiclePropertyStatus;
import android.hardware.automotive.vehicle.VehiclePropertyType;
import android.hardware.automotive.vehicle.VehicleUnit;
import android.util.Slog;

import com.android.car.hal.HalPropValue;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.property.CarPropertyHelper;
import com.android.car.internal.property.PropIdAreaId;
import com.android.car.internal.util.ConstantDebugUtils;

import java.util.Arrays;
import java.util.StringJoiner;

/**
 * Utility class for converting {@link VehicleProperty} related information to human-readable names.
 */
public final class HalPropertyDebugUtils {
    private static final String TAG = HalPropertyDebugUtils.class.getSimpleName();
    private static final int MAX_BYTE_SIZE = 20;
    private static final String NO_VALUE = "NO_VALUE";


    /**
     * HalPropertyDebugUtils only contains static fields and methods and must never be
     * instantiated.
     */
    @ExcludeFromCodeCoverageGeneratedReport(reason = PRIVATE_CONSTRUCTOR)
    private HalPropertyDebugUtils() {
        throw new UnsupportedOperationException("Must never be called");
    }

    /**
     * Gets a user-friendly representation string representation of a {@code propertyId}.
     */
    public static String toPropertyIdString(int propertyId) {
        String hexSuffix = "(0x" + toHexString(propertyId) + ")";
        if (isSystemPropertyId(propertyId)) {
            return toName(VehicleProperty.class, propertyId) + hexSuffix;
        } else if (CarPropertyHelper.isVendorProperty(propertyId)) {
            return "VENDOR_PROPERTY" + hexSuffix;
        } else if (CarPropertyHelper.isBackportedProperty(propertyId)) {
            return "BACKPORTED_PROPERTY" + hexSuffix;
        }
        return "INVALID_PROPERTY_ID" + hexSuffix;
    }

    /**
     * Gets the HAL property's ID based on the passed name.
     */
    @Nullable
    public static Integer toPropertyId(String propertyName) {
        return ConstantDebugUtils.toValue(VehicleProperty.class, propertyName);
    }

    /**
     * Gets a user-friendly string representation of an {@code areaId} for the given
     * {@code propertyId}.
     */
    public static String toAreaIdString(int propertyId, int areaId) {
        switch (propertyId & VehicleArea.MASK) {
            case VehicleArea.GLOBAL -> {
                if (areaId == 0) {
                    return "GLOBAL(0x0)";
                }
                return "INVALID_GLOBAL_AREA_ID(0x" + toHexString(areaId) + ")";
            }
            case VehicleArea.DOOR -> {
                return processOptionalFlagsString(
                        flagsToOptionalString(VehicleAreaDoor.class, areaId),
                        VehicleAreaDoor.class.getSimpleName(), areaId);
            }
            case VehicleArea.SEAT -> {
                return processOptionalFlagsString(
                        flagsToOptionalString(VehicleAreaSeat.class, areaId),
                        VehicleAreaSeat.class.getSimpleName(), areaId);
            }
            case VehicleArea.MIRROR -> {
                return processOptionalFlagsString(
                        flagsToOptionalString(VehicleAreaMirror.class, areaId),
                        VehicleAreaMirror.class.getSimpleName(), areaId);
            }
            case VehicleArea.WHEEL -> {
                return processOptionalFlagsString(
                        flagsToOptionalString(VehicleAreaWheel.class, areaId),
                        VehicleAreaWheel.class.getSimpleName(), areaId);
            }
            case VehicleArea.WINDOW -> {
                return processOptionalFlagsString(
                        flagsToOptionalString(VehicleAreaWindow.class, areaId),
                        VehicleAreaWindow.class.getSimpleName(), areaId);
            }
            default -> {
                return "UNKNOWN_AREA_ID(0x" + toHexString(areaId) + ")";
            }
        }
    }

    /**
     * Gets a user-friendly representation string representation of the value of a
     * {@link HalPropValue} instance.
     */
    public static String toValueString(HalPropValue halPropValue) {
        int propertyId = halPropValue.getPropId();
        int valueType = propertyId & VehiclePropertyType.MASK;
        String propertyUnits = getUnitsIfSupported(propertyId);
        StringJoiner stringJoiner = new StringJoiner(", ", "[", "]");
        switch (valueType) {
            case VehiclePropertyType.BOOLEAN -> {
                if (halPropValue.getInt32ValuesSize() != 1) {
                    return NO_VALUE;
                }
                return halPropValue.getInt32Value(0) == 0 ? "FALSE" : "TRUE";
            }
            case VehiclePropertyType.INT32 -> {
                if (halPropValue.getInt32ValuesSize() != 1) {
                    return NO_VALUE;
                }
                return getIntValueName(propertyId, halPropValue.getInt32Value(0), propertyUnits);
            }
            case VehiclePropertyType.INT32_VEC -> {
                for (int i = 0; i < halPropValue.getInt32ValuesSize(); i++) {
                    stringJoiner.add(getIntValueName(propertyId, halPropValue.getInt32Value(i),
                            propertyUnits));
                }
                return stringJoiner.toString();
            }
            case VehiclePropertyType.FLOAT -> {
                if (halPropValue.getFloatValuesSize() != 1) {
                    return NO_VALUE;
                }
                return halPropValue.getFloatValue(0) + propertyUnits;
            }
            case VehiclePropertyType.FLOAT_VEC -> {
                for (int i = 0; i < halPropValue.getFloatValuesSize(); i++) {
                    stringJoiner.add(halPropValue.getFloatValue(i) + propertyUnits);
                }
                return stringJoiner.toString();
            }
            case VehiclePropertyType.INT64 -> {
                if (halPropValue.getInt64ValuesSize() != 1) {
                    return NO_VALUE;
                }
                return halPropValue.getInt64Value(0) + propertyUnits;
            }
            case VehiclePropertyType.INT64_VEC -> {
                for (int i = 0; i < halPropValue.getInt64ValuesSize(); i++) {
                    stringJoiner.add(halPropValue.getInt64Value(i) + propertyUnits);
                }
                return stringJoiner.toString();
            }
            case VehiclePropertyType.STRING -> {
                return halPropValue.getStringValue();
            }
            case VehiclePropertyType.BYTES -> {
                String bytesString = "";
                byte[] byteValues = halPropValue.getByteArray();
                if (byteValues.length > MAX_BYTE_SIZE) {
                    byte[] bytes = Arrays.copyOf(byteValues, MAX_BYTE_SIZE);
                    bytesString = Arrays.toString(bytes);
                } else {
                    bytesString = Arrays.toString(byteValues);
                }
                return bytesString;
            }
        }
        String bytesString = "";
        byte[] byteValues = halPropValue.getByteArray();
        if (byteValues.length > MAX_BYTE_SIZE) {
            byte[] bytes = Arrays.copyOf(byteValues, MAX_BYTE_SIZE);
            bytesString = Arrays.toString(bytes);
        } else {
            bytesString = Arrays.toString(byteValues);
        }
        return "floatValues: " + halPropValue.dumpFloatValues() + ", int32Values: "
                + halPropValue.dumpInt32Values() + ", int64Values: "
                + halPropValue.dumpInt64Values() + ", bytes: " + bytesString + ", string: "
                + halPropValue.getStringValue();
    }

    private static String getIntValueName(int propertyId, int value, String propertyUnits) {
        if (EnumForVehicleProperty.values.containsKey(propertyId)) {
            for (int i = 0; i < EnumForVehicleProperty.values.get(propertyId).size(); i++) {
                Class<?> enumClazz = EnumForVehicleProperty.values.get(propertyId).get(i);
                String valueName = ConstantDebugUtils.toName(enumClazz, value);
                if (valueName != null) {
                    return valueName + "(0x" + toHexString(value) + ")";
                }
            }
            Slog.w(TAG,
                    "Failed to find enum name for property ID: " + toPropertyIdString(propertyId)
                            + " value: " + value);
        }
        return value + propertyUnits;
    }

    private static String getUnitsIfSupported(int propertyId) {
        if (!UnitsForVehicleProperty.values.containsKey(propertyId)) {
            return "";
        }
        Integer units = UnitsForVehicleProperty.values.get(propertyId);
        String unitsString = ConstantDebugUtils.toName(VehicleUnit.class, units);
        if (unitsString == null) {
            return "";
        }
        return " " + unitsString;
    }

    /**
     * Gets a user-friendly representation string representation of {@link VehicleArea}
     * constant for the passed {@code propertyId}.
     */
    public static String toAreaTypeString(int propertyId) {
        int areaType = propertyId & VehicleArea.MASK;
        return toDebugString(VehicleArea.class, areaType);
    }

    /**
     * Gets a user-friendly representation string representation of {@link VehiclePropertyGroup}
     * constant for the passed {@code propertyId}.
     */
    public static String toGroupString(int propertyId) {
        int group = propertyId & VehiclePropertyGroup.MASK;
        return toDebugString(VehiclePropertyGroup.class, group);
    }

    /**
     * Gets a user-friendly representation string representation of {@link VehiclePropertyType}
     * constant for the passed {@code propertyId}.
     */
    public static String toValueTypeString(int propertyId) {
        int valueType = propertyId & VehiclePropertyType.MASK;
        return toDebugString(VehiclePropertyType.class, valueType);
    }

    /**
     * Gets a user-friendly representation string representation of
     * {@link VehiclePropertyAccess} constant.
     */
    public static String toAccessString(int access) {
        return toDebugString(VehiclePropertyAccess.class, access);
    }

    /**
     * Gets a user-friendly representation string representation of
     * {@link VehiclePropertyChangeMode} constant.
     */
    public static String toChangeModeString(int changeMode) {
        return toDebugString(VehiclePropertyChangeMode.class, changeMode);
    }

    /**
     * Gets a user-friendly representation string representation of
     * {@link VehiclePropertyStatus} constant.
     */
    public static String toStatusString(int status) {
        return toDebugString(VehiclePropertyStatus.class, status);
    }

    private static String toDebugString(Class<?> clazz, int constantValue) {
        String hexSuffix = "(0x" + toHexString(constantValue) + ")";
        if (toName(clazz, constantValue) == null) {
            String invalidConstantValue = "INVALID_" + clazz.getSimpleName() + hexSuffix;
            Slog.e(TAG, invalidConstantValue);
            return invalidConstantValue;
        }
        return toName(clazz, constantValue) + hexSuffix;
    }

    /**
     * Gets human-readable representation of a {@code PropIdAreaId} structure.
     *
     * Note that the property ID is the VHAL property ID, not the CarPropertyManager property ID.
     */
    public static String toHalPropIdAreaIdString(PropIdAreaId propIdAreaId) {
        return "PropIdAreaId{propId=" + toPropertyIdString(propIdAreaId.propId)
            + ", areaId=" + toAreaIdString(propIdAreaId.propId, propIdAreaId.areaId) + "}";
    }

    /**
     * Gets human-readable representation of a list of {@code PropIdAreaId}.
     *
     * Note that the property ID is the VHAL property ID, not the CarPropertyManager property ID.
     */
    public static String toHalPropIdAreaIdsString(Iterable<PropIdAreaId> propIdAreaIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (PropIdAreaId propIdAreaId : propIdAreaIds) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append(toHalPropIdAreaIdString(propIdAreaId));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Returns {@code true} if {@code propertyId} is defined in {@link VehicleProperty}.
     * {@code false} otherwise.
     */
    private static boolean isSystemPropertyId(int propertyId) {
        return toName(VehicleProperty.class, propertyId) != null;
    }

    private static String processOptionalFlagsString(@Nullable String flagsString, String clazzName,
            int areaId) {
        StringBuilder stringBuilder = new StringBuilder();
        if (flagsString == null) {
            stringBuilder.append("INVALID_").append(clazzName).append("_AREA_ID");
        } else {
            stringBuilder.append(flagsString);
        }
        return stringBuilder.append("(0x").append(toHexString(areaId)).append(")").toString();
    }
}
