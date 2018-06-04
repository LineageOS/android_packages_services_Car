/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.drivingstate.CarDrivingStateEvent;
import android.car.drivingstate.CarDrivingStateManager;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.hardware.automotive.vehicle.V2_0.VehicleGear;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.os.SystemClock;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.android.car.vehiclehal.VehiclePropValueBuilder;
import com.android.internal.annotations.GuardedBy;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CarDrivingRestrictionsTest extends MockedCarTestBase {
    private static final String TAG = CarDrivingRestrictionsTest.class
            .getSimpleName();
    private CarDrivingStateManager mCarDrivingStateManager;
    private CarUxRestrictionsManager mCarUxRManager;

    @Override
    protected synchronized void configureMockedHal() {
        addProperty(VehicleProperty.PERF_VEHICLE_SPEED, VehiclePropValueBuilder
                .newBuilder(VehicleProperty.PERF_VEHICLE_SPEED)
                .addFloatValue(0f)
                .build());
        addProperty(VehicleProperty.PARKING_BRAKE_ON, VehiclePropValueBuilder
                .newBuilder(VehicleProperty.PARKING_BRAKE_ON)
                .setBooleanValue(false)
                .build());
        addProperty(VehicleProperty.GEAR_SELECTION, VehiclePropValueBuilder
                .newBuilder(VehicleProperty.GEAR_SELECTION)
                .addIntValue(0)
                .build());
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mCarDrivingStateManager = (CarDrivingStateManager) getCar()
                .getCarManager(Car.CAR_DRIVING_STATE_SERVICE);
        mCarUxRManager = (CarUxRestrictionsManager) getCar()
                .getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
    }

    @Test
    public void testDrivingStateChange() throws CarNotConnectedException, InterruptedException {
        CarDrivingStateEvent drivingEvent;
        CarUxRestrictions restrictions;
        DrivingStateListener listener = new DrivingStateListener();
        mCarDrivingStateManager.registerListener(listener);
        mCarUxRManager.registerListener(listener);
        // With no gear value available, driving state should be unknown
        listener.reset();
        getMockedVehicleHal().injectEvent(
                VehiclePropValueBuilder.newBuilder(VehicleProperty.PERF_VEHICLE_SPEED)
                        .addFloatValue(0.0f)
                        .setTimestamp(SystemClock.elapsedRealtimeNanos())
                        .build());

        // Test Parked state and corresponding restrictions based on car_ux_restrictions_map.xml
        getMockedVehicleHal().injectEvent(
                VehiclePropValueBuilder.newBuilder(VehicleProperty.GEAR_SELECTION)
                        .addIntValue(VehicleGear.GEAR_PARK)
                        .setTimestamp(SystemClock.elapsedRealtimeNanos())
                        .build());
        drivingEvent = listener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_PARKED);

        // Switch gear to drive.  Driving state changes to Idling but the UX restrictions don't
        // change between parked and idling.
        listener.reset();
        getMockedVehicleHal().injectEvent(
                VehiclePropValueBuilder.newBuilder(VehicleProperty.GEAR_SELECTION)
                        .addIntValue(VehicleGear.GEAR_DRIVE)
                        .setTimestamp(SystemClock.elapsedRealtimeNanos())
                        .build());
        drivingEvent = listener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_IDLING);

        // Test Moving state and corresponding restrictions based on car_ux_restrictions_map.xml
        listener.reset();
        getMockedVehicleHal().injectEvent(
                VehiclePropValueBuilder.newBuilder(VehicleProperty.PERF_VEHICLE_SPEED)
                        .addFloatValue(30.0f)
                        .setTimestamp(SystemClock.elapsedRealtimeNanos())
                        .build());
        drivingEvent = listener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_MOVING);
        restrictions = listener.waitForUxRestrictionsChange();
        assertNotNull(restrictions);
        assertTrue(restrictions.isRequiresDistractionOptimization());
        assertThat(restrictions.getActiveRestrictions())
                .isEqualTo(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);

        // Test Idling state and corresponding restrictions based on car_ux_restrictions_map.xml
        listener.reset();
        getMockedVehicleHal().injectEvent(
                VehiclePropValueBuilder.newBuilder(VehicleProperty.PERF_VEHICLE_SPEED)
                        .addFloatValue(0.0f)
                        .setTimestamp(SystemClock.elapsedRealtimeNanos())
                        .build());
        drivingEvent = listener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_IDLING);
        restrictions = listener.waitForUxRestrictionsChange();
        assertNotNull(restrictions);
        assertFalse(restrictions.isRequiresDistractionOptimization());
        assertThat(restrictions.getActiveRestrictions())
                .isEqualTo(CarUxRestrictions.UX_RESTRICTIONS_BASELINE);

    }

    /**
     * Callback function we register for driving state update notifications.
     */
    private class DrivingStateListener implements
            CarDrivingStateManager.CarDrivingStateEventListener,
            CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
        private final Object mDrivingStateLock = new Object();
        @GuardedBy("mDrivingStateLock")
        private CarDrivingStateEvent mLastEvent = null;
        private final Object mUxRLock = new Object();
        @GuardedBy("mUxRLock")
        private CarUxRestrictions mLastRestrictions = null;

        void reset() {
            mLastEvent = null;
            mLastRestrictions = null;
        }

        // Returns True to indicate receipt of a driving state event.  False indicates a timeout.
        CarDrivingStateEvent waitForDrivingStateChange() throws InterruptedException {
            long start = SystemClock.elapsedRealtime();

            synchronized (mDrivingStateLock) {
                while (mLastEvent == null
                        && (start + DEFAULT_WAIT_TIMEOUT_MS > SystemClock.elapsedRealtime())) {
                    mDrivingStateLock.wait(100L);
                }
                return mLastEvent;
            }
        }

        @Override
        public void onDrivingStateChanged(CarDrivingStateEvent event) {
            Log.d(TAG, "onDrivingStateChanged, event: " + event.eventValue);
            synchronized (mDrivingStateLock) {
                // We're going to hold a reference to this object
                mLastEvent = event;
                mDrivingStateLock.notify();
            }
        }

        CarUxRestrictions waitForUxRestrictionsChange() throws InterruptedException {
            long start = SystemClock.elapsedRealtime();
            synchronized (mUxRLock) {
                while (mLastRestrictions == null
                        && (start + DEFAULT_WAIT_TIMEOUT_MS > SystemClock.elapsedRealtime())) {
                    mUxRLock.wait(100L);
                }
            }
            return mLastRestrictions;
        }

        @Override
        public void onUxRestrictionsChanged(CarUxRestrictions restrictions) {
            Log.d(TAG, "onUxRestrictionsChanged, restrictions: "
                    + restrictions.getActiveRestrictions());
            synchronized (mUxRLock) {
                mLastRestrictions = restrictions;
                mUxRLock.notify();
            }
        }
    }
}
