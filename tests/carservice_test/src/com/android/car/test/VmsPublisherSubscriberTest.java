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

import android.annotation.ArrayRes;
import android.car.Car;
import android.car.VehicleAreaType;
import android.car.annotation.FutureFeature;
import android.car.vms.VmsSubscriberManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.V2_1.VehicleProperty;

import com.android.car.vehiclehal.test.MockedVehicleHal;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@FutureFeature
public class VmsPublisherSubscriberTest extends MockedCarTestBase {
    public static final int LAYER_ID = 88;
    public static final int LAYER_VERSION = 19;
    public static final byte[] PAYLOAD = new byte[]{2, 3, 5, 7, 11, 13, 17};

    private HalHandler mHalHandler;
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

    /**
     * Creates a context with the resource vmsPublisherClients overridden. The overridden value
     * contains the name of the test service defined also in this test package.
     */
    @Override
    protected Context getCarServiceContext() throws PackageManager.NameNotFoundException {
        Context context = getContext()
                .createPackageContext("com.android.car", Context.CONTEXT_IGNORE_SECURITY);
        Resources resources = new Resources(context.getAssets(),
                context.getResources().getDisplayMetrics(),
                context.getResources().getConfiguration()) {
            @Override
            public String[] getStringArray(@ArrayRes int id) throws NotFoundException {
                if (id == com.android.car.R.array.vmsPublisherClients) {
                    return new String[]{"com.android.car.test/.VmsPublisherClientMockService"};
                }
                return super.getStringArray(id);
            }
        };
        ContextWrapper wrapper = new ContextWrapper(context) {
            @Override
            public Resources getResources() {
                return resources;
            }
        };
        return wrapper;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSubscriberSemaphore = new Semaphore(0);
    }

    /**
     * The method setUp initializes all the Car services, including the VmsPublisherService.
     * The VmsPublisherService will start and configure its list of clients. This list was
     * overridden in the method getCarServiceContext. Therefore, only VmsPublisherClientMockService
     * will be started. This test method subscribes to a layer and triggers
     * VmsPublisherClientMockService.onVmsSubscriptionChange. In turn, the mock service will publish
     * a message, which is validated in this test.
     */
    public void testPublisherToSubscriber() throws Exception {
        VmsSubscriberManager vmsSubscriberManager = (VmsSubscriberManager) getCar().getCarManager(
                Car.VMS_SUBSCRIBER_SERVICE);
        TestListener listener = new TestListener();
        vmsSubscriberManager.setListener(listener);
        vmsSubscriberManager.subscribe(LAYER_ID, LAYER_VERSION);

        assertTrue(mSubscriberSemaphore.tryAcquire(2L, TimeUnit.SECONDS));
        assertEquals(LAYER_ID, listener.getLayerId());
        assertEquals(LAYER_VERSION, listener.getLayerVersion());
        assertTrue(Arrays.equals(PAYLOAD, listener.getPayload()));
    }

    private class HalHandler implements MockedVehicleHal.VehicleHalPropertyHandler {
    }

    private class TestListener implements VmsSubscriberManager.OnVmsMessageReceivedListener {
        private int mLayerId;
        private int mLayerVersion;
        private byte[] mPayload;

        @Override
        public void onVmsMessageReceived(int layerId, int layerVersion, byte[] payload) {
            assertEquals(LAYER_ID, layerId);
            assertEquals(LAYER_VERSION, layerVersion);
            assertTrue(Arrays.equals(PAYLOAD, payload));
            mLayerId = layerId;
            mLayerVersion = layerVersion;
            mPayload = payload;
            mSubscriberSemaphore.release();
        }

        public int getLayerId() {
            return mLayerId;
        }

        public int getLayerVersion() {
            return mLayerVersion;
        }

        public byte[] getPayload() {
            return mPayload;
        }
    }
}
