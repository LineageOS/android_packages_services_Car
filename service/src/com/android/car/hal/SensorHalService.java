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

import static java.lang.Integer.toHexString;

import android.annotation.Nullable;
import android.car.hardware.CarSensorEvent;
import android.car.hardware.CarSensorManager;
import android.hardware.vehicle.V2_0.VehicleGear;
import android.hardware.vehicle.V2_0.VehicleIgnitionState;
import android.hardware.vehicle.V2_0.VehiclePropConfig;
import android.hardware.vehicle.V2_0.VehiclePropValue;
import android.hardware.vehicle.V2_0.VehicleProperty;
import android.hardware.vehicle.V2_0.VehiclePropertyAccess;
import android.hardware.vehicle.V2_0.VehiclePropertyChangeMode;
import android.hardware.vehicle.V2_0.VehiclePropertyType;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

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

    private static final int SENSOR_TYPE_INVALID = NOT_SUPPORTED_PROPERTY;

    private final VehicleHal mHal;
    private boolean mIsReady = false;
    private SensorHalServiceBase.SensorListener mSensorListener;

    // Manager property Id to HAL property Id mapping.
    private final static ManagerToHalPropIdMap mManagerToHalPropIdMap =
            ManagerToHalPropIdMap.create(
                    CarSensorManager.SENSOR_TYPE_CAR_SPEED, VehicleProperty.PERF_VEHICLE_SPEED,
                    CarSensorManager.SENSOR_TYPE_GEAR, VehicleProperty.GEAR_SELECTION,
                    CarSensorManager.SENSOR_TYPE_NIGHT, VehicleProperty.NIGHT_MODE,
                    CarSensorManager.SENSOR_TYPE_PARKING_BRAKE, VehicleProperty.PARKING_BRAKE_ON,
                    CarSensorManager.SENSOR_TYPE_DRIVING_STATUS, VehicleProperty.DRIVING_STATUS,
                    CarSensorManager.SENSOR_TYPE_FUEL_LEVEL, VehicleProperty.FUEL_LEVEL_LOW,
                    CarSensorManager.SENSOR_TYPE_IGNITION_STATE, VehicleProperty.IGNITION_STATE);

    private final static SparseIntArray mMgrGearToHalMap = initSparseIntArray(
            VehicleGear.GEAR_NEUTRAL, CarSensorEvent.GEAR_NEUTRAL,
            VehicleGear.GEAR_REVERSE, CarSensorEvent.GEAR_REVERSE,
            VehicleGear.GEAR_PARK, CarSensorEvent.GEAR_PARK,
            VehicleGear.GEAR_DRIVE, CarSensorEvent.GEAR_DRIVE,
            VehicleGear.GEAR_LOW, CarSensorEvent.GEAR_FIRST, // Also GEAR_1 - the value is the same.
            VehicleGear.GEAR_2, CarSensorEvent.GEAR_SECOND,
            VehicleGear.GEAR_3, CarSensorEvent.GEAR_THIRD,
            VehicleGear.GEAR_4, CarSensorEvent.GEAR_FOURTH,
            VehicleGear.GEAR_5, CarSensorEvent.GEAR_FIFTH,
            VehicleGear.GEAR_6, CarSensorEvent.GEAR_SIXTH,
            VehicleGear.GEAR_7, CarSensorEvent.GEAR_SEVENTH,
            VehicleGear.GEAR_8, CarSensorEvent.GEAR_EIGHTH,
            VehicleGear.GEAR_9, CarSensorEvent.GEAR_NINTH);

    private final static SparseIntArray mMgrIgnitionStateToHalMap = initSparseIntArray(
            VehicleIgnitionState.UNDEFINED, CarSensorEvent.IGNITION_STATE_UNDEFINED,
            VehicleIgnitionState.LOCK, CarSensorEvent.IGNITION_STATE_LOCK,
            VehicleIgnitionState.OFF, CarSensorEvent.IGNITION_STATE_OFF,
            VehicleIgnitionState.ACC, CarSensorEvent.IGNITION_STATE_ACC,
            VehicleIgnitionState.ON, CarSensorEvent.IGNITION_STATE_ON,
            VehicleIgnitionState.START, CarSensorEvent.IGNITION_STATE_START);

    private final SparseArray<VehiclePropConfig> mSensorToPropConfig = new SparseArray<>();

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
            int sensor = mManagerToHalPropIdMap.getManagerPropId(halProperty.prop);
            if (sensor != SENSOR_TYPE_INVALID
                    && halProperty.changeMode != VehiclePropertyChangeMode.STATIC
                    && ((halProperty.access & VehiclePropertyAccess.READ) != 0)) {
                supportedProperties.add(halProperty);
                mSensorToPropConfig.append(sensor, halProperty);
            }
        }
        return supportedProperties;
    }

    @Override
    public synchronized void release() {
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

    @Nullable
    private Integer mapHalEnumValueToMgr(int propId, int halValue) {
        int mgrValue = halValue;

        switch (propId) {
            case VehicleProperty.GEAR_SELECTION:
                mgrValue = mMgrGearToHalMap.get(halValue, -1);
                break;
            case VehicleProperty.IGNITION_STATE:
                mgrValue =  mMgrIgnitionStateToHalMap.get(halValue, -1);
            default:
                break; // Do nothing
        }
        return mgrValue == -1 ? null : mgrValue;
    }

    private CarSensorEvent createCarSensorEvent(VehiclePropValue v) {
        int property = v.prop;
        int sensorType = mManagerToHalPropIdMap.getManagerPropId(property);
        if (sensorType == SENSOR_TYPE_INVALID) {
            throw new RuntimeException("no sensor defined for property 0x" + toHexString(property));
        }

        int dataType = property & VehiclePropertyType.MASK;

        switch (dataType) {
            case VehiclePropertyType.BOOLEAN:
                if (DBG_EVENTS) {
                    Log.i(CarLog.TAG_SENSOR, "boolean event, property:" +
                            toHexString(property) + " value:" + v.value.int32Values.get(0));
                }
                return CarSensorEventFactory.createBooleanEvent(sensorType, v.timestamp,
                        v.value.int32Values.get(0) == 1);
            case VehiclePropertyType.INT32:
                if (DBG_EVENTS) {
                    Log.i(CarLog.TAG_SENSOR, "int event, property:" +
                            toHexString(property) + " value:" + v.value.int32Values.get(0));
                }
                Integer mgrVal = mapHalEnumValueToMgr(property, v.value.int32Values.get(0));
                return mgrVal == null ? null
                        : CarSensorEventFactory.createIntEvent(sensorType, v.timestamp, mgrVal);
            case VehiclePropertyType.FLOAT: {
                if (DBG_EVENTS) {
                    Log.i(CarLog.TAG_SENSOR, "float event, property:" +
                            toHexString(property) + " value:" + v.value.floatValues.get(0));
                }
                return CarSensorEventFactory.createFloatEvent(sensorType, v.timestamp,
                        v.value.floatValues.get(0));
            }
            default:
                Log.w(CarLog.TAG_SENSOR, "createCarSensorEvent: unsupported type: 0x"
                        + toHexString(dataType));
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
        int[] supportedSensors = new int[mSensorToPropConfig.size()];
        for (int i = 0; i < supportedSensors.length; i++) {
            supportedSensors[i] = mSensorToPropConfig.keyAt(i);
        }
        return supportedSensors;
    }

    @Override
    public synchronized boolean requestSensorStart(int sensorType, int rate) {
        VehiclePropConfig config = mSensorToPropConfig.get(sensorType);
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
            config = mSensorToPropConfig.get(sensorType);
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
        VehiclePropConfig config = mSensorToPropConfig.get(sensorType);
        if (config == null) {
            return;
        }
        mHal.unsubscribeProperty(this, config.prop);
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*Sensor HAL*");
        writer.println("**Supported properties**");
        for (int i = 0; i < mSensorToPropConfig.size(); i++) {
            writer.println(mSensorToPropConfig.valueAt(i).toString());
        }
    }

    private static SparseIntArray initSparseIntArray(int... keyValuePairs) {
        int inputLength = keyValuePairs.length;
        if (inputLength % 2 != 0) {
            throw new IllegalArgumentException("Odd number of key-value elements");
        }

        SparseIntArray map = new SparseIntArray(inputLength / 2);
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}
