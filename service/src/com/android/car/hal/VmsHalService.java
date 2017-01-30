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

import static java.lang.Integer.toHexString;

import android.annotation.Nullable;
import android.car.VehicleAreaType;
import android.car.vms.VmsProperty;
import android.hardware.vehicle.V2_0.VehiclePropConfig;
import android.hardware.vehicle.V2_0.VehiclePropValue;
import android.hardware.vehicle.V2_0.VehicleProperty;
import android.util.Log;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 * This is a glue layer between the VehicleHal and the VmsService. It sends VMS properties back and
 * forth.
 */
public class VmsHalService extends HalServiceBase {
    private static final boolean DBG = true;
    private static final int HAL_PROPERTY_ID = VehicleProperty.VEHICLE_MAP_SERVICE;
    private static final String TAG = "VmsHalService";

    private boolean mIsSupported = false;
    @GuardedBy("mListenerLock")
    private VmsHalListener mListener;
    private final Object mListenerLock = new Object();
    private final VehicleHal mVehicleHal;

    /**
     * The VmsService implements this interface to receive data from the HAL.
     */
    public interface VmsHalListener {
        void onChange(VmsProperty propVal);
    }

    protected VmsHalService(VehicleHal vehicleHal) {
        mVehicleHal = vehicleHal;
        if (DBG) {
            Log.d(TAG, "started VmsHalService!");
        }
    }

    public void setListener(VmsHalListener listener) {
        synchronized (mListenerLock) {
            mListener = listener;
        }
    }

    /**
     * Returns property or null if property is not ready yet.
     */
    @Nullable
    public VmsProperty getProperty() {
        VehiclePropValue value = null;
        try {
            value = mVehicleHal.get(HAL_PROPERTY_ID);
        } catch (PropertyTimeoutException e) {
            Log.e(CarLog.TAG_PROPERTY, "get, property not ready 0x" + toHexString(HAL_PROPERTY_ID));
        }

        return value == null ? null : toVmsProperty(value);
    }

    /**
     * Updates the VMS HAL property with the given value.
     *
     * @param property the value used to update the HAL property.
     * @return         true if the call to the HAL to update the property was successful.
     */
    public boolean setProperty(VmsProperty property) {
        VehiclePropValue halProp = toVehiclePropValue(property);
        boolean success = false;
        try {
            mVehicleHal.set(halProp);
            success = true;
        } catch (PropertyTimeoutException e) {
            Log.e(CarLog.TAG_PROPERTY, "set, property not ready 0x" + toHexString(HAL_PROPERTY_ID));
        }
        return success;
    }

    @Override
    public void init() {
        if (DBG) {
            Log.d(TAG, "init()");
        }
        if (mIsSupported) {
            mVehicleHal.subscribeProperty(this, HAL_PROPERTY_ID, 0);
        }
    }

    @Override
    public void release() {
        if (DBG) {
            Log.d(TAG, "release()");
        }
        if (mIsSupported) {
            mVehicleHal.unsubscribeProperty(this, HAL_PROPERTY_ID);
        }
        synchronized (mListenerLock) {
            mListener = null;
        }
    }

    @Override
    public Collection<VehiclePropConfig> takeSupportedProperties(
            Collection<VehiclePropConfig> allProperties) {
        List<VehiclePropConfig> taken = new LinkedList<>();
        for (VehiclePropConfig p : allProperties) {
            if (p.prop == HAL_PROPERTY_ID) {
                taken.add(p);
                mIsSupported = true;
                if (DBG) {
                    Log.d(TAG, "takeSupportedProperties: " + toHexString(p.prop));
                }
                break;
            }
        }
        return taken;
    }

    @Override
    public void handleHalEvents(List<VehiclePropValue> values) {
        VmsHalListener listener;
        synchronized (mListenerLock) {
            listener = mListener;
        }
        if (listener != null) {
            for (VehiclePropValue v : values) {
                VmsProperty propVal = toVmsProperty(v);
                listener.onChange(propVal);
                if (DBG) {
                    Log.d(TAG, "handleHalEvents event: " + toHexString(v.prop));
                }
            }
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("VmsProperty " + (mIsSupported ? "" : "not") + " supported.");
    }

    // TODO(antoniocortes): update the following two methods once we have the actual VMS property.
    /** Converts {@link VehiclePropValue} to {@link VmsProperty} */
    static VmsProperty toVmsProperty(VehiclePropValue halValue) {
        VehiclePropValue.RawValue v = halValue.value;
        return new VmsProperty(v.stringValue);
    }

    /** Converts {@link VmsProperty} to {@link VehiclePropValue} */
    static VehiclePropValue toVehiclePropValue(VmsProperty carProp) {
        VehiclePropValue vehicleProp = new VehiclePropValue();
        vehicleProp.prop = HAL_PROPERTY_ID;
        vehicleProp.areaId = VehicleAreaType.VEHICLE_AREA_TYPE_NONE;
        VehiclePropValue.RawValue v = vehicleProp.value;
        v.stringValue = carProp.getValue();
        return vehicleProp;
    }
}
