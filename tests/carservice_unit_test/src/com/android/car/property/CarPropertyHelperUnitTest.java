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

package com.android.car.internal.property;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.car.VehiclePropertyIds;

import org.junit.Test;

import java.util.List;

public final class CarPropertyHelperUnitTest {

    private static final int SYSTEM_ERROR_CODE = 0x0123;
    private static final int VENDOR_ERROR_CODE = 0x1234;
    private static final int COMBINED_ERROR_CODE = 0x12340123;
    private static final int TEST_PROPERTY_ID1 = 0x234;
    private static final int TEST_PROPERTY_ID2 = 0x54321;

    @Test
    public void testIsSupported() {
        // VehiclePropertyGroup:VENDOR, VehicleArea:GLOBAL,VehiclePropertyType:STRING, ID:0x0001
        assertThat(CarPropertyHelper.isSupported(0x21100001)).isTrue();
        // VEHICLE_SPEED_DISPLAY_UNITS is special because car property manager property ID is
        // different than vehicle HAL property ID.
        assertThat(CarPropertyHelper.isSupported(
                VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS)).isTrue();
         // This is a regular system property.
        assertThat(CarPropertyHelper.isSupported(VehiclePropertyIds.INFO_VIN)).isTrue();

        assertThat(CarPropertyHelper.isSupported(VehiclePropertyIds.INVALID)).isFalse();
        // This is a wrong property ID. It is like INFO_VIN but with the wrong VehicleArea.
        assertThat(CarPropertyHelper.isSupported(0x12100100)).isFalse();
    }

    @Test
    public void testGetVhalSystemErrorcode() {
        assertThat(CarPropertyHelper.getVhalSystemErrorCode(COMBINED_ERROR_CODE)).isEqualTo(
                SYSTEM_ERROR_CODE);
    }

    @Test
    public void testGetVhalVendorErrorCode() {
        assertThat(CarPropertyHelper.getVhalVendorErrorCode(COMBINED_ERROR_CODE)).isEqualTo(
                VENDOR_ERROR_CODE);
    }

    @Test
    public void testPropertyIdsToString() {
        assertWithMessage("String of multiple propertyIds").that(CarPropertyHelper
                        .propertyIdsToString(List.of(TEST_PROPERTY_ID1, TEST_PROPERTY_ID2,
                                VehiclePropertyIds.INFO_VIN)))
                .isEqualTo("[0x234, 0x54321, INFO_VIN]");
    }

    @Test
    public void testIsVendorProperty() {
        assertThat(CarPropertyHelper.isVendorProperty(0x21100001)).isTrue();
        assertThat(CarPropertyHelper.isVendorProperty(
                VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS)).isFalse();
    }
}
