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

import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePermissionModel.VEHICLE_PERMISSION_NO_RESTRICTION;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType.VEHICLE_VALUE_TYPE_BYTES;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType.VEHICLE_VALUE_TYPE_INT32;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType.VEHICLE_VALUE_TYPE_STRING;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_FLOAT;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleZone.VEHICLE_ZONE_ROW_1_LEFT;
import static com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleZone.VEHICLE_ZONE_ROW_1_RIGHT;
import static com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue.newBuilder;
import static java.util.Collections.singletonList;

import android.car.Car;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarVendorExtensionManager;
import android.car.test.CarTestManager;
import android.car.test.CarTestManagerBinderWrapper;
import android.util.Log;
import android.util.SparseArray;

import com.google.android.collect.Lists;
import com.google.protobuf.ByteString;

import com.android.car.vehiclenetwork.VehicleNetwork.VehicleNetworkHalMock;
import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfig;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfigs;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehiclePropValueUtil;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for {@link CarVendorExtensionManager}
 */
public class CarVendorExtensionManagerTest extends CarApiTestBase {

    private static final String TAG = CarVendorExtensionManager.class.getSimpleName();

    private static final int CUSTOM_GLOBAL_INT_PROP_ID =
            VehicleNetworkConsts.VEHICLE_PROPERTY_CUSTOM_START;
    private static final int CUSTOM_ZONED_FLOAT_PROP_ID =
            VehicleNetworkConsts.VEHICLE_PROPERTY_CUSTOM_START + 1;
    private static final int CUSTOM_BYTES_PROP_ID_1 =
            VehicleNetworkConsts.VEHICLE_PROPERTY_CUSTOM_START + 2;
    private static final int CUSTOM_BYTES_PROP_ID_2 =
            VehicleNetworkConsts.VEHICLE_PROPERTY_CUSTOM_START + 3;
    private static final int CUSTOM_STRING_PROP_ID =
            VehicleNetworkConsts.VEHICLE_PROPERTY_CUSTOM_START + 4;

    private static final float EPS = 1e-9f;

    private static final int MIN_PROP_INT32 = 0x0000005;
    private static final int MAX_PROP_INT32 = 0xDeadBee;

    private static final float MIN_PROP_FLOAT = 10.42f;
    private static final float MAX_PROP_FLOAT = 42.10f;

    private static final MockedVehicleHal mVehicleHal = new MockedVehicleHal();

    private static final VehiclePropConfigs VEHICLE_HAL_CONFIGS = VehiclePropConfigs.newBuilder()
            .addConfigs(VehiclePropConfig.newBuilder()
                    .setProp(CUSTOM_GLOBAL_INT_PROP_ID)
                    .setAccess(VEHICLE_PROP_ACCESS_READ_WRITE)
                    .setChangeMode(VEHICLE_PROP_CHANGE_MODE_ON_CHANGE)
                    .setValueType(VEHICLE_VALUE_TYPE_INT32)
                    .setPermissionModel(VEHICLE_PERMISSION_NO_RESTRICTION)
                    .addConfigArray(0)
                    .setSampleRateMin(0f)
                    .setSampleRateMax(0f)
                    .addAllInt32Mins(singletonList(MIN_PROP_INT32))
                    .addAllInt32Maxs(singletonList(MAX_PROP_INT32))
                    .build())
            .addConfigs(VehiclePropConfig.newBuilder()
                    .setProp(CUSTOM_ZONED_FLOAT_PROP_ID)
                    .setAccess(VEHICLE_PROP_ACCESS_READ_WRITE)
                    .setChangeMode(VEHICLE_PROP_CHANGE_MODE_ON_CHANGE)
                    .setValueType(VEHICLE_VALUE_TYPE_ZONED_FLOAT)
                    .setPermissionModel(VEHICLE_PERMISSION_NO_RESTRICTION)
                    .setZones(VEHICLE_ZONE_ROW_1_LEFT | VEHICLE_ZONE_ROW_1_RIGHT)
                    .addConfigArray(0)
                    .setSampleRateMin(0f)
                    .setSampleRateMax(0f)
                    .addAllFloatMins(Lists.newArrayList(MIN_PROP_FLOAT, MIN_PROP_FLOAT))
                    .addAllFloatMaxs(Lists.newArrayList(MAX_PROP_FLOAT, MAX_PROP_FLOAT))
                    .build())
            .addConfigs(VehiclePropConfig.newBuilder()
                    .setProp(CUSTOM_BYTES_PROP_ID_1)
                    .setAccess(VEHICLE_PROP_ACCESS_READ_WRITE)
                    .setChangeMode(VEHICLE_PROP_CHANGE_MODE_ON_CHANGE)
                    .setValueType(VEHICLE_VALUE_TYPE_BYTES)
                    .setPermissionModel(VEHICLE_PERMISSION_NO_RESTRICTION)
                    .setZones(VEHICLE_ZONE_ROW_1_LEFT | VEHICLE_ZONE_ROW_1_RIGHT)
                    .addConfigArray(0)
                    .setSampleRateMin(0f)
                    .setSampleRateMax(0f)
                    .build())
            .addConfigs(VehiclePropConfig.newBuilder()
                    .setProp(CUSTOM_BYTES_PROP_ID_2)
                    .setAccess(VEHICLE_PROP_ACCESS_READ_WRITE)
                    .setChangeMode(VEHICLE_PROP_CHANGE_MODE_ON_CHANGE)
                    .setValueType(VEHICLE_VALUE_TYPE_BYTES)
                    .setPermissionModel(VEHICLE_PERMISSION_NO_RESTRICTION)
                    .addConfigArray(0)
                    .setSampleRateMin(0f)
                    .setSampleRateMax(0f)
                    .build())
            .addConfigs(VehiclePropConfig.newBuilder()
                    .setProp(CUSTOM_STRING_PROP_ID)
                    .setAccess(VEHICLE_PROP_ACCESS_READ_WRITE)
                    .setChangeMode(VEHICLE_PROP_CHANGE_MODE_ON_CHANGE)
                    .setValueType(VEHICLE_VALUE_TYPE_STRING)
                    .setPermissionModel(VEHICLE_PERMISSION_NO_RESTRICTION)
                    .addConfigArray(0)
                    .setSampleRateMin(0f)
                    .setSampleRateMax(0f)
                    .build())
            .build();

    private CarVendorExtensionManager mManager;


    @Override
    protected void setUp() throws Exception {
        super.setUp();

        CarTestManagerBinderWrapper testManagerWrapper =
                (CarTestManagerBinderWrapper) getCar().getCarManager(Car.TEST_SERVICE);

        CarTestManager carTestManager = new CarTestManager(testManagerWrapper);
        carTestManager.startMocking(mVehicleHal, 0);

        mManager = (CarVendorExtensionManager) getCar().getCarManager(Car.VENDOR_EXTENSION_SERVICE);
        assertNotNull(mManager);
    }

    public void testPropertyList() throws Exception {
        List<CarPropertyConfig> configs = mManager.getProperties();
        assertEquals(VEHICLE_HAL_CONFIGS.getConfigsCount(), configs.size());

        SparseArray<CarPropertyConfig> configById = new SparseArray<>(configs.size());
        for (CarPropertyConfig config : configs) {
            configById.put(config.getPropertyId(), config);
        }

        CarPropertyConfig prop1 = configById.get(CUSTOM_GLOBAL_INT_PROP_ID);
        assertNotNull(prop1);
        assertEquals(Integer.class, prop1.getPropertyType());
        assertEquals(MIN_PROP_INT32, prop1.getMinValue());
        assertEquals(MAX_PROP_INT32, prop1.getMaxValue());
    }

    public void testIntGlobalProperty() throws Exception {
        final int value = 0xbeef;
        mManager.setGlobalProperty(Integer.class, CUSTOM_GLOBAL_INT_PROP_ID, value);
        int actualValue = mManager.getGlobalProperty(Integer.class, CUSTOM_GLOBAL_INT_PROP_ID);
        assertEquals(value, actualValue);
    }

    public void testFloatZonedProperty() throws Exception {
        final float value = MIN_PROP_FLOAT + 1;
        mManager.setProperty(
                Float.class,
                CUSTOM_ZONED_FLOAT_PROP_ID,
                VEHICLE_ZONE_ROW_1_RIGHT,
                value);

        float actualValue = mManager.getProperty(
                Float.class, CUSTOM_ZONED_FLOAT_PROP_ID, VEHICLE_ZONE_ROW_1_RIGHT);
        assertEquals(value, actualValue, EPS);
    }

    public void testByteArrayProperty() throws Exception {
        final Byte[] expectedData = new Byte[] { 1, 2, 3, 4, -1, 127, -127, 0 };

        // Write to CUSTOM_BYTES_PROP_ID_1 and read this value from CUSTOM_BYTES_PROP_ID_2
        mManager.setGlobalProperty(
                Byte[].class,
                CUSTOM_BYTES_PROP_ID_1,
                expectedData);

        Byte[] actualData = mManager.getGlobalProperty(
                Byte[].class,
                CUSTOM_BYTES_PROP_ID_2);

        assertEquals(Arrays.toString(expectedData), Arrays.toString(actualData));
    }

    public void testStringProperty() throws Exception {
        final String expectedString = "εὕρηκα!";  // Test some utf as well.

        mManager.setGlobalProperty(
                String.class,
                CUSTOM_STRING_PROP_ID,
                expectedString);

        String actualString = mManager.getGlobalProperty(
                String.class,
                CUSTOM_STRING_PROP_ID);

        assertEquals(expectedString, actualString);
    }

    private static class MockedVehicleHal implements VehicleNetworkHalMock {
        private final SparseArray<VehiclePropValue> mValues = new SparseArray<>();

        private byte[] mBytes = null;

        @Override
        public VehiclePropConfigs onListProperties() {
            return VEHICLE_HAL_CONFIGS;
        }

        @Override
        public void onPropertySet(VehiclePropValue value) {
            if (value.getProp() == CUSTOM_BYTES_PROP_ID_1) {
                mBytes = value.getBytesValue().toByteArray();
            }

            mValues.put(value.getProp(), newBuilder(value).build());
        }

        @Override
        public VehiclePropValue onPropertyGet(VehiclePropValue value) {
            Log.d(TAG, "onPropertyGet: 0x" + Integer.toHexString(value.getProp()) + ", "
                    + VehiclePropValueUtil.toString(value));

            if (value.getProp() == CUSTOM_BYTES_PROP_ID_2 && mBytes != null) {
                Log.d(TAG, "Returning byte array property, value: " + Arrays.toString(mBytes));
                return VehiclePropValue
                        .newBuilder(value)
                        .setBytesValue(ByteString.copyFrom(mBytes))
                        .build();
            }

            VehiclePropValue existingValue = mValues.get(value.getProp());
            if (existingValue != null) {
                return existingValue;
            }

            int type = value.getValueType();

            // VehicleNetwork will fail if we do not set empty data for these properties.
            if (type == VEHICLE_VALUE_TYPE_BYTES && !value.hasBytesValue()) {
                return VehiclePropValue.newBuilder(value).setBytesValue(ByteString.EMPTY).build();
            } else if (type == VEHICLE_VALUE_TYPE_STRING && !value.hasStringValue()) {
                return VehiclePropValue.newBuilder(value).setStringValue("").build();
            } else {
                return value;
            }
        }

        @Override
        public void onPropertySubscribe(int property, float sampleRate, int zones) { }

        @Override
        public void onPropertyUnsubscribe(int property) { }
    }
}
