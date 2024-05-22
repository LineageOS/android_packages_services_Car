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

package com.google.android.car.kitchensink;

import android.car.Car;
import android.car.CarOccupantZoneManager;
import android.car.CarProjectionManager;
import android.car.hardware.CarSensorManager;
import android.car.hardware.hvac.CarHvacManager;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.property.CarPropertyManager;
import android.car.os.CarPerformanceManager;
import android.car.telemetry.CarTelemetryManager;
import android.car.watchdog.CarWatchdogManager;
import android.os.Handler;

public interface KitchenSinkHelper {
    /**
     * @return Car api
     */
    Car getCar();

    /**
     * Uses the handler to post the runnable to run after car service is connected or reconnected.
     *
     * This should be used to do initialization with the managers, e.g. get the property list from
     * the property manager. If you cache your manager instance locally, you must use this to
     * reset your local manager instance to the new instance returned via getXXXManager.
     */
    void requestRefreshManager(Runnable r, Handler h);

    /**
     * Gets the car property manager.
     */
    CarPropertyManager getPropertyManager();

    /**
     * Gets the HVAC manager.
     */
    CarHvacManager getHvacManager();

    /**
     * Gets the occulant zone manager.
     */
    CarOccupantZoneManager getOccupantZoneManager();

    /**
     * Gets the power manager.
     */
    CarPowerManager getPowerManager();

    /**
     * Gets the sensor manager.
     */
    CarSensorManager getSensorManager();

    /**
     * Gets the projection manager.
     */
    CarProjectionManager getProjectionManager();

    /**
     * Gets the telemetry manager.
     */
    CarTelemetryManager getCarTelemetryManager();

    /**
     * Gets the car watchdog manager.
     */
    CarWatchdogManager getCarWatchdogManager();

    /**
     * Gets the performance manager.
     */
    CarPerformanceManager getPerformanceManager();

}
