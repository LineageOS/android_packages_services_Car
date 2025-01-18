/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.car.internal.property.CarPropertyHelper.newPropIdAreaId;

import static com.google.common.truth.Truth.assertThat;

import android.car.VehicleAreaDoor;
import android.car.VehicleAreaMirror;
import android.car.VehicleAreaSeat;
import android.car.VehicleAreaWheel;
import android.car.VehicleAreaWindow;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarHvacFanDirection;
import android.hardware.automotive.vehicle.StatusCode;
import android.hardware.automotive.vehicle.UserInfo;
import android.hardware.automotive.vehicle.V2_0.UserFlags;

import org.junit.Test;

import java.util.List;

public final class DebugUtilsUnitTest {

    @Test
    public void aidlConstantToString() {
        assertThat(DebugUtils.constantToString(StatusCode.class, StatusCode.OK)).isEqualTo("OK");
        assertThat(DebugUtils.constantToString(StatusCode.class, StatusCode.TRY_AGAIN)).isEqualTo(
                "TRY_AGAIN");
        assertThat(
                DebugUtils.constantToString(StatusCode.class, StatusCode.NOT_AVAILABLE)).isEqualTo(
                "NOT_AVAILABLE");
        assertThat(
                DebugUtils.constantToString(StatusCode.class, StatusCode.ACCESS_DENIED)).isEqualTo(
                "ACCESS_DENIED");
        assertThat(
                DebugUtils.constantToString(StatusCode.class, StatusCode.INTERNAL_ERROR)).isEqualTo(
                "INTERNAL_ERROR");
    }

    @Test
    public void aidlFlagsToString() {
        assertThat(DebugUtils.flagsToString(UserInfo.class, "USER_FLAG_", 0)).isEqualTo("0x0");
        assertThat(DebugUtils.flagsToString(UserInfo.class, "USER_FLAG_",
                UserInfo.USER_FLAG_SYSTEM)).isEqualTo("SYSTEM");
        assertThat(DebugUtils.flagsToString(UserInfo.class, "USER_FLAG_",
                UserInfo.USER_FLAG_SYSTEM | UserInfo.USER_FLAG_ADMIN)).isAnyOf("ADMIN|SYSTEM",
                "SYSTEM|ADMIN");
    }

    @Test
    public void hidlConstantToString() {
        assertThat(DebugUtils.constantToString(
                android.hardware.automotive.vehicle.V2_0.StatusCode.class,
                android.hardware.automotive.vehicle.V2_0.StatusCode.OK)).isEqualTo("OK");
        assertThat(DebugUtils.constantToString(
                android.hardware.automotive.vehicle.V2_0.StatusCode.class,
                android.hardware.automotive.vehicle.V2_0.StatusCode.TRY_AGAIN)).isEqualTo(
                "TRY_AGAIN");
        assertThat(DebugUtils.constantToString(
                android.hardware.automotive.vehicle.V2_0.StatusCode.class,
                android.hardware.automotive.vehicle.V2_0.StatusCode.NOT_AVAILABLE)).isEqualTo(
                "NOT_AVAILABLE");
        assertThat(DebugUtils.constantToString(
                android.hardware.automotive.vehicle.V2_0.StatusCode.class,
                android.hardware.automotive.vehicle.V2_0.StatusCode.ACCESS_DENIED)).isEqualTo(
                "ACCESS_DENIED");
        assertThat(DebugUtils.constantToString(
                android.hardware.automotive.vehicle.V2_0.StatusCode.class,
                android.hardware.automotive.vehicle.V2_0.StatusCode.INTERNAL_ERROR)).isEqualTo(
                "INTERNAL_ERROR");
    }

    @Test
    public void hidlFlagsToString() {
        assertThat(DebugUtils.flagsToString(UserFlags.class, "USER_FLAG_", 0)).isEqualTo("0x0");
        assertThat(DebugUtils.flagsToString(UserFlags.class, "", UserFlags.SYSTEM)).isEqualTo(
                "SYSTEM");
        assertThat(DebugUtils.flagsToString(UserFlags.class, "",
                UserFlags.SYSTEM | UserFlags.ADMIN)).isAnyOf("ADMIN|SYSTEM", "SYSTEM|ADMIN");
    }

    @Test
    public void testFlagsToString_handlesInvalidAreaId() {
        assertThat(DebugUtils.flagsToString(VehicleAreaDoor.class, "", /*areaId=*/
                0)).isEqualTo("0x0");
    }

    @Test
    public void testFlagsToString_handlesZeroBitFlag() {
        assertThat(DebugUtils.flagsToString(CarHvacFanDirection.class, "", /*areaId=*/
                CarHvacFanDirection.UNKNOWN)).isEqualTo("UNKNOWN");
    }

    @Test
    public void testFlagsToString_handlesPartiallyInvalidAreaId() {
        assertThat(DebugUtils.flagsToString(VehicleAreaMirror.class, "", /*areaId=*/
                VehicleAreaMirror.MIRROR_DRIVER_CENTER
                        | VehicleAreaSeat.SEAT_ROW_3_RIGHT)).isEqualTo(
                "MIRROR_DRIVER_CENTER|0x400");
    }

    @Test
    public void testFlagsToString_handlesSingleBitAreaId() {
        assertThat(DebugUtils.flagsToString(VehicleAreaDoor.class, "", /*areaId=*/
                VehicleAreaDoor.DOOR_HOOD)).isEqualTo("DOOR_HOOD");
    }

    @Test
    public void testFlagsToString_handlesMultipleBitAreaId() {
        assertThat(DebugUtils.flagsToString(VehicleAreaDoor.class, "", /*areaId=*/
                VehicleAreaDoor.DOOR_HOOD | VehicleAreaDoor.DOOR_ROW_1_RIGHT)).isEqualTo(
                "DOOR_HOOD|DOOR_ROW_1_RIGHT");
    }

    @Test
    public void testFlagsToString_handlesMultipleBitAreaIdWithPrefix() {
        assertThat(DebugUtils.flagsToString(VehicleAreaDoor.class, "DOOR_", /*areaId=*/
                VehicleAreaDoor.DOOR_HOOD | VehicleAreaDoor.DOOR_ROW_1_RIGHT)).isEqualTo(
                "HOOD|ROW_1_RIGHT");
    }

    @Test
    public void testFlagsToOptionalString_handlesNoPrefix() {
        assertThat(DebugUtils.flagsToOptionalString(VehicleAreaDoor.class, /*areaId=*/
                VehicleAreaDoor.DOOR_HOOD | VehicleAreaDoor.DOOR_ROW_1_RIGHT)).isEqualTo(
                "DOOR_HOOD|DOOR_ROW_1_RIGHT");
    }

    @Test
    public void testFlagsOptionalToString_handlesInvalidAreaId() {
        assertThat(DebugUtils.flagsToOptionalString(VehicleAreaDoor.class, "", /*areaId=*/
                0)).isNull();
    }

    @Test
    public void testFlagsToOptionalString_handlesZeroBitFlag() {
        assertThat(DebugUtils.flagsToOptionalString(CarHvacFanDirection.class, "", /*areaId=*/
                CarHvacFanDirection.UNKNOWN)).isEqualTo("UNKNOWN");
    }

    @Test
    public void testFlagsToOptionalString_handlesPartiallyInvalidAreaId() {
        assertThat(DebugUtils.flagsToOptionalString(VehicleAreaMirror.class, "", /*areaId=*/
                VehicleAreaMirror.MIRROR_DRIVER_CENTER
                        | VehicleAreaSeat.SEAT_ROW_3_RIGHT)).isEqualTo(
                "MIRROR_DRIVER_CENTER|0x400");
    }

    @Test
    public void testFlagsToOptionalString_handlesSingleBitAreaId() {
        assertThat(DebugUtils.flagsToOptionalString(VehicleAreaDoor.class, "", /*areaId=*/
                VehicleAreaDoor.DOOR_HOOD)).isEqualTo("DOOR_HOOD");
    }

    @Test
    public void testFlagsToOptionalString_handlesMultipleBitAreaId() {
        assertThat(DebugUtils.flagsToOptionalString(VehicleAreaDoor.class, "", /*areaId=*/
                VehicleAreaDoor.DOOR_HOOD | VehicleAreaDoor.DOOR_ROW_1_RIGHT)).isEqualTo(
                "DOOR_HOOD|DOOR_ROW_1_RIGHT");
    }

    @Test
    public void testFlagsToOptionalString_handlesMultipleBitAreaIdWithPrefix() {
        assertThat(DebugUtils.flagsToOptionalString(VehicleAreaDoor.class, "DOOR_", /*areaId=*/
                VehicleAreaDoor.DOOR_HOOD | VehicleAreaDoor.DOOR_ROW_1_RIGHT)).isEqualTo(
                "HOOD|ROW_1_RIGHT");
    }

    @Test
    public void testToAreaIdString_handlesGlobalAreaId() {
        assertThat(DebugUtils.toAreaIdString(VehiclePropertyIds.WHEEL_TICK, /*areaId=*/
                0)).isEqualTo("GLOBAL");
    }

    @Test
    public void testToAreaIdString_handlesInvalidGlobalAreaId() {
        assertThat(DebugUtils.toAreaIdString(VehiclePropertyIds.WHEEL_TICK,
                VehicleAreaSeat.SEAT_ROW_3_RIGHT)).isEqualTo("INVALID_GLOBAL_AREA_ID(0x400)");
    }

    @Test
    public void testToAreaIdString_handlesDoorAreaType() {
        assertThat(DebugUtils.toAreaIdString(VehiclePropertyIds.DOOR_CHILD_LOCK_ENABLED,
                VehicleAreaDoor.DOOR_ROW_2_LEFT | VehicleAreaDoor.DOOR_ROW_1_RIGHT)).isEqualTo(
                "ROW_2_LEFT|ROW_1_RIGHT");
    }

    @Test
    public void testToAreaIdString_handlesMirrorAreaType() {
        assertThat(DebugUtils.toAreaIdString(VehiclePropertyIds.MIRROR_Z_MOVE,
                VehicleAreaMirror.MIRROR_DRIVER_LEFT | VehicleAreaMirror.MIRROR_DRIVER_RIGHT
                        | VehicleAreaMirror.MIRROR_DRIVER_CENTER)).isEqualTo(
                "DRIVER_CENTER|DRIVER_RIGHT|DRIVER_LEFT");
    }

    @Test
    public void testToAreaIdString_handlesSeatAreaType() {
        assertThat(DebugUtils.toAreaIdString(VehiclePropertyIds.SEAT_BELT_BUCKLED,
                VehicleAreaSeat.SEAT_ROW_2_CENTER)).isEqualTo("ROW_2_CENTER");
    }

    @Test
    public void testToAreaIdString_handlesWheelAreaType() {
        assertThat(DebugUtils.toAreaIdString(VehiclePropertyIds.TIRE_PRESSURE,
                VehicleAreaWheel.WHEEL_UNKNOWN)).isEqualTo("UNKNOWN");
    }

    @Test
    public void testToAreaIdString_handlesWindowAreaType() {
        assertThat(DebugUtils.toAreaIdString(VehiclePropertyIds.WINDOW_POS,
                VehicleAreaWindow.WINDOW_REAR_WINDSHIELD)).isEqualTo("REAR_WINDSHIELD");
    }

    @Test
    public void testToAreaIdString_handlesVendorAreaType() {
        assertThat(DebugUtils.toAreaIdString(VehiclePropertyIds.ULTRASONICS_SENSOR_DETECTION_RANGE,
                /*areaId=*/0x89)).isEqualTo("VENDOR_AREA_ID(0x89)");
    }

    @Test
    public void testToAreaIdString_handlesUnknownAreaType() {
        assertThat(DebugUtils.toAreaIdString(VehiclePropertyIds.INVALID,
                /*areaId=*/0x89)).isEqualTo("UNKNOWN_AREA_TYPE_AREA_ID(0x89)");
    }

    @Test
    public void testToAreaIdString_handlesNoMatchForAreaType() {
        assertThat(DebugUtils.toAreaIdString(VehiclePropertyIds.MIRROR_Y_MOVE,
                VehicleAreaWindow.WINDOW_ROOF_TOP_2)).isEqualTo("UNKNOWN_MIRROR_AREA_ID(0x20000)");
    }

    @Test
    public void testToAreaIdString_handlesPartialMatchForAreaType() {
        assertThat(DebugUtils.toAreaIdString(VehiclePropertyIds.MIRROR_Y_POS,
                VehicleAreaWindow.WINDOW_ROOF_TOP_2
                        | VehicleAreaMirror.MIRROR_DRIVER_RIGHT)).isEqualTo("DRIVER_RIGHT|0x20000");
    }

    @Test
    public void testPropIdAreaIdToDebugString() {
        var propIdAreaId = newPropIdAreaId(VehiclePropertyIds.PERF_VEHICLE_SPEED, /*areaId=*/0);

        assertThat(DebugUtils.toDebugString(propIdAreaId)).isEqualTo(
                "PropIdAreaId{propId=PERF_VEHICLE_SPEED, areaId=GLOBAL}");
    }

    @Test
    public void testPropIdAreaIdListToDebugString() {
        var propIdAreaId1 = newPropIdAreaId(VehiclePropertyIds.PERF_VEHICLE_SPEED, /*areaId=*/0);
        var propIdAreaId2 = newPropIdAreaId(VehiclePropertyIds.HVAC_FAN_SPEED,
                VehicleAreaSeat.SEAT_ROW_1_CENTER);

        assertThat(DebugUtils.toDebugString(List.of(propIdAreaId1, propIdAreaId2))).isEqualTo(
                "propIdAreaIds: [PropIdAreaId{propId=PERF_VEHICLE_SPEED, areaId=GLOBAL}, "
                        + "PropIdAreaId{propId=HVAC_FAN_SPEED, areaId=ROW_1_CENTER}]");
    }
}
