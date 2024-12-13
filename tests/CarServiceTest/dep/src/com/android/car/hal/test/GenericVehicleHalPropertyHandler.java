/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.car.hal.test;

import android.annotation.Nullable;

import java.util.List;

/**
 * An interface used to control fake VHAL behavior in tests.
 */
public interface GenericVehicleHalPropertyHandler<ValueType> {
    /**
     * Called when setting a property value.
     */
    default void onPropertySet(ValueType value) {}

    /**
     * Called when getting a property value.
     */
    default ValueType onPropertyGet(ValueType value) {
        return null;
    }

    /**
     * Called when subscribing to a property.
     */
    default void onPropertySubscribe(int property, float sampleRate) {}

    /**
     * Called when unsubscribing to a property.
     */
    default void onPropertyUnsubscribe(int property) {}

    /**
     * Called for getMinMaxSupportedValue.
     *
     * Must return an array that contains two elements, one for min, one for max. If not specified,
     * the element can be null.
     */
    ValueType[] onGetMinMaxSupportedValue(int propertyId, int areaId);

    /**
     * Called for getSupportedValuesLists.
     *
     * If no supported values list is specified, return null.
     */
    default @Nullable List<ValueType> onGetSupportedValuesList(int propertyId, int areaId) {
        return null;
    }
}
