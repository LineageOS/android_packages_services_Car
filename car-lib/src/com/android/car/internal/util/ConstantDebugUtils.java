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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utility class to convert integer constants to and from their value or name.
 */
public final class ConstantDebugUtils {
    private static final String TAG = ConstantDebugUtils.class.getSimpleName();
    private static final Map<ConstantKey, ConstantClazz>
            CONSTANT_KEY_TO_CONSTANT_CLAZZ = new ConcurrentHashMap<>();

    @ExcludeFromCodeCoverageGeneratedReport(reason = PRIVATE_CONSTRUCTOR)
    private ConstantDebugUtils() {
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
        return getConstantClazz(new ConstantKey(clazz, prefix)).toName(value);
    }

    /**
     * Gets the constant's value based on the passed {@code name} for the specified {@code clazz}.
     * Returns {@code null} if name does not exist.
     */
    @Nullable
    public static Integer toValue(Class<?> clazz, String name) {
        return getConstantClazz(new ConstantKey(clazz)).toValue(name);
    }

    /**
     * Gets the all the constant values for the specified {@code clazz}.
     */
    public static List<Integer> getValues(Class<?> clazz) {
        return getValues(clazz, "");
    }

    /**
     * Gets the all the constant values for the specified {@code clazz} and {@code prefix} combo.
     */
    public static List<Integer> getValues(Class<?> clazz, String prefix) {
        return getConstantClazz(new ConstantKey(clazz, prefix)).getConstantValuesList();
    }

    private static ConstantClazz getConstantClazz(ConstantKey constantKey) {
        return CONSTANT_KEY_TO_CONSTANT_CLAZZ.computeIfAbsent(constantKey,
                k -> new ConstantClazz(constantKey));
    }

    private static class ConstantClazz {
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
        private final AtomicReference<SparseArray<String>> mValueToNameHolder =
                new AtomicReference<>();
        /*
         * Used to cache the list of constant values.
         */
        private final AtomicReference<List<Integer>> mValuesHolder = new AtomicReference<>();

        @ExcludeFromCodeCoverageGeneratedReport(reason = PRIVATE_CONSTRUCTOR)
        private ConstantClazz(ConstantKey constantKey) {
            mConstantKey = constantKey;
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

        private List<Integer> getConstantValuesList() {
            List<Integer> values = mValuesHolder.get();
            if (values == null) {
                values = createConstantValuesList();
                mValuesHolder.compareAndSet(null, values);
            }
            return values;
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

        /**
         * Creates a list of the constant values.
         */
        private List<Integer> createConstantValuesList() {
            List<Integer> values = new ArrayList<>();
            for (int i = 0; i < mConstantKey.clazz().getDeclaredFields().length; i++) {
                Field candidateField = mConstantKey.clazz().getDeclaredFields()[i];
                try {
                    if (isMatchingConstant(candidateField)) {
                        values.add(candidateField.getInt(null));
                    }
                } catch (IllegalAccessException e) {
                    Slog.wtf(TAG, "Failed trying to find value for " + candidateField.getName(), e);
                }
            }
            values.sort(Collections.reverseOrder());
            return values;
        }

        private boolean isMatchingConstant(Field field) {
            int modifiers = field.getModifiers();

            // Checks for "public static final int PREFIX_".
            return !(!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers) || !field.getType().equals(int.class) || (
                    !mConstantKey.prefix().isEmpty() && !field.getName().startsWith(
                            mConstantKey.prefix())));
        }

        private String getConstantName(Field field) {
            return field.getName().substring(mConstantKey.prefix().length());
        }
    }

    private record ConstantKey(Class<?> clazz, String prefix) {
        ConstantKey(Class<?> clazz) {
            this(clazz, "");
        }
    }
}
