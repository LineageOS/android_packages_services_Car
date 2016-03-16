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
package com.android.car.apitest;

import java.util.List;

import android.car.Car;
import android.car.VehicleZoneUtil;
import android.car.hardware.hvac.CarHvacManager;
import android.car.hardware.hvac.CarHvacManager.CarHvacBaseProperty;
import android.car.hardware.hvac.CarHvacManager.CarHvacFloatProperty;
import android.car.hardware.hvac.CarHvacManager.CarHvacIntProperty;
import android.util.Log;

public class CarHvacManagerTest extends CarApiTestBase {
    private static final String TAG = CarHvacManagerTest.class.getSimpleName();

    private CarHvacManager mHvacManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mHvacManager = (CarHvacManager) getCar().getCarManager(Car.HVAC_SERVICE);
        assertNotNull(mHvacManager);
    }

    public void testAllHvacProperties() throws Exception {
        List<CarHvacBaseProperty> properties = mHvacManager.getPropertyList();
        for (CarHvacBaseProperty property : properties) {
            switch (property.getType()) {
                case CarHvacManager.PROPERTY_TYPE_BOOLEAN:
                case CarHvacManager.PROPERTY_TYPE_FLOAT:
                case CarHvacManager.PROPERTY_TYPE_INT:
                case CarHvacManager.PROPERTY_TYPE_INT_VECTOR:
                case CarHvacManager.PROPERTY_TYPE_FLOAT_VECTOR:
                    assrtTypeAndZone(property);
                    break;
                default:
                    fail("Unknown property type " + property.getType());
                    break;
            }
        }
    }

    private void assrtTypeAndZone(CarHvacBaseProperty property) {
        switch (property.getPropertyId()) {
            case CarHvacManager.HVAC_MIRROR_DEFROSTER_ON: // non-zoned bool
            case CarHvacManager.HVAC_AUTOMATIC_MODE_ON:
            case CarHvacManager.HVAC_AIR_RECIRCULATION_ON:
                assertEquals(CarHvacManager.PROPERTY_TYPE_BOOLEAN, property.getType());
                assertFalse(property.isZonedProperty());
                break;
            case CarHvacManager.HVAC_STEERING_WHEEL_TEMP: // non-zoned int
                assertEquals(CarHvacManager.PROPERTY_TYPE_INT, property.getType());
                assertFalse(property.isZonedProperty());
                checkIntMinMax((CarHvacIntProperty) property);
                break;
            case CarHvacManager.HVAC_ZONED_TEMP_SETPOINT: // zoned float
            case CarHvacManager.HVAC_ZONED_TEMP_ACTUAL:
                assertEquals(CarHvacManager.PROPERTY_TYPE_FLOAT, property.getType());
                assertTrue(property.isZonedProperty());
                checkFloatMinMax((CarHvacFloatProperty) property);
                break;
            case CarHvacManager.HVAC_ZONED_TEMP_IS_FARENHEIT: // zoned boolean
            case CarHvacManager.HVAC_ZONED_AC_ON:
            case CarHvacManager.HVAC_WINDOW_DEFROSTER_ON:
                assertEquals(CarHvacManager.PROPERTY_TYPE_BOOLEAN, property.getType());
                assertTrue(property.isZonedProperty());
                break;
            case CarHvacManager.HVAC_ZONED_FAN_SPEED_SETPOINT: // zoned int
            case CarHvacManager.HVAC_ZONED_FAN_SPEED_RPM:
            case CarHvacManager.HVAC_ZONED_FAN_POSITION_AVAILABLE:
            case CarHvacManager.HVAC_ZONED_FAN_POSITION:
            case CarHvacManager.HVAC_ZONED_SEAT_TEMP:
                assertEquals(CarHvacManager.PROPERTY_TYPE_INT, property.getType());
                assertTrue(property.isZonedProperty());
                checkIntMinMax((CarHvacIntProperty) property);
                break;
        }
    }

    private void checkIntMinMax(CarHvacIntProperty property) {
        Log.i(TAG, "checkIntMinMax propery:" + property);
        if (property.isZonedProperty()) {
            assertTrue(property.getZones() != 0);
            for (int zone : VehicleZoneUtil.listAllZones(property.getZones())) {
                int min = property.getMinValue(zone);
                int max = property.getMaxValue(zone);
                assertTrue(min <= max);
            }
            try {
                int min = property.getMinValue();
                fail();
            } catch (IllegalArgumentException e) {
                // expected
            }
            try {
                int max = property.getMaxValue();
                fail();
            } catch (IllegalArgumentException e) {
                // expected
            }
        } else {
            int min = property.getMinValue();
            int max = property.getMaxValue();
            assertTrue(min <= max);
            for (int i = 0; i < 32; i++) {
                try {
                    min = property.getMinValue(0x1 << i);
                    fail();
                } catch (IllegalArgumentException e) {
                    // expected
                }
                try {
                    max = property.getMaxValue(0x1 << i);
                    fail();
                } catch (IllegalArgumentException e) {
                    // expected
                }
            }
        }
    }

    private void checkFloatMinMax(CarHvacFloatProperty property) {
        Log.i(TAG, "checkFloatMinMax propery:" + property);
        if (property.isZonedProperty()) {
            assertTrue(property.getZones() != 0);
            for (int zone : VehicleZoneUtil.listAllZones(property.getZones())) {
                float min = property.getMinValue(zone);
                float max = property.getMaxValue(zone);
                assertTrue(min <= max);
            }
            try {
                float min = property.getMinValue();
                fail();
            } catch (IllegalArgumentException e) {
                // expected
            }
            try {
                float max = property.getMaxValue();
                fail();
            } catch (IllegalArgumentException e) {
                // expected
            }
        } else {
            float min = property.getMinValue();
            float max = property.getMaxValue();
            assertTrue(min <= max);
            for (int i = 0; i < 32; i++) {
                try {
                    min = property.getMinValue(0x1 << i);
                    fail();
                } catch (IllegalArgumentException e) {
                    // expected
                }
                try {
                    max = property.getMaxValue(0x1 << i);
                    fail();
                } catch (IllegalArgumentException e) {
                    // expected
                }
            }
        }
    }
}
