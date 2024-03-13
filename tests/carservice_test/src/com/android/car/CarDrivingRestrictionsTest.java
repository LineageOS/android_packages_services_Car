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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.car.Car;
import android.car.drivingstate.CarDrivingStateEvent;
import android.car.drivingstate.CarDrivingStateManager;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.CarPropertyValue;
import android.hardware.automotive.vehicle.VehicleGear;
import android.hardware.automotive.vehicle.VehicleIgnitionState;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.car.hal.test.AidlVehiclePropValueBuilder;
import com.android.internal.annotations.GuardedBy;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CarDrivingRestrictionsTest extends MockedCarTestBase {
    private static final String TAG = CarDrivingRestrictionsTest.class.getSimpleName();
    private CarDrivingStateManager mCarDrivingStateManager;
    private CarUxRestrictionsManager mCarUxRManager;
    private DrivingStateListener mDrivingStateListener;
    // Currently set restrictions currently set in car_ux_restrictions_map.xml
    private static final int UX_RESTRICTIONS_MOVING = CarUxRestrictions.UX_RESTRICTIONS_NO_DIALPAD
            | CarUxRestrictions.UX_RESTRICTIONS_NO_FILTERING
            | CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH
            | CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD
            | CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO
            | CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT
            | CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP
            | CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE;
    private static final int UX_RESTRICTIONS_IDLE = CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO;

    @Override
    protected void configureMockedHal() {
        addAidlProperty(VehicleProperty.PERF_VEHICLE_SPEED, AidlVehiclePropValueBuilder
                .newBuilder(VehicleProperty.PERF_VEHICLE_SPEED)
                .addFloatValues(0f)
                .build());
        addAidlProperty(VehicleProperty.PARKING_BRAKE_ON, AidlVehiclePropValueBuilder
                .newBuilder(VehicleProperty.PARKING_BRAKE_ON)
                .setBooleanValue(false)
                .build());
        addAidlProperty(VehicleProperty.GEAR_SELECTION, AidlVehiclePropValueBuilder
                .newBuilder(VehicleProperty.GEAR_SELECTION)
                .addIntValues(0)
                .build());
        addAidlProperty(VehicleProperty.IGNITION_STATE, AidlVehiclePropValueBuilder
                .newBuilder(VehicleProperty.IGNITION_STATE)
                .addIntValues(0)
                .build());
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mCarDrivingStateManager = (CarDrivingStateManager) getCar()
                .getCarManager(Car.CAR_DRIVING_STATE_SERVICE);
        mCarUxRManager = (CarUxRestrictionsManager) getCar()
                .getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
        mDrivingStateListener = new DrivingStateListener();
        mCarDrivingStateManager.registerListener(mDrivingStateListener);
        mCarUxRManager.registerListener(mDrivingStateListener);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        mCarDrivingStateManager.unregisterListener();
        mCarUxRManager.unregisterListener();
    }

    private void aidlInjectIntEvent(int propertyId, int value, int status) {
        getAidlMockedVehicleHal().injectEvent(
                AidlVehiclePropValueBuilder.newBuilder(propertyId)
                        .addIntValues(value)
                        .setTimestamp(SystemClock.elapsedRealtimeNanos())
                        .setStatus(status)
                        .build());
    }

    private void aidlInjectVehicleSpeedEvent(float value, int status) {
        getAidlMockedVehicleHal().injectEvent(
                AidlVehiclePropValueBuilder.newBuilder(VehicleProperty.PERF_VEHICLE_SPEED)
                        .addFloatValues(value)
                        .setTimestamp(SystemClock.elapsedRealtimeNanos())
                        .setStatus(status)
                        .build());
    }

    private void aidlInjectParkingBrakeEvent(boolean value, int status) {
        getAidlMockedVehicleHal().injectEvent(
                AidlVehiclePropValueBuilder.newBuilder(VehicleProperty.PARKING_BRAKE_ON)
                        .setBooleanValue(value)
                        .setTimestamp(SystemClock.elapsedRealtimeNanos())
                        .setStatus(status)
                        .build());
    }

    @Test
    public void testDrivingStateChange() throws InterruptedException {
        CarDrivingStateEvent drivingEvent;
        CarUxRestrictions restrictions;
        // With no gear value available, driving state should be unknown
        mDrivingStateListener.reset();
        // Test Parked state and corresponding restrictions based on car_ux_restrictions_map.xml
        Log.d(TAG, "Injecting gear park");
        aidlInjectIntEvent(VehicleProperty.GEAR_SELECTION, VehicleGear.GEAR_PARK,
                CarPropertyValue.STATUS_AVAILABLE);
        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_PARKED);

        Log.d(TAG, "Injecting speed 0");
        aidlInjectVehicleSpeedEvent(0.0f, CarPropertyValue.STATUS_AVAILABLE);

        // Switch gear to drive. Driving state changes to Idling but the UX restrictions don't
        // change between parked and idling.
        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting gear drive");
        aidlInjectIntEvent(VehicleProperty.GEAR_SELECTION, VehicleGear.GEAR_DRIVE,
                CarPropertyValue.STATUS_AVAILABLE);
        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_IDLING);

        // Test Moving state and corresponding restrictions based on car_ux_restrictions_map.xml
        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting speed 30");
        aidlInjectVehicleSpeedEvent(30.0f, CarPropertyValue.STATUS_AVAILABLE);
        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_MOVING);
        restrictions = mDrivingStateListener.waitForUxRestrictionsChange(UX_RESTRICTIONS_MOVING);
        assertNotNull(restrictions);
        assertTrue(restrictions.isRequiresDistractionOptimization());
        assertThat(restrictions.getActiveRestrictions()).isEqualTo(UX_RESTRICTIONS_MOVING);

        // Test Idling state and corresponding restrictions based on car_ux_restrictions_map.xml
        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting speed 0");
        aidlInjectVehicleSpeedEvent(0.0f, CarPropertyValue.STATUS_AVAILABLE);
        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_IDLING);
        restrictions = mDrivingStateListener.waitForUxRestrictionsChange(UX_RESTRICTIONS_IDLE);
        assertNotNull(restrictions);
        assertTrue(restrictions.isRequiresDistractionOptimization());
        assertThat(restrictions.getActiveRestrictions()).isEqualTo(UX_RESTRICTIONS_IDLE);

        // Test Moving state and corresponding restrictions when driving in reverse.
        Log.d(TAG, "Injecting gear reverse");
        aidlInjectIntEvent(VehicleProperty.GEAR_SELECTION, VehicleGear.GEAR_REVERSE,
                CarPropertyValue.STATUS_AVAILABLE);

        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting speed -10");
        aidlInjectVehicleSpeedEvent(-10.0f, CarPropertyValue.STATUS_AVAILABLE);
        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_MOVING);
        restrictions = mDrivingStateListener.waitForUxRestrictionsChange(UX_RESTRICTIONS_MOVING);
        assertNotNull(restrictions);
        assertTrue(restrictions.isRequiresDistractionOptimization());
        assertThat(restrictions.getActiveRestrictions()).isEqualTo(UX_RESTRICTIONS_MOVING);

        // Apply Parking brake. Supported gears is not provided in this test and hence
        // Automatic transmission should be assumed and hence parking brake state should not
        // make a difference to the driving state.
        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting parking brake on");
        aidlInjectParkingBrakeEvent(true, CarPropertyValue.STATUS_AVAILABLE);
        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNull(drivingEvent);
    }

    @Test
    public void testDrivingStateChangeForMalformedInputs() throws InterruptedException {
        CarDrivingStateEvent drivingEvent;
        CarUxRestrictions restrictions;

        // Start with gear = park and speed = 0 to begin with a known state.
        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting gear park");
        aidlInjectIntEvent(VehicleProperty.GEAR_SELECTION, VehicleGear.GEAR_PARK,
                CarPropertyValue.STATUS_AVAILABLE);
        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_PARKED);

        Log.d(TAG, "Injecting speed 0");
        aidlInjectVehicleSpeedEvent(0.0f, CarPropertyValue.STATUS_AVAILABLE);

        // Inject an invalid gear. Since speed is still valid, idling will be the expected
        // driving state
        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting gear -1 with status unavailable");
        aidlInjectIntEvent(VehicleProperty.GEAR_SELECTION, -1, CarPropertyValue.STATUS_UNAVAILABLE);
        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_IDLING);

        // Now, send in an invalid speed value as well, now the driving state will be unknown and
        // the UX restrictions will change to fully restricted.
        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting speed -1 with status unavailable");
        aidlInjectVehicleSpeedEvent(-1.0f, CarPropertyValue.STATUS_UNAVAILABLE);
        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_UNKNOWN);
        restrictions = mDrivingStateListener.waitForUxRestrictionsChange(
                CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
        assertNotNull(restrictions);
        assertTrue(restrictions.isRequiresDistractionOptimization());
        assertThat(restrictions.getActiveRestrictions())
                .isEqualTo(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    private CarDrivingStateEvent setupDrivingStateUnknown() throws InterruptedException {
        CarDrivingStateEvent drivingEvent;
        mDrivingStateListener.reset();

        // Inject an invalid gear.
        Log.d(TAG, "Injecting gear -1 with status unavailable");
        aidlInjectIntEvent(VehicleProperty.GEAR_SELECTION, -1, CarPropertyValue.STATUS_UNAVAILABLE);

        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNull(drivingEvent);

        // Now, send in an invalid speed value as well, now the driving state will be unknown.
        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting speed -1 with status unavailable");
        aidlInjectVehicleSpeedEvent(-1.0f, CarPropertyValue.STATUS_UNAVAILABLE);

        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_UNKNOWN);
        return drivingEvent;
    }

    @Test
    public void testGearUnknown_speedUnknown_ignitionStateOff_drivingStateInferredAsParked()
            throws InterruptedException {
        setupDrivingStateUnknown();

        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting ignition state off");
        aidlInjectIntEvent(VehicleProperty.IGNITION_STATE, VehicleIgnitionState.OFF,
                CarPropertyValue.STATUS_AVAILABLE);

        CarDrivingStateEvent drivingEvent;
        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_PARKED);
    }

    @Test
    public void testGearUnknown_speedUnknown_ignitionStateAcc_drivingStateInferredAsParked()
            throws InterruptedException {
        setupDrivingStateUnknown();

        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting ignition state acc");
        aidlInjectIntEvent(VehicleProperty.IGNITION_STATE, VehicleIgnitionState.ACC,
                CarPropertyValue.STATUS_AVAILABLE);

        CarDrivingStateEvent drivingEvent;
        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_PARKED);
    }

    @Test
    public void testGearUnknown_speedUnknown_ignitionStateLock_drivingStateInferredAsParked()
            throws InterruptedException {
        setupDrivingStateUnknown();

        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting ignition state lock");
        aidlInjectIntEvent(VehicleProperty.IGNITION_STATE, VehicleIgnitionState.LOCK,
                CarPropertyValue.STATUS_AVAILABLE);

        CarDrivingStateEvent drivingEvent;
        drivingEvent = mDrivingStateListener.waitForDrivingStateChange();
        assertNotNull(drivingEvent);
        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_PARKED);
    }

    @Test
    public void testGearUnknown_speedUnknown_ignitionStateOn_drivingStateInferredAsUnknown()
            throws InterruptedException {
        CarDrivingStateEvent drivingEvent;
        drivingEvent = setupDrivingStateUnknown();
        CarUxRestrictions restrictions;
        restrictions = mDrivingStateListener.waitForUxRestrictionsChange(
                CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);

        mDrivingStateListener.reset();
        Log.d(TAG, "Injecting ignition state on");
        aidlInjectIntEvent(VehicleProperty.IGNITION_STATE, VehicleIgnitionState.ON,
                CarPropertyValue.STATUS_AVAILABLE);

        assertThat(drivingEvent.eventValue).isEqualTo(CarDrivingStateEvent.DRIVING_STATE_UNKNOWN);
        assertNotNull(restrictions);
        assertTrue(restrictions.isRequiresDistractionOptimization());
        assertThat(restrictions.getActiveRestrictions())
                .isEqualTo(CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
    }

    /**
     * Callback function we register for driving state update notifications.
     */
    private static final class DrivingStateListener implements
            CarDrivingStateManager.CarDrivingStateEventListener,
            CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
        private final Object mDrivingStateLock = new Object();
        @GuardedBy("mDrivingStateLock")
        private CarDrivingStateEvent mLastEvent = null;
        private final Object mUxRLock = new Object();
        @GuardedBy("mUxRLock")
        private CarUxRestrictions mLastRestrictions = null;

        void reset() {
            synchronized (mDrivingStateLock) {
                mLastEvent = null;
            }
            synchronized (mUxRLock) {
                mLastRestrictions = null;
            }
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
                mDrivingStateLock.notifyAll();
            }
        }

        CarUxRestrictions waitForUxRestrictionsChange(int expectedRestrictions)
                throws InterruptedException {
            long start = SystemClock.elapsedRealtime();
            synchronized (mUxRLock) {
                while ((mLastRestrictions == null
                        || mLastRestrictions.getActiveRestrictions() != expectedRestrictions)
                        && (start + DEFAULT_WAIT_TIMEOUT_MS > SystemClock.elapsedRealtime())) {
                    mUxRLock.wait(100L);
                }
                return mLastRestrictions;
            }
        }

        @Override
        public void onUxRestrictionsChanged(CarUxRestrictions restrictions) {
            Log.d(TAG, "onUxRestrictionsChanged, restrictions: "
                    + restrictions.getActiveRestrictions());
            synchronized (mUxRLock) {
                mLastRestrictions = restrictions;
                mUxRLock.notifyAll();
            }
        }
    }
}
