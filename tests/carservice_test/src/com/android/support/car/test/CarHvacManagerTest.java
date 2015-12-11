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

package com.android.support.car.test;

import android.util.Log;

import com.android.car.hardware.hvac.CarHvacManager;
import com.android.car.hardware.hvac.CarHvacManager.CarHvacEventListener;
import com.android.car.hardware.hvac.CarHvacManager.CarHvacBooleanValue;
import com.android.car.hardware.hvac.CarHvacManager.CarHvacFloatValue;
import com.android.car.hardware.hvac.CarHvacManager.CarHvacIntValue;
import com.android.car.CarSystem;
import com.android.car.VehicleHalEmulator;
import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropAccess;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropChangeMode;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleWindow;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleZone;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehiclePropConfigUtil;
import com.android.car.vehiclenetwork.VehiclePropValueUtil;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;

public class CarHvacManagerTest extends MockedCarTestBase {
    private static final String TAG = CarHvacManagerTest.class.getSimpleName();

    // Use this semaphore to block until the callback is heard of.
    private Semaphore mAvailable;

    private CarHvacManager mCarHvacManager;
    private boolean mEventBoolVal;
    private float mEventFloatVal;
    private int mEventIntVal;
    private int mEventZoneVal;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mAvailable = new Semaphore(0);
        HvacPropertyHandler handler = new HvacPropertyHandler();
        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.createProperty(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_HVAC_DEFROSTER,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_BOOLEAN,
                        VehicleWindow.VEHICLE_WINDOW_FRONT_WINDSHIELD), handler);
        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.createProperty(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_HVAC_FAN_SPEED,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_INT32,
                        VehicleZone.VEHICLE_ZONE_ROW_1_LEFT), handler);
        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.createProperty(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_HVAC_TEMPERATURE_SET,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_FLOAT,
                        VehicleZone.VEHICLE_ZONE_ROW_1_LEFT), handler);
        getVehicleHalEmulator().start();
        mCarHvacManager =
                (CarHvacManager) getCarApi().getCarManager(CarSystem.HVAC_SERVICE);
    }

    // Test a boolean property
    public void testHvacRearDefrosterOn() throws Exception {
        boolean defrost;

        mCarHvacManager.setBooleanProperty(CarHvacManager.HVAC_WINDOW_DEFROSTER_ON,
                VehicleWindow.VEHICLE_WINDOW_FRONT_WINDSHIELD, true);
        defrost = mCarHvacManager.getBooleanProperty(CarHvacManager.HVAC_WINDOW_DEFROSTER_ON,
                VehicleWindow.VEHICLE_WINDOW_FRONT_WINDSHIELD);
        assertEquals("Front defroster is " + defrost, true, defrost);

        mCarHvacManager.setBooleanProperty(CarHvacManager.HVAC_WINDOW_DEFROSTER_ON,
                VehicleWindow.VEHICLE_WINDOW_FRONT_WINDSHIELD, false);
        defrost = mCarHvacManager.getBooleanProperty(CarHvacManager.HVAC_WINDOW_DEFROSTER_ON,
                VehicleWindow.VEHICLE_WINDOW_FRONT_WINDSHIELD);
        assertEquals("Front defroster is " + defrost, false, defrost);
    }

    // Test an integer property
    public void testHvacFanSpeed() throws Exception {
        int speed;

        mCarHvacManager.setIntProperty(CarHvacManager.HVAC_ZONED_FAN_SPEED_SETPOINT,
                VehicleZone.VEHICLE_ZONE_ROW_1_LEFT, 15);
        speed = mCarHvacManager.getIntProperty(CarHvacManager.HVAC_ZONED_FAN_SPEED_SETPOINT,
                VehicleZone.VEHICLE_ZONE_ROW_1_LEFT);
        assertEquals("Fan speed is " + speed, 15, speed);

        mCarHvacManager.setIntProperty(CarHvacManager.HVAC_ZONED_FAN_SPEED_SETPOINT,
                VehicleZone.VEHICLE_ZONE_ROW_1_LEFT, 23);
        speed = mCarHvacManager.getIntProperty(CarHvacManager.HVAC_ZONED_FAN_SPEED_SETPOINT,
                VehicleZone.VEHICLE_ZONE_ROW_1_LEFT);
        assertEquals("Fan speed is " + speed, 23, speed);
    }

    // Test an float property
    public void testHvacTempSetpoint() throws Exception {
        float temp;

        mCarHvacManager.setFloatProperty(CarHvacManager.HVAC_ZONED_TEMP_SETPOINT,
                VehicleZone.VEHICLE_ZONE_ROW_1_LEFT, 70);
        temp = mCarHvacManager.getFloatProperty(CarHvacManager.HVAC_ZONED_TEMP_SETPOINT,
                VehicleZone.VEHICLE_ZONE_ROW_1_LEFT);
        assertEquals("Temperature setpoint is  " + temp, 70.0, temp, 0);

        mCarHvacManager.setFloatProperty(CarHvacManager.HVAC_ZONED_TEMP_SETPOINT,
                VehicleZone.VEHICLE_ZONE_ROW_1_LEFT, (float) 65.5);
        temp = mCarHvacManager.getFloatProperty(CarHvacManager.HVAC_ZONED_TEMP_SETPOINT,
                VehicleZone.VEHICLE_ZONE_ROW_1_LEFT);
        assertEquals("Temperature setpoint is  " + temp, 65.5, temp, 0);
    }

    // Test an event
    public void testEvent() throws Exception {
        boolean success;
        EventListener l = new EventListener();
        mCarHvacManager.registerListener(l);

        // Inject a boolean event and wait for its callback in onPropertySet.
        VehiclePropValue v = VehiclePropValueUtil.createZonedBooleanValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_HVAC_AC_ON,
                VehicleZone.VEHICLE_ZONE_ALL, true, 0);
        assertEquals("Lock should be freed by now.", 0, mAvailable.availablePermits());
        getVehicleHalEmulator().injectEvent(v);

        success = mAvailable.tryAcquire(2L, TimeUnit.SECONDS);
        assertEquals("injectEvent, onEvent timeout!", true, success);
        assertEquals("Value is incorrect", mEventBoolVal, true);
        assertEquals("Zone is incorrect", mEventZoneVal, VehicleZone.VEHICLE_ZONE_ALL);

        // Inject a float event and wait for its callback in onPropertySet.
        v = VehiclePropValueUtil.createZonedFloatValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_HVAC_TEMPERATURE_CURRENT,
                VehicleZone.VEHICLE_ZONE_ROW_1_ALL, 67, 0);
        assertEquals("Lock should be freed by now.", 0, mAvailable.availablePermits());
        getVehicleHalEmulator().injectEvent(v);

        success = mAvailable.tryAcquire(2L, TimeUnit.SECONDS);
        assertEquals("injectEvent, onEvent timeout!", true, success);
        assertEquals("Value is incorrect", mEventFloatVal, 67, 0);
        assertEquals("Zone is incorrect", mEventZoneVal, VehicleZone.VEHICLE_ZONE_ROW_1_ALL);

        // Inject an integer event and wait for its callback in onPropertySet.
        v = VehiclePropValueUtil.createZonedIntValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_HVAC_FAN_SPEED,
                VehicleZone.VEHICLE_ZONE_ROW_1_RIGHT, 4, 0);
        assertEquals("Lock should be freed by now.", 0, mAvailable.availablePermits());
        getVehicleHalEmulator().injectEvent(v);

        success = mAvailable.tryAcquire(2L, TimeUnit.SECONDS);
        assertEquals("injectEvent, onEvent timeout!", true, success);
        assertEquals("Value is incorrect", mEventIntVal, 4);
        assertEquals("Zone is incorrect", mEventZoneVal, VehicleZone.VEHICLE_ZONE_ROW_1_RIGHT);
    }


    private class HvacPropertyHandler
            implements VehicleHalEmulator.VehicleHalPropertyHandler {
        HashMap<Integer, VehiclePropValue> mMap = new HashMap<Integer, VehiclePropValue>();

        @Override
        public synchronized void onPropertySet(VehiclePropValue value) {
            Log.d(TAG, "onPropertySet intValue = " + value.getZonedValue().getInt32Value() +
                    " floatValue = " + value.getZonedValue().getFloatValue());
            mMap.put(value.getProp(), value);
        }

        @Override
        public synchronized VehiclePropValue onPropertyGet(VehiclePropValue value) {
            Log.d(TAG, "onPropertyGet intValue = " + value.getZonedValue().getInt32Value() +
                    " floatValue = " + value.getZonedValue().getFloatValue());
            return mMap.get(value.getProp());
        }

        @Override
        public synchronized void onPropertySubscribe(int property, int sampleRate) {
            Log.d(TAG, "onPropertySubscribe property " + property + " sampleRate " + sampleRate);
        }

        @Override
        public synchronized void onPropertyUnsubscribe(int property) {
            Log.d(TAG, "onPropertyUnSubscribe property " + property);
        }
    }

    private class EventListener implements CarHvacEventListener {
        public EventListener() { }

        @Override
        public void onChangeEvent(final CarHvacManager.CarHvacBaseProperty value) {
            switch (value.getType()) {
                case CarHvacManager.PROPERTY_TYPE_BOOLEAN:
                    CarHvacBooleanValue boolVal = (CarHvacBooleanValue) value;
                    mEventBoolVal = boolVal.getValue();
                    mEventZoneVal = boolVal.getZone();
                    Log.d(TAG, "onChangeEvent - propId = " + boolVal.getPropertyId() +
                            " bool = " + boolVal.getValue());
                    break;
                case CarHvacManager.PROPERTY_TYPE_FLOAT:
                    CarHvacFloatValue floatVal = (CarHvacFloatValue) value;
                    mEventFloatVal = floatVal.getValue();
                    mEventZoneVal = floatVal.getZone();
                    Log.d(TAG, "onChangeEvent - propId = " + floatVal.getPropertyId() +
                            " float = " + floatVal.getValue());
                    break;
                case CarHvacManager.PROPERTY_TYPE_INT:
                    CarHvacIntValue intVal = (CarHvacIntValue) value;
                    mEventIntVal = intVal.getValue();
                    mEventZoneVal = intVal.getZone();
                    Log.d(TAG, "onChangeEvent - propId = " + intVal.getPropertyId() +
                            " int = " + intVal.getValue());
                    break;
            }
            mAvailable.release();
        }

        @Override
        public void onErrorEvent(final int propertyId, final int zone) {
            Log.d(TAG, "Error:  propertyId=" + propertyId + "  zone=" + zone);
        }

    }
}
