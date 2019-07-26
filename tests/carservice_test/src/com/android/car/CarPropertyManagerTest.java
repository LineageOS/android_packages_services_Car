/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.hardware.automotive.vehicle.V2_0.VehicleArea;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyGroup;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyType;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.car.vehiclehal.test.MockedVehicleHal.VehicleHalPropertyHandler;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Test for {@link android.car.hardware.property.CarPropertyManager}
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class CarPropertyManagerTest extends MockedCarTestBase {

    private static final String TAG = CarPropertyManagerTest.class.getSimpleName();

    /**
     * configArray[0], 1 indicates the property has a String value
     * configArray[1], 1 indicates the property has a Boolean value .
     * configArray[2], 1 indicates the property has a Integer value
     * configArray[3], the number indicates the size of Integer[]  in the property.
     * configArray[4], 1 indicates the property has a Long value .
     * configArray[5], the number indicates the size of Long[]  in the property.
     * configArray[6], 1 indicates the property has a Float value .
     * configArray[7], the number indicates the size of Float[] in the property.
     * configArray[8], the number indicates the size of byte[] in the property.
     */
    private static final java.util.Collection<Integer> CONFIG_ARRAY_1 =
            Arrays.asList(1, 0, 1, 0, 1, 0, 0, 0, 0);
    private static final java.util.Collection<Integer> CONFIG_ARRAY_2 =
            Arrays.asList(1, 1, 1, 0, 0, 0, 0, 2, 0);
    private static final Object[] EXPECTED_VALUE_1 = {"android", 1, 1L};
    private static final Object[] EXPECTED_VALUE_2 = {"android", true, 3, 1.1f, 2f};

    private static final int CUSTOM_GLOBAL_MIXED_PROP_ID_1 =
            0x1101 | VehiclePropertyGroup.VENDOR | VehiclePropertyType.MIXED | VehicleArea.GLOBAL;
    private static final int CUSTOM_GLOBAL_MIXED_PROP_ID_2 =
            0x1102 | VehiclePropertyGroup.VENDOR | VehiclePropertyType.MIXED | VehicleArea.GLOBAL;

    private CarPropertyManager mManager;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mManager = (CarPropertyManager) getCar().getCarManager(Car.PROPERTY_SERVICE);
        Assert.assertNotNull(mManager);
    }

    @Test
    public void testMixedPropertyConfigs() {
        List<CarPropertyConfig> configs = mManager.getPropertyList();
        Assert.assertEquals(2, configs.size());

        for (CarPropertyConfig cfg : configs) {
            switch (cfg.getPropertyId()) {
                case CUSTOM_GLOBAL_MIXED_PROP_ID_1:
                    Assert.assertArrayEquals(CONFIG_ARRAY_1.toArray(),
                            cfg.getConfigArray().toArray());
                    break;
                case CUSTOM_GLOBAL_MIXED_PROP_ID_2:
                    Assert.assertArrayEquals(CONFIG_ARRAY_2.toArray(),
                            cfg.getConfigArray().toArray());
                    break;
                default:
                    Assert.fail("Unexpected CarPropertyConfig: " + cfg.toString());
            }
        }
    }

    @Test
    public void testGetMixTypeProperty() {
        mManager.setProperty(Object[].class, CUSTOM_GLOBAL_MIXED_PROP_ID_1,
                0, EXPECTED_VALUE_1);
        CarPropertyValue<Object[]> result = mManager.getProperty(
                CUSTOM_GLOBAL_MIXED_PROP_ID_1, 0);
        Assert.assertArrayEquals(EXPECTED_VALUE_1, result.getValue());

        mManager.setProperty(Object[].class, CUSTOM_GLOBAL_MIXED_PROP_ID_2,
                0, EXPECTED_VALUE_2);
        result = mManager.getProperty(
                CUSTOM_GLOBAL_MIXED_PROP_ID_2, 0);
        Assert.assertArrayEquals(EXPECTED_VALUE_2, result.getValue());
    }


    @Override
    protected synchronized void configureMockedHal() {
        PropertyHandler handler = new PropertyHandler();
        addProperty(CUSTOM_GLOBAL_MIXED_PROP_ID_1, handler).setConfigArray(CONFIG_ARRAY_1);
        addProperty(CUSTOM_GLOBAL_MIXED_PROP_ID_2, handler).setConfigArray(CONFIG_ARRAY_2);


    }

    private class PropertyHandler implements VehicleHalPropertyHandler {
        HashMap<Integer, VehiclePropValue> mMap = new HashMap<>();
        @Override
        public synchronized void onPropertySet(VehiclePropValue value) {
            mMap.put(value.prop, value);
        }

        @Override
        public synchronized VehiclePropValue onPropertyGet(VehiclePropValue value) {
            VehiclePropValue currentValue = mMap.get(value.prop);
            return currentValue != null ? currentValue : value;
        }

        @Override
        public synchronized void onPropertySubscribe(int property, float sampleRate) {
            Log.d(TAG, "onPropertySubscribe property "
                    + property + " sampleRate " + sampleRate);
        }

        @Override
        public synchronized void onPropertyUnsubscribe(int property) {
            Log.d(TAG, "onPropertyUnSubscribe property " + property);
        }
    }
}
