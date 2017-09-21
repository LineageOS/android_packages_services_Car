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
import android.test.suitebuilder.annotation.MediumTest;
import com.android.car.storagemonitoring.WearInformation;

/** Test the public entry points for the CarStorageMonitoringManager */
@MediumTest
public class CarStorageMonitoringTest extends MockedCarTestBase {
    private static final String TAG = CarStorageMonitoringTest.class.getSimpleName();

    private CarStorageMonitoringManager mCarStorageMonitoringManager;

    @Override
    protected synchronized void configureFakeSystemInterface() {
        setFlashWearInformation(new WearInformation(
            WearInformation.UNKNOWN_LIFETIME_ESTIMATE,
            WearInformation.UNKNOWN_LIFETIME_ESTIMATE,
            WearInformation.PRE_EOL_INFO_NORMAL));
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mCarStorageMonitoringManager =
            (CarStorageMonitoringManager) getCar().getCarManager(Car.STORAGE_MONITORING_SERVICE);
    }

    public void testReadPreEolInformation() throws Exception {
        assertEquals(WearInformation.PRE_EOL_INFO_NORMAL,
                mCarStorageMonitoringManager.getPreEolIndicatorStatus());
    }
}
