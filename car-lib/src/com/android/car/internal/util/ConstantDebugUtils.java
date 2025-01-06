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

package com.android.car.internal.util;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.PRIVATE_CONSTRUCTOR;

import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utility class to convert integer constants to and from their value or name.
 */
public final class ConstantDebugUtils {
    private static final String TAG = ConstantDebugUtils.class.getSimpleName();
    private static final AtomicReference<Map<ConstantKey, ConstantDebugUtils>>
            CONSTANT_KEY_TO_CONSTANT_DEBUG_UTILS_HOLDER = new AtomicReference<>();
    private final ConstantKey mConstantKey;
    /*
     * Used to cache the mapping of property names to IDs. This
     * will be initialized during the first usage.
     */
    private final AtomicReference<ArrayMap<String, Integer>> mNameToValueHolder =
            new AtomicReference<>();
    /*
     * Used to cache the mapping of property IDs to names. This
     * will be initialized during the first usage.
     */
    private final AtomicReference<SparseArray<String>> mValueToNameHolder = new AtomicReference<>();

    @ExcludeFromCodeCoverageGeneratedReport(reason = PRIVATE_CONSTRUCTOR)
    private ConstantDebugUtils(ConstantKey constantKey) {
        mConstantKey = constantKey;
    }

    /**
     * Gets the constant's name based on the value for the specified {@code clazz}. Returns
     * {@code null} if value does not exist.
     */
    @Nullable
    public static String toName(Class<?> clazz, int value) {
        return toName(clazz, "", value);
    }

    /**
     * Gets the constant's name based on the {@code value} for the specified {@code clazz} and
     * {@code prefix} combo. Returns {@code null} if value does not exist.
     */
    @Nullable
    public static String toName(Class<?> clazz, String prefix, int value) {
        return getConstantDebugUtils(new ConstantKey(clazz, prefix)).toName(value);
    }

    /**
     * Gets the constant's value based on the passed {@code name} for the specified {@code clazz}.
     * Returns {@code null} if name does not exist.
     */
    @Nullable
    public static Integer toValue(Class<?> clazz, String name) {
        return getConstantDebugUtils(new ConstantKey(clazz)).toValue(name);
    }

    /**
     * Gets the all the constant values for the specified {@code clazz}.
     */
    public static Collection<Integer> getValues(Class<?> clazz) {
        return getValues(clazz, "");
    }

    /**
     * Gets the all the constant values for the specified {@code clazz} and {@code prefix} combo.
     */
    public static Collection<Integer> getValues(Class<?> clazz, String prefix) {
        return getConstantDebugUtils(
                new ConstantKey(clazz, prefix)).getConstantNameToValueMapping().values();
    }

    private static ConstantDebugUtils getConstantDebugUtils(ConstantKey constantKey) {
        Map<ConstantKey, ConstantDebugUtils> clazzToConstantDebugUtils =
                CONSTANT_KEY_TO_CONSTANT_DEBUG_UTILS_HOLDER.get();
        if (clazzToConstantDebugUtils == null || clazzToConstantDebugUtils.get(constantKey)
                == null) {
            clazzToConstantDebugUtils = getConstantKeyToConstantDebugUtils(
                    clazzToConstantDebugUtils, constantKey);
            CONSTANT_KEY_TO_CONSTANT_DEBUG_UTILS_HOLDER.set(clazzToConstantDebugUtils);
        }
        return clazzToConstantDebugUtils.get(constantKey);
    }

    private static Map<ConstantKey, ConstantDebugUtils> getConstantKeyToConstantDebugUtils(
            @Nullable Map<ConstantKey, ConstantDebugUtils> constantKeyToConstantDebugUtils,
            ConstantKey constantKey) {
        Map<ConstantKey, ConstantDebugUtils> outputConstantKeyToConstantDebugsUtils;
        if (constantKeyToConstantDebugUtils == null) {
            outputConstantKeyToConstantDebugsUtils = new ArrayMap<>();
        } else {
            outputConstantKeyToConstantDebugsUtils = new ArrayMap<>(
                    constantKeyToConstantDebugUtils.size());
            outputConstantKeyToConstantDebugsUtils.putAll(constantKeyToConstantDebugUtils);
        }
        outputConstantKeyToConstantDebugsUtils.put(constantKey,
                new ConstantDebugUtils(constantKey));
        return outputConstantKeyToConstantDebugsUtils;
    }

    @Nullable
    private String toName(int value) {
        return getConstantValueToNameMapping().get(value);
    }

    @Nullable
    private Integer toValue(String name) {
        if (!mConstantKey.prefix().isEmpty() && name.startsWith(mConstantKey.prefix())) {
            return getConstantNameToValueMapping().get(
                    name.substring(mConstantKey.prefix().length()));
        }
        return getConstantNameToValueMapping().get(name);
    }

    private ArrayMap<String, Integer> getConstantNameToValueMapping() {
        ArrayMap<String, Integer> nameToValue = mNameToValueHolder.get();
        if (nameToValue == null) {
            nameToValue = createConstantNameToValueMapping();
            mNameToValueHolder.compareAndSet(null, nameToValue);
        }
        return nameToValue;
    }

    private SparseArray<String> getConstantValueToNameMapping() {
        SparseArray<String> valueToName = mValueToNameHolder.get();
        if (valueToName == null) {
            valueToName = createConstantValueToNameMapping();
            mValueToNameHolder.compareAndSet(null, valueToName);
        }
        return valueToName;
    }

    /**
     * Creates a mapping property names to their IDs.
     */
    private ArrayMap<String, Integer> createConstantNameToValueMapping() {
        ArrayMap<String, Integer> constantNameToValue = new ArrayMap<>();
        for (int i = 0; i < mConstantKey.clazz().getDeclaredFields().length; i++) {
            Field candidateField = mConstantKey.clazz().getDeclaredFields()[i];
            try {
                if (isMatchingConstant(candidateField)) {
                    constantNameToValue.put(getConstantName(candidateField),
                            candidateField.getInt(null));
                }
            } catch (IllegalAccessException e) {
                Slog.wtf(TAG, "Failed trying to find value for " + candidateField.getName(), e);
            }
        }
        return constantNameToValue;
    }

    /**
     * Creates a SparseArray mapping constant values to their String representations
     * directly from this class.
     */
    private SparseArray<String> createConstantValueToNameMapping() {
        SparseArray<String> constantValueToName = new SparseArray<>();
        for (int i = 0; i < mConstantKey.clazz().getDeclaredFields().length; i++) {
            Field candidateField = mConstantKey.clazz().getDeclaredFields()[i];
            try {
                if (isMatchingConstant(candidateField)) {
                    constantValueToName.put(candidateField.getInt(null),
                            getConstantName(candidateField));
                }
            } catch (IllegalAccessException e) {
                Slog.wtf(TAG, "Failed trying to find value for " + candidateField.getName(), e);
            }
        }
        return constantValueToName;
    }

    private boolean isMatchingConstant(Field field) {
        int modifiers = field.getModifiers();
        // Checks for "public static final int PREFIX_".
        return !(!Modifier.isPublic(modifiers)
                || !Modifier.isStatic(modifiers)
                || !Modifier.isFinal(modifiers)
                || !field.getType().equals(int.class)
                || (!mConstantKey.prefix().isEmpty()
                && !field.getName().startsWith(mConstantKey.prefix())));
    }

    private String getConstantName(Field field) {
        return field.getName().substring(mConstantKey.prefix().length());
    }

    private record ConstantKey(Class<?> clazz, String prefix) {
        ConstantKey(Class<?> clazz) {
            this(clazz, "");
        }
    }
}
