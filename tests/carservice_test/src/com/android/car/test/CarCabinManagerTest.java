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
import android.car.hardware.cabin.CarCabinManager;
import android.car.hardware.cabin.CarCabinManager.CarCabinEventListener;
import android.car.hardware.cabin.CarCabinManager.CabinPropertyId;
import android.car.hardware.CarPropertyValue;
import android.car.test.VehicleHalEmulator;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;

import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleDoor;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropAccess;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropChangeMode;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleWindow;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehiclePropConfigUtil;
import com.android.car.vehiclenetwork.VehiclePropValueUtil;

import java.util.HashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@MediumTest
public class CarCabinManagerTest extends MockedCarTestBase {
    private static final String TAG = CarCabinManagerTest.class.getSimpleName();

    // Use this semaphore to block until the callback is heard of.
    private Semaphore mAvailable;

    private CarCabinManager mCarCabinManager;
    private boolean mEventBoolVal;
    private float mEventFloatVal;
    private int mEventIntVal;
    private int mEventZoneVal;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mAvailable = new Semaphore(0);
        CabinPropertyHandler handler = new CabinPropertyHandler();
        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.createZonedProperty(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_DOOR_LOCK,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_BOOLEAN,
                        VehicleNetworkConsts.VehicleDoor.VEHICLE_DOOR_ROW_1_LEFT,
                        0), handler);
        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.createZonedProperty(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_POS,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_INT32,
                        VehicleWindow.VEHICLE_WINDOW_ROW_1_LEFT,
                        0), handler);

        getVehicleHalEmulator().start();
        mCarCabinManager = (CarCabinManager) getCar().getCarManager(Car.CABIN_SERVICE);
    }

    // Test a boolean property
    public void testCabinDoorLockOn() throws Exception {
        mCarCabinManager.setBooleanProperty(CabinPropertyId.DOOR_LOCK,
                VehicleDoor.VEHICLE_DOOR_ROW_1_LEFT, true);
        boolean lock = mCarCabinManager.getBooleanProperty(CabinPropertyId.DOOR_LOCK,
                VehicleDoor.VEHICLE_DOOR_ROW_1_LEFT);
        assertTrue(lock);

        mCarCabinManager.setBooleanProperty(CabinPropertyId.DOOR_LOCK,
                VehicleDoor.VEHICLE_DOOR_ROW_1_LEFT, false);
        lock = mCarCabinManager.getBooleanProperty(CabinPropertyId.DOOR_LOCK,
                VehicleDoor.VEHICLE_DOOR_ROW_1_LEFT);
        assertFalse(lock);
    }

    // Test an integer property
    public void testCabinWindowPos() throws Exception {
        mCarCabinManager.setIntProperty(CabinPropertyId.WINDOW_POS,
                VehicleWindow.VEHICLE_WINDOW_ROW_1_LEFT, 50);
        int windowPos = mCarCabinManager.getIntProperty(CabinPropertyId.WINDOW_POS,
                VehicleWindow.VEHICLE_WINDOW_ROW_1_LEFT);
        assertEquals(50, windowPos);

        mCarCabinManager.setIntProperty(CabinPropertyId.WINDOW_POS,
                VehicleWindow.VEHICLE_WINDOW_ROW_1_LEFT, 25);
        windowPos = mCarCabinManager.getIntProperty(CabinPropertyId.WINDOW_POS,
                VehicleWindow.VEHICLE_WINDOW_ROW_1_LEFT);
        assertEquals(25, windowPos);
    }

    // Test an event
    public void testEvent() throws Exception {
        mCarCabinManager.registerListener(new EventListener());

        // Inject a boolean event and wait for its callback in onPropertySet.
        VehiclePropValue v = VehiclePropValueUtil.createZonedBooleanValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_DOOR_LOCK,
                VehicleDoor.VEHICLE_DOOR_ROW_1_LEFT, true, 0);
        assertEquals(0, mAvailable.availablePermits());
        getVehicleHalEmulator().injectEvent(v);

        assertTrue(mAvailable.tryAcquire(2L, TimeUnit.SECONDS));
        assertTrue(mEventBoolVal);
        assertEquals(mEventZoneVal, VehicleDoor.VEHICLE_DOOR_ROW_1_LEFT);

        // Inject an integer event and wait for its callback in onPropertySet.
        v = VehiclePropValueUtil.createZonedIntValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_POS,
                VehicleWindow.VEHICLE_WINDOW_ROW_1_LEFT, 75, 0);
        assertEquals(0, mAvailable.availablePermits());
        getVehicleHalEmulator().injectEvent(v);

        assertTrue(mAvailable.tryAcquire(2L, TimeUnit.SECONDS));
        assertEquals(mEventIntVal, 75);
        assertEquals(mEventZoneVal, VehicleWindow.VEHICLE_WINDOW_ROW_1_LEFT);
    }


    private class CabinPropertyHandler
            implements VehicleHalEmulator.VehicleHalPropertyHandler {
        HashMap<Integer, VehiclePropValue> mMap = new HashMap<>();

        @Override
        public synchronized void onPropertySet(VehiclePropValue value) {
            mMap.put(value.getProp(), value);
        }

        @Override
        public synchronized VehiclePropValue onPropertyGet(VehiclePropValue value) {
            VehiclePropValue currentValue = mMap.get(value.getProp());
            // VNS will call getProperty method when subscribe is called, just return empty value.
            return currentValue != null ? currentValue : value;
        }

        @Override
        public synchronized void onPropertySubscribe(int property, float sampleRate, int zones) {
            Log.d(TAG, "onPropertySubscribe property " + property + " sampleRate " + sampleRate);
        }

        @Override
        public synchronized void onPropertyUnsubscribe(int property) {
            Log.d(TAG, "onPropertyUnSubscribe property " + property);
        }
    }

    private class EventListener implements CarCabinEventListener {
        public EventListener() { }

        @Override
        public void onChangeEvent(final CarPropertyValue value) {
            Log.d(TAG, "onChangeEvent: "  + value);
            Object o = value.getValue();
            mEventZoneVal = value.getAreaId();

            if (o instanceof Integer) {
                mEventIntVal = (Integer) o;
            } else if (o instanceof Boolean) {
                mEventBoolVal = (Boolean) o;
            } else {
                Log.e(TAG, "onChangeEvent:  Unknown instance type = " + o.getClass().getName());
            }
            mAvailable.release();
        }

        @Override
        public void onErrorEvent(final int propertyId, final int zone) {
            Log.d(TAG, "Error:  propertyId=" + propertyId + "  zone=" + zone);
        }
    }
}
