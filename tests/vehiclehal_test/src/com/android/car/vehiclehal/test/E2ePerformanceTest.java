/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.car.vehiclehal.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.car.Car;
import android.car.hardware.CarSensorManager;
import android.car.hardware.CarSensorManager.OnSensorChangedListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.hardware.automotive.vehicle.V2_0.IVehicle;
import android.hardware.automotive.vehicle.V2_0.StatusCode;
import android.hardware.automotive.vehicle.V2_0.VehicleArea;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyGroup;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyType;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.LargeTest;

import com.google.android.collect.Lists;

import com.android.car.vehiclehal.VehiclePropValueBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * This test suite will make e2e test and measure some performance characteristics. The main idea
 * is to send command to Vehicle HAL to generate some events with certain time interval and capture
 * these events through car public API, e.g. CarSensorManager.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class E2ePerformanceTest {
    private static String TAG = Utils.concatTag(E2ePerformanceTest.class);

    private IVehicle mVehicle;
    private final CarConnectionListener mConnectionListener = new CarConnectionListener();
    private Context mContext;
    private Car mCar;

    private static Handler sEventHandler;
    private static final HandlerThread sHandlerThread = new HandlerThread(TAG);

    private static final int DEFAULT_WAIT_TIMEOUT_MS = 1000;

    private static final int GENERATE_FAKE_DATA_CONTROLLING_PROPERTY = 0x0666
            | VehiclePropertyGroup.VENDOR
            | VehicleArea.GLOBAL
            | VehiclePropertyType.COMPLEX;

    private static final int CMD_START = 1;
    private static final int CMD_STOP = 0;

    private HalEventsGenerator mEventsGenerator;

    @BeforeClass
    public static void setupEventHandler() {
        sHandlerThread.start();
        sEventHandler = new Handler(sHandlerThread.getLooper());
    }

    @Before
    public void connectToVehicleHal() throws Exception {
        mVehicle = Utils.getVehicle();

        mVehicle.getPropConfigs(Lists.newArrayList(GENERATE_FAKE_DATA_CONTROLLING_PROPERTY),
                (status, propConfigs) -> assumeTrue(status == StatusCode.OK));

        mEventsGenerator = new HalEventsGenerator(mVehicle);
    }

    @Before
    public void connectToCarService() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mCar = Car.createCar(mContext, mConnectionListener, sEventHandler);
        assertNotNull(mCar);
        mCar.connect();
        mConnectionListener.waitForConnection(DEFAULT_WAIT_TIMEOUT_MS);
    }

    @After
    public void disconnect() throws Exception {
        if (mVehicle != null) {
            mEventsGenerator.stop();
            mVehicle = null;
            mEventsGenerator = null;
        }
        if (mCar != null) {
            mCar.disconnect();
            mCar = null;
        }
    }

    @Test
    public void singleOnChangeProperty() throws Exception {
        final int PROP = CarSensorManager.SENSOR_TYPE_ODOMETER;
        // Expecting to receive at least 10 events within 150ms.
        final int EXPECTED_EVENTS = 10;
        final int EXPECTED_TIME_DURATION_MS = 150;
        final float INITIAL_VALUE = 1000;
        final float INCREMENT = 1.0f;

        CarSensorManager mgr = (CarSensorManager) mCar.getCarManager(Car.SENSOR_SERVICE);
        assertNotNull(mgr);
        assertTrue(mgr.isSensorSupported(CarSensorManager.SENSOR_TYPE_ODOMETER));

        mEventsGenerator
                .setIntervalMs(10)
                .setInitialValue(INITIAL_VALUE)
                .setIncrement(INCREMENT)
                .setDispersion(100)
                .start(VehicleProperty.PERF_ODOMETER);

        CountDownLatch latch = new CountDownLatch(EXPECTED_EVENTS);
        OnSensorChangedListener listener = event -> latch.countDown();

        mgr.registerListener(listener, PROP, CarSensorManager.SENSOR_RATE_FASTEST);
        try {
            assertTrue(latch.await(EXPECTED_TIME_DURATION_MS, TimeUnit.MILLISECONDS));
        } finally {
            mgr.unregisterListener(listener);
        }
    }

    private static class CarConnectionListener implements ServiceConnection {
        private final Semaphore mConnectionWait = new Semaphore(0);

        void waitForConnection(long timeoutMs) throws InterruptedException {
            mConnectionWait.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mConnectionWait.release();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) { }
    }

    static class HalEventsGenerator {
        private final IVehicle mVehicle;

        private long mIntervalMs;
        private float mInitialValue;
        private float mDispersion;
        private float mIncrement;

        HalEventsGenerator(IVehicle vehicle) {
            mVehicle = vehicle;
            reset();
        }

        HalEventsGenerator reset() {
            mIntervalMs = 1000;
            mInitialValue = 1000;
            mDispersion = 0;
            mInitialValue = 0;
            return this;
        }

        HalEventsGenerator setIntervalMs(long intervalMs) {
            mIntervalMs = intervalMs;
            return this;
        }

        HalEventsGenerator setInitialValue(float initialValue) {
            mInitialValue = initialValue;
            return this;
        }

        HalEventsGenerator setDispersion(float dispersion) {
            mDispersion = dispersion;
            return this;
        }

        HalEventsGenerator setIncrement(float increment) {
            mIncrement = increment;
            return this;
        }

        void start(int propId) throws RemoteException {
            VehiclePropValue request =
                    VehiclePropValueBuilder.newBuilder(GENERATE_FAKE_DATA_CONTROLLING_PROPERTY)
                        .addIntValue(CMD_START, propId)
                        .setInt64Value(mIntervalMs * 1000_000)
                        .addFloatValue(mInitialValue, mDispersion, mIncrement)
                        .build();
            assertEquals(StatusCode.OK, mVehicle.set(request));
        }

        void stop() throws RemoteException {
            stop(0);
        }

        void stop(int propId) throws RemoteException {
            VehiclePropValue request =
                    VehiclePropValueBuilder.newBuilder(GENERATE_FAKE_DATA_CONTROLLING_PROPERTY)
                        .addIntValue(CMD_STOP, propId)
                        .build();
            assertEquals(StatusCode.OK, mVehicle.set(request));
        }
    }
}
