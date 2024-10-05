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

import static com.google.common.truth.Truth.assertWithMessage;

import javax.annotation.concurrent.ThreadSafe;

/**
 * A generic property handler that fails for all operations.
 */
@ThreadSafe
public class GenericFailingPropertyHandler<ValueType>
        implements GenericVehicleHalPropertyHandler<ValueType> {
    @Override
    public void onPropertySet(ValueType value) {
        assertWithMessage("Unexpected onPropertySet call").fail();
    }

    @Override
    public ValueType onPropertyGet(ValueType value) {
        assertWithMessage("Unexpected onPropertyGet call").fail();
        return null;
    }

    @Override
    public void onPropertySubscribe(int property, float sampleRate) {
        assertWithMessage("Unexpected onPropertySubscribe call").fail();
    }

    @Override
    public void onPropertyUnsubscribe(int property) {
        assertWithMessage("Unexpected onPropertyUnsubscribe call").fail();
    }
}
