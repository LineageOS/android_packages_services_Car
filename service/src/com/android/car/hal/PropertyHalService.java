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

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.VehiclePropertyIds;
import android.car.builtin.os.BuildHelper;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CarPropertyManager;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.os.ServiceSpecificException;
import android.util.Pair;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarServiceUtils;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Common interface for HAL services that send Vehicle Properties back and forth via ICarProperty.
 * Services that communicate by passing vehicle properties back and forth via ICarProperty should
 * extend this class.
 */
public class PropertyHalService extends HalServiceBase {
    private final boolean mDbg = true;
    private final LinkedList<CarPropertyEvent> mEventsToDispatch = new LinkedList<>();
    // Use SparseArray to save memory.
    @GuardedBy("mLock")
    private final SparseArray<CarPropertyConfig<?>> mMgrPropIdToCarPropConfig = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<HalPropConfig> mHalPropIdToPropConfig =
            new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseArray<Pair<String, String>> mMgrPropIdToPermissions = new SparseArray<>();
    // Only contains property ID if value is different for the CarPropertyManager and the HAL.
    private static final BidirectionalSparseIntArray MGR_PROP_ID_TO_HAL_PROP_ID =
            BidirectionalSparseIntArray.create(
                    new int[]{VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS,
                            VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS});
    private static final String TAG = CarLog.tagFor(PropertyHalService.class);
    private final VehicleHal mVehicleHal;
    private final PropertyHalServiceIds mPropertyHalServiceIds;
    private final HalPropValueBuilder mPropValueBuilder;

    @GuardedBy("mLock")
    private PropertyHalListener mPropertyHalListener;
    @GuardedBy("mLock")
    private final Set<Integer> mSubscribedHalPropIds;

    private final Object mLock = new Object();

    /**
     * Converts manager property ID to Vehicle HAL property ID.
     */
    private static int managerToHalPropId(int mgrPropId) {
        return MGR_PROP_ID_TO_HAL_PROP_ID.getValue(mgrPropId, mgrPropId);
    }

    /**
     * Converts Vehicle HAL property ID to manager property ID.
     */
    private static int halToManagerPropId(int halPropId) {
        return MGR_PROP_ID_TO_HAL_PROP_ID.getKey(halPropId, halPropId);
    }

    // Checks if the property exists in this VHAL before calling methods in IVehicle.
    private boolean isPropertySupportedInVehicle(int halPropId) {
        synchronized (mLock) {
            return mHalPropIdToPropConfig.contains(halPropId);
        }
    }

    private void assertPropertySupported(int halPropId) {
        if (!isPropertySupportedInVehicle(halPropId)) {
            throw new IllegalArgumentException("Vehicle property not supported: "
                    + VehiclePropertyIds.toString(halToManagerPropId(halPropId)));
        }
    }

    /**
     * PropertyHalListener used to send events to CarPropertyService
     */
    public interface PropertyHalListener {
        /**
         * This event is sent whenever the property value is updated
         */
        void onPropertyChange(List<CarPropertyEvent> events);

        /**
         * This event is sent when the set property call fails
         */
        void onPropertySetError(int property, int area,
                @CarPropertyManager.CarSetPropertyErrorCode int errorCode);

    }

    public PropertyHalService(VehicleHal vehicleHal) {
        mPropertyHalServiceIds = new PropertyHalServiceIds();
        mSubscribedHalPropIds = new HashSet<>();
        mVehicleHal = vehicleHal;
        if (mDbg) {
            Slogf.d(TAG, "started PropertyHalService");
        }
        mPropValueBuilder = vehicleHal.getHalPropValueBuilder();
    }

    /**
     * Set the listener for the HAL service
     */
    public void setPropertyHalListener(PropertyHalListener propertyHalListener) {
        synchronized (mLock) {
            mPropertyHalListener = propertyHalListener;
        }
    }

    /**
     * @return SparseArray<CarPropertyConfig> List of configs available.
     */
    public SparseArray<CarPropertyConfig<?>> getPropertyList() {
        if (mDbg) {
            Slogf.d(TAG, "getPropertyList");
        }
        synchronized (mLock) {
            if (mMgrPropIdToCarPropConfig.size() == 0) {
                for (int i = 0; i < mHalPropIdToPropConfig.size(); i++) {
                    HalPropConfig halPropConfig = mHalPropIdToPropConfig.valueAt(i);
                    int mgrPropId = halToManagerPropId(halPropConfig.getPropId());
                    CarPropertyConfig<?> carPropertyConfig = halPropConfig.toCarPropertyConfig(
                            mgrPropId);
                    mMgrPropIdToCarPropConfig.put(mgrPropId, carPropertyConfig);
                }
            }
            return mMgrPropIdToCarPropConfig;
        }
    }

    /**
     * Returns property or null if property is not ready yet.
     *
     * @param mgrPropId property id in {@link VehiclePropertyIds}
     * @throws IllegalArgumentException if argument is not valid.
     * @throws ServiceSpecificException if there is an exception in HAL.
     */
    @Nullable
    public CarPropertyValue getProperty(int mgrPropId, int areaId)
            throws IllegalArgumentException, ServiceSpecificException {
        int halPropId = managerToHalPropId(mgrPropId);
        assertPropertySupported(halPropId);

        // CarPropertyManager catches and rethrows exception, no need to handle here.
        HalPropValue halPropValue = mVehicleHal.get(halPropId, areaId);
        if (halPropValue == null) {
            return null;
        }
        HalPropConfig halPropConfig;
        synchronized (mLock) {
            halPropConfig = mHalPropIdToPropConfig.get(halPropId);
        }
        return halPropValue.toCarPropertyValue(mgrPropId, halPropConfig);
    }

    /**
     * Returns sample rate for the property
     */
    public float getSampleRate(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        assertPropertySupported(halPropId);
        return mVehicleHal.getSampleRate(halPropId);
    }

    /**
     * Get the read permission string for the property.
     */
    @Nullable
    public String getReadPermission(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        return mPropertyHalServiceIds.getReadPermission(halPropId);
    }

    /**
     * Get the write permission string for the property.
     */
    @Nullable
    public String getWritePermission(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        return mPropertyHalServiceIds.getWritePermission(halPropId);
    }

    /**
     * Get permissions for all properties in the vehicle.
     *
     * @return a SparseArray. key: propertyId, value: Pair(readPermission, writePermission).
     */
    @NonNull
    public SparseArray<Pair<String, String>> getPermissionsForAllProperties() {
        synchronized (mLock) {
            if (mMgrPropIdToPermissions.size() != 0) {
                return mMgrPropIdToPermissions;
            }
            for (int i = 0; i < mHalPropIdToPropConfig.size(); i++) {
                int halPropId = mHalPropIdToPropConfig.keyAt(i);
                mMgrPropIdToPermissions.put(halToManagerPropId(halPropId),
                        new Pair<>(mPropertyHalServiceIds.getReadPermission(halPropId),
                                mPropertyHalServiceIds.getWritePermission(halPropId)));
            }
            return mMgrPropIdToPermissions;
        }
    }

    /**
     * Return true if property is a display_units property
     */
    public boolean isDisplayUnitsProperty(int mgrPropId) {
        int halPropId = managerToHalPropId(mgrPropId);
        return mPropertyHalServiceIds.isPropertyToChangeUnits(halPropId);
    }

    /**
     * Set the property value.
     *
     * @throws IllegalArgumentException if argument is invalid.
     * @throws ServiceSpecificException if there is an exception in HAL.
     */
    public void setProperty(CarPropertyValue carPropertyValue)
            throws IllegalArgumentException, ServiceSpecificException {
        int halPropId = managerToHalPropId(carPropertyValue.getPropertyId());
        assertPropertySupported(halPropId);
        HalPropConfig halPropConfig;
        synchronized (mLock) {
            halPropConfig = mHalPropIdToPropConfig.get(halPropId);
        }
        HalPropValue halPropValue = mPropValueBuilder.build(carPropertyValue, halPropId,
                halPropConfig);
        // CarPropertyManager catches and rethrows exception, no need to handle here.
        mVehicleHal.set(halPropValue);
    }

    /**
     * Subscribe to this property at the specified update rate.
     *
     * @throws IllegalArgumentException thrown if property is not supported by VHAL.
     */
    public void subscribeProperty(int mgrPropId, float rate) throws IllegalArgumentException {
        if (mDbg) {
            Slogf.d(TAG, "subscribeProperty propId: %s, rate=%f",
                    VehiclePropertyIds.toString(mgrPropId), rate);
        }
        int halPropId = managerToHalPropId(mgrPropId);
        assertPropertySupported(halPropId);
        synchronized (mLock) {
            HalPropConfig halPropConfig = mHalPropIdToPropConfig.get(halPropId);
            if (rate > halPropConfig.getMaxSampleRate()) {
                rate = halPropConfig.getMaxSampleRate();
            } else if (rate < halPropConfig.getMinSampleRate()) {
                rate = halPropConfig.getMinSampleRate();
            }
            mSubscribedHalPropIds.add(halPropId);
        }

        mVehicleHal.subscribeProperty(this, halPropId, rate);
    }

    /**
     * Unsubscribe the property and turn off update events for it.
     */
    public void unsubscribeProperty(int mgrPropId) {
        if (mDbg) {
            Slogf.d(TAG, "unsubscribeProperty propId=%s", VehiclePropertyIds.toString(mgrPropId));
        }
        int halPropId = managerToHalPropId(mgrPropId);
        assertPropertySupported(halPropId);
        synchronized (mLock) {
            if (mSubscribedHalPropIds.contains(halPropId)) {
                mSubscribedHalPropIds.remove(halPropId);
                mVehicleHal.unsubscribeProperty(this, halPropId);
            }
        }
    }

    @Override
    public void init() {
        if (mDbg) {
            Slogf.d(TAG, "init()");
        }
    }

    @Override
    public void release() {
        if (mDbg) {
            Slogf.d(TAG, "release()");
        }
        synchronized (mLock) {
            for (Integer halPropId : mSubscribedHalPropIds) {
                mVehicleHal.unsubscribeProperty(this, halPropId);
            }
            mSubscribedHalPropIds.clear();
            mHalPropIdToPropConfig.clear();
            mMgrPropIdToCarPropConfig.clear();
            mMgrPropIdToPermissions.clear();
            mPropertyHalListener = null;
        }
    }

    @Override
    public boolean isSupportedProperty(int halPropId) {
        return mPropertyHalServiceIds.isSupportedProperty(halPropId);
    }

    @Override
    public int[] getAllSupportedProperties() {
        return CarServiceUtils.EMPTY_INT_ARRAY;
    }

    // The method is called in HAL init(). Avoid handling complex things in here.
    @Override
    public void takeProperties(Collection<HalPropConfig> halPropConfigs) {
        for (HalPropConfig halPropConfig : halPropConfigs) {
            int halPropId = halPropConfig.getPropId();
            if (isSupportedProperty(halPropId)) {
                synchronized (mLock) {
                    mHalPropIdToPropConfig.put(halPropId, halPropConfig);
                }
                if (mDbg) {
                    Slogf.d(TAG, "takeSupportedProperties: %s",
                            VehiclePropertyIds.toString(halToManagerPropId(halPropId)));
                }
            }
        }
        if (mDbg) {
            Slogf.d(TAG, "takeSupportedProperties() took %d properties", halPropConfigs.size());
        }
        // If vehicle hal support to select permission for vendor properties.
        HalPropConfig customizePermission;
        synchronized (mLock) {
            customizePermission = mHalPropIdToPropConfig.get(
                    VehicleProperty.SUPPORT_CUSTOMIZE_VENDOR_PERMISSION);
        }
        if (customizePermission != null) {
            mPropertyHalServiceIds.customizeVendorPermission(customizePermission.getConfigArray());
        }
    }

    @Override
    public void onHalEvents(List<HalPropValue> halPropValues) {
        PropertyHalListener propertyHalListener;
        synchronized (mLock) {
            propertyHalListener = mPropertyHalListener;
        }
        if (propertyHalListener != null) {
            for (HalPropValue halPropValue : halPropValues) {
                if (halPropValue == null) {
                    continue;
                }
                int halPropId = halPropValue.getPropId();
                if (!isPropertySupportedInVehicle(halPropId)) {
                    Slogf.w(TAG, "onHalEvents - received HalPropValue for unsupported property: %s",
                            VehiclePropertyIds.toString(halToManagerPropId(halPropId)));
                    continue;
                }
                // Check payload if it is an userdebug build.
                if (BuildHelper.isDebuggableBuild() && !mPropertyHalServiceIds.checkPayload(
                        halPropValue)) {
                    Slogf.w(TAG,
                            "Drop event for property: %s because it is failed "
                                    + "in payload checking.", halPropValue);
                    continue;
                }
                int mgrPropId = halToManagerPropId(halPropId);
                HalPropConfig halPropConfig;
                synchronized (mLock) {
                    halPropConfig = mHalPropIdToPropConfig.get(halPropId);
                }
                CarPropertyValue<?> carPropertyValue = halPropValue.toCarPropertyValue(mgrPropId,
                        halPropConfig);
                CarPropertyEvent carPropertyEvent = new CarPropertyEvent(
                        CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE, carPropertyValue);
                mEventsToDispatch.add(carPropertyEvent);
            }
            propertyHalListener.onPropertyChange(mEventsToDispatch);
            mEventsToDispatch.clear();
        }
    }

    @Override
    public void onPropertySetError(ArrayList<VehiclePropError> vehiclePropErrors) {
        PropertyHalListener propertyHalListener;
        synchronized (mLock) {
            propertyHalListener = mPropertyHalListener;
        }
        if (propertyHalListener != null) {
            for (VehiclePropError vehiclePropError : vehiclePropErrors) {
                int mgrPropId = halToManagerPropId(vehiclePropError.propId);
                propertyHalListener.onPropertySetError(mgrPropId, vehiclePropError.areaId,
                        vehiclePropError.errorCode);
            }
        }
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("  Properties available:");
        synchronized (mLock) {
            for (int i = 0; i < mHalPropIdToPropConfig.size(); i++) {
                HalPropConfig halPropConfig = mHalPropIdToPropConfig.valueAt(i);
                writer.println("    " + halPropConfig.toString());
            }
        }
    }
}
