/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.car.vehiclenetwork.haltest;

import static java.lang.Integer.toHexString;
import static junit.framework.Assert.assertTrue;

import android.util.Log;

import com.android.car.vehiclenetwork.VehicleNetwork.VehicleNetworkListener;
import com.android.car.vehiclenetwork.VehicleNetworkConsts.VehicleValueType;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValues;
import com.android.car.vehiclenetwork.VehicleNetworkProtoUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * This class must be used in testing environment only. Here's an example of usage:
 * <ul>
 *     <li>listener.reset();
 *     <li>listener.addExpectedValues(myPropertyId, myZone, 10, 20, 30)
 *     <li>... set values through VehicleNetworkService ...
 *     <li>listener.waitAndVerifyValues()
 *</ul>
 *
 */
class RecordingVehicleNetworkListener implements VehicleNetworkListener {

    private final static String TAG = VnsHalBaseTestCase.TAG;
    private final static int EVENTS_WAIT_TIMEOUT_MS = 2000;

    private final Set<NormalizedValue> mExpectedValues = new HashSet<>();
    // Using Set here instead of List as we probably shouldn't assert the order event was received.
    private final Set<NormalizedValue> mRecordedEvents = new HashSet<>();

    synchronized void reset() {
        mExpectedValues.clear();
        mRecordedEvents.clear();
    }

    void addExpectedValues(int propertyId, int zone, Object... values) {
        for (Object value : values) {
            mExpectedValues.add(NormalizedValue.createFor(propertyId, zone, value));
        }
    }

    /**
     * Waits for events to come for #EVENTS_WAIT_TIMEOUT_MS milliseconds and asserts that recorded
     * values match with expected.
     * */
    synchronized void waitAndVerifyValues() throws InterruptedException {
        long currentTime = System.currentTimeMillis();
        long deadline = currentTime + EVENTS_WAIT_TIMEOUT_MS;
        while (currentTime < deadline && !isExpectedMatchedRecorded()) {
            wait(deadline - currentTime);
            currentTime = System.currentTimeMillis();
        }
        assertTrue("Expected values: " + Arrays.toString(mExpectedValues.toArray())
                        + " doesn't match recorded: " + Arrays.toString(mRecordedEvents.toArray()),
                isExpectedMatchedRecorded());
    }

    private boolean isExpectedMatchedRecorded() {
        for (NormalizedValue expectedValue : mExpectedValues) {
            if (!mRecordedEvents.contains(expectedValue)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onVehicleNetworkEvents(VehiclePropValues values) {
        for (VehiclePropValue value : values.getValuesList()) {
            Log.d(TAG, "onVehicleNetworkEvents, value: "
                    + VehicleNetworkProtoUtil.VehiclePropValueToString(value));

            synchronized (this) {
                mRecordedEvents.add(NormalizedValue.createFor(value));
                notifyAll();
            }
        }
    }

    @Override
    public void onHalError(int errorCode, int property, int operation) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onHalRestart(boolean inMocking) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onPropertySet(VehiclePropValue value) {
        // TODO
    }

    // To be used to compare expected vs recorded values.
    private static class NormalizedValue {
        private final int propertyId;
        private final int zone;
        private final List<Object> value;

        static NormalizedValue createFor(VehiclePropValue value) {
            return new NormalizedValue(value.getProp(), value.getZone(), getObjectValue(value));
        }

        static NormalizedValue createFor(int propertyId, int zone, Object value) {
            return new NormalizedValue(propertyId, zone, wrapSingleObjectToList(value));
        }

        // Do not call this ctor directly, use appropriate factory methods to create an object.
        private NormalizedValue(int propertyId, int zone, List<Object> value) {
            this.propertyId = propertyId;
            this.zone = zone;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            NormalizedValue propValue = (NormalizedValue) o;
            return propertyId == propValue.propertyId &&
                    zone == propValue.zone &&
                    Objects.equals(value, propValue.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(propertyId, zone, value);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + " { "
                    + "prop: 0x" + toHexString(propertyId)
                    + ", zone: 0x" + toHexString(zone)
                    + ", value: " + Arrays.toString(value.toArray())
                    + " }";
        }

        private static List<Object> wrapSingleObjectToList(Object value) {
            if (value instanceof Integer
                    || value instanceof Float) {
                List<Object> list = new ArrayList<>(1);
                list.add(value);
                return list;
            } else if (value instanceof Boolean) {
                List<Object> list = new ArrayList<>(1);
                list.add((Boolean)value ? 1 : 0);
                return list;
            } else if (value instanceof Collection<?>) {
                return new ArrayList<>((Collection<?>) value);
            } else {
                throw new IllegalArgumentException("Unexpected type: " + value);
            }
        }

        // Converts any VehiclePropValue to either ArrayList<Integer> or ArrayList<Float>
        private static List<Object> getObjectValue(VehiclePropValue val) {
            switch (val.getValueType()) {
                case VehicleValueType.VEHICLE_VALUE_TYPE_BOOLEAN:
                case VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_BOOLEAN:
                case VehicleValueType.VEHICLE_VALUE_TYPE_INT32:
                case VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC2:
                case VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC3:
                case VehicleValueType.VEHICLE_VALUE_TYPE_INT32_VEC4:
                case VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_INT32:
                case VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_INT32_VEC2:
                case VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_INT32_VEC3:
                case VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_INT32_VEC4:
                    return new ArrayList<>(val.getInt32ValuesList());
                case VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT:
                case VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT_VEC2:
                case VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT_VEC3:
                case VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT_VEC4:
                case VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_FLOAT:
                case VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_FLOAT_VEC2:
                case VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_FLOAT_VEC3:
                case VehicleValueType.VEHICLE_VALUE_TYPE_ZONED_FLOAT_VEC4:
                    return new ArrayList<>(val.getFloatValuesList());
                default:
                    throw new IllegalArgumentException("Unexpected type: " + val.getValueType());
            }
        }
    }
}