/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.car.test;

import android.car.Car;
import android.car.CarInfoManager;
import android.os.Bundle;
import android.util.Log;

import com.android.car.test.MockedCarTestBase;
import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehiclePropConfigUtil;
import com.android.car.vehiclenetwork.VehiclePropValueUtil;

public class CarInfoManagerTest extends MockedCarTestBase {

    private static final String TAG = CarInfoManagerTest.class.getSimpleName();

    private static final String MAKE_NAME = "ANDROID";

    private CarInfoManager mCarInfoManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        getVehicleHalEmulator().addStaticProperty(
                VehiclePropConfigUtil.createStaticStringProperty(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_INFO_MAKE),
                VehiclePropValueUtil.createStringValue(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_INFO_MAKE, MAKE_NAME, 0));
        getVehicleHalEmulator().start();
        mCarInfoManager =
                (CarInfoManager) getCar().getCarManager(Car.INFO_SERVICE);
    }

    public void testManufactuter() throws Exception {
        Bundle info = mCarInfoManager.getBasicInfo();
        assertEquals(MAKE_NAME, info.getCharSequence(CarInfoManager.BASIC_INFO_KEY_MANUFACTURER));
    }

    public void testNoSuchInfo() throws Exception {
        final String NO_SUCH_NAME = "no-such-information-available";
        Bundle info = mCarInfoManager.getBasicInfo();
        assertNotNull(info);
        assertNull(info.getCharSequence(NO_SUCH_NAME));
    }
}
