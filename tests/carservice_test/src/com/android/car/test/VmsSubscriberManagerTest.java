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

package com.android.car.test;

import android.car.Car;
import android.car.VehicleAreaType;
import android.car.annotation.FutureFeature;
import android.car.vms.VmsLayer;
import android.car.vms.VmsSubscriberManager;
import android.car.vms.VmsSubscriberManager.VmsSubscriberClientListener;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.V2_1.VehicleProperty;
import android.hardware.automotive.vehicle.V2_1.VmsMessageType;
import android.os.SystemClock;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;
import com.android.car.vehiclehal.VehiclePropValueBuilder;
import com.android.car.vehiclehal.test.MockedVehicleHal.VehicleHalPropertyHandler;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@FutureFeature
@MediumTest
public class VmsSubscriberManagerTest extends MockedCarTestBase {
    private static final String TAG = "VmsSubscriberManagerTest";
    private static final int SUBSCRIPTION_LAYER_ID = 2;
    private static final int SUBSCRIPTION_LAYER_VERSION = 3;
    private static final VmsLayer SUBSCRIPTION_LAYER = new VmsLayer(SUBSCRIPTION_LAYER_ID,
            SUBSCRIPTION_LAYER_VERSION);

    private HalHandler mHalHandler;
    // Used to block until the HAL property is updated in HalHandler.onPropertySet.
    private Semaphore mHalHandlerSemaphore;
    // Used to block until a value is propagated to the TestListener.onVmsMessageReceived.
    private Semaphore mSubscriberSemaphore;

    @Override
    protected synchronized void configureMockedHal() {
        mHalHandler = new HalHandler();
        addProperty(VehicleProperty.VEHICLE_MAP_SERVICE, mHalHandler)
                .setChangeMode(VehiclePropertyChangeMode.ON_CHANGE)
                .setAccess(VehiclePropertyAccess.READ_WRITE)
                .setSupportedAreas(VehicleAreaType.VEHICLE_AREA_TYPE_NONE);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSubscriberSemaphore = new Semaphore(0);
        mHalHandlerSemaphore = new Semaphore(0);
    }

    // Test injecting a value in the HAL and verifying it propagates to a subscriber.
    public void testSubscribe() throws Exception {
        VmsSubscriberManager vmsSubscriberManager = (VmsSubscriberManager) getCar().getCarManager(
                Car.VMS_SUBSCRIBER_SERVICE);
        TestListener listener = new TestListener();
        vmsSubscriberManager.setListener(listener);
        vmsSubscriberManager.subscribe(SUBSCRIPTION_LAYER);

        // Inject a value and wait for its callback in TestListener.onVmsMessageReceived.
        VehiclePropValue v = VehiclePropValueBuilder.newBuilder(VehicleProperty.VEHICLE_MAP_SERVICE)
                .setAreaId(VehicleAreaType.VEHICLE_AREA_TYPE_NONE)
                .setTimestamp(SystemClock.elapsedRealtimeNanos())
                .build();
        v.value.int32Values.add(VmsMessageType.DATA); // MessageType
        v.value.int32Values.add(SUBSCRIPTION_LAYER_ID);
        v.value.int32Values.add(SUBSCRIPTION_LAYER_VERSION);
        v.value.bytes.add((byte) 0xa);
        v.value.bytes.add((byte) 0xb);
        assertEquals(0, mSubscriberSemaphore.availablePermits());

        getMockedVehicleHal().injectEvent(v);
        assertTrue(mSubscriberSemaphore.tryAcquire(2L, TimeUnit.SECONDS));
        assertEquals(SUBSCRIPTION_LAYER, listener.getLayer());
        byte[] expectedPayload = {(byte) 0xa, (byte) 0xb};
        assertTrue(Arrays.equals(expectedPayload, listener.getPayload()));
    }


    // Test injecting a value in the HAL and verifying it propagates to a subscriber.
    public void testSubscribeAll() throws Exception {
        VmsSubscriberManager vmsSubscriberManager = (VmsSubscriberManager) getCar().getCarManager(
            Car.VMS_SUBSCRIBER_SERVICE);
        TestListener listener = new TestListener();
        vmsSubscriberManager.setListener(listener);
        vmsSubscriberManager.subscribeAll();

        // Inject a value and wait for its callback in TestListener.onVmsMessageReceived.
        VehiclePropValue v = VehiclePropValueBuilder.newBuilder(VehicleProperty.VEHICLE_MAP_SERVICE)
            .setAreaId(VehicleAreaType.VEHICLE_AREA_TYPE_NONE)
            .setTimestamp(SystemClock.elapsedRealtimeNanos())
            .build();
        v.value.int32Values.add(VmsMessageType.DATA); // MessageType
        v.value.int32Values.add(SUBSCRIPTION_LAYER_ID);
        v.value.int32Values.add(SUBSCRIPTION_LAYER_VERSION);
        v.value.bytes.add((byte) 0xa);
        v.value.bytes.add((byte) 0xb);
        assertEquals(0, mSubscriberSemaphore.availablePermits());

        getMockedVehicleHal().injectEvent(v);
        assertTrue(mSubscriberSemaphore.tryAcquire(2L, TimeUnit.SECONDS));
        assertEquals(SUBSCRIPTION_LAYER, listener.getLayer());
        byte[] expectedPayload = {(byte) 0xa, (byte) 0xb};
        assertTrue(Arrays.equals(expectedPayload, listener.getPayload()));
    }

    private class HalHandler implements VehicleHalPropertyHandler {
        private VehiclePropValue mValue;

        @Override
        public synchronized void onPropertySet(VehiclePropValue value) {
            mValue = value;
            mHalHandlerSemaphore.release();
        }

        @Override
        public synchronized VehiclePropValue onPropertyGet(VehiclePropValue value) {
            return mValue != null ? mValue : value;
        }

        @Override
        public synchronized void onPropertySubscribe(int property, int zones, float sampleRate) {
            Log.d(TAG, "onPropertySubscribe property " + property + " sampleRate " + sampleRate);
        }

        @Override
        public synchronized void onPropertyUnsubscribe(int property) {
            Log.d(TAG, "onPropertyUnSubscribe property " + property);
        }

        public VehiclePropValue getValue() {
            return mValue;
        }
    }


    private class TestListener implements VmsSubscriberClientListener{
        private VmsLayer mLayer;
        private byte[] mPayload;

        @Override
        public void onVmsMessageReceived(VmsLayer layer, byte[] payload) {
            Log.d(TAG, "onVmsMessageReceived: layer: " + layer + " Payload: " + payload);
            mLayer = layer;
            mPayload = payload;
            mSubscriberSemaphore.release();
        }

        @Override
        public void onLayersAvailabilityChange(List<VmsLayer> availableLayers) {
            Log.d(TAG, "onLayersAvailabilityChange: Layers: " + availableLayers);
        }

        public VmsLayer getLayer() {
            return mLayer;
        }

        public byte[] getPayload() {
            return mPayload;
        }
    }
}
