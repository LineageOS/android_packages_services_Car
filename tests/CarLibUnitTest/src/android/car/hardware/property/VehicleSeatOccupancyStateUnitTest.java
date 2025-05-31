/*
 * Copyright (C) 2025 The Android Open Source Project
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
package android.car.hardware.property;

import static android.car.feature.Flags.FLAG_VEHICLE_PROPERTY_ENUMS_REMOVE_SYSTEM_API_TAGS;

import static com.google.common.truth.Truth.assertWithMessage;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

@SmallTest
@RunWith(Parameterized.class)
public class VehicleSeatOccupancyStateUnitTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private final int mJavaConstantValue;
    private final int mHalConstantValue;

    public VehicleSeatOccupancyStateUnitTest(int javaConstantValue, int halConstantValue) {
        mJavaConstantValue = javaConstantValue;
        mHalConstantValue = halConstantValue;
    }

    @Parameterized.Parameters
    public static Collection constantValues() {
        return Arrays.asList(
                new Object[][] {
                        {
                                android.car.VehicleSeatOccupancyState.UNKNOWN,
                                android.hardware.automotive.vehicle.VehicleSeatOccupancyState
                                        .UNKNOWN
                        },
                        {
                                android.car.VehicleSeatOccupancyState.VACANT,
                                android.hardware.automotive.vehicle.VehicleSeatOccupancyState.VACANT
                        },
                        {
                                android.car.VehicleSeatOccupancyState.OCCUPIED,
                                android.hardware.automotive.vehicle.VehicleSeatOccupancyState
                                        .OCCUPIED
                        },
                });
    }

    @Test
    @RequiresFlagsEnabled(FLAG_VEHICLE_PROPERTY_ENUMS_REMOVE_SYSTEM_API_TAGS)
    public void testMatchWithVehicleHal() {
        assertWithMessage("Java constant")
                .that(mJavaConstantValue)
                .isEqualTo(mHalConstantValue);
    }
}
