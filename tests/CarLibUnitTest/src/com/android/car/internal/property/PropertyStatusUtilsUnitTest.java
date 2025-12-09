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

package com.android.car.internal.property;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.VehicleHalStatusCode;

import org.junit.Test;

public class PropertyStatusUtilsUnitTest {

    @Test
    public void isPropertyStatusError_true() {
        assertThat(PropertyStatusUtils.isPropertyStatusError(CarPropertyValue.STATUS_ERROR))
                .isTrue();
    }

    @Test
    public void isPropertyStatusError_false() {
        assertThat(PropertyStatusUtils.isPropertyStatusError(CarPropertyValue.STATUS_UNAVAILABLE))
                .isFalse();
        assertThat(PropertyStatusUtils.isPropertyStatusError(CarPropertyValue.STATUS_AVAILABLE))
                .isFalse();
    }

    @Test
    public void isPropertyStatusNotAvailable_true() {
        for (int status : new int[] {
                CarPropertyValue.STATUS_UNAVAILABLE,
                CarPropertyValue.STATUS_NOT_AVAILABLE_GENERAL,
                CarPropertyValue.STATUS_NOT_AVAILABLE_SPEED_LOW,
                CarPropertyValue.STATUS_NOT_AVAILABLE_SPEED_HIGH,
                CarPropertyValue.STATUS_NOT_AVAILABLE_POOR_VISIBILITY,
                CarPropertyValue.STATUS_NOT_AVAILABLE_SAFETY,
                CarPropertyValue.STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED
        }) {
            assertThat(PropertyStatusUtils.isPropertyStatusNotAvailable(status)).isTrue();
        }
    }

    @Test
    public void isPropertyStatusNotAvailable_false() {
        assertThat(PropertyStatusUtils.isPropertyStatusNotAvailable(CarPropertyValue.STATUS_ERROR))
                .isFalse();
        assertThat(PropertyStatusUtils.isPropertyStatusNotAvailable(
                CarPropertyValue.STATUS_AVAILABLE)).isFalse();
    }

    @Test
    public void isPropertyStatusAvailable_true() {
        assertThat(PropertyStatusUtils.isPropertyStatusAvailable(CarPropertyValue.STATUS_AVAILABLE))
                .isTrue();
    }

    @Test
    public void isPropertyStatusAvailable_false() {
        assertThat(PropertyStatusUtils.isPropertyStatusAvailable(CarPropertyValue.STATUS_ERROR))
                .isFalse();
        assertThat(PropertyStatusUtils.isPropertyStatusAvailable(
                CarPropertyValue.STATUS_NOT_AVAILABLE_GENERAL)).isFalse();
    }

    @Test
    public void testGetNotAvailablePropertyStatusFromStatusCode_validStatus() {
        assertThat(
                        PropertyStatusUtils.getNotAvailablePropertyStatusFromStatusCode(
                                VehicleHalStatusCode.STATUS_NOT_AVAILABLE))
                .isEqualTo(CarPropertyValue.STATUS_NOT_AVAILABLE_GENERAL);
        assertThat(
                        PropertyStatusUtils.getNotAvailablePropertyStatusFromStatusCode(
                                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_DISABLED))
                .isEqualTo(CarPropertyValue.STATUS_NOT_AVAILABLE_DISABLED);
        assertThat(
                        PropertyStatusUtils.getNotAvailablePropertyStatusFromStatusCode(
                                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_LOW))
                .isEqualTo(CarPropertyValue.STATUS_NOT_AVAILABLE_SPEED_LOW);
        assertThat(
                        PropertyStatusUtils.getNotAvailablePropertyStatusFromStatusCode(
                                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SPEED_HIGH))
                .isEqualTo(CarPropertyValue.STATUS_NOT_AVAILABLE_SPEED_HIGH);
        assertThat(
                        PropertyStatusUtils.getNotAvailablePropertyStatusFromStatusCode(
                                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_POOR_VISIBILITY))
                .isEqualTo(CarPropertyValue.STATUS_NOT_AVAILABLE_POOR_VISIBILITY);
        assertThat(
                        PropertyStatusUtils.getNotAvailablePropertyStatusFromStatusCode(
                                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SAFETY))
                .isEqualTo(CarPropertyValue.STATUS_NOT_AVAILABLE_SAFETY);
        assertThat(
                        PropertyStatusUtils.getNotAvailablePropertyStatusFromStatusCode(
                                VehicleHalStatusCode.STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED))
                .isEqualTo(CarPropertyValue.STATUS_NOT_AVAILABLE_SUBSYSTEM_NOT_CONNECTED);
    }

    @Test
    public void testGetNotAvailablePropertyStatusFromStatusCode_invalidStatus_throwsException() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PropertyStatusUtils.getNotAvailablePropertyStatusFromStatusCode(
                                VehicleHalStatusCode.STATUS_OK));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PropertyStatusUtils.getNotAvailablePropertyStatusFromStatusCode(
                                VehicleHalStatusCode.STATUS_TRY_AGAIN));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PropertyStatusUtils.getNotAvailablePropertyStatusFromStatusCode(
                                VehicleHalStatusCode.STATUS_INVALID_ARG));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PropertyStatusUtils.getNotAvailablePropertyStatusFromStatusCode(
                                VehicleHalStatusCode.STATUS_ACCESS_DENIED));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PropertyStatusUtils.getNotAvailablePropertyStatusFromStatusCode(
                                VehicleHalStatusCode.STATUS_INTERNAL_ERROR));
    }
}
