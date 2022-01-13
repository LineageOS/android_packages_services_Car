/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.vehiclehal;

import android.hardware.automotive.vehicle.V2_0.DiagnosticFloatSensorIndex;
import android.hardware.automotive.vehicle.V2_0.DiagnosticIntegerSensorIndex;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;

public final class DiagnosticJsonTestUtils {

    public static final int ANY_TIMESTAMP_VALUE = 1;

    public static VehiclePropValue buildEmptyVehiclePropertyValue(int expectedProperty,
            int expectedTimestamp) {
        VehiclePropValue expected = new VehiclePropValue();
        expected.prop = expectedProperty;
        expected.timestamp = expectedTimestamp;
        expected.value = new VehiclePropValue.RawValue();
        for (int i = 0; i < DiagnosticIntegerSensorIndex.LAST_SYSTEM_INDEX + 1; i++) {
            expected.value.int32Values.add(0);
        }
        for (int i = 0; i < DiagnosticFloatSensorIndex.LAST_SYSTEM_INDEX + 1; i++) {
            expected.value.floatValues.add(0f);
        }
        expected.value.stringValue = null;
        return expected;
    }

    private DiagnosticJsonTestUtils() {
    }
}
