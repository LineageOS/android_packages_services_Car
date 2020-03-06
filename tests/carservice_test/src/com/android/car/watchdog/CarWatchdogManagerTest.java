/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.car.watchdog.CarWatchdogManager.TIMEOUT_NORMAL;

import static org.testng.Assert.assertThrows;

import android.car.Car;
import android.car.watchdog.CarWatchdogManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class CarWatchdogManagerTest extends MockedCarTestBase {

    private static final String TAG = CarWatchdogManagerTest.class.getSimpleName();

    private CarWatchdogManager mCarWatchdogManager;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mCarWatchdogManager = (CarWatchdogManager) getCar().getCarManager(Car.CAR_WATCHDOG_SERVICE);
    }

    @Test
    public void testRegisterClient() throws Exception {
        TestClient client = new TestClient();
        mCarWatchdogManager.registerClient(getContext().getMainExecutor(), client, TIMEOUT_NORMAL);
        mCarWatchdogManager.unregisterClient(client);
    }

    @Test
    public void testUnregisterUnregisteredClient() throws Exception {
        TestClient client = new TestClient();
        mCarWatchdogManager.registerClient(getContext().getMainExecutor(), client, TIMEOUT_NORMAL);
        mCarWatchdogManager.unregisterClient(client);
        // The following call should not throw an exception.
        mCarWatchdogManager.unregisterClient(client);
    }

    @Test
    public void testRegisterMultipleClients() {
        Executor executor = getContext().getMainExecutor();
        TestClient client1 = new TestClient();
        TestClient client2 = new TestClient();
        mCarWatchdogManager.registerClient(executor, client1, TIMEOUT_NORMAL);
        assertThrows(IllegalStateException.class,
                () -> mCarWatchdogManager.registerClient(executor, client2, TIMEOUT_NORMAL));
    }

    public class TestClient extends CarWatchdogManager.CarWatchdogClientCallback {
        @Override
        public boolean onCheckHealthStatus(int sessionId, int timeout) {
            return true;
        }
    }
}
