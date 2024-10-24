/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.car.VehicleGear;

import androidx.test.filters.SmallTest;

import com.android.compatibility.common.util.ApiTest;

import org.junit.Test;

@SmallTest
public final class VehicleGearTest extends CarLessApiTestBase {

    @Test
    @ApiTest(apis = {"android.car.VehicleGear#GEAR_UNKNOWN", "android.car.VehicleGear#GEAR_NEUTRAL",
            "android.car.VehicleGear#GEAR_REVERSE", "android.car.VehicleGear#GEAR_DRIVE",
            "android.car.VehicleGear#GEAR_FIRST", "android.car.VehicleGear#GEAR_SECOND",
            "android.car.VehicleGear#GEAR_THIRD", "android.car.VehicleGear#GEAR_FOURTH",
            "android.car.VehicleGear#GEAR_FIFTH", "android.car.VehicleGear#GEAR_SIXTH",
            "android.car.VehicleGear#GEAR_SEVENTH", "android.car.VehicleGear#GEAR_EIGHTH",
            "android.car.VehicleGear#GEAR_NINTH"})
    public void testMatchWithVehicleHal() {
        assertThat(VehicleGear.GEAR_UNKNOWN)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_UNKNOWN);

        assertThat(VehicleGear.GEAR_NEUTRAL)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_NEUTRAL);

        assertThat(VehicleGear.GEAR_REVERSE)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_REVERSE);

        assertThat(VehicleGear.GEAR_PARK)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_PARK);

        assertThat(VehicleGear.GEAR_DRIVE)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_DRIVE);

        assertThat(VehicleGear.GEAR_FIRST)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_1);

        assertThat(VehicleGear.GEAR_SECOND)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_2);

        assertThat(VehicleGear.GEAR_THIRD)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_3);

        assertThat(VehicleGear.GEAR_FOURTH)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_4);

        assertThat(VehicleGear.GEAR_FIFTH)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_5);

        assertThat(VehicleGear.GEAR_SIXTH)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_6);

        assertThat(VehicleGear.GEAR_SEVENTH)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_7);

        assertThat(VehicleGear.GEAR_EIGHTH)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_8);

        assertThat(VehicleGear.GEAR_NINTH)
                .isEqualTo(android.hardware.automotive.vehicle.VehicleGear.GEAR_9);
    }

    @Test
    @ApiTest(apis = {"android.car.VehicleGear#toString"})
    public void testToString() {
        assertThat(VehicleGear.toString(VehicleGear.GEAR_UNKNOWN)).isEqualTo("GEAR_UNKNOWN");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_NEUTRAL)).isEqualTo("GEAR_NEUTRAL");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_REVERSE)).isEqualTo("GEAR_REVERSE");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_PARK)).isEqualTo("GEAR_PARK");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_DRIVE)).isEqualTo("GEAR_DRIVE");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_FIRST)).isEqualTo("GEAR_FIRST");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_SECOND)).isEqualTo("GEAR_SECOND");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_THIRD)).isEqualTo("GEAR_THIRD");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_FOURTH)).isEqualTo("GEAR_FOURTH");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_FIFTH)).isEqualTo("GEAR_FIFTH");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_SIXTH)).isEqualTo("GEAR_SIXTH");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_SEVENTH)).isEqualTo("GEAR_SEVENTH");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_EIGHTH)).isEqualTo("GEAR_EIGHTH");

        assertThat(VehicleGear.toString(VehicleGear.GEAR_NINTH)).isEqualTo("GEAR_NINTH");

        assertThat(VehicleGear.toString(3)).isEqualTo("0x3");

        assertThat(VehicleGear.toString(12)).isEqualTo("0xc");
    }
}
