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
package com.android.car.hal;

import static com.android.car.hal.CarPropertyUtils.toCarPropertyValue;
import static com.android.car.hal.CarPropertyUtils.toVehiclePropValue;
import static java.lang.Integer.toHexString;

import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.car.CarLog;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfig;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Common interface for HAL services that send Vehicle Properties back and forth via ICarProperty.
 * Services that communicate by passing vehicle properties back and forth via ICarProperty should
 * extend this class.
 */
public abstract class PropertyHalServiceBase extends HalServiceBase {
    private final boolean mDbg;
    private final SparseIntArray mHalPropToValueType = new SparseIntArray();
    private final ConcurrentHashMap<Integer, CarPropertyConfig<?>> mProps =
            new ConcurrentHashMap<>();
    private final String mTag;
    private final VehicleHal mVehicleHal;

    @GuardedBy("mLock")
    private PropertyHalListener mListener;
    private final Object mLock = new Object();

    protected final static int NOT_SUPPORTED_PROPERTY = -1;

    public interface PropertyHalListener {
        void onPropertyChange(CarPropertyEvent event);
        void onError(int zone, int property);
    }

    protected PropertyHalServiceBase(VehicleHal vehicleHal, String tag, boolean dbg) {
        mVehicleHal = vehicleHal;
        mTag = "PropertyHalServiceBase." + tag;
        mDbg = dbg;

        if (mDbg) {
            Log.d(mTag, "started PropertyHalServiceBase!");
        }
    }

    public void setListener(PropertyHalListener listener) {
        synchronized (mLock) {
            mListener = listener;
        }
    }

    public List<CarPropertyConfig> getPropertyList() {
        return new ArrayList<>(mProps.values());
    }

    public CarPropertyValue getProperty(int mgrPropId, int areaId) {
        int halPropId = managerToHalPropId(mgrPropId);
        if (halPropId == NOT_SUPPORTED_PROPERTY) {
            throw new IllegalArgumentException("Invalid property Id : 0x" + toHexString(mgrPropId));
        }

        VehiclePropValue value = null;
        try {
            VehiclePropValue valueRequest = VehiclePropValue.newBuilder()
                    .setProp(halPropId)
                    .setZone(areaId)
                    .setValueType(mHalPropToValueType.get(halPropId))
                    .build();

            value = mVehicleHal.getVehicleNetwork().getProperty(valueRequest);
        } catch (ServiceSpecificException e) {
            Log.e(CarLog.TAG_PROPERTY, "get, property not ready 0x" + toHexString(halPropId), e);
        }

        return value == null ? null : toCarPropertyValue(value, mgrPropId);
    }

    public void setProperty(CarPropertyValue prop) {
        int halPropId = managerToHalPropId(prop.getPropertyId());
        if (halPropId == NOT_SUPPORTED_PROPERTY) {
            throw new IllegalArgumentException("Invalid property Id : 0x"
                    + toHexString(prop.getPropertyId()));
        }
        VehiclePropValue halProp = toVehiclePropValue(prop, halPropId);
        try {
            mVehicleHal.getVehicleNetwork().setProperty(halProp);
        } catch (ServiceSpecificException e) {
            Log.e(CarLog.TAG_PROPERTY, "set, property not ready 0x" + toHexString(halPropId), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void init() {
        if (mDbg) {
            Log.d(mTag, "init()");
        }
        // Subscribe to each of the properties
        for (Integer prop : mProps.keySet()) {
            mVehicleHal.subscribeProperty(this, prop, 0);
        }
    }

    @Override
    public void release() {
        if (mDbg) {
            Log.d(mTag, "release()");
        }

        for (Integer prop : mProps.keySet()) {
            mVehicleHal.unsubscribeProperty(this, prop);
        }

        // Clear the property list
        mProps.clear();

        synchronized (mLock) {
            mListener = null;
        }
    }

    @Override
    public List<VehiclePropConfig> takeSupportedProperties(
            List<VehiclePropConfig> allProperties) {
        List<VehiclePropConfig> taken = new LinkedList<>();

        for (VehiclePropConfig p : allProperties) {
            int mgrPropId = halToManagerPropId(p.getProp());

            if (mgrPropId == NOT_SUPPORTED_PROPERTY) {
                continue;  // The property is not handled by this HAL.
            }

            CarPropertyConfig config = CarPropertyUtils.toCarPropertyConfig(p, mgrPropId);

            taken.add(p);
            mProps.put(p.getProp(), config);
            mHalPropToValueType.put(p.getProp(), p.getValueType());

            if (mDbg) {
                Log.d(mTag, "takeSupportedProperties: " + toHexString(p.getProp()));
            }
        }
        return taken;
    }

    @Override
    public void handleHalEvents(List<VehiclePropValue> values) {
        PropertyHalListener listener;
        synchronized (this) {
            listener = mListener;
        }
        if (listener != null) {
            for (VehiclePropValue v : values) {
                int prop = v.getProp();
                int mgrPropId = halToManagerPropId(prop);

                if (mgrPropId == NOT_SUPPORTED_PROPERTY) {
                    Log.e(mTag, "Property is not supported: 0x" + toHexString(prop));
                    continue;
                }

                CarPropertyEvent event;
                CarPropertyValue<?> propVal = toCarPropertyValue(v, mgrPropId);
                event = new CarPropertyEvent(CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE,
                        propVal);

                listener.onPropertyChange(event);
                if (mDbg) {
                    Log.d(mTag, "handleHalEvents event: " + event);
                }
            }
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println(mTag);
        writer.println("  Properties available:");
        for (CarPropertyConfig prop : mProps.values()) {
            writer.println("    " + prop.toString());
        }
    }

    /**
     * Converts manager property ID to Vehicle HAL property ID.
     * If property is not supported, it will return {@link #NOT_SUPPORTED_PROPERTY}.
     */
    abstract protected int managerToHalPropId(int managerPropId);

    /**
     * Converts Vehicle HAL property ID to manager property ID.
     * If property is not supported, it will return {@link #NOT_SUPPORTED_PROPERTY}.
     */
    abstract protected int halToManagerPropId(int halPropId);

    /**
     * Helper class that maintains bi-directional mapping between manager's property
     * Id (public or system API) and vehicle HAL property Id.
     *
     * <p>This class is supposed to be immutable. Use {@link #create(int[])} factory method to
     * instantiate this class.
     */
    static class ManagerToHalPropIdMap {
        private final SparseIntArray mMap;
        private final SparseIntArray mInverseMap;

        /**
         * Creates {@link ManagerToHalPropIdMap} for provided [manager prop Id, hal prop Id] pairs.
         *
         * <p> The input array should have an odd number of elements.
         */
        static ManagerToHalPropIdMap create(int[] mgrToHalPropIds) {
            int inputLength = mgrToHalPropIds.length;
            if (inputLength % 2 != 0) {
                throw new IllegalArgumentException("Odd number of key-value elements");
            }

            ManagerToHalPropIdMap biMap = new ManagerToHalPropIdMap(inputLength / 2);
            for (int i = 0; i < mgrToHalPropIds.length; i += 2) {
                biMap.put(mgrToHalPropIds[i], mgrToHalPropIds[i + 1]);
            }
            return biMap;
        }

        private ManagerToHalPropIdMap(int initialCapacity) {
            mMap = new SparseIntArray(initialCapacity);
            mInverseMap = new SparseIntArray(initialCapacity);
        }

        private void put(int managerPropId, int halPropId) {
            mMap.put(managerPropId, halPropId);
            mInverseMap.put(halPropId, managerPropId);
        }

        int getHalPropId(int managerPropId) {
            return mMap.get(managerPropId, NOT_SUPPORTED_PROPERTY);
        }

        int getManagerPropId(int halPropId) {
            return mInverseMap.get(halPropId, NOT_SUPPORTED_PROPERTY);
        }
    }
}
