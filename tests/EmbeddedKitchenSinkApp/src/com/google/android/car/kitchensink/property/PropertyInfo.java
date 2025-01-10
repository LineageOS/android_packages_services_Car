/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.car.kitchensink.property;

import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyConfig;
import android.hardware.automotive.vehicle.TestVendorProperty;
import android.util.ArraySet;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;

class PropertyInfo implements Comparable<PropertyInfo> {
    private static final Set<Integer> TEST_VENDOR_PROPERTY_IDS = getTestVendorPropertyIds();
    private static final String TAG = "PropertyTestFragment";

    public final CarPropertyConfig mConfig;
    public final String mName;
    public final int mPropId;

    PropertyInfo(CarPropertyConfig config) {
        mConfig = config;
        mPropId = config.getPropertyId();
        mName = getPropertyName(mPropId);
    }

    /**
     * Gets the human-readable name for the property.
     *
     * <p>This function translates both systme property IDs and test vendor property IDs to
     * human-readable string.
     */
    public static String getPropertyName(int propertyId) {
        if (TEST_VENDOR_PROPERTY_IDS.contains(propertyId)) {
            return TestVendorProperty.$.toString(propertyId);
        }
        return VehiclePropertyIds.toString(propertyId);
    }

    @Override
    public String toString() {
        return mName;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof PropertyInfo) {
            return ((PropertyInfo) other).mPropId == mPropId;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mPropId;
    }

    @Override
    public int compareTo(PropertyInfo propertyInfo) {
        return mName.compareTo(propertyInfo.mName);
    }

    private static Set<Integer> getTestVendorPropertyIds() {
        Set<Integer> testVendorPropertyIds = new ArraySet<>();
        for (int i = 0; i < TestVendorProperty.class.getDeclaredFields().length; i++) {
            Field candidateField = TestVendorProperty.class.getDeclaredFields()[i];
            try {
                if (isMatchingConstant(candidateField)) {
                    testVendorPropertyIds.add(candidateField.getInt(null));
                }
            } catch (IllegalAccessException e) {
                Log.wtf(TAG, "Failed trying to find value for " + candidateField.getName(), e);
            }
        }
        return testVendorPropertyIds;
    }

    private static boolean isMatchingConstant(Field field) {
        int modifiers = field.getModifiers();
        return !(!Modifier.isPublic(modifiers)
                || !Modifier.isStatic(modifiers)
                || !Modifier.isFinal(modifiers)
                || !field.getType().equals(int.class));
    }
}
