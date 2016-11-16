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

import static android.hardware.vehicle.V2_0.VehicleProperty.DRIVING_STATUS;
import static android.hardware.vehicle.V2_0.VehicleProperty.FUEL_LEVEL_LOW;
import static android.hardware.vehicle.V2_0.VehicleProperty.GEAR_SELECTION;
import static android.hardware.vehicle.V2_0.VehicleProperty.NIGHT_MODE;
import static android.hardware.vehicle.V2_0.VehicleProperty.PARKING_BRAKE_ON;
import static android.hardware.vehicle.V2_0.VehicleProperty.PERF_VEHICLE_SPEED;
import static java.lang.Integer.toHexString;

import android.annotation.Nullable;
import android.car.hardware.CarSensorEvent;
import android.car.hardware.CarSensorManager;
import android.hardware.vehicle.V2_0.VehiclePropConfig;
import android.hardware.vehicle.V2_0.VehiclePropValue;
import android.hardware.vehicle.V2_0.VehicleProperty;
import android.hardware.vehicle.V2_0.VehiclePropertyAccess;
import android.hardware.vehicle.V2_0.VehiclePropertyChangeMode;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarSensorEventFactory;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 * Sensor HAL implementation for physical sensors in car.
 */
public class SensorHalService extends SensorHalServiceBase {

    private static final boolean DBG_EVENTS = false;

    private static final int SENSOR_TYPE_INVALID = -1;

    private final VehicleHal mHal;
    private boolean mIsReady = false;
    private SensorHalServiceBase.SensorListener mSensorListener;
    private final SparseArray<VehiclePropConfig> mSensorToHalProperty = new SparseArray<>();

    public SensorHalService(VehicleHal hal) {
        mHal = hal;
    }

    @Override
    public synchronized void init() {
        mIsReady = true;
    }

    @Override
    public synchronized Collection<VehiclePropConfig> takeSupportedProperties(
            Collection<VehiclePropConfig> allProperties) {
        LinkedList<VehiclePropConfig> supportedProperties = new LinkedList<>();
        for (VehiclePropConfig halProperty : allProperties) {
            int sensor = getSensorTypeFromHalProperty(halProperty.prop);
            if (sensor != SENSOR_TYPE_INVALID
                    && halProperty.changeMode != VehiclePropertyChangeMode.STATIC
                    && ((halProperty.access & VehiclePropertyAccess.READ) != 0)) {
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

    // Should be used only inside handleHalEvents method.
    private final LinkedList<CarSensorEvent> mEventsToDispatch = new LinkedList<>();
    @Override
    public void handleHalEvents(List<VehiclePropValue> values) {
        for (VehiclePropValue v : values) {
            CarSensorEvent event = createCarSensorEvent(v);
            if (event != null) {
                mEventsToDispatch.add(event);
            }
        }
        SensorHalServiceBase.SensorListener sensorListener = null;
        synchronized (this) {
            sensorListener = mSensorListener;
        }
        if (sensorListener != null) {
            sensorListener.onSensorEvents(mEventsToDispatch);
        }
        mEventsToDispatch.clear();
    }

    private CarSensorEvent createCarSensorEvent(VehiclePropValue v) {
        int property = v.prop;
        int sensorType = getSensorTypeFromHalProperty(property);
        if (sensorType == SENSOR_TYPE_INVALID) {
            throw new RuntimeException("handleBooleanHalEvent no sensor defined for property " +
                    property);
        }
        switch (property) {
            // boolean
            case VehicleProperty.NIGHT_MODE:
            case VehicleProperty.PARKING_BRAKE_ON:
            case VehicleProperty.FUEL_LEVEL_LOW: {
                if (DBG_EVENTS) {
                    Log.i(CarLog.TAG_SENSOR, "boolean event, property:" +
                            toHexString(property) + " value:" + v.value.int32Values.get(0));
                }
                return CarSensorEventFactory.createBooleanEvent(sensorType, v.timestamp,
                        v.value.int32Values.get(0) == 1);
            }
            // int
            case VehicleProperty.GEAR_SELECTION:
            case VehicleProperty.DRIVING_STATUS: {
                if (DBG_EVENTS) {
                    Log.i(CarLog.TAG_SENSOR, "int event, property:" +
                            toHexString(property) + " value:" + v.value.int32Values.get(0));
                }
                return CarSensorEventFactory.createIntEvent(sensorType, v.timestamp,
                        v.value.int32Values.get(0));
            }
            // float
            case VehicleProperty.PERF_VEHICLE_SPEED: {
                if (DBG_EVENTS) {
                    Log.i(CarLog.TAG_SENSOR, "float event, property:" +
                            toHexString(property) + " value:" + v.value.floatValues.get(0));
                }
                return CarSensorEventFactory.createFloatEvent(sensorType, v.timestamp,
                        v.value.floatValues.get(0));
            }
        }
        return null;
    }

    @Override
    public synchronized void registerSensorListener(SensorHalServiceBase.SensorListener listener) {
        mSensorListener = listener;
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
        VehiclePropConfig config = mSensorToHalProperty.get(sensorType);
        if (config == null) {
            return false;
        }
        //TODO calculate sampling rate properly, bug: 32095903
        mHal.subscribeProperty(this, config.prop, fixSamplingRateForProperty(config, rate));
        return true;
    }

    @Nullable
    public CarSensorEvent getCurrentSensorValue(int sensorType) {
        VehiclePropConfig config;
        synchronized (this) {
            config = mSensorToHalProperty.get(sensorType);
        }
        if (config == null) {
            Log.e(CarLog.TAG_SENSOR, "sensor type not available 0x" +
                    toHexString(sensorType));
            return null;
        }
        try {
            VehiclePropValue value = mHal.get(config.prop);
            return createCarSensorEvent(value);
        } catch (PropertyTimeoutException e) {
            Log.e(CarLog.TAG_SENSOR, "property not ready 0x" + toHexString(config.prop), e);
            return null;
        }
    }

    private float fixSamplingRateForProperty(VehiclePropConfig prop, int carSensorManagerRate) {
        switch (prop.changeMode) {
            case VehiclePropertyChangeMode.ON_CHANGE:
            case VehiclePropertyChangeMode.ON_SET:
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
        VehiclePropConfig config = mSensorToHalProperty.get(sensorType);
        if (config == null) {
            return;
        }
        mHal.unsubscribeProperty(this, config.prop);
    }

    /**
     * Covert hal property to sensor type. This is also used to check if specific property
     * is supported by sensor hal or not.
     * @param halPropertyType
     * @return
     */
    static int getSensorTypeFromHalProperty(int halPropertyType) {
        switch (halPropertyType) {
            case PERF_VEHICLE_SPEED:
                return CarSensorManager.SENSOR_TYPE_CAR_SPEED;
            case GEAR_SELECTION:
                return CarSensorManager.SENSOR_TYPE_GEAR;
            case NIGHT_MODE:
                return CarSensorManager.SENSOR_TYPE_NIGHT;
            case PARKING_BRAKE_ON:
                return CarSensorManager.SENSOR_TYPE_PARKING_BRAKE;
            case DRIVING_STATUS:
                return CarSensorManager.SENSOR_TYPE_DRIVING_STATUS;
            case FUEL_LEVEL_LOW:
                return CarSensorManager.SENSOR_TYPE_FUEL_LEVEL;
            default:
                return SENSOR_TYPE_INVALID;
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*Sensor HAL*");
        writer.println("**Supported properties**");
        for (int i = 0; i < mSensorToHalProperty.size(); i++) {
            writer.println(mSensorToHalProperty.valueAt(i).toString());
        }
    }
}
