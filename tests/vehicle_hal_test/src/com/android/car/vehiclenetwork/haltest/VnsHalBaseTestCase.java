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

package com.android.car.vehiclenetwork.haltest;

import static java.lang.Integer.toHexString;

import android.car.VehicleZoneUtil;
import android.os.HandlerThread;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.car.vehiclenetwork.VehicleNetwork;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for testing vehicle HAL using vehicle network service (VNS).
 */
public class VnsHalBaseTestCase extends AndroidTestCase {

    protected static final String TAG = "VnsHalTest";

    protected static final int PROP_TIMEOUT_MS = 5000;
    protected static final int NO_ZONE = -1;

    private final HandlerThread mHandlerThread =
            new HandlerThread(VnsHalBaseTestCase.class.getSimpleName());
    protected VehicleNetwork mVehicleNetwork;
    protected RecordingVehicleNetworkListener mListener;

    protected Map<Integer, VehiclePropConfig> mConfigsMap;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mHandlerThread.start();
        mListener = new RecordingVehicleNetworkListener();
        mVehicleNetwork = VehicleNetwork.createVehicleNetwork(mListener,
                mHandlerThread.getLooper());
        setupConfigsMap();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mHandlerThread.quit();
    }

    private void setupConfigsMap() {
        mConfigsMap = new HashMap<>();
        for (VehiclePropConfig config : mVehicleNetwork.listProperties().getConfigsList()) {
            mConfigsMap.put(config.getProp(), config);
        }
        assertTrue(mConfigsMap.size() > 0);
    }

    protected boolean isPropertyAvailable(int... properties) {
        assertTrue(properties.length > 0);

        for (int propertyId : properties) {
            if (!mConfigsMap.containsKey(propertyId)) {
                // Property is not supported by vehicle HAL, nothing to test.
                Log.w(TAG, "Property: 0x" + Integer.toHexString(propertyId)
                        + " is not available, ignoring...");
                return false;
            }
        }

        return true;
    }

    protected void setPropertyAndVerify(int propertyId, boolean switchOn) {
        setPropertyAndVerify(propertyId, NO_ZONE, switchOn);
    }

    protected void setPropertyAndVerify(int propertyId, int zone, boolean switchOn) {
        int val = switchOn ? 1 : 0;

        if (zone == NO_ZONE) {
            mVehicleNetwork.setBooleanProperty(propertyId, switchOn);
        } else {
            mVehicleNetwork.setZonedBooleanProperty(propertyId, zone, switchOn);
        }
        verifyValue(propertyId, zone, val);
    }

    protected void setPropertyAndVerify(String message, int propertyId, int zone, int value) {
        if (zone == NO_ZONE) {
            mVehicleNetwork.setIntProperty(propertyId, value);
        } else {
            mVehicleNetwork.setZonedIntProperty(propertyId, zone, value);
        }
        verifyValue(message, propertyId, zone, value);
    }

    protected void verifyValue(int propertyId, boolean val) {
        verifyValue(propertyId, NO_ZONE, val);
    }

    protected void verifyValue(int propertyId, int zone, boolean val) {
        int intVal = val ? 1 : 0;
        assertEquals(intVal, waitForIntValue(propertyId, zone, intVal));
    }

    protected void verifyValue(int propertyId, int zone, int val) {
        assertEquals(val, waitForIntValue(propertyId, zone, val));
    }

    protected void verifyValue(String message, int propertyId, int zone, int val) {
        assertEquals(message, val, waitForIntValue(propertyId, zone, val));
    }

    protected int getFirstZoneForProperty(int propertyId) {
        return VehicleZoneUtil.getFirstZone(mConfigsMap.get(propertyId).getZones());
    }

    protected void verifyIntZonedProperty(int propertyId) throws InterruptedException {
        if (!isPropertyAvailable(propertyId)) {
            return;
        }

        VehiclePropConfig config = mConfigsMap.get(propertyId);

        // Assert setting edge-case values.
        iterateOnZones(config, (message, zone, minValue, maxValue) -> {
            setPropertyAndVerify(message, propertyId, zone, minValue);
            setPropertyAndVerify(message, propertyId, zone, maxValue);
        });

        // Setting out of the range values.
        iterateOnZones(config, ((message, zone, minValue, maxValue) -> {
            mVehicleNetwork.setZonedIntProperty(propertyId, zone, minValue - 1);
            verifyValue(message, propertyId, zone, minValue);

            mVehicleNetwork.setZonedIntProperty(propertyId, zone, maxValue + 20);
            verifyValue(message, propertyId, zone, maxValue);
        }));

        // Verify that subsequent SET calls will result in correct value at the end.
        mVehicleNetwork.subscribe(propertyId, 0f, config.getZones());
        int zone = VehicleZoneUtil.getFirstZone(config.getZones());
        int minValue = config.getInt32Mins(0);
        int maxValue = config.getInt32Maxs(0);
        int finalValue = (minValue + maxValue) / 2;
        mListener.reset();
        // We can only expect to see finalValue in the events as vehicle HAL may batch
        // set commands and use only last value.
        mListener.addExpectedValues(propertyId, zone, finalValue);
        mVehicleNetwork.setZonedIntProperty(propertyId, zone, minValue);
        mVehicleNetwork.setZonedIntProperty(propertyId, zone, maxValue);
        mVehicleNetwork.setZonedIntProperty(propertyId, zone, finalValue);
        verifyValue(propertyId, zone, finalValue);
        mListener.waitAndVerifyValues();
        mVehicleNetwork.unsubscribe(propertyId);
    }

    protected int waitForIntValue(int propertyId, int zone, int expectedValue) {
        int actualValue = mVehicleNetwork.getZonedIntProperty(propertyId, zone);
        long deadline = System.currentTimeMillis() + PROP_TIMEOUT_MS;

        while (System.currentTimeMillis() <= deadline && actualValue != expectedValue) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            actualValue = mVehicleNetwork.getZonedIntProperty(propertyId, zone);
        }

        return actualValue;
    }

    protected void iterateOnZones(VehiclePropConfig config, IntZonesFunctor f) {
        int[] zones = VehicleZoneUtil.listAllZones(config.getZones());
        int zoneIndex = 0;
        boolean isBooleanType = config.getValueType() ==
                VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_BOOLEAN;

        for (int zone : zones) {
            int minValue = isBooleanType ? 0 : config.getInt32Mins(zoneIndex);
            int maxValue = isBooleanType ? 1 : config.getInt32Maxs(zoneIndex);
            String message = "PropertyId: 0x" + toHexString(config.getProp())
                    + ", zone[" + zoneIndex + "]: 0x" + toHexString(zone);
            f.func(message, zone, minValue, maxValue);
            zoneIndex++;
        }

    }

    protected interface IntZonesFunctor {
        void func(String message, int zone, int minValue, int maxValue);
    }


}
