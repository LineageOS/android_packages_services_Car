/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.car.Car;
import android.car.CarInfoManager;
import android.os.Bundle;

public class CarInfoManagerTest extends CarApiTestBase {

    private CarInfoManager mInfoManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mInfoManager = (CarInfoManager) getCar().getCarManager(Car.INFO_SERVICE);
        assertNotNull(mInfoManager);
    }

    public void testNoSuchInfo() throws Exception {
        final String NO_SUCH_NAME = "no-such-information-available";
        try {
            String name = mInfoManager.getString(NO_SUCH_NAME);
            fail("wrong param check");
        } catch (IllegalArgumentException e) {
            // expected
        }
        try {
            Integer intValue = mInfoManager.getInt(NO_SUCH_NAME);
            fail("wrong param check");
        } catch (IllegalArgumentException e) {
            //expected
        }
        try {
            Float floatValue = mInfoManager.getFloat(NO_SUCH_NAME);
            fail("wrong param check");
        } catch (IllegalArgumentException e) {
            //expected
        }
        try {
            Long longValue = mInfoManager.getLong(NO_SUCH_NAME);
            fail("wrong param check");
        } catch (IllegalArgumentException e) {
            //expected
        }
        Bundle bundleValue = mInfoManager.getBundle(NO_SUCH_NAME);
        assertNull(bundleValue);
    }
}
