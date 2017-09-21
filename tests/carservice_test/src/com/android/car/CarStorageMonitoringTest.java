/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.car;

import android.car.Car;
import android.car.storagemonitoring.CarStorageMonitoringManager;
import android.car.storagemonitoring.WearEstimate;
import android.test.suitebuilder.annotation.MediumTest;
import com.android.car.storagemonitoring.WearInformation;

/** Test the public entry points for the CarStorageMonitoringManager */
@MediumTest
public class CarStorageMonitoringTest extends MockedCarTestBase {
    private static final String TAG = CarStorageMonitoringTest.class.getSimpleName();

    private static final WearInformation DEFAULT_WEAR_INFORMATION =
        new WearInformation(10, 0, WearInformation.PRE_EOL_INFO_NORMAL);

    private CarStorageMonitoringManager mCarStorageMonitoringManager;

    @Override
    protected synchronized void configureFakeSystemInterface() {
        setFlashWearInformation(DEFAULT_WEAR_INFORMATION);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mCarStorageMonitoringManager =
            (CarStorageMonitoringManager) getCar().getCarManager(Car.STORAGE_MONITORING_SERVICE);
    }

    public void testReadPreEolInformation() throws Exception {
        assertEquals(DEFAULT_WEAR_INFORMATION.preEolInfo,
                mCarStorageMonitoringManager.getPreEolIndicatorStatus());
    }

    public void testReadWearEstimate() throws Exception {
        final WearEstimate wearEstimate = mCarStorageMonitoringManager.getWearEstimate();

        assertNotNull(wearEstimate);
        assertEquals(DEFAULT_WEAR_INFORMATION.lifetimeEstimateA, wearEstimate.typeA);
        assertEquals(DEFAULT_WEAR_INFORMATION.lifetimeEstimateB, wearEstimate.typeB);
    }
}
