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

package com.android.car;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.car.Car;
import android.car.VehicleAreaType;
import android.car.vms.VmsAvailableLayers;
import android.car.vms.VmsLayer;
import android.car.vms.VmsSubscriberManager;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyChangeMode;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class VmsWhiteListTest extends MockedCarTestBase {
    private static final String TAG = CarStorageMonitoringTest.class.getSimpleName();
    public final VmsLayer LAYER = new VmsLayer(0, 0, 0);

    @Rule
    public TestName mTestName = new TestName();

    private String getName() {
        return mTestName.getMethodName();
    }

    static class ResourceOverrides {
        private final HashMap<Integer, String[]> mStringOverrides = new HashMap<>();

        void override(int id, String[] value) {
            mStringOverrides.put(id, value);
        }

        void overrideResources(MockResources resources) {
            mStringOverrides.forEach(resources::overrideResource);
        }
    }

    private final Map<String, ResourceOverrides> mPerTestResources =
            new HashMap<String, ResourceOverrides>() {
                {
                    put("testSubscribeSuccessfully",
                            new ResourceOverrides() {{
                                override(com.android.car.R.array.allowedVmsSubscriberClients,
                                        new String[]{"com.android.car"});
                            }});
                    put("testSubscribeUnsuccessfully",
                            new ResourceOverrides() {{
                                override(com.android.car.R.array.allowedVmsSubscriberClients,
                                        new String[]{});
                            }});
                }
            };

    @Override
    protected synchronized void configureResourceOverrides(MockResources resources) {
        final ResourceOverrides overrides = mPerTestResources.getOrDefault(getName(), null);
        if (overrides != null) {
            overrides.overrideResources(resources);
        }
    }

    @Override
    protected synchronized void configureMockedHal() {
        addProperty(VehicleProperty.VEHICLE_MAP_SERVICE)
                .setChangeMode(VehiclePropertyChangeMode.ON_CHANGE)
                .setAccess(VehiclePropertyAccess.READ_WRITE)
                .addAreaConfig(VehicleAreaType.VEHICLE_AREA_TYPE_NONE, 0, 0);
    }

    private void configureSubscriptions(VmsSubscriberManager vmsSubscriberManager) {
        try {
            vmsSubscriberManager.registerClientCallback(mClientCallback);
            vmsSubscriberManager.subscribe(LAYER);
        } catch (android.car.CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected!", e);
        }
    }

    private final VmsSubscriberManager.VmsSubscriberClientCallback mClientCallback =
            new VmsSubscriberManager.VmsSubscriberClientCallback() {
                @Override
                public void onVmsMessageReceived(VmsLayer layer, byte[] payload) {
                    Log.e(TAG, "Message received!");
                }

                @Override
                public void onLayersAvailabilityChanged(VmsAvailableLayers availableLayers) {
                    Log.e(TAG, "Layers availability changed!");
                }
            };

    /**
     * This test verifies that the client can subscribe if it belongs to a whitelisted package.
     */
    @Test
    public void testSubscribeSuccessfully() {
        try {
            VmsSubscriberManager vmsSubscriberManager =
                    (VmsSubscriberManager) getCar().getCarManager(Car.VMS_SUBSCRIBER_SERVICE);
            vmsSubscriberManager.registerClientCallback(mClientCallback);
            vmsSubscriberManager.subscribe(LAYER);
        } catch (android.car.CarNotConnectedException e) {
            fail("Car is not connected! " + e.getMessage());
        }
    }

    /**
     * This test verifies that the client cannot subscribe if it doesn't belong to a whitelisted
     * package.
     */
    @Test
    public void testSubscribeUnsuccessfully() {
        try {
            VmsSubscriberManager vmsSubscriberManager =
                    (VmsSubscriberManager) getCar().getCarManager(Car.VMS_SUBSCRIBER_SERVICE);
            try {
                vmsSubscriberManager.registerClientCallback(mClientCallback);
                vmsSubscriberManager.subscribe(LAYER);
            } catch (java.lang.SecurityException e) {
                assertTrue(e.getMessage().contains(
                        "Package is not whitelisted as a VMS Subscriber"));
            }
        } catch (android.car.CarNotConnectedException e) {
            fail("Car is not connected!" + e.getMessage());
        }
    }
}
