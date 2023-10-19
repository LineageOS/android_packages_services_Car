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
import static com.android.car.hal.property.HalPropertyDebugUtils.toChangeModeString;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.hardware.automotive.vehicle.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.VehiclePropertyChangeMode;

import org.junit.Test;

public class HalPropertyDebugUtilsUnitTest {

    @Test
    public void testToAccessString() {
        assertThat(toAccessString(VehiclePropertyAccess.READ_WRITE)).isEqualTo("READ_WRITE(0x3)");
        assertThrows(IllegalArgumentException.class, () -> toAccessString(-1));
    }

    @Test
    public void testToChangeModeString() {
        assertThat(toChangeModeString(VehiclePropertyChangeMode.CONTINUOUS)).isEqualTo(
                "CONTINUOUS(0x2)");
        assertThrows(IllegalArgumentException.class, () -> toChangeModeString(-1));
    }
}
