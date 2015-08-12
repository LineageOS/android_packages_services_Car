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

package com.android.car.hal;

import android.support.car.CarSensorEvent;
import android.support.car.CarSensorManager;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarSensorEventFactory;

import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.List;

/**
 * Sensor HAL implementation for physical sensors in car.
 */
public class SensorHalService extends SensorHalServiceBase {

    private static final boolean DBG_EVENTS = true;

    private static final int SENSOR_TYPE_INVALD = -1;

    private final VehicleHal mHal;
    private boolean mIsReady = false;
    private SensorHalServiceBase.SensorListener mSensorListener;
    private final SparseArray<HalProperty> mSensorToHalProperty = new SparseArray<HalProperty>();

    public SensorHalService(VehicleHal hal) {
        mHal = hal;
    }

    @Override
    public synchronized void init() {
        //TODO
        mIsReady = true;
    }

    @Override
    public synchronized List<HalProperty> takeSupportedProperties(List<HalProperty> allProperties) {
        LinkedList<HalProperty> supportedProperties = new LinkedList<HalProperty>();
        for (HalProperty halProperty : allProperties) {
            int sensor = getSensorTypeFromHalProperty(halProperty.propertyType);
            if (sensor != SENSOR_TYPE_INVALD &&
                    halProperty.changeMode !=
                    HalPropertyConst.VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_STATIC &&
                    (halProperty.accessType == HalPropertyConst.VehiclePropAccess.PROP_ACCESS_READ
                    || halProperty.accessType ==
                    HalPropertyConst.VehiclePropAccess.PROP_ACCESS_WRITE)) {
                supportedProperties.add(halProperty);
                mSensorToHalProperty.append(sensor, halProperty);
            }
        }
        return supportedProperties;
    }

    @Override
    public synchronized void release() {
        mSensorToHalProperty.clear();
        mIsReady = false;
    }

    @Override
    public void handleBooleanHalEvent(int property, boolean value, long timeStamp) {
        if (DBG_EVENTS) {
            Log.i(CarLog.TAG_SENSOR, "boolean event, property:" + property + " value:" + value);
        }
        switch (property) {
            case HalPropertyConst.VEHICLE_PROPERTY_NIGHT_MODE:
            case HalPropertyConst.VEHICLE_PROPERTY_PARKING_BRAKE_ON:
                break;
            default:
                throw new RuntimeException("handleBooleanHalEvent wrong property " + property);
        }
        int sensorType = getSensorTypeFromHalProperty(property);
        if (sensorType == SENSOR_TYPE_INVALD) {
            throw new RuntimeException("handleBooleanHalEvent no sensor defined for property " +
                    property);
        }
        CarSensorEvent event = CarSensorEventFactory.createBooleanEvent(sensorType, timeStamp,
                value);
        SensorHalServiceBase.SensorListener sensorListener = null;
        synchronized (this) {
            sensorListener = mSensorListener;
        }
        sensorListener.onSensorEvent(event);
    }

    @Override
    public void handleIntHalEvent(int property, int value, long timeStamp) {
        if (DBG_EVENTS) {
            Log.i(CarLog.TAG_SENSOR, "int event, property:" + property + " value:" + value);
        }
        switch (property) {
            case HalPropertyConst.VEHICLE_PROPERTY_GEAR_SELECTION:
            case HalPropertyConst.VEHICLE_PROPERTY_DRIVING_STATUS:
                break;
            default:
                throw new RuntimeException("handleIntHalEvent wrong property " + property);
        }
        int sensorType = getSensorTypeFromHalProperty(property);
        if (sensorType == SENSOR_TYPE_INVALD) {
            throw new RuntimeException("handleIntHalEvent no sensor defined for property " +
                    property);
        }
        CarSensorEvent event = CarSensorEventFactory.createIntEvent(sensorType, timeStamp, value);
        SensorHalServiceBase.SensorListener sensorListener = null;
        synchronized (this) {
            sensorListener = mSensorListener;
        }
        sensorListener.onSensorEvent(event);
    }

    @Override
    public void handleFloatHalEvent(int property, float value, long timeStamp) {
        if (DBG_EVENTS) {
            Log.i(CarLog.TAG_SENSOR, "float event, property:" + property + " value:" + value);
        }
        switch (property) {
            case HalPropertyConst.VEHICLE_PROPERTY_PERF_VEHICLE_SPEED:
                break;
            default:
                throw new RuntimeException("handleFloatHalEvent wrong property " + property);
        }
        int sensorType = getSensorTypeFromHalProperty(property);
        if (sensorType == SENSOR_TYPE_INVALD) {
            throw new RuntimeException("handleFloatHalEvent no sensor defined for property " +
                    property);
        }
        CarSensorEvent event = CarSensorEventFactory.createFloatEvent(sensorType, timeStamp, value);
        SensorHalServiceBase.SensorListener sensorListener = null;
        synchronized (this) {
            sensorListener = mSensorListener;
        }
        sensorListener.onSensorEvent(event);
    }

    @Override
    public synchronized void registerSensorListener(SensorHalServiceBase.SensorListener listener) {
        mSensorListener = listener;
        if (mIsReady) {
            listener.onSensorHalReady(this);
        }
    }

    @Override
    public synchronized boolean isReady() {
        return mIsReady;
    }

    @Override
    public synchronized int[] getSupportedSensors() {
        int[] supportedSensors = new int[mSensorToHalProperty.size()];
        for (int i = 0; i < supportedSensors.length; i++) {
            supportedSensors[i] = mSensorToHalProperty.keyAt(i);
        }
        return supportedSensors;
    }

    @Override
    public synchronized boolean requestSensorStart(int sensorType, int rate) {
        HalProperty halProp = mSensorToHalProperty.get(sensorType);
        if (halProp == null) {
            return false;
        }
        //TODO calculate sampling rate properly
        int r = mHal.subscribeProperty(halProp.propertyType,
                fixSamplingRateForProperty(halProp, rate));
        return r == 0;
    }

    private float fixSamplingRateForProperty(HalProperty prop, int carSensorManagerRate) {
        if (prop.changeMode ==
                HalPropertyConst.VehiclePropChangeMode.VEHICLE_PROP_CHANGE_MODE_ON_CHANGE) {
            return 0;
        }
        float rate = 1.0f;
        switch (carSensorManagerRate) {
            case CarSensorManager.SENSOR_RATE_FASTEST:
            case CarSensorManager.SENSOR_RATE_FAST:
                rate = 10f;
                break;
            case CarSensorManager.SENSOR_RATE_UI:
                rate = 5f;
                break;
            default: // fall back to default.
                break;
        }
        if (rate > prop.maxSampleRate) {
            rate = prop.maxSampleRate;
        }
        if (rate < prop.minSampleRate) {
            rate = prop.minSampleRate;
        }
        return rate;
    }

    @Override
    public synchronized void requestSensorStop(int sensorType) {
        HalProperty halProp = mSensorToHalProperty.get(sensorType);
        if (halProp == null) {
            return;
        }
        mHal.unsubscribeProperty(halProp.propertyType);
    }

    public synchronized void onSensorEvents(List<CarSensorEvent> events) {
        if (mSensorListener != null) {
            mSensorListener.onSensorEvents(events);
        }
    }

    public synchronized void onSensorEvent(CarSensorEvent event) {
        if (mSensorListener != null) {
            mSensorListener.onSensorEvent(event);
        }
    }

    /**
     * Covert hal property to sensor type. This is also used to check if specific property
     * is supported by sensor hal or not.
     * @param halPropertyType
     * @return
     */
    static int getSensorTypeFromHalProperty(int halPropertyType) {
        switch (halPropertyType) {
            case HalPropertyConst.VEHICLE_PROPERTY_PERF_VEHICLE_SPEED:
                return CarSensorManager.SENSOR_TYPE_CAR_SPEED;
            case HalPropertyConst.VEHICLE_PROPERTY_GEAR_SELECTION:
                return CarSensorManager.SENSOR_TYPE_GEAR;
            case HalPropertyConst.VEHICLE_PROPERTY_NIGHT_MODE:
                return CarSensorManager.SENSOR_TYPE_NIGHT;
            case HalPropertyConst.VEHICLE_PROPERTY_PARKING_BRAKE_ON:
                return CarSensorManager.SENSOR_TYPE_PARKING_BRAKE;
            case HalPropertyConst.VEHICLE_PROPERTY_DRIVING_STATUS:
                return CarSensorManager.SENSOR_TYPE_DRIVING_STATUS;
            default:
                Log.e(CarLog.TAG_SENSOR, "unknown sensor property from HAL " + halPropertyType);
                return SENSOR_TYPE_INVALD;
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("***Sensor HAL***");
        writer.println("****Supported properties****");
        for (int i = 0; i < mSensorToHalProperty.size(); i++) {
            writer.println(mSensorToHalProperty.valueAt(i).toString());
        }
    }
}
