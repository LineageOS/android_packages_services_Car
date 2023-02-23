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

package android.car.apitest;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.car.Car;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.property.CarInternalErrorException;
import android.car.hardware.property.CarPropertyManager;
import android.hardware.automotive.vehicle.VehicleArea;
import android.hardware.automotive.vehicle.VehiclePropertyGroup;
import android.hardware.automotive.vehicle.VehiclePropertyType;
import android.util.ArraySet;

import com.android.compatibility.common.util.ApiTest;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

public final class CarPropertyManagerTest extends CarApiTestBase {
    private static final int VENDOR_ERROR_CODE_PROPERTY_ID = 0x2a13 | VehiclePropertyGroup.VENDOR
            | VehicleArea.GLOBAL | VehiclePropertyType.INT32;
    private static final int EXPECTED_VENDOR_ERROR_CODE = 0x00ab;

    private CarPropertyManager mCarPropertyManager;

    @Before
    public void setUp() throws Exception {
        mCarPropertyManager = (CarPropertyManager) getCar().getCarManager(Car.PROPERTY_SERVICE);
        assertThat(mCarPropertyManager).isNotNull();
    }

    @ApiTest(apis = {"android.car.hardware.property.CarInternalErrorException#getVendorErrorCode"})
    @Test
    public void testGetProperty_withVendorPropertyId_throws() {
        List<CarPropertyConfig> carPropertyConfigList = mCarPropertyManager.getPropertyList(
                new ArraySet(List.of(VENDOR_ERROR_CODE_PROPERTY_ID)));
        assumeTrue("VENDOR_ERROR_CODE_PROPERTY_ID is supported",
                !carPropertyConfigList.isEmpty());
        CarInternalErrorException thrown = assertThrows(CarInternalErrorException.class, () ->
                mCarPropertyManager.getProperty(VENDOR_ERROR_CODE_PROPERTY_ID, /* areaId = */ 0));

        assertThat(thrown.getVendorErrorCode()).isEqualTo(EXPECTED_VENDOR_ERROR_CODE);
    }

    @ApiTest(apis = {"android.car.hardware.property.CarInternalErrorException#getVendorErrorCode"})
    @Test
    public void testSetProperty_withVendorPropertyId_throws() {
        List<CarPropertyConfig> carPropertyConfigList = mCarPropertyManager.getPropertyList(
                new ArraySet(List.of(VENDOR_ERROR_CODE_PROPERTY_ID)));
        assumeTrue("VENDOR_ERROR_CODE_PROPERTY_ID is supported",
                !carPropertyConfigList.isEmpty());
        CarInternalErrorException thrown = assertThrows(CarInternalErrorException.class,
                () -> mCarPropertyManager.setProperty(Integer.class, VENDOR_ERROR_CODE_PROPERTY_ID,
                        /* areaId = */ 0, /* val= */ 0));

        assertThat(thrown.getVendorErrorCode()).isEqualTo(EXPECTED_VENDOR_ERROR_CODE);
    }
}
