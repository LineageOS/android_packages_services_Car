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

import android.car.VehiclePropertyIds;
import android.hardware.automotive.vehicle.StatusCode;
import android.hardware.automotive.vehicle.UserInfo;
import android.hardware.automotive.vehicle.V2_0.UserFlags;

import org.junit.Test;

import java.util.List;

public final class DebugUtilsUnitTest {

    @Test
    public void aidlConstantToString() {
        assertThat(DebugUtils.constantToString(StatusCode.class, StatusCode.OK)).isEqualTo("OK");
        assertThat(DebugUtils.constantToString(StatusCode.class, StatusCode.TRY_AGAIN))
                .isEqualTo("TRY_AGAIN");
        assertThat(DebugUtils.constantToString(StatusCode.class, StatusCode.NOT_AVAILABLE))
                .isEqualTo("NOT_AVAILABLE");
        assertThat(DebugUtils.constantToString(StatusCode.class, StatusCode.ACCESS_DENIED))
                .isEqualTo("ACCESS_DENIED");
        assertThat(DebugUtils.constantToString(StatusCode.class, StatusCode.INTERNAL_ERROR))
                .isEqualTo("INTERNAL_ERROR");
    }

    @Test
    public void aidlFlagsToString() {
        assertThat(DebugUtils.flagsToString(UserInfo.class, "USER_FLAG_", 0)).isEqualTo("0");
        assertThat(
                DebugUtils.flagsToString(UserInfo.class, "USER_FLAG_", UserInfo.USER_FLAG_SYSTEM))
                .isEqualTo("SYSTEM");
        assertThat(
                DebugUtils.flagsToString(
                        UserInfo.class, "USER_FLAG_",
                        UserInfo.USER_FLAG_SYSTEM | UserInfo.USER_FLAG_ADMIN))
                .isAnyOf("ADMIN|SYSTEM", "SYSTEM|ADMIN");
    }

    @Test
    public void hidlConstantToString() {
        assertThat(
                DebugUtils.constantToString(
                        android.hardware.automotive.vehicle.V2_0.StatusCode.class,
                        android.hardware.automotive.vehicle.V2_0.StatusCode.OK))
                .isEqualTo("OK");
        assertThat(
                DebugUtils.constantToString(
                        android.hardware.automotive.vehicle.V2_0.StatusCode.class,
                        android.hardware.automotive.vehicle.V2_0.StatusCode.TRY_AGAIN))
                .isEqualTo("TRY_AGAIN");
        assertThat(
                DebugUtils.constantToString(
                        android.hardware.automotive.vehicle.V2_0.StatusCode.class,
                        android.hardware.automotive.vehicle.V2_0.StatusCode.NOT_AVAILABLE))
                .isEqualTo("NOT_AVAILABLE");
        assertThat(
                DebugUtils.constantToString(
                        android.hardware.automotive.vehicle.V2_0.StatusCode.class,
                        android.hardware.automotive.vehicle.V2_0.StatusCode.ACCESS_DENIED))
                .isEqualTo("ACCESS_DENIED");
        assertThat(
                DebugUtils.constantToString(
                        android.hardware.automotive.vehicle.V2_0.StatusCode.class,
                        android.hardware.automotive.vehicle.V2_0.StatusCode.INTERNAL_ERROR))
                .isEqualTo("INTERNAL_ERROR");
    }

    @Test
    public void hidlFlagsToString() {
        assertThat(DebugUtils.flagsToString(UserFlags.class, "USER_FLAG_", 0)).isEqualTo("0");
        assertThat(
                DebugUtils.flagsToString(UserFlags.class, "", UserFlags.SYSTEM))
                .isEqualTo("SYSTEM");
        assertThat(
                DebugUtils.flagsToString(
                        UserFlags.class, "",
                        UserFlags.SYSTEM | UserFlags.ADMIN))
                .isAnyOf("ADMIN|SYSTEM", "SYSTEM|ADMIN");
    }

    @Test
    public void testPropIdAreaIdToDebugString() {
        var propIdAreaId = newPropIdAreaId(VehiclePropertyIds.PERF_VEHICLE_SPEED, /*areaId=*/1);

        assertThat(DebugUtils.toDebugString(propIdAreaId)).isEqualTo(
                "PropIdAreaId{propId=PERF_VEHICLE_SPEED, areaId=1}");
    }

    @Test
    public void testPropIdAreaIdListToDebugString() {
        var propIdAreaId1 = newPropIdAreaId(VehiclePropertyIds.PERF_VEHICLE_SPEED, /*areaId=*/1);
        var propIdAreaId2 = newPropIdAreaId(VehiclePropertyIds.HVAC_FAN_SPEED, /*areaId=*/2);

        assertThat(DebugUtils.toDebugString(List.of(propIdAreaId1, propIdAreaId2))).isEqualTo(
                "propIdAreaIds: [PropIdAreaId{propId=PERF_VEHICLE_SPEED, areaId=1}, "
                + "PropIdAreaId{propId=HVAC_FAN_SPEED, areaId=2}]");
    }
}
