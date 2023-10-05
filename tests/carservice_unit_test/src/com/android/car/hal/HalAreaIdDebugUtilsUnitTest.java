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

package com.android.car.hal;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.automotive.vehicle.VehicleAreaDoor;
import android.hardware.automotive.vehicle.VehicleAreaMirror;
import android.hardware.automotive.vehicle.VehicleAreaSeat;
import android.hardware.automotive.vehicle.VehicleAreaWheel;
import android.hardware.automotive.vehicle.VehicleAreaWindow;
import android.hardware.automotive.vehicle.VehicleProperty;

import org.junit.Test;

public class HalAreaIdDebugUtilsUnitTest {

    @Test
    public void testToDebugString_handlesGlobalAreaType() {
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.ABS_ACTIVE, /*areaId=*/
                0)).isEqualTo("GLOBAL");
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.ABS_ACTIVE, /*areaId=*/
                1)).isEqualTo("INVALID_GLOBAL_AREA_TYPE(0x1)");
    }

    @Test
    public void testToDebugString_handlesDoorAreaType() {
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.DOOR_POS,
                android.hardware.automotive.vehicle.VehicleAreaDoor.REAR)).isEqualTo("REAR");
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.DOOR_POS,
                VehicleAreaDoor.REAR | VehicleAreaDoor.HOOD)).isEqualTo("REAR|HOOD");
    }

    @Test
    public void testToDebugString_handlesSeatAreaType() {
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.HVAC_POWER_ON,
                VehicleAreaSeat.UNKNOWN)).isEqualTo("UNKNOWN");
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.HVAC_POWER_ON,
                VehicleAreaSeat.ROW_1_LEFT | VehicleAreaSeat.ROW_2_RIGHT)).isEqualTo(
                "ROW_2_RIGHT|ROW_1_LEFT");
    }

    @Test
    public void testToDebugString_handlesMirrorAreaType() {
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.MIRROR_Y_MOVE,
                VehicleAreaMirror.DRIVER_CENTER)).isEqualTo("DRIVER_CENTER");
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.MIRROR_Y_MOVE,
                VehicleAreaMirror.DRIVER_LEFT | VehicleAreaMirror.DRIVER_RIGHT)).isEqualTo(
                "DRIVER_RIGHT|DRIVER_LEFT");
    }

    @Test
    public void testToDebugString_handlesWheelAreaType() {
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.TIRE_PRESSURE,
                VehicleAreaWheel.UNKNOWN)).isEqualTo("UNKNOWN");
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.TIRE_PRESSURE,
                VehicleAreaWheel.LEFT_REAR | VehicleAreaWheel.RIGHT_REAR)).isEqualTo(
                "RIGHT_REAR|LEFT_REAR");
    }

    @Test
    public void testToDebugString_handlesWindowAreaType() {
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.WINDOW_POS,
                VehicleAreaWindow.FRONT_WINDSHIELD)).isEqualTo("FRONT_WINDSHIELD");
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.WINDOW_POS,
                VehicleAreaWindow.ROOF_TOP_1 | VehicleAreaWindow.ROOF_TOP_2)).isEqualTo(
                "ROOF_TOP_2|ROOF_TOP_1");
    }

    @Test
    public void testToDebugString_handlesUnknownAreaType() {
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.INVALID, /*areadId=*/
                0)).isEqualTo("UNKNOWN_AREA_TYPE(0x0)");
    }

    @Test
    public void testToDebugString_handlesInvalidAreaTypeUndefinedBit() {
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.MIRROR_Y_POS,
                VehicleAreaSeat.ROW_3_RIGHT)).isEqualTo("INVALID_VehicleAreaMirror(0x400)");
    }

    @Test
    public void testToDebugString_handlesInvalidAreaTypeNoDefinedBits() {
        assertThat(HalAreaIdDebugUtils.toDebugString(VehicleProperty.MIRROR_Y_MOVE, /*areaId=*/
                0)).isEqualTo("INVALID_VehicleAreaMirror(0x0)");
    }
}
