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
import android.car.VehicleAreaType;
import android.car.annotation.FutureFeature;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.hardware.automotive.vehicle.V2_0.VmsBaseMessageIntegerValuesIndex;
import android.hardware.automotive.vehicle.V2_0.VmsMessageType;
import android.test.suitebuilder.annotation.MediumTest;

import com.android.car.R;
import com.android.car.vehiclehal.VehiclePropValueBuilder;
import com.android.car.vehiclehal.test.MockedVehicleHal;
import com.android.car.vehiclehal.test.MockedVehicleHal.VehicleHalPropertyHandler;

import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@FutureFeature
@MediumTest
public class VmsPublisherPermissionsTest extends MockedCarTestBase {
    private static final String TAG = "VmsPublisherTest";
    private static final int MOCK_PUBLISHER_LAYER_ID = 0;
    private static final int MOCK_PUBLISHER_LAYER_VERSION = 0;
    private static final int MOCK_PUBLISHER_LAYER_FUSION_INT_VALUE = 0;

    private HalHandler mHalHandler;
    // Used to block until the HAL property is updated in HalHandler.onPropertySet.
    private Semaphore mHalHandlerSemaphore;

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
                if (id == R.array.vmsPublisherClients) {
                    return new String[]{
                            "com.google.android.car.vms.publisher/"
                                    + ".VmsPublisherClientSampleService"};
                } else if (id == R.array.vmsSafePermissions) {
                    return new String[]{"android.permission.ACCESS_FINE_LOCATION"};
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

    private VehiclePropValue getHalSubscriptionRequest() {
        return VehiclePropValueBuilder.newBuilder(VehicleProperty.VEHICLE_MAP_SERVICE)
                .addIntValue(VmsMessageType.SUBSCRIBE)
                .addIntValue(MOCK_PUBLISHER_LAYER_ID)
                .addIntValue(MOCK_PUBLISHER_LAYER_VERSION)
                .addIntValue(MOCK_PUBLISHER_LAYER_FUSION_INT_VALUE)
                .build();
    }

    @Override
    protected void setUp() throws Exception {
        if (!VmsTestUtils.canRunTest(TAG)) return;
        /**
         * First init the semaphore, setUp will start a series of events that will ultimately
         * update the HAL layer and release this semaphore.
         */
        mHalHandlerSemaphore = new Semaphore(0);
        super.setUp();

        // Inject a subscribe event which simulates the HAL is subscribed to the Sample Publisher.
        MockedVehicleHal mHal = getMockedVehicleHal();
        mHal.injectEvent(getHalSubscriptionRequest());
    }

    @Override
    protected synchronized void tearDown() throws Exception {
        if (!VmsTestUtils.canRunTest(TAG)) return;
        super.tearDown();
    }

    /**
     * The method setUp initializes all the Car services, including the VmsPublisherService.
     * The VmsPublisherService will start and configure its list of clients. This list was
     * overridden in the method getCarServiceContext.
     * Therefore, only VmsPublisherClientSampleService will be started.
     * The service VmsPublisherClientSampleService will publish one message, which is validated in
     * this test.
     */
    public void testPermissions() throws Exception {
        if (!VmsTestUtils.canRunTest(TAG)) return;
        assertTrue(mHalHandlerSemaphore.tryAcquire(2L, TimeUnit.SECONDS));
        // At this point the client initialization finished. Let's validate the permissions.
        // The VMS service is only allowed to grant ACCESS_FINE_LOCATION but not CAMERA.
        assertTrue(
                getContext().getPackageManager().checkPermission(
                        "android.permission.ACCESS_FINE_LOCATION",
                        "com.google.android.car.vms.publisher")
                        == PackageManager.PERMISSION_GRANTED);
        assertFalse(getContext().getPackageManager().checkPermission(
                "android.permission.CAMERA", "com.google.android.car.vms.publisher")
                == PackageManager.PERMISSION_GRANTED);
    }

    private class HalHandler implements VehicleHalPropertyHandler {
        @Override
        public synchronized void onPropertySet(VehiclePropValue value) {
            // If this is the data message release the semaphore so the test can continue.
            ArrayList<Integer> int32Values = value.value.int32Values;
            if (int32Values.get(VmsBaseMessageIntegerValuesIndex.MESSAGE_TYPE) ==
                    VmsMessageType.DATA) {
                mHalHandlerSemaphore.release();
            }
        }
    }
}
