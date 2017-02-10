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

package com.android.car.hal;

import android.annotation.Nullable;
import android.car.annotation.FutureFeature;
import android.car.hardware.CarDiagnosticEvent;
import android.car.hardware.CarDiagnosticManager;
import android.car.hardware.CarSensorManager;
import android.hardware.automotive.vehicle.V2_0.Obd2FloatSensorIndex;
import android.hardware.automotive.vehicle.V2_0.Obd2IntegerSensorIndex;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyChangeMode;
import android.util.Log;
import android.util.SparseArray;
import com.android.car.CarLog;
import com.android.car.CarServiceUtils;
import com.android.car.vehiclehal.VehiclePropValueBuilder;
import java.io.PrintWriter;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;

/**
 * Diagnostic HAL service supporting gathering diagnostic info from VHAL and translating it into
 * higher-level semantic information
 */
@FutureFeature
public class DiagnosticHalService extends SensorHalServiceBase {
    private DiagnosticListener mDiagnosticListener;
    protected final SparseArray<VehiclePropConfig> mVehiclePropertyToConfig = new SparseArray<>();

    public DiagnosticHalService(VehicleHal hal) {
        super(hal);
    }

    @Override
    protected int getTokenForProperty(VehiclePropConfig propConfig) {
        switch (propConfig.prop) {
            case VehicleProperty.OBD2_LIVE_FRAME:
                mVehiclePropertyToConfig.put(propConfig.prop, propConfig);
                Log.i(CarLog.TAG_DIAGNOSTIC, String.format("configArray for OBD2_LIVE_FRAME is %s",
                    propConfig.configArray));
                return CarDiagnosticManager.FRAME_TYPE_FLAG_LIVE;
            case VehicleProperty.OBD2_FREEZE_FRAME:
                mVehiclePropertyToConfig.put(propConfig.prop, propConfig);
                Log.i(CarLog.TAG_DIAGNOSTIC, String.format("configArray for OBD2_FREEZE_FRAME is %s",
                    propConfig.configArray));
                return CarDiagnosticManager.FRAME_TYPE_FLAG_FREEZE;
            case VehicleProperty.OBD2_FREEZE_FRAME_INFO:
                return propConfig.prop;
            default:
                return SENSOR_TYPE_INVALID;
        }
    }

    private VehiclePropConfig getPropConfig(int halPropId) {
        return mVehiclePropertyToConfig.get(halPropId, null);
    }

    private int getNumIntegerSensors(int halPropId) {
        int count = Obd2IntegerSensorIndex.LAST_SYSTEM_INDEX + 1;
        count = count + getPropConfig(halPropId).configArray.get(0);
        return count;
    }

    private int getNumFloatSensors(int halPropId) {
        int count = Obd2FloatSensorIndex.LAST_SYSTEM_INDEX + 1;
        count = count + getPropConfig(halPropId).configArray.get(1);
        return count;
    }

    private CarDiagnosticEvent createCarDiagnosticEvent(VehiclePropValue value) {
        if (null == value)
            return null;

        final boolean isFreezeFrame = value.prop == VehicleProperty.OBD2_FREEZE_FRAME;

        CarDiagnosticEvent.Builder builder =
                (isFreezeFrame
                                ? CarDiagnosticEvent.Builder.freezeFrame()
                                : CarDiagnosticEvent.Builder.liveFrame())
                        .atTimestamp(value.timestamp);

        BitSet bitset = BitSet.valueOf(CarServiceUtils.toByteArray(value.value.bytes));

        int numIntegerProperties = getNumIntegerSensors(value.prop);
        int numFloatProperties = getNumFloatSensors(value.prop);

        for (int i = 0; i < numIntegerProperties; ++i) {
            if (bitset.get(i)) {
                builder.withIntValue(i, value.value.int32Values.get(i));
            }
        }

        for (int i = 0; i < numFloatProperties; ++i) {
            if (bitset.get(numIntegerProperties + i)) {
                builder.withFloatValue(i, value.value.floatValues.get(i));
            }
        }

        builder.withDTC(value.value.stringValue);

        return builder.build();
    }

    /** Listener for monitoring diagnostic event. */
    public interface DiagnosticListener {
        /**
         * Diagnostic events are available.
         *
         * @param events
         */
        void onDiagnosticEvents(List<CarDiagnosticEvent> events);
    }

    // Should be used only inside handleHalEvents method.
    private final LinkedList<CarDiagnosticEvent> mEventsToDispatch = new LinkedList<>();

    @Override
    public void handleHalEvents(List<VehiclePropValue> values) {
        for (VehiclePropValue value : values) {
            CarDiagnosticEvent event = createCarDiagnosticEvent(value);
            if (event != null) {
                mEventsToDispatch.add(event);
            }
        }

        DiagnosticListener listener = null;
        synchronized (this) {
            listener = mDiagnosticListener;
        }
        if (listener != null) {
            listener.onDiagnosticEvents(mEventsToDispatch);
        }
        mEventsToDispatch.clear();
    }

    public synchronized void setDiagnosticListener(DiagnosticListener listener) {
        mDiagnosticListener = listener;
    }

    public DiagnosticListener getDiagnosticListener() {
        return mDiagnosticListener;
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*Diagnostic HAL*");
    }

    @Override
    protected float fixSamplingRateForProperty(VehiclePropConfig prop, int carSensorManagerRate) {
        //TODO(egranata): tweak this for diagnostics
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

    @Nullable
    public CarDiagnosticEvent getCurrentLiveFrame() {
        try {
            VehiclePropValue value = mHal.get(VehicleProperty.OBD2_LIVE_FRAME);
            return createCarDiagnosticEvent(value);
        } catch (PropertyTimeoutException e) {
            Log.e(CarLog.TAG_DIAGNOSTIC, "timeout trying to read OBD2_LIVE_FRAME");
            return null;
        }
    }

    @Nullable
    public long[] getFreezeFrameTimestamps() {
        try {
            VehiclePropValue value = mHal.get(VehicleProperty.OBD2_FREEZE_FRAME_INFO);
            long[] timestamps = new long[value.value.int64Values.size()];
            for (int i = 0; i < timestamps.length; ++i) {
                timestamps[i] = value.value.int64Values.get(i);
            }
            return timestamps;
        } catch (PropertyTimeoutException e) {
            Log.e(CarLog.TAG_DIAGNOSTIC, "timeout trying to read OBD2_DTC_INFO");
            return null;
        }
    }

    @Nullable
    public CarDiagnosticEvent getFreezeFrame(long timestamp) {
        VehiclePropValueBuilder builder = VehiclePropValueBuilder.newBuilder(
            VehicleProperty.OBD2_FREEZE_FRAME);
        builder.setInt64Value(timestamp);
        try {
            VehiclePropValue value = mHal.get(builder.build());
            return createCarDiagnosticEvent(value);
        } catch (PropertyTimeoutException e) {
            Log.e(CarLog.TAG_DIAGNOSTIC, "timeout trying to read OBD2_DTC_INFO");
            return null;
        }
    }

    public void clearFreezeFrames(long... timestamps) {
        VehiclePropValueBuilder builder = VehiclePropValueBuilder.newBuilder(
            VehicleProperty.OBD2_FREEZE_FRAME_CLEAR);
        builder.setInt64Value(timestamps);
        try {
            mHal.set(builder.build());
        } catch (PropertyTimeoutException e) {
            Log.e(CarLog.TAG_DIAGNOSTIC, "timeout trying to write OBD2_DTC_CLEAR");
        }
    }
}
