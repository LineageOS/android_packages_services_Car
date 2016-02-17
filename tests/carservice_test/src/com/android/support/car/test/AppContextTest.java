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

import android.car.test.VehicleHalEmulator.VehicleHalPropertyHandler;
import android.content.Context;
import android.media.AudioManager;
import android.support.car.Car;
import android.support.car.CarAppContextManager;
import android.util.Log;

import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleAudioContextFlag;
import com.android.car.vehiclenetwork.VehiclePropConfigUtil;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePermissionModel;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropAccess;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehiclePropChangeMode;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class AppContextTest extends MockedCarTestBase {
    private static final String TAG = AppContextTest.class.getSimpleName();
    private static final long DEFAULT_WAIT_TIMEOUT_MS = 1000;
    private final AppContextHandler mAppContextHandler = new AppContextHandler();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        getVehicleHalEmulator().addProperty(
                VehiclePropConfigUtil.getBuilder(
                        VehicleNetworkConsts.VEHICLE_PROPERTY_AUDIO_CONTEXT,
                        VehiclePropAccess.VEHICLE_PROP_ACCESS_WRITE,
                        VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE,
                        VehicleValueType.VEHICLE_VALUE_TYPE_INT32,
                        VehiclePermissionModel.VEHICLE_PERMISSION_SYSTEM_APP_ONLY,
                        0 /*configFlags*/, 0 /*sampleRateMax*/, 0 /*sampleRateMin*/).build(),
                        mAppContextHandler);
        getVehicleHalEmulator().start();
    }

    public void testCotextChange() throws Exception {
        CarAppContextManager manager = (CarAppContextManager) getSupportCar().getCarManager(
                Car.APP_CONTEXT_SERVICE);
        ContextChangeListerner listener = new ContextChangeListerner();
        manager.registerContextListener(listener, CarAppContextManager.APP_CONTEXT_NAVIGATION |
                CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
        manager.setActiveContexts(CarAppContextManager.APP_CONTEXT_NAVIGATION);
        mAppContextHandler.waitForPropertySetAndAssert(DEFAULT_WAIT_TIMEOUT_MS,
                VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_NAVIGATION_FLAG);
        manager.setActiveContexts(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
        mAppContextHandler.waitForPropertySetAndAssert(DEFAULT_WAIT_TIMEOUT_MS,
                VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_NAVIGATION_FLAG |
                VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_VOICE_COMMAND_FLAG);
        manager.resetActiveContexts(CarAppContextManager.APP_CONTEXT_NAVIGATION);
        mAppContextHandler.waitForPropertySetAndAssert(DEFAULT_WAIT_TIMEOUT_MS,
                VehicleAudioContextFlag.VEHICLE_AUDIO_CONTEXT_VOICE_COMMAND_FLAG);
        manager.resetActiveContexts(CarAppContextManager.APP_CONTEXT_VOICE_COMMAND);
        mAppContextHandler.waitForPropertySetAndAssert(DEFAULT_WAIT_TIMEOUT_MS, 0);
        manager.unregisterContextListener();
    }

    private class AppContextHandler implements VehicleHalPropertyHandler {
        private final Semaphore mWaitSemaphore = new Semaphore(0);
        private int mCurrentContext;

        public boolean waitForPropertySetAndAssert(long timeoutMs, int expected) throws Exception {
            if (!mWaitSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            assertEquals(expected, mCurrentContext);
            return true;
        }

        @Override
        public void onPropertySet(VehiclePropValue value) {
            mCurrentContext = value.getInt32Values(0);
            mWaitSemaphore.release();
        }

        @Override
        public VehiclePropValue onPropertyGet(VehiclePropValue value) {
            fail();
            return null;
        }

        @Override
        public void onPropertySubscribe(int property, float sampleRate, int zones) {
            fail();
        }

        @Override
        public void onPropertyUnsubscribe(int property) {
            fail();
        }
    }

    private class ContextChangeListerner implements CarAppContextManager.AppContextChangeListener {
        private int mLastChangeEvent;
        private final Semaphore mChangeWait = new Semaphore(0);
        private int mLastLossEvent;
        private final Semaphore mLossEventWait = new Semaphore(0);

        public boolean waitForContextChangeAndAssert(long timeoutMs, int expectedContexts)
                throws Exception {
            if (!mChangeWait.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            assertEquals(expectedContexts, mLastChangeEvent);
            return true;
        }

        public boolean waitForOwnershipLossAndAssert(long timeoutMs, int expectedContexts)
                throws Exception {
            if (!mLossEventWait.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            assertEquals(expectedContexts, mLastLossEvent);
            return true;
        }

        @Override
        public void onAppContextChange(int activeContexts) {
            Log.i(TAG, "onAppContextChange " + Integer.toHexString(activeContexts));
            mLastChangeEvent = activeContexts;
            mChangeWait.release();
        }

        @Override
        public void onAppContextOwnershipLoss(int context) {
            Log.i(TAG, "onAppContextOwnershipLoss " + Integer.toHexString(context));
            mLastLossEvent = context;
            mLossEventWait.release();
        }
    }
}
