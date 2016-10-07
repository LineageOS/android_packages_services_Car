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
import android.car.CarProjectionManager;
import android.car.test.VehicleHalEmulator;
import android.os.SystemClock;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;
import android.view.KeyEvent;

import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehiclePropConfigUtil;
import com.android.car.vehiclenetwork.VehiclePropValueUtil;

import java.util.HashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@MediumTest
public class CarProjectionManagerTest extends MockedCarTestBase {
    private static final String TAG = CarProjectionManagerTest.class.getSimpleName();

    private final Semaphore mLongAvailable = new Semaphore(0);
    private final Semaphore mAvailable = new Semaphore(0);

    private final CarProjectionManager.CarProjectionListener mListener =
            new CarProjectionManager.CarProjectionListener() {
                @Override
                public void onVoiceAssistantRequest(boolean fromLongPress) {
                    if (fromLongPress) {
                        mLongAvailable.release();
                    } else {
                        mAvailable.release();
                    }
                }
            };

    private CarProjectionManager mManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PropertyHandler handler = new PropertyHandler();
        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.getBuilder(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_HW_KEY_INPUT,
                        VehicleNetworkConsts.VehiclePropAccess.VEHICLE_PROP_ACCESS_READ,
                        VehicleNetworkConsts.VehiclePropChangeMode
                            .VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleNetworkConsts.VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC4,
                        VehicleNetworkConsts.VehiclePermissionModel
                            .VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        0 /*configFlags*/, 0 /*sampleRateMax*/, 0 /*sampleRateMin*/).build(),
                handler);
        getVehicleHalEmulator().start();
        mManager = (CarProjectionManager) getCar().getCarManager(Car.PROJECTION_SERVICE);
    }

    public void testShortPressListener() throws Exception {
        mManager.regsiterProjectionListener(
                mListener,
                CarProjectionManager.PROJECTION_VOICE_SEARCH);
        assertEquals(0, mAvailable.availablePermits());
        assertEquals(0, mLongAvailable.availablePermits());
        sendVoiceKey(false);
        assertTrue(mAvailable.tryAcquire(2L, TimeUnit.SECONDS));
        assertEquals(0, mLongAvailable.availablePermits());
    }

    public void testLongPressListener() throws Exception {
        mManager.regsiterProjectionListener(
                mListener,
                CarProjectionManager.PROJECTION_LONG_PRESS_VOICE_SEARCH);
        assertEquals(0, mLongAvailable.availablePermits());
        assertEquals(0, mAvailable.availablePermits());
        sendVoiceKey(true);
        assertTrue(mLongAvailable.tryAcquire(2L, TimeUnit.SECONDS));
        assertEquals(0, mAvailable.availablePermits());
    }

    public void testMixedPressListener() throws Exception {
        mManager.regsiterProjectionListener(
                mListener,
                CarProjectionManager.PROJECTION_LONG_PRESS_VOICE_SEARCH
                | CarProjectionManager.PROJECTION_VOICE_SEARCH);
        assertEquals(0, mLongAvailable.availablePermits());
        assertEquals(0, mAvailable.availablePermits());
        sendVoiceKey(true);
        assertTrue(mLongAvailable.tryAcquire(2L, TimeUnit.SECONDS));
        assertEquals(0, mAvailable.availablePermits());

        assertEquals(0, mLongAvailable.availablePermits());
        assertEquals(0, mAvailable.availablePermits());
        sendVoiceKey(false);
        assertTrue(mAvailable.tryAcquire(2L, TimeUnit.SECONDS));
        assertEquals(0, mLongAvailable.availablePermits());
    }

    public void sendVoiceKey(boolean isLong) throws InterruptedException {
        int[] values = {
            VehicleNetworkConsts.VehicleHwKeyInputAction.VEHICLE_HW_KEY_INPUT_ACTION_DOWN,
            KeyEvent.KEYCODE_VOICE_ASSIST, 0, 0 };

        VehiclePropValue injectValue = VehiclePropValueUtil.createIntVectorValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_HW_KEY_INPUT, values,
                SystemClock.elapsedRealtimeNanos());

        getVehicleHalEmulator().injectEvent(injectValue);

        if (isLong) {
            Thread.sleep(1200); // Long press is > 1s.
        }

        int[] upValues = {
            VehicleNetworkConsts.VehicleHwKeyInputAction.VEHICLE_HW_KEY_INPUT_ACTION_UP,
            KeyEvent.KEYCODE_VOICE_ASSIST, 0, 0 };

        injectValue = VehiclePropValueUtil.createIntVectorValue(
                VehicleNetworkConsts.VEHICLE_PROPERTY_HW_KEY_INPUT, upValues,
                SystemClock.elapsedRealtimeNanos());

        getVehicleHalEmulator().injectEvent(injectValue);
    }


    private class PropertyHandler
            implements VehicleHalEmulator.VehicleHalPropertyHandler {
        HashMap<Integer, VehiclePropValue> mMap = new HashMap<>();

        @Override
        public synchronized void onPropertySet(VehiclePropValue value) {
            Log.d(TAG, "onPropertySet:" + value);
            mMap.put(value.getProp(), value);
        }

        @Override
        public synchronized VehiclePropValue onPropertyGet(VehiclePropValue value) {
            Log.d(TAG, "onPropertyGet:" + value);
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
}
