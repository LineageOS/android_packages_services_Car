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

import android.car.VehiclePropertyIds;

import com.android.car.internal.property.PropIdAreaId;

import java.util.List;

// Copied from frameworks/base and kept only used codes
/**
 * <p>Various utilities for debugging and logging.</p>
 */
public final class DebugUtils {
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
     * Use prefixed constants (static final values) on given class to turn flags
     * into human-readable string.
     */
    public static String flagsToString(Class<?> clazz, String prefix, int flagsToConvert) {
        int flags = flagsToConvert;
        final StringBuilder res = new StringBuilder();
        boolean flagsWasZero = flags == 0;

        for (Integer bitFlag : ConstantDebugUtils.getValues(clazz, prefix)) {

            if (bitFlag == 0 && flagsWasZero) {
                return ConstantDebugUtils.toName(clazz, prefix, bitFlag);
            }
            if (bitFlag != 0 && (flags & bitFlag) == bitFlag) {
                flags &= ~bitFlag;
                res.append(ConstantDebugUtils.toName(clazz, prefix, bitFlag)).append('|');
            }
        }

        if (flags != 0 || res.isEmpty()) {
            res.append(Integer.toHexString(flags));
        } else {
            res.deleteCharAt(res.length() - 1);
        }
        return res.toString();
    }

    /**
     * Gets human-readable representation of a {@code PropIdAreaId} structure.
     */
    public static String toDebugString(PropIdAreaId propIdAreaId) {
        return "PropIdAreaId{propId=" + VehiclePropertyIds.toString(propIdAreaId.propId)
            + ", areaId=" + propIdAreaId.areaId + "}";
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
}
