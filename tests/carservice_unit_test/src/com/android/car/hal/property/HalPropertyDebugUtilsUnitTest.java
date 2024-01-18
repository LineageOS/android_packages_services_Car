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

package com.android.car.hal.property;

import static com.android.car.hal.property.HalPropertyDebugUtils.toAccessString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toAreaTypeString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toChangeModeString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toGroupString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toStatusString;
import static com.android.car.hal.property.HalPropertyDebugUtils.toValueTypeString;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.automotive.vehicle.FuelType;
import android.hardware.automotive.vehicle.VehicleGear;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.VehiclePropertyGroup;
import android.hardware.automotive.vehicle.VehiclePropertyStatus;
import android.hardware.automotive.vehicle.VehiclePropertyType;

import com.android.car.hal.HalPropValueBuilder;

import org.junit.Test;

public class HalPropertyDebugUtilsUnitTest {

    @Test
    public void testToPropertyIdString() {
        assertThat(HalPropertyDebugUtils.toPropertyIdString(VehicleProperty.ABS_ACTIVE)).isEqualTo(
                "ABS_ACTIVE(0x1120040a)");
        assertThat(HalPropertyDebugUtils.toPropertyIdString(
                VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS)).isEqualTo(
                "VEHICLE_SPEED_DISPLAY_UNITS(0x11400605)");
        assertThat(HalPropertyDebugUtils.toPropertyIdString(VehiclePropertyGroup.VENDOR)).isEqualTo(
                "VENDOR_PROPERTY(0x" + Integer.toHexString(VehiclePropertyGroup.VENDOR) + ")");
        assertThat(HalPropertyDebugUtils.toPropertyIdString(-1)).isEqualTo(
                "INVALID_PROPERTY_ID(0x" + Integer.toHexString(-1) + ")");
    }

    @Test
    public void testToPropertyId() {
        assertThat(HalPropertyDebugUtils.toPropertyId("ABS_ACTIVE")).isEqualTo(
                VehicleProperty.ABS_ACTIVE);
        assertThat(HalPropertyDebugUtils.toPropertyId("VEHICLE_SPEED_DISPLAY_UNITS")).isEqualTo(
                VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS);
        assertThat(HalPropertyDebugUtils.toPropertyId("saljflsadj")).isNull();
    }

    @Test
    public void testToValueString() {
        assertThat(HalPropertyDebugUtils.toValueString(
                new HalPropValueBuilder(true).build(VehicleProperty.PARKING_BRAKE_ON, 0,
                        0))).isEqualTo("FALSE");
        assertThat(HalPropertyDebugUtils.toValueString(
                new HalPropValueBuilder(true).build(VehicleProperty.GEAR_SELECTION, 0,
                        VehicleGear.GEAR_9))).isEqualTo("GEAR_9(0x1000)");
        assertThat(HalPropertyDebugUtils.toValueString(
                new HalPropValueBuilder(true).build(VehicleProperty.STEERING_WHEEL_DEPTH_POS, 0,
                        87))).isEqualTo("87");
        assertThat(HalPropertyDebugUtils.toValueString(
                new HalPropValueBuilder(true).build(VehicleProperty.INFO_FUEL_TYPE, 0,
                        FuelType.FUEL_TYPE_E85))).isEqualTo("[FUEL_TYPE_E85(0x6)]");
        assertThat(HalPropertyDebugUtils.toValueString(
                new HalPropValueBuilder(true).build(VehicleProperty.INFO_FUEL_TYPE, 0,
                        new int[]{FuelType.FUEL_TYPE_E85, FuelType.FUEL_TYPE_LPG,
                                FuelType.FUEL_TYPE_LEADED}))).isEqualTo(
                "[FUEL_TYPE_E85(0x6), FUEL_TYPE_LPG(0x7), FUEL_TYPE_LEADED(0x2)]");
        assertThat(HalPropertyDebugUtils.toValueString(
                new HalPropValueBuilder(true).build(VehicleProperty.PERF_VEHICLE_SPEED, 0,
                        11.1f))).isEqualTo("11.1");
        assertThat(HalPropertyDebugUtils.toValueString(new HalPropValueBuilder(true).build(
                VehicleProperty.HVAC_TEMPERATURE_VALUE_SUGGESTION, 0,
                new float[]{11.1f, 33.3f}))).isEqualTo("[11.1, 33.3]");
        assertThat(HalPropertyDebugUtils.toValueString(
                new HalPropValueBuilder(true).build(VehicleProperty.VHAL_HEARTBEAT, 0,
                        -1L))).isEqualTo("-1");
        assertThat(HalPropertyDebugUtils.toValueString(
                new HalPropValueBuilder(true).build(VehicleProperty.WHEEL_TICK, 0,
                        new long[]{-1, -2}))).isEqualTo("[-1, -2]");
        assertThat(HalPropertyDebugUtils.toValueString(
                new HalPropValueBuilder(true).build(VehicleProperty.INFO_MAKE, 0,
                        "testMake"))).isEqualTo("testMake");
        assertThat(HalPropertyDebugUtils.toValueString(
                new HalPropValueBuilder(true).build(VehicleProperty.STORAGE_ENCRYPTION_BINDING_SEED,
                        0, new byte[]{}))).isEqualTo("[]");
        assertThat(HalPropertyDebugUtils.toValueString(
                new HalPropValueBuilder(true).build(VehicleProperty.VEHICLE_MAP_SERVICE, 0,
                        new byte[]{}))).isEqualTo(
                "floatValues: [], int32Values: [], int64Values: [], bytes: [], string: ");
    }

    @Test
    public void testToAreaTypeString() {
        assertThat(toAreaTypeString(VehicleProperty.HVAC_AC_ON)).isEqualTo("SEAT(0x5000000)");
        assertThat(toAreaTypeString(VehicleProperty.TIRE_PRESSURE)).isEqualTo("WHEEL(0x7000000)");
        assertThat(toAreaTypeString(VehiclePropertyType.STRING)).isEqualTo(
                "INVALID_VehicleArea(0x0)");
    }

    @Test
    public void testToGroupString() {
        assertThat(toGroupString(VehicleProperty.CURRENT_GEAR)).isEqualTo("SYSTEM(0x10000000)");
        assertThat(toGroupString(VehiclePropertyGroup.VENDOR)).isEqualTo("VENDOR(0x20000000)");
        assertThat(toGroupString(VehiclePropertyType.STRING)).isEqualTo(
                "INVALID_VehiclePropertyGroup(0x0)");
    }

    @Test
    public void testToValueTypeString() {
        assertThat(toValueTypeString(VehicleProperty.CURRENT_GEAR)).isEqualTo("INT32(0x400000)");
        assertThat(toValueTypeString(VehicleProperty.WHEEL_TICK)).isEqualTo("INT64_VEC(0x510000)");
        assertThat(toValueTypeString(VehiclePropertyGroup.VENDOR)).isEqualTo(
                "INVALID_VehiclePropertyType(0x0)");
    }

    @Test
    public void testToAccessString() {
        assertThat(toAccessString(VehiclePropertyAccess.READ_WRITE)).isEqualTo("READ_WRITE(0x3)");
        assertThat(toAccessString(-1)).isEqualTo("INVALID_VehiclePropertyAccess(0xffffffff)");
    }

    @Test
    public void testToChangeModeString() {
        assertThat(toChangeModeString(VehiclePropertyChangeMode.CONTINUOUS)).isEqualTo(
                "CONTINUOUS(0x2)");
        assertThat(toChangeModeString(-1)).isEqualTo(
                "INVALID_VehiclePropertyChangeMode(0xffffffff)");
    }

    @Test
    public void testToStatusString() {
        assertThat(toStatusString(VehiclePropertyStatus.ERROR)).isEqualTo(
                "ERROR(0x2)");
        assertThat(toStatusString(-1)).isEqualTo(
                "INVALID_VehiclePropertyStatus(0xffffffff)");
    }
}
