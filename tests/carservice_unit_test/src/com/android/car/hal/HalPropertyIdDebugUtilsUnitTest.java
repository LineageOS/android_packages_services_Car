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

import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyGroup;

import org.junit.Test;

public class HalPropertyIdDebugUtilsUnitTest {

    @Test
    public void testToDebugString() {
        assertThat(HalPropertyIdDebugUtils.toDebugString(VehicleProperty.ABS_ACTIVE)).isEqualTo(
                "ABS_ACTIVE");
        assertThat(HalPropertyIdDebugUtils.toDebugString(
                VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS)).isEqualTo(
                "VEHICLE_SPEED_DISPLAY_UNITS");
        assertThat(HalPropertyIdDebugUtils.toDebugString(VehiclePropertyGroup.VENDOR)).isEqualTo(
                "VENDOR_PROPERTY(0x" + Integer.toHexString(VehiclePropertyGroup.VENDOR) + ")");
        assertThat(HalPropertyIdDebugUtils.toDebugString(-1)).isEqualTo(
                "0x" + Integer.toHexString(-1));
    }

    @Test
    public void testToId() {
        assertThat(HalPropertyIdDebugUtils.toId("ABS_ACTIVE")).isEqualTo(
                VehicleProperty.ABS_ACTIVE);
        assertThat(HalPropertyIdDebugUtils.toId("VEHICLE_SPEED_DISPLAY_UNITS")).isEqualTo(
                VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS);
        assertThat(HalPropertyIdDebugUtils.toId("saljflsadj")).isNull();
    }
}
