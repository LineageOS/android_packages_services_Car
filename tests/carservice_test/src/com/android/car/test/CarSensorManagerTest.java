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
import android.car.hardware.CarSensorEvent;
import android.car.hardware.CarSensorManager;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;

import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehiclePropValueUtil;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Test the public entry points for the CarSensorManager
 */
@MediumTest
public class CarSensorManagerTest extends MockedCarTestBase {
    private static final String TAG = CarSensorManagerTest.class.getSimpleName();

    private CarSensorManager mCarSensorManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // Our tests simply rely on the properties already added by default in the
        // VehilceHalEmulator.  We don't actually need to add any of our own.

        // Start the HAL layer and set up the sensor manager service
        getVehicleHalEmulator().start();
        mCarSensorManager = (CarSensorManager) getCar().getCarManager(Car.SENSOR_SERVICE);
    }

    /**
     * Test single sensor availability entry point
     * @throws Exception
     */
    public void testSensorAvailability() throws Exception {
        // NOTE:  Update this test if/when the reserved values put into use.  For now, we
        //        expect them to never be supported.
        assertFalse(mCarSensorManager.isSensorSupported(CarSensorManager.SENSOR_TYPE_RESERVED1));
        assertFalse(mCarSensorManager.isSensorSupported(CarSensorManager.SENSOR_TYPE_RESERVED13));
        assertFalse(mCarSensorManager.isSensorSupported(CarSensorManager.SENSOR_TYPE_RESERVED21));

        // We expect these sensors to always be available
        assertTrue(mCarSensorManager.isSensorSupported(CarSensorManager.SENSOR_TYPE_CAR_SPEED));
        assertTrue(mCarSensorManager.isSensorSupported(CarSensorManager.SENSOR_TYPE_FUEL_LEVEL));
        assertTrue(mCarSensorManager.isSensorSupported(CarSensorManager.SENSOR_TYPE_PARKING_BRAKE));
        assertTrue(mCarSensorManager.isSensorSupported(CarSensorManager.SENSOR_TYPE_GEAR));
        assertTrue(mCarSensorManager.isSensorSupported(CarSensorManager.SENSOR_TYPE_NIGHT));
        assertTrue(mCarSensorManager.isSensorSupported(CarSensorManager.SENSOR_TYPE_DRIVING_STATUS));
    }

    /**
     * Test sensor enumeration entry point
     * @throws Exception
     */
    public void testSensorEnumeration() throws Exception {
        int[] supportedSensors = mCarSensorManager.getSupportedSensors();
        assertNotNull(supportedSensors);

        Log.i(TAG, "Found " + supportedSensors.length + " supported sensors.");

        // Unfortunately, we don't have a definitive range for legal sensor values,
        // so we have set a "reasonable" range here.  The ending value, in particular,
        // will need to be updated if/when new sensor types are allowed.
        // Here we are ensuring that all the enumerated sensors also return supported.
        for (int candidate = 0; candidate <= CarSensorManager.SENSOR_TYPE_RESERVED21; ++candidate) {
            boolean supported = mCarSensorManager.isSensorSupported(candidate);
            boolean found = false;
            for (int sensor : supportedSensors) {
                if (candidate == sensor) {
                    found = true;
                    Log.i(TAG, "Sensor type " + sensor + " is supported.");
                    break;
                }
            }

            // Make sure the individual query on a sensor type is consistent
            assertEquals(found, supported);
        }

        // Here we simply ensure that one specific expected sensor is always available to help
        // ensure we don't have a trivially broken test finding nothing.
        boolean found = false;
        for (int sensor : supportedSensors) {
            if (sensor == CarSensorManager.SENSOR_TYPE_DRIVING_STATUS) {
                found = true;
                break;
            }
        }
        assertTrue("We expect at least DRIVING_STATUS to be available", found);
    }

    /**
     * Test senor notification registration, delivery, and unregistration
     * @throws Exception
     */
    public void testEvents() throws Exception {
        // Set up our listener callback
        SensorListener listener = new SensorListener();
        mCarSensorManager.registerListener(listener,
                CarSensorManager.SENSOR_TYPE_NIGHT,
                CarSensorManager.SENSOR_RATE_NORMAL);

        VehiclePropValue value = null;
        CarSensorEvent.NightData data = null;
        CarSensorEvent event = null;

        // Consume any sensor events queued up on startup
        while (listener.waitForSensorChange()) {};

        // Validate that no events are now pending
        listener.checkNoSensorChangePosted();


        // Set the value TRUE and wait for the event to arrive
        value = VehiclePropValueUtil.createBooleanValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_NIGHT_MODE, true, 1);
        getVehicleHalEmulator().injectEvent(value);
        assertTrue(listener.waitForSensorChange());

        // Validate that no events remain pending
        listener.checkNoSensorChangePosted();

        // Ensure we got the expected event
        assertEquals(listener.getLastEvent().sensorType, CarSensorManager.SENSOR_TYPE_NIGHT);

        // Ensure we got the expected value in our callback
        data = listener.getLastEvent().getNightData(data);
        Log.d(TAG, "NightMode " + data.isNightMode + " at " + data.timestamp);
        assertTrue(data.isNightMode);

        // Ensure we have the expected value in the sensor manager's cache
        event = mCarSensorManager.getLatestSensorEvent(CarSensorManager.SENSOR_TYPE_NIGHT);
        data = event.getNightData(data);
        assertEquals("Unexpected event timestamp", data.timestamp, 1);
        assertTrue("Unexpected value", data.isNightMode);


        // Set the value FALSE
        value = VehiclePropValueUtil.createBooleanValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_NIGHT_MODE, false, 1001);
        getVehicleHalEmulator().injectEvent(value);
        assertTrue(listener.waitForSensorChange());

        // Ensure we got the expected event
        assertEquals(listener.getLastEvent().sensorType, CarSensorManager.SENSOR_TYPE_NIGHT);

        // Ensure we got the expected value in our callback
        data = listener.getLastEvent().getNightData(data);
        assertEquals("Unexpected event timestamp", data.timestamp, 1001);
        assertFalse("Unexpected value", data.isNightMode);

        // Ensure we have the expected value in the sensor manager's cache
        event = mCarSensorManager.getLatestSensorEvent(CarSensorManager.SENSOR_TYPE_NIGHT);
        data = event.getNightData(data);
        assertFalse(data.isNightMode);

        // Unregister our handler (from all sensor types)
        mCarSensorManager.unregisterListener(listener);

        // Set the value TRUE again
        value = VehiclePropValueUtil.createBooleanValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_NIGHT_MODE, true, 2001);
        listener.checkNoSensorChangePosted();
        getVehicleHalEmulator().injectEvent(value);

        // Ensure we did not get a callback (should timeout)
        Log.i(TAG, "waiting for unexpected callback -- should timeout.");
        assertFalse(listener.waitForSensorChange());
        listener.checkNoSensorChangePosted();

        // Despite us not having a callback registered, the Sensor Manager should see the update
        event = mCarSensorManager.getLatestSensorEvent(CarSensorManager.SENSOR_TYPE_NIGHT);
        data = event.getNightData(data);
        assertEquals("Unexpected event timestamp", data.timestamp, 2001);
        assertTrue("Unexpected value", data.isNightMode);
    }


    /**
     * Test senor multiple liseners notification registration, delivery and unregistration.
     * @throws Exception
     */
    public void testEventsWithMultipleListeners() throws Exception {
        // Set up our listeners callback
        SensorListener listener1 = new SensorListener("1");
        SensorListener listener2 = new SensorListener("2");
        SensorListener listener3 = new SensorListener("3");

        mCarSensorManager.registerListener(listener1,
                CarSensorManager.SENSOR_TYPE_NIGHT,
                CarSensorManager.SENSOR_RATE_NORMAL);

        mCarSensorManager.registerListener(listener2,
                CarSensorManager.SENSOR_TYPE_NIGHT,
                CarSensorManager.SENSOR_RATE_NORMAL);

        mCarSensorManager.registerListener(listener3,
                CarSensorManager.SENSOR_TYPE_NIGHT,
                CarSensorManager.SENSOR_RATE_FASTEST);

        VehiclePropValue value = null;
        CarSensorEvent.NightData data = null;
        CarSensorEvent event = null;

        // Consume any sensor events queued up on startup
        while (listener1.waitForSensorChange()) {};

        // Consume any sensor events queued up on startup
        while (listener2.waitForSensorChange()) {};

        // Consume any sensor events queued up on startup
        while (listener3.waitForSensorChange()) {};

        // Validate that no events are now pending
        listener1.checkNoSensorChangePosted();

        // Validate that no events are now pending
        listener2.checkNoSensorChangePosted();

        // Validate that no events are now pending
        listener3.checkNoSensorChangePosted();

        // Set the value TRUE and wait for the event to arrive
        value = VehiclePropValueUtil.createBooleanValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_NIGHT_MODE, true, 1);
        getVehicleHalEmulator().injectEvent(value);
        assertTrue(listener1.waitForSensorChange());
        assertTrue(listener2.waitForSensorChange());
        assertTrue(listener3.waitForSensorChange());

        // Validate that no events remain pending
        listener1.checkNoSensorChangePosted();

        // Validate that no events remain pending
        listener2.checkNoSensorChangePosted();

        // Validate that no events remain pending
        listener3.checkNoSensorChangePosted();

        // Ensure we got the expected event
        assertEquals(listener1.getLastEvent().sensorType, CarSensorManager.SENSOR_TYPE_NIGHT);
        assertEquals(listener2.getLastEvent().sensorType, CarSensorManager.SENSOR_TYPE_NIGHT);
        assertEquals(listener3.getLastEvent().sensorType, CarSensorManager.SENSOR_TYPE_NIGHT);

        // Ensure we got the expected value in our callback
        data = listener1.getLastEvent().getNightData(data);
        Log.d(TAG, "NightMode " + data.isNightMode + " at " + data.timestamp);
        assertTrue(data.isNightMode);

        data = listener2.getLastEvent().getNightData(data);
        Log.d(TAG, "NightMode " + data.isNightMode + " at " + data.timestamp);
        assertTrue(data.isNightMode);

        data = listener3.getLastEvent().getNightData(data);
        Log.d(TAG, "NightMode " + data.isNightMode + " at " + data.timestamp);
        assertTrue(data.isNightMode);

        // Ensure we have the expected value in the sensor manager's cache
        event = mCarSensorManager.getLatestSensorEvent(CarSensorManager.SENSOR_TYPE_NIGHT);
        data = event.getNightData(data);
        assertEquals("Unexpected event timestamp", data.timestamp, 1);
        assertTrue("Unexpected value", data.isNightMode);

        // Set the value FALSE
        value = VehiclePropValueUtil.createBooleanValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_NIGHT_MODE, false, 1001);
        getVehicleHalEmulator().injectEvent(value);
        assertTrue(listener1.waitForSensorChange());
        assertTrue(listener2.waitForSensorChange());
        assertTrue(listener3.waitForSensorChange());

        // Validate that no events remain pending
        listener1.checkNoSensorChangePosted();

        // Validate that no events remain pending
        listener2.checkNoSensorChangePosted();

        // Validate that no events remain pending
        listener3.checkNoSensorChangePosted();

        // Ensure we got the expected event
        assertEquals(listener1.getLastEvent().sensorType, CarSensorManager.SENSOR_TYPE_NIGHT);
        assertEquals(listener2.getLastEvent().sensorType, CarSensorManager.SENSOR_TYPE_NIGHT);
        assertEquals(listener3.getLastEvent().sensorType, CarSensorManager.SENSOR_TYPE_NIGHT);

        // Ensure we got the expected value in our callback
        data = listener1.getLastEvent().getNightData(data);
        assertEquals("Unexpected event timestamp", data.timestamp, 1001);
        assertFalse("Unexpected value", data.isNightMode);

        data = listener2.getLastEvent().getNightData(data);
        assertEquals("Unexpected event timestamp", data.timestamp, 1001);
        assertFalse("Unexpected value", data.isNightMode);

        data = listener3.getLastEvent().getNightData(data);
        assertEquals("Unexpected event timestamp", data.timestamp, 1001);
        assertFalse("Unexpected value", data.isNightMode);

        // Ensure we have the expected value in the sensor manager's cache
        event = mCarSensorManager.getLatestSensorEvent(CarSensorManager.SENSOR_TYPE_NIGHT);
        data = event.getNightData(data);
        assertFalse(data.isNightMode);

        Log.d(TAG, "Unregistering listener3");
        mCarSensorManager.unregisterListener(listener3);

        Log.d(TAG, "Rate changed - expect sensor restart and change event sent.");
        assertTrue(listener1.waitForSensorChange());
        assertTrue(listener2.waitForSensorChange());
        assertFalse(listener2.waitForSensorChange());

        // Set the value TRUE again
        value = VehiclePropValueUtil.createBooleanValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_NIGHT_MODE, true, 2001);
        listener1.checkNoSensorChangePosted();
        listener2.checkNoSensorChangePosted();
        listener3.checkNoSensorChangePosted();
        getVehicleHalEmulator().injectEvent(value);

        assertTrue(listener1.waitForSensorChange());
        assertTrue(listener2.waitForSensorChange());

        // Ensure we did not get a callback (should timeout)
        Log.i(TAG, "waiting for unexpected callback -- should timeout.");
        assertFalse(listener3.waitForSensorChange());

        listener1.checkNoSensorChangePosted();
        listener2.checkNoSensorChangePosted();
        listener3.checkNoSensorChangePosted();

        Log.d(TAG, "Unregistering listener2");
        mCarSensorManager.unregisterListener(listener3);

        Log.d(TAG, "Rate did nor change - dont expect sensor restart and change event sent.");
        assertFalse(listener1.waitForSensorChange());
        assertFalse(listener2.waitForSensorChange());
        assertFalse(listener2.waitForSensorChange());
    }


    /**
     * Callback function we register for sensor update notifications.
     * This tracks the number of times it has been called via the mAvailable semaphore,
     * and keeps a reference to the most recent event delivered.
     */
    class SensorListener implements CarSensorManager.OnSensorChangedListener {
        private final String mTag;

        SensorListener(String tag) {
            mTag = tag;
        }

        SensorListener() {
            this("");
        }

        // Initialize the semaphore with ZERO callback events indicated
        private Semaphore mAvailable = new Semaphore(0);

        private CarSensorEvent mLastEvent = null;

        public CarSensorEvent getLastEvent() {
            return mLastEvent;
        }

        public void checkNoSensorChangePosted() {
            // Verify that no permits are available (ie: the callback has not fired)
            assertEquals("No events expected at this point for " + mTag + ".",
                    0, mAvailable.availablePermits());
        }

        // Returns True to indicate receipt of a sensor event.  False indicates a timeout.
        public boolean waitForSensorChange() throws InterruptedException {
            Log.i(TAG, mTag + ":Waiting to for sensor update...");

            long startTime = System.currentTimeMillis();
            boolean result = mAvailable.tryAcquire(2L, TimeUnit.SECONDS);
            long duration  = System.currentTimeMillis() - startTime;

            Log.d(TAG, mTag + ": tryAcquire returned " + result + " in " + duration + "ms");

            return result;
        }

        @Override
        public void onSensorChanged(CarSensorEvent event) {
            Log.d(TAG, mTag + ": onSensorChanged: " + event);

            // We're going to hold a reference to this object
            mLastEvent = event;

            // Add one to the semaphore, indicating that we have run
            mAvailable.release();
        }
    }

}
