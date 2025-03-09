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
package android.car.apitest;

import static android.car.feature.Flags.FLAG_ANDROID_B_VEHICLE_PROPERTIES;

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
public class VehicleSizeClassTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private final int mJavaConstantValue;
    private final int mHalConstantValue;

    public VehicleSizeClassTest(int javaConstantValue, int halConstantValue) {
        mJavaConstantValue = javaConstantValue;
        mHalConstantValue = halConstantValue;
    }

    @Parameterized.Parameters
    public static Collection constantValues() {
        return Arrays.asList(
                new Object[][] {
                        {
                                android.car.hardware.property.VehicleSizeClass.EPA_TWO_SEATER,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EPA_TWO_SEATER
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EPA_MINICOMPACT,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EPA_MINICOMPACT
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EPA_SUBCOMPACT,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EPA_SUBCOMPACT
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EPA_COMPACT,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EPA_COMPACT
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EPA_MIDSIZE,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EPA_MIDSIZE
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EPA_LARGE,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EPA_LARGE
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass
                                        .EPA_SMALL_STATION_WAGON,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .EPA_SMALL_STATION_WAGON
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass
                                        .EPA_MIDSIZE_STATION_WAGON,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .EPA_MIDSIZE_STATION_WAGON
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass
                                        .EPA_LARGE_STATION_WAGON,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .EPA_LARGE_STATION_WAGON
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass
                                        .EPA_SMALL_PICKUP_TRUCK,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .EPA_SMALL_PICKUP_TRUCK
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass
                                        .EPA_STANDARD_PICKUP_TRUCK,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .EPA_STANDARD_PICKUP_TRUCK
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EPA_VAN,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EPA_VAN
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EPA_MINIVAN,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EPA_MINIVAN
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EPA_SMALL_SUV,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EPA_SMALL_SUV
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EPA_STANDARD_SUV,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .EPA_STANDARD_SUV
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EU_A_SEGMENT,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EU_A_SEGMENT
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EU_B_SEGMENT,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EU_B_SEGMENT
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EU_C_SEGMENT,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EU_C_SEGMENT
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EU_D_SEGMENT,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EU_D_SEGMENT
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EU_E_SEGMENT,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EU_E_SEGMENT
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EU_F_SEGMENT,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EU_F_SEGMENT
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EU_J_SEGMENT,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EU_J_SEGMENT
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EU_M_SEGMENT,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EU_M_SEGMENT
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.EU_S_SEGMENT,
                                android.hardware.automotive.vehicle.VehicleSizeClass.EU_S_SEGMENT
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.JPN_KEI,
                                android.hardware.automotive.vehicle.VehicleSizeClass.JPN_KEI
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.JPN_SMALL_SIZE,
                                android.hardware.automotive.vehicle.VehicleSizeClass.JPN_SMALL_SIZE
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.JPN_NORMAL_SIZE,
                                android.hardware.automotive.vehicle.VehicleSizeClass.JPN_NORMAL_SIZE
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.US_GVWR_CLASS_1_CV,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .US_GVWR_CLASS_1_CV
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.US_GVWR_CLASS_2_CV,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .US_GVWR_CLASS_2_CV
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.US_GVWR_CLASS_3_CV,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .US_GVWR_CLASS_3_CV
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.US_GVWR_CLASS_4_CV,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .US_GVWR_CLASS_4_CV
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.US_GVWR_CLASS_5_CV,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .US_GVWR_CLASS_5_CV
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.US_GVWR_CLASS_6_CV,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .US_GVWR_CLASS_6_CV
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.US_GVWR_CLASS_7_CV,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .US_GVWR_CLASS_7_CV
                        },
                        {
                                android.car.hardware.property.VehicleSizeClass.US_GVWR_CLASS_8_CV,
                                android.hardware.automotive.vehicle.VehicleSizeClass
                                        .US_GVWR_CLASS_8_CV
                        },
                });
    }

    @Test
    @RequiresFlagsEnabled(FLAG_ANDROID_B_VEHICLE_PROPERTIES)
    public void testMatchWithVehicleHal() {
        assertWithMessage("Java constant")
                .that(mJavaConstantValue)
                .isEqualTo(mHalConstantValue);
    }
}
