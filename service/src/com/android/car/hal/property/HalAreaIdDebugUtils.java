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

import static com.android.car.internal.util.ConstantDebugUtils.toName;

import static java.lang.Integer.toHexString;

import android.annotation.Nullable;
import android.hardware.automotive.vehicle.VehicleArea;
import android.hardware.automotive.vehicle.VehicleAreaDoor;
import android.hardware.automotive.vehicle.VehicleAreaMirror;
import android.hardware.automotive.vehicle.VehicleAreaSeat;
import android.hardware.automotive.vehicle.VehicleAreaWheel;
import android.hardware.automotive.vehicle.VehicleAreaWindow;
import android.util.ArrayMap;

import com.android.car.internal.util.ConstantDebugUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utility class for converting area IDs to readable names.
 */
public final class HalAreaIdDebugUtils {
    private static final AtomicReference<Map<Class<?>, List<Integer>>> CLAZZ_TO_AREA_BITS_HOLDER =
            new AtomicReference<>();

    /**
     * HalAreaIdDebugUtils only contains static fields and methods and must never be
     * instantiated.
     */
    private HalAreaIdDebugUtils() {
        throw new UnsupportedOperationException("Must never be called");
    }

    /**
     * Gets a user-friendly string representation of an {@code areaId} for the given
     * {@code propertyId}.
     */
    public static String toDebugString(int propertyId, int areaId) {
        switch (propertyId & VehicleArea.MASK) {
            case VehicleArea.GLOBAL -> {
                if (areaId == 0) {
                    return "GLOBAL(0x0)";
                } else {
                    return "INVALID_GLOBAL_AREA_ID(0x" + toHexString(areaId) + ")";
                }
            }
            case VehicleArea.DOOR -> {
                return convertAreaIdToDebugString(VehicleAreaDoor.class, areaId);
            }
            case VehicleArea.SEAT -> {
                if (areaId == VehicleAreaSeat.UNKNOWN) {
                    return toName(VehicleAreaSeat.class, areaId);
                } else {
                    return convertAreaIdToDebugString(VehicleAreaSeat.class, areaId);
                }
            }
            case VehicleArea.MIRROR -> {
                return convertAreaIdToDebugString(VehicleAreaMirror.class, areaId);
            }
            case VehicleArea.WHEEL -> {
                if (areaId == VehicleAreaWheel.UNKNOWN) {
                    return toName(VehicleAreaWheel.class, areaId);
                } else {
                    return convertAreaIdToDebugString(VehicleAreaWheel.class, areaId);
                }
            }
            case VehicleArea.WINDOW -> {
                return convertAreaIdToDebugString(VehicleAreaWindow.class, areaId);
            }
            default -> {
                return "UNKNOWN_AREA_ID(0x" + toHexString(areaId) + ")";
            }
        }
    }

    private static String convertAreaIdToDebugString(Class<?> clazz, int areaId) {
        String output = "";

        Map<Class<?>, List<Integer>> clazzToAreaBits = CLAZZ_TO_AREA_BITS_HOLDER.get();
        if (clazzToAreaBits == null || clazzToAreaBits.get(clazz) == null) {
            clazzToAreaBits = getClazzToAreaBitsMapping(clazzToAreaBits, clazz);
            CLAZZ_TO_AREA_BITS_HOLDER.set(clazzToAreaBits);
        }

        int areaBitMask = 0;
        for (int i = 0; i < clazzToAreaBits.get(clazz).size(); i++) {
            int areaBit = clazzToAreaBits.get(clazz).get(i).intValue();
            if (areaBit == 0) {
                continue;
            }
            areaBitMask |= areaBit;
            if ((areaId & areaBit) == areaBit) {
                if (!output.isEmpty()) {
                    output += "|";
                }
                output += toName(clazz, areaBit);
            }
        }

        if ((areaId | areaBitMask) != areaBitMask || output.isEmpty()) {
            output += "INVALID_" + clazz.getSimpleName() + "_AREA_ID";
        }

        output += "(0x" + toHexString(areaId) + ")";
        return output;
    }

    private static Map<Class<?>, List<Integer>> getClazzToAreaBitsMapping(
            @Nullable Map<Class<?>, List<Integer>> clazzToAreaBits, Class<?> clazz) {
        Map<Class<?>, List<Integer>> outputClazzToAreaBits;
        if (clazzToAreaBits == null) {
            outputClazzToAreaBits = new ArrayMap<>();
        } else {
            outputClazzToAreaBits = new ArrayMap<>(clazzToAreaBits.size());
            outputClazzToAreaBits.putAll(clazzToAreaBits);
        }

        List<Integer> areaBits = new ArrayList<>(ConstantDebugUtils.getValues(clazz));
        Collections.sort(areaBits, Collections.reverseOrder());

        outputClazzToAreaBits.put(clazz, areaBits);
        return outputClazzToAreaBits;
    }
}
