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
import android.car.vms.VmsAssociatedLayer;
import android.car.vms.VmsLayer;
import android.car.vms.VmsSubscriberManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.V2_1.VehicleProperty;
import android.test.suitebuilder.annotation.MediumTest;

import com.android.car.vehiclehal.test.MockedVehicleHal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@FutureFeature
@MediumTest
public class VmsPublisherSubscriberTest extends MockedCarTestBase {
    private static final int LAYER_ID = 88;
    private static final int LAYER_VERSION = 19;
    private static final int LAYER_SUB_TYPE = 55;
    private static final int EXPECTED_PUBLISHER_ID = 0;
    private static final String TAG = "VmsPubSubTest";

    public static final VmsLayer LAYER = new VmsLayer(LAYER_ID, LAYER_VERSION, LAYER_SUB_TYPE);
    public static final VmsAssociatedLayer ASSOCIATED_LAYER =
            new VmsAssociatedLayer(LAYER, new HashSet<>(Arrays.asList(EXPECTED_PUBLISHER_ID)));
    public static final byte[] PAYLOAD = new byte[]{2, 3, 5, 7, 11, 13, 17};

    private static final List<VmsAssociatedLayer> AVAILABLE_ASSOCIATED_LAYERS =
            new ArrayList<>(Arrays.asList(ASSOCIATED_LAYER));

    private HalHandler mHalHandler;
    // Used to block until a value is propagated to the TestListener.onVmsMessageReceived.
    private Semaphore mSubscriberSemaphore;
    private Semaphore mAvailabilitySemaphore;

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
        if (!VmsTestUtils.canRunTest(TAG)) return;
        super.setUp();
        mSubscriberSemaphore = new Semaphore(0);
        mAvailabilitySemaphore = new Semaphore(0);
    }

    @Override
    protected synchronized void tearDown() throws Exception {
        if (!VmsTestUtils.canRunTest(TAG)) return;
        super.tearDown();
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
        if (!VmsTestUtils.canRunTest(TAG)) return;
        VmsSubscriberManager vmsSubscriberManager = (VmsSubscriberManager) getCar().getCarManager(
                Car.VMS_SUBSCRIBER_SERVICE);
        TestListener listener = new TestListener();
        vmsSubscriberManager.setListener(listener);
        vmsSubscriberManager.subscribe(LAYER);

        assertTrue(mSubscriberSemaphore.tryAcquire(2L, TimeUnit.SECONDS));
        assertEquals(LAYER, listener.getLayer());
        assertTrue(Arrays.equals(PAYLOAD, listener.getPayload()));
    }

    /**
     * The Mock service will get a publisher ID by sending its information when it will get
     * ServiceReady as well as on SubscriptionChange. Since clients are not notified when
     * publishers are assigned IDs, this test waits until the availability is changed which indicates
     * that the Mock service has gotten its ServiceReady and publisherId.
     */
    public void testPublisherInfo() throws Exception {
        if (!VmsTestUtils.canRunTest(TAG)) return;
        VmsSubscriberManager vmsSubscriberManager = (VmsSubscriberManager) getCar().getCarManager(
                Car.VMS_SUBSCRIBER_SERVICE);
        // Subscribe to layer as a way to make sure the mock client completed setting the information.
        TestListener listener = new TestListener();
        vmsSubscriberManager.setListener(listener);
        vmsSubscriberManager.subscribe(LAYER);

        assertTrue(mAvailabilitySemaphore.tryAcquire(2L, TimeUnit.SECONDS));

        byte[] info = vmsSubscriberManager.getPublisherInfo(EXPECTED_PUBLISHER_ID);
        assertTrue(Arrays.equals(PAYLOAD, info));
    }

    /**
     * The Mock service offers all the subscribed layers as available layers, so in this
     * test the listener subscribes to a layer and verifies that it gets the notification that it
     * is available.
     */
    public void testAvailability() throws Exception {
        if (!VmsTestUtils.canRunTest(TAG)) return;
        VmsSubscriberManager vmsSubscriberManager = (VmsSubscriberManager) getCar().getCarManager(
            Car.VMS_SUBSCRIBER_SERVICE);
        TestListener listener = new TestListener();
        vmsSubscriberManager.setListener(listener);
        vmsSubscriberManager.subscribe(LAYER);

        assertTrue(mAvailabilitySemaphore.tryAcquire(2L, TimeUnit.SECONDS));
        assertEquals(AVAILABLE_ASSOCIATED_LAYERS, listener.getAvailalbeLayers());
    }

    private class HalHandler implements MockedVehicleHal.VehicleHalPropertyHandler {
    }

    private class TestListener implements VmsSubscriberManager.VmsSubscriberClientListener {
        private VmsLayer mLayer;
        private byte[] mPayload;
        private List<VmsLayer> mAvailableLayers;

        @Override
        public void onVmsMessageReceived(VmsLayer layer, byte[] payload) {
            assertEquals(LAYER, layer);
            assertTrue(Arrays.equals(PAYLOAD, payload));
            mLayer = layer;
            mPayload = payload;
            mSubscriberSemaphore.release();
        }

        @Override
        public void onLayersAvailabilityChange(List<VmsLayer> availableLayers) {
            assertEquals(AVAILABLE_ASSOCIATED_LAYERS, availableLayers);
            mAvailableLayers = availableLayers;
            mAvailabilitySemaphore.release();
        }

        @Override
        public void onCarDisconnected() {

        }

        public VmsLayer getLayer() {
            return mLayer;
        }

        public byte[] getPayload() {
            return mPayload;
        }

        public List<VmsLayer> getAvailalbeLayers() {
            return mAvailableLayers;
        }
    }
}
