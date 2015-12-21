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
package com.android.car.vehiclenetwork.libtest;

import android.os.HandlerThread;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.car.vehiclenetwork.VehicleNetwork;
import com.android.car.vehiclenetwork.VehicleNetwork.VehicleNetworkHalMock;
import com.android.car.vehiclenetwork.VehicleNetwork.VehicleNetworkListener;
import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropAccess;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropChangeMode;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfig;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfigs;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValues;
import com.android.car.vehiclenetwork.VehiclePropConfigUtil;
import com.android.car.vehiclenetwork.VehiclePropValueUtil;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class VehicleNetworkMockedTest extends AndroidTestCase {
    private static final String TAG = VehicleNetworkMockedTest.class.getSimpleName();

    private static final long TIMEOUT_MS = 1000;

    private static final int CUSTOM_PROPERTY_INT32 =
            VehicleNetworkConsts.VEHICLE_PROPERTY_CUSTOM_START;

    private final HandlerThread mHandlerThread = new HandlerThread(
            VehicleNetworkTest.class.getSimpleName());
    private VehicleNetwork mVehicleNetwork;
    private EventListener mListener = new EventListener();
    private final VehicleHalMock mVehicleHalMock = new VehicleHalMock();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mHandlerThread.start();
        mVehicleNetwork = VehicleNetwork.createVehicleNetwork(mListener,
                mHandlerThread.getLooper());
        mVehicleHalMock.registerProperty(
                VehiclePropConfigUtil.createProperty(
                        CUSTOM_PROPERTY_INT32,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_READ_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_INT32, 0x0),
                new DefaultVehiclePropertyHandler(VehiclePropValueUtil.createIntValue(
                        CUSTOM_PROPERTY_INT32, 0, 0)));
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mHandlerThread.quit();
        mVehicleNetwork.stopMocking();
    }

    public void testHalRestartListening() throws Exception {
        mVehicleNetwork.startHalRestartMonitoring();
        mVehicleNetwork.startMocking(mVehicleHalMock);
        assertTrue(mListener.waitForHalRestartAndAssert(TIMEOUT_MS, true /*expectedInMocking*/));
        mVehicleNetwork.stopMocking();
        assertTrue(mListener.waitForHalRestartAndAssert(TIMEOUT_MS, false /*expectedInMocking*/));
        mVehicleNetwork.stopHalRestartMonitoring();
    }

    public void testGlobalErrorListening() throws Exception {
        mVehicleNetwork.startErrorListening();
        mVehicleNetwork.startMocking(mVehicleHalMock);
        final int ERROR_CODE = 0x1;
        final int ERROR_OPERATION = 0x10;
        mVehicleNetwork.injectHalError(ERROR_CODE, 0, ERROR_OPERATION);
        assertTrue(mListener.waitForHalErrorAndAssert(TIMEOUT_MS, ERROR_CODE, 0, ERROR_OPERATION));
        mVehicleNetwork.injectHalError(ERROR_CODE, CUSTOM_PROPERTY_INT32, ERROR_OPERATION);
        assertTrue(mListener.waitForHalErrorAndAssert(TIMEOUT_MS,
                ERROR_CODE, CUSTOM_PROPERTY_INT32, ERROR_OPERATION));
        mVehicleNetwork.stopMocking();
        mVehicleNetwork.stopErrorListening();
    }

    public void testPropertyErrorListening() throws Exception {
        mVehicleNetwork.startMocking(mVehicleHalMock);
        mVehicleNetwork.subscribe(CUSTOM_PROPERTY_INT32, 0);
        final int ERROR_CODE = 0x1;
        final int ERROR_OPERATION = 0x10;
        mVehicleNetwork.injectHalError(ERROR_CODE, CUSTOM_PROPERTY_INT32, ERROR_OPERATION);
        assertTrue(mListener.waitForHalErrorAndAssert(TIMEOUT_MS,
                ERROR_CODE, CUSTOM_PROPERTY_INT32, ERROR_OPERATION));
        mVehicleNetwork.unsubscribe(CUSTOM_PROPERTY_INT32);
        mVehicleNetwork.stopMocking();
    }

    private class EventListener implements VehicleNetworkListener {
        boolean mInMocking;
        private final Semaphore mRestartWait = new Semaphore(0);

        int mErrorCode;
        int mErrorProperty;
        int mErrorOperation;
        private final Semaphore mErrorWait = new Semaphore(0);

        @Override
        public void onVehicleNetworkEvents(VehiclePropValues values) {
            // TODO Auto-generated method stub
        }

        @Override
        public void onHalError(int errorCode, int property, int operation) {
            mErrorCode = errorCode;
            mErrorProperty = property;
            mErrorOperation = operation;
            mErrorWait.release();
        }

        public boolean waitForHalErrorAndAssert(long timeoutMs, int expectedErrorCode,
                int expectedErrorProperty, int expectedErrorOperation) throws Exception {
            if (!mErrorWait.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            assertEquals(expectedErrorCode, mErrorCode);
            assertEquals(expectedErrorProperty, mErrorProperty);
            assertEquals(expectedErrorOperation, mErrorOperation);
            return true;
        }

        @Override
        public void onHalRestart(boolean inMocking) {
            mInMocking = inMocking;
            mRestartWait.release();
        }

        public boolean waitForHalRestartAndAssert(long timeoutMs, boolean expectedInMocking)
                throws Exception {
            if (!mRestartWait.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            assertEquals(expectedInMocking, mInMocking);
            return true;
        }
    }

    private interface VehiclePropertyHandler {
        void onPropertySet(VehiclePropValue value);
        VehiclePropValue onPropertyGet(VehiclePropValue property);
        void onPropertySubscribe(int property, float sampleRate, int zones);
        void onPropertyUnsubscribe(int property);
    }

    private class VehicleHalMock implements VehicleNetworkHalMock {
        private LinkedList<VehiclePropConfig> mConfigs = new LinkedList<>();
        private HashMap<Integer, VehiclePropertyHandler> mHandlers = new HashMap<>();

        public synchronized void registerProperty(VehiclePropConfig config,
                VehiclePropertyHandler handler) {
            int property = config.getProp();
            mConfigs.add(config);
            mHandlers.put(property, handler);
        }

        @Override
        public synchronized VehiclePropConfigs onListProperties() {
            Log.i(TAG, "onListProperties, num properties:" + mConfigs.size());
            VehiclePropConfigs configs =
                    VehiclePropConfigs.newBuilder().addAllConfigs(mConfigs).build();
            return configs;
        }

        @Override
        public void onPropertySet(VehiclePropValue value) {
            int property = value.getProp();
            VehiclePropertyHandler handler = getPropertyHandler(property);
            if (handler == null) {
                fail("onPropertySet for unknown property " + Integer.toHexString(property));
            }
            handler.onPropertySet(value);
        }

        @Override
        public VehiclePropValue onPropertyGet(VehiclePropValue value) {
            int property = value.getProp();
            VehiclePropertyHandler handler = getPropertyHandler(property);
            if (handler == null) {
                fail("onPropertyGet for unknown property " + Integer.toHexString(property));
            }
            return handler.onPropertyGet(value);
        }

        @Override
        public void onPropertySubscribe(int property, float sampleRate, int zones) {
            VehiclePropertyHandler handler = getPropertyHandler(property);
            if (handler == null) {
                fail("onPropertySubscribe for unknown property " + Integer.toHexString(property));
            }
            handler.onPropertySubscribe(property, sampleRate, zones);
        }

        @Override
        public void onPropertyUnsubscribe(int property) {
            VehiclePropertyHandler handler = getPropertyHandler(property);
            if (handler == null) {
                fail("onPropertyUnsubscribe for unknown property " + Integer.toHexString(property));
            }
            handler.onPropertyUnsubscribe(property);
        }

        public synchronized VehiclePropertyHandler getPropertyHandler(int property) {
            return mHandlers.get(property);
        }
    }

    private class DefaultVehiclePropertyHandler implements VehiclePropertyHandler {
        private VehiclePropValue mValue;

        DefaultVehiclePropertyHandler(VehiclePropValue initialValue) {
            mValue = initialValue;
        }

        @Override
        public void onPropertySet(VehiclePropValue value) {
            // TODO Auto-generated method stub
        }

        @Override
        public synchronized VehiclePropValue onPropertyGet(VehiclePropValue property) {
            return mValue;
        }

        @Override
        public void onPropertySubscribe(int property, float sampleRate, int zones) {
            // TODO Auto-generated method stub
        }

        @Override
        public void onPropertyUnsubscribe(int property) {
            // TODO Auto-generated method stub
        }
    }
}
