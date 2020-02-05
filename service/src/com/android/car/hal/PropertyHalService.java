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
package com.android.car.hal;

import static com.android.car.hal.CarPropertyUtils.toCarPropertyValue;
import static com.android.car.hal.CarPropertyUtils.toMixedCarPropertyValue;
import static com.android.car.hal.CarPropertyUtils.toMixedVehiclePropValue;
import static com.android.car.hal.CarPropertyUtils.toVehiclePropValue;

import static java.lang.Integer.toHexString;

import android.annotation.Nullable;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.VehicleHalStatusCode;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyType;
import android.os.ServiceSpecificException;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Common interface for HAL services that send Vehicle Properties back and forth via ICarProperty.
 * Services that communicate by passing vehicle properties back and forth via ICarProperty should
 * extend this class.
 */
public class PropertyHalService extends HalServiceBase {
    private final boolean mDbg = true;
    private final LinkedList<CarPropertyEvent> mEventsToDispatch = new LinkedList<>();
    @GuardedBy("mLock")
    private final Map<Integer, CarPropertyConfig<?>> mProps = new HashMap<>();
    @GuardedBy("mLock")
    private final SparseArray<VehiclePropConfig> mPropConfigSparseArray = new SparseArray<>();
    private static final String TAG = "PropertyHalService";
    private final VehicleHal mVehicleHal;
    private final PropertyHalServiceIds mPropIds;

    @GuardedBy("mLock")
    private PropertyHalListener mListener;
    @GuardedBy("mLock")
    private Set<Integer> mSubscribedPropIds;

    private final Object mLock = new Object();

    /**
     * Converts manager property ID to Vehicle HAL property ID.
     * If property is not supported, it will return {@link #NOT_SUPPORTED_PROPERTY}.
     */
    private int managerToHalPropId(int propId) {
        synchronized (mLock) {
            if (mPropConfigSparseArray.get(propId) != null) {
                return propId;
            } else {
                return NOT_SUPPORTED_PROPERTY;
            }
        }
    }

    /**
     * Converts Vehicle HAL property ID to manager property ID.
     * If property is not supported, it will return {@link #NOT_SUPPORTED_PROPERTY}.
     */
    private int halToManagerPropId(int halPropId) {
        synchronized (mLock) {
            if (mPropConfigSparseArray.get(halPropId) != null) {
                return halPropId;
            } else {
                return NOT_SUPPORTED_PROPERTY;
            }
        }
    }

    /**
     * PropertyHalListener used to send events to CarPropertyService
     */
    public interface PropertyHalListener {
        /**
         * This event is sent whenever the property value is updated
         * @param events
         */
        void onPropertyChange(List<CarPropertyEvent> events);
        /**
         * This event is sent when the set property call fails
         * @param property
         * @param area
         */
        void onPropertySetError(int property, int area);
    }

    public PropertyHalService(VehicleHal vehicleHal) {
        mPropIds = new PropertyHalServiceIds();
        mSubscribedPropIds = new HashSet<Integer>();
        mVehicleHal = vehicleHal;
        if (mDbg) {
            Log.d(TAG, "started PropertyHalService");
        }
    }

    /**
     * Set the listener for the HAL service
     * @param listener
     */
    public void setListener(PropertyHalListener listener) {
        synchronized (mLock) {
            mListener = listener;
        }
    }

    /**
     *
     * @return List<CarPropertyConfig> List of configs available.
     */
    public Map<Integer, CarPropertyConfig<?>> getPropertyList() {
        if (mDbg) {
            Log.d(TAG, "getPropertyList");
        }
        synchronized (mLock) {
            if (mProps.size() == 0) {
                for (int i = 0; i < mPropConfigSparseArray.size(); i++) {
                    VehiclePropConfig p = mPropConfigSparseArray.valueAt(i);
                    CarPropertyConfig config = CarPropertyUtils.toCarPropertyConfig(p, p.prop);
                    mProps.put(p.prop, config);
                }
            }
        }
        return mProps;
    }

    /**
     * Returns property or null if property is not ready yet.
     * @param mgrPropId
     * @param areaId
     */
    @Nullable
    public CarPropertyValue getProperty(int mgrPropId, int areaId) {
        int halPropId = managerToHalPropId(mgrPropId);
        if (halPropId == NOT_SUPPORTED_PROPERTY) {
            throw new IllegalArgumentException("Invalid property Id : 0x" + toHexString(mgrPropId));
        }

        VehiclePropValue value = null;
        try {
            value = mVehicleHal.get(halPropId, areaId);
        } catch (PropertyTimeoutException e) {
            Log.e(CarLog.TAG_PROPERTY, "get, property not ready 0x" + toHexString(halPropId), e);
        }

        if (isMixedTypeProperty(halPropId)) {
            VehiclePropConfig propConfig;
            synchronized (mLock) {
                propConfig = mPropConfigSparseArray.get(halPropId);
            }
            boolean containBooleanType = propConfig.configArray.get(1) == 1;
            return value == null ? null : toMixedCarPropertyValue(value,
                    mgrPropId, containBooleanType);
        }
        return value == null ? null : toCarPropertyValue(value, mgrPropId);
    }

    /**
     * Returns sample rate for the property
     * @param propId
     */
    public float getSampleRate(int propId) {
        return mVehicleHal.getSampleRate(propId);
    }

    /**
     * Get the read permission string for the property.
     * @param propId
     */
    @Nullable
    public String getReadPermission(int propId) {
        return mPropIds.getReadPermission(propId);
    }

    /**
     * Get the write permission string for the property.
     * @param propId
     */
    @Nullable
    public String getWritePermission(int propId) {
        return mPropIds.getWritePermission(propId);
    }

    /**
     * Return true if property is a display_units property
     * @param propId
     */
    public boolean isDisplayUnitsProperty(int propId) {
        return mPropIds.isPropertyToChangeUnits(propId);
    }

    /**
     * Set the property value.
     * @param prop
     */
    public void setProperty(CarPropertyValue prop) {
        int halPropId = managerToHalPropId(prop.getPropertyId());
        if (halPropId == NOT_SUPPORTED_PROPERTY) {
            throw new IllegalArgumentException("Invalid property Id : 0x"
                    + toHexString(prop.getPropertyId()));
        }

        VehiclePropValue halProp;
        if (isMixedTypeProperty(halPropId)) {
            // parse mixed type property value.
            VehiclePropConfig propConfig;
            synchronized (mLock) {
                propConfig = mPropConfigSparseArray.get(prop.getPropertyId());
            }
            int[] configArray = propConfig.configArray.stream().mapToInt(i->i).toArray();
            halProp = toMixedVehiclePropValue(prop, halPropId, configArray);
        } else {
            halProp = toVehiclePropValue(prop, halPropId);
        }
        try {
            mVehicleHal.set(halProp);
        } catch (PropertyTimeoutException e) {
            // TODO(b/147896616): throw ServiceSpecificException at first place.
            Log.e(CarLog.TAG_PROPERTY, "set, property not ready 0x" + toHexString(halPropId), e);
            throw new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN);
        }
    }

    /**
     * Subscribe to this property at the specified update rate.
     * @param propId
     * @param rate
     */
    public void subscribeProperty(int propId, float rate) {
        if (mDbg) {
            Log.d(TAG, "subscribeProperty propId=0x" + toHexString(propId) + ", rate=" + rate);
        }
        int halPropId = managerToHalPropId(propId);
        if (halPropId == NOT_SUPPORTED_PROPERTY) {
            throw new IllegalArgumentException("Invalid property Id : 0x"
                    + toHexString(propId));
        }
        synchronized (mLock) {
            VehiclePropConfig cfg = mPropConfigSparseArray.get(propId);
            if (rate > cfg.maxSampleRate) {
                rate = cfg.maxSampleRate;
            } else if (rate < cfg.minSampleRate) {
                rate = cfg.minSampleRate;
            }
            mSubscribedPropIds.add(halPropId);
        }

        mVehicleHal.subscribeProperty(this, halPropId, rate);
    }

    /**
     * Unsubscribe the property and turn off update events for it.
     * @param propId
     */
    public void unsubscribeProperty(int propId) {
        if (mDbg) {
            Log.d(TAG, "unsubscribeProperty propId=0x" + toHexString(propId));
        }
        int halPropId = managerToHalPropId(propId);
        if (halPropId == NOT_SUPPORTED_PROPERTY) {
            throw new IllegalArgumentException("Invalid property Id : 0x"
                    + toHexString(propId));
        }
        synchronized (mLock) {
            if (mSubscribedPropIds.contains(halPropId)) {
                mSubscribedPropIds.remove(halPropId);
                mVehicleHal.unsubscribeProperty(this, halPropId);
            }
        }
    }

    @Override
    public void init() {
        if (mDbg) {
            Log.d(TAG, "init()");
        }
    }

    @Override
    public void release() {
        if (mDbg) {
            Log.d(TAG, "release()");
        }
        synchronized (mLock) {
            for (Integer prop : mSubscribedPropIds) {
                mVehicleHal.unsubscribeProperty(this, prop);
            }
            mSubscribedPropIds.clear();
            mPropConfigSparseArray.clear();
            mProps.clear();
            mListener = null;
        }
    }

    @Override
    public Collection<VehiclePropConfig> takeSupportedProperties(
            Collection<VehiclePropConfig> allProperties) {
        List<VehiclePropConfig> taken = new LinkedList<>();
        for (VehiclePropConfig p : allProperties) {
            if (mPropIds.isSupportedProperty(p.prop)) {
                taken.add(p);
                synchronized (mLock) {
                    mPropConfigSparseArray.put(p.prop, p);
                }
                if (mDbg) {
                    Log.d(TAG, "takeSupportedProperties: " + toHexString(p.prop));
                }
            }
        }
        if (mDbg) {
            Log.d(TAG, "takeSupportedProperties() took " + taken.size() + " properties");
        }
        // If vehicle hal support to select permission for vendor properties.
        VehiclePropConfig customizePermission;
        synchronized (mLock) {
            customizePermission = mPropConfigSparseArray.get(
                    VehicleProperty.SUPPORT_CUSTOMIZE_VENDOR_PERMISSION);
        }
        if (customizePermission != null) {
            mPropIds.customizeVendorPermission(customizePermission.configArray);
        }
        return taken;
    }

    @Override
    public void handleHalEvents(List<VehiclePropValue> values) {
        PropertyHalListener listener;
        synchronized (mLock) {
            listener = mListener;
        }
        if (listener != null) {
            for (VehiclePropValue v : values) {
                if (v == null) {
                    continue;
                }
                int mgrPropId = halToManagerPropId(v.prop);
                if (mgrPropId == NOT_SUPPORTED_PROPERTY) {
                    Log.e(TAG, "Property is not supported: 0x" + toHexString(v.prop));
                    continue;
                }
                CarPropertyValue<?> propVal;
                if (isMixedTypeProperty(v.prop)) {
                    // parse mixed type property value.
                    VehiclePropConfig propConfig;
                    synchronized (mLock) {
                        propConfig = mPropConfigSparseArray.get(v.prop);
                    }
                    boolean containBooleanType = propConfig.configArray.get(1) == 1;
                    propVal = toMixedCarPropertyValue(v, mgrPropId, containBooleanType);
                } else {
                    propVal = toCarPropertyValue(v, mgrPropId);
                }
                CarPropertyEvent event = new CarPropertyEvent(
                        CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, propVal);
                mEventsToDispatch.add(event);
            }
            listener.onPropertyChange(mEventsToDispatch);
            mEventsToDispatch.clear();
        }
    }

    @Override
    public void handlePropertySetError(int property, int area) {
        PropertyHalListener listener;
        synchronized (mLock) {
            listener = mListener;
        }
        if (listener != null) {
            listener.onPropertySetError(property, area);
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("  Properties available:");
        synchronized (mLock) {
            for (int i = 0; i < mPropConfigSparseArray.size(); i++) {
                VehiclePropConfig p = mPropConfigSparseArray.valueAt(i);
                writer.println("    " + p);
            }
        }
    }

    private static boolean isMixedTypeProperty(int propId) {
        return (propId & VehiclePropertyType.MASK) == VehiclePropertyType.MIXED;
    }
}
