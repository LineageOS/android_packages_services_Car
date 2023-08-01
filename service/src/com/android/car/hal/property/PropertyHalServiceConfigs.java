/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car.hal.property;

import static android.car.Car.PERMISSION_VENDOR_EXTENSION;

import android.annotation.Nullable;
import android.car.VehiclePropertyIds;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarHvacFanDirection;
import android.content.Context;
import android.hardware.automotive.vehicle.AutomaticEmergencyBrakingState;
import android.hardware.automotive.vehicle.BlindSpotWarningState;
import android.hardware.automotive.vehicle.CruiseControlCommand;
import android.hardware.automotive.vehicle.CruiseControlState;
import android.hardware.automotive.vehicle.CruiseControlType;
import android.hardware.automotive.vehicle.ElectronicTollCollectionCardStatus;
import android.hardware.automotive.vehicle.ElectronicTollCollectionCardType;
import android.hardware.automotive.vehicle.EmergencyLaneKeepAssistState;
import android.hardware.automotive.vehicle.ErrorState;
import android.hardware.automotive.vehicle.EvChargeState;
import android.hardware.automotive.vehicle.EvConnectorType;
import android.hardware.automotive.vehicle.EvRegenerativeBrakingState;
import android.hardware.automotive.vehicle.EvStoppingMode;
import android.hardware.automotive.vehicle.ForwardCollisionWarningState;
import android.hardware.automotive.vehicle.FuelType;
import android.hardware.automotive.vehicle.GsrComplianceRequirementType;
import android.hardware.automotive.vehicle.HandsOnDetectionDriverState;
import android.hardware.automotive.vehicle.HandsOnDetectionWarning;
import android.hardware.automotive.vehicle.LaneCenteringAssistCommand;
import android.hardware.automotive.vehicle.LaneCenteringAssistState;
import android.hardware.automotive.vehicle.LaneDepartureWarningState;
import android.hardware.automotive.vehicle.LaneKeepAssistState;
import android.hardware.automotive.vehicle.LocationCharacterization;
import android.hardware.automotive.vehicle.PortLocationType;
import android.hardware.automotive.vehicle.TrailerState;
import android.hardware.automotive.vehicle.VehicleAreaSeat;
import android.hardware.automotive.vehicle.VehicleGear;
import android.hardware.automotive.vehicle.VehicleIgnitionState;
import android.hardware.automotive.vehicle.VehicleLightState;
import android.hardware.automotive.vehicle.VehicleLightSwitch;
import android.hardware.automotive.vehicle.VehicleOilLevel;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyStatus;
import android.hardware.automotive.vehicle.VehiclePropertyType;
import android.hardware.automotive.vehicle.VehicleSeatOccupancyState;
import android.hardware.automotive.vehicle.VehicleTurnSignal;
import android.hardware.automotive.vehicle.VehicleUnit;
import android.hardware.automotive.vehicle.WindshieldWipersState;
import android.hardware.automotive.vehicle.WindshieldWipersSwitch;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.car.CarLog;
import com.android.car.hal.BidirectionalSparseIntArray;
import com.android.car.hal.HalPropValue;
import com.android.car.hal.property.PropertyPermissionInfo.AllOfPermissions;
import com.android.car.hal.property.PropertyPermissionInfo.AnyOfPermissions;
import com.android.car.hal.property.PropertyPermissionInfo.PermissionCondition;
import com.android.car.hal.property.PropertyPermissionInfo.PropertyPermissions;
import com.android.car.hal.property.PropertyPermissionInfo.SinglePermission;
import com.android.car.internal.property.CarPropertyHelper;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A singleton class to define which AIDL HAL property IDs are used by PropertyHalService.
 * This class binds the read and write permissions to the property ID.
 */
public class PropertyHalServiceConfigs {
    private static final boolean USE_RUNTIME_CONFIG_FILE = true;
    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static PropertyHalServiceConfigs sPropertyHalServiceConfigs;
    private static final PermissionCondition SINGLE_PERMISSION_VENDOR_EXTENSION =
            new SinglePermission(PERMISSION_VENDOR_EXTENSION);
    // Only contains property ID if value is different for the CarPropertyManager and the HAL.
    private static final BidirectionalSparseIntArray MGR_PROP_ID_TO_HAL_PROP_ID =
            BidirectionalSparseIntArray.create(
                    new int[]{VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS,
                            VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS});

    /**
     * Represents one VHAL property that is exposed through
     * {@link android.car.hardware.property.CarPropertyManager}.
     *
     * Note that the property ID is defined in {@link android.car.VehiclePropertyIds} and it might
     * be different than the property ID used by VHAL, defined in {@link VehicleProperty}.
     * The latter is represented by {@code halPropId}.
     *
     * If the property is an {@code Integer} enum property, its supported enum values are listed
     * in {@code dataEnums}. If the property is a flag-combination property, its valid bit flag is
     * listed in {@code validBitFlag}.
     */
    @VisibleForTesting
    /* package */ static final class CarSvcPropertyConfig {
        public int propertyId;
        public int halPropId;
        public String propertyName;
        public String description;
        public PropertyPermissions permissions;
        public Set<Integer> dataEnums;
        public Integer validBitFlag;

        @Override
        public boolean equals(Object object) {
            if (object == this) {
                return true;
            }
            // instanceof will return false if object is null.
            if (!(object instanceof CarSvcPropertyConfig)) {
                return false;
            }
            CarSvcPropertyConfig other = (CarSvcPropertyConfig) object;
            return (propertyId == other.propertyId
                    && halPropId == other.halPropId
                    && Objects.equals(propertyName, other.propertyName)
                    && Objects.equals(description, other.description)
                    && Objects.equals(permissions, other.permissions)
                    && Objects.equals(dataEnums, other.dataEnums)
                    && Objects.equals(validBitFlag, other.validBitFlag));
        }

        @Override
        public int hashCode() {
            return propertyId + halPropId + Objects.hashCode(propertyName)
                    + Objects.hashCode(description) + Objects.hashCode(permissions)
                    + Objects.hashCode(dataEnums) + Objects.hashCode(validBitFlag);
        }

        @Override
        public String toString() {
            StringBuilder stringBuffer = new StringBuilder().append("CarSvcPropertyConfig{");
            stringBuffer.append("propertyId: ").append(propertyId)
                    .append(", halPropId: ").append(halPropId)
                    .append(", propertyName: ").append(propertyName)
                    .append(", description: ").append(description)
                    .append(", permissions: ").append(permissions);
            if (dataEnums != null) {
                stringBuffer.append(", dataEnums: ").append(dataEnums);
            }
            if (validBitFlag != null) {
                stringBuffer.append(", validBitFlag: ").append(validBitFlag);
            }
            return stringBuffer.append("}").toString();
        }
    };

    private static final String CONFIG_RESOURCE_NAME = "CarSvcProps.json";
    private static final String JSON_FIELD_NAME_PROPERTIES = "properties";

    private final boolean mUseRuntimeConfigFile;
    /**
     * Index key is an AIDL HAL property ID, and the value is readPermission, writePermission.
     * If the property can not be written (or read), set value as NULL.
     * Throw an IllegalArgumentException when try to write READ_ONLY properties or read WRITE_ONLY
     * properties.
     */
    private final SparseIntArray mHalPropIdToValidBitFlag = new SparseIntArray();
    private final PropertyPermissionInfo mPropertyPermissionInfo = new PropertyPermissionInfo();
    private static final String TAG = CarLog.tagFor(PropertyHalServiceConfigs.class);

    private final SparseArray<Set<Integer>> mHalPropIdToEnumSet = new SparseArray<>();
    private final SparseArray<CarSvcPropertyConfig> mHalPropIdToCarSvcConfig;
    private final BidirectionalSparseIntArray mMgrPropIdToHalPropId;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArray<PropertyPermissions> mVendorHalPropIdToPermissions =
            new SparseArray<>();

    /**
     * Should only be used in unit tests. Use {@link getInsance} instead.
     */
    @VisibleForTesting
    /* package */ PropertyHalServiceConfigs(boolean useRuntimeConfigFile) {
        mUseRuntimeConfigFile = useRuntimeConfigFile;
        if (mUseRuntimeConfigFile) {
            InputStream defaultConfigInputStream = this.getClass().getClassLoader()
                    .getResourceAsStream(CONFIG_RESOURCE_NAME);
            mHalPropIdToCarSvcConfig = parseJsonConfig(defaultConfigInputStream,
                    "defaultResource");
            List<Integer> halPropIdMgrIds = new ArrayList<>();
            for (int i = 0; i < mHalPropIdToCarSvcConfig.size(); i++) {
                CarSvcPropertyConfig config = mHalPropIdToCarSvcConfig.valueAt(i);
                if (config.halPropId != config.propertyId) {
                    halPropIdMgrIds.add(config.propertyId);
                    halPropIdMgrIds.add(config.halPropId);
                }
            }
            int[] halPropIdMgrIdArray = new int[halPropIdMgrIds.size()];
            for (int i = 0; i < halPropIdMgrIds.size(); i++) {
                halPropIdMgrIdArray[i] = halPropIdMgrIds.get(i);
            }
            mMgrPropIdToHalPropId = BidirectionalSparseIntArray.create(halPropIdMgrIdArray);
            return;
        }

        mHalPropIdToCarSvcConfig = null;
        mMgrPropIdToHalPropId = null;
        populateEnumSet();
    }

    /**
     * Gets the hard-coded HAL property ID to enum value set. For unit test only.
     *
     * TODO(b/293354967): Remove this once we migrate to runtime config.
     */
    @VisibleForTesting
    /* package */ SparseArray<Set<Integer>> getHalPropIdToEnumSet() {
        return mHalPropIdToEnumSet;
    }

    /**
     * Gets the singleton instance for {@link PropertyHalServiceConfigs}.
     */
    public static PropertyHalServiceConfigs getInstance() {
        synchronized (sLock) {
            if (sPropertyHalServiceConfigs == null) {
                sPropertyHalServiceConfigs = new PropertyHalServiceConfigs(USE_RUNTIME_CONFIG_FILE);
            }
            return sPropertyHalServiceConfigs;
        }
    }

    /**
     * Create a new instance for {@link PropertyHalServiceConfigs}.
     */
    @VisibleForTesting
    public static PropertyHalServiceConfigs newConfigs() {
        return new PropertyHalServiceConfigs(USE_RUNTIME_CONFIG_FILE);
    }

    /**
     * Returns all possible supported enum values for the {@code halPropId}. If property does not
     * support an enum, then it returns {@code null}.
     */
    @Nullable
    public Set<Integer> getAllPossibleSupportedEnumValues(int halPropId) {
        if (mUseRuntimeConfigFile) {
            if (!mHalPropIdToCarSvcConfig.contains(halPropId)) {
                return null;
            }
            Set<Integer> dataEnums = mHalPropIdToCarSvcConfig.get(halPropId).dataEnums;
            return dataEnums != null ? Collections.unmodifiableSet(dataEnums) : null;
        }
        return mHalPropIdToEnumSet.contains(halPropId)
                ? Collections.unmodifiableSet(mHalPropIdToEnumSet.get(halPropId)) : null;
    }

    /**
     * Checks property value's format for all properties. Checks property value range if property
     * has @data_enum flag in types.hal.
     * @return true if property value's payload is valid.
     */
    public boolean checkPayload(HalPropValue propValue) {
        int propId = propValue.getPropId();
        // Mixed property uses config array to indicate the data format. Checked it when convert it
        // to CarPropertyValue.
        if ((propId & VehiclePropertyType.MASK) == VehiclePropertyType.MIXED) {
            return true;
        }
        if (propValue.getStatus() != VehiclePropertyStatus.AVAILABLE) {
            return true;
        }
        if (!checkFormatForAllProperties(propValue)) {
            Slogf.e(TAG, "Property value: " + propValue + " has an invalid data format");
            return false;
        }
        if (mUseRuntimeConfigFile) {
            CarSvcPropertyConfig carSvcPropertyConfig = mHalPropIdToCarSvcConfig.get(propId);
            if (carSvcPropertyConfig == null) {
                // This is not a system property.
                return true;
            }
            if (carSvcPropertyConfig.dataEnums != null) {
                return checkDataEnum(propValue, carSvcPropertyConfig.dataEnums);
            }
            if (carSvcPropertyConfig.validBitFlag != null) {
                return checkValidBitFlag(propValue, carSvcPropertyConfig.validBitFlag);
            }
            return true;
        }
        if (mHalPropIdToEnumSet.contains(propId)) {
            return checkDataEnum(propValue, mHalPropIdToEnumSet.get(propId));
        }
        if (mHalPropIdToValidBitFlag.indexOfKey(propId) >= 0) {
            return checkValidBitFlag(propValue, mHalPropIdToValidBitFlag.get(propId));
        }
        return true;
    }

    /**
     * Gets readPermission using a HAL-level propertyId.
     *
     * @param halPropId HAL-level propertyId
     * @return PermissionCondition object that represents halPropId's readPermission
     */
    @Nullable
    public PermissionCondition getReadPermission(int halPropId) {
        if (mUseRuntimeConfigFile) {
            if (CarPropertyHelper.isVendorProperty(halPropId)) {
                return getVendorReadPermission(halPropId);
            }
            CarSvcPropertyConfig carSvcPropertyConfig = mHalPropIdToCarSvcConfig.get(
                    halPropId);
            if (carSvcPropertyConfig == null) {
                Slogf.w(TAG, "halPropId: "  + halPropIdToName(halPropId) + " is not supported,"
                        + " no read permission");
                return null;
            }
            return carSvcPropertyConfig.permissions.getReadPermission();
        }
        return mPropertyPermissionInfo.getReadPermission(halPropId);
    }

    @Nullable
    private PermissionCondition getVendorReadPermission(int halPropId) {
        String halPropIdName = halPropIdToName(halPropId);
        synchronized (mLock) {
            PropertyPermissions propertyPermissions = mVendorHalPropIdToPermissions.get(halPropId);
            if (propertyPermissions == null) {
                Slogf.v(TAG, "no custom vendor read permission for: " + halPropIdName
                        + ", default to PERMISSION_VENDOR_EXTENSION");
                return SINGLE_PERMISSION_VENDOR_EXTENSION;
            }
            PermissionCondition readPermission = propertyPermissions.getReadPermission();
            if (readPermission == null) {
                Slogf.v(TAG, "vendor propId is not available for reading: " + halPropIdName);
            }
            return readPermission;
        }
    }

    /**
     * Gets writePermission using a HAL-level propertyId.
     *
     * @param halPropId HAL-level propertyId
     * @return PermissionCondition object that represents halPropId's writePermission
     */
    @Nullable
    public PermissionCondition getWritePermission(int halPropId) {
        if (mUseRuntimeConfigFile) {
            if (CarPropertyHelper.isVendorProperty(halPropId)) {
                return getVendorWritePermission(halPropId);
            }
            CarSvcPropertyConfig carSvcPropertyConfig = mHalPropIdToCarSvcConfig.get(
                    halPropId);
            if (carSvcPropertyConfig == null) {
                Slogf.w(TAG, "halPropId: "  + halPropIdToName(halPropId) + " is not supported,"
                        + " no write permission");
                return null;
            }
            return carSvcPropertyConfig.permissions.getWritePermission();
        }
        return mPropertyPermissionInfo.getWritePermission(halPropId);
    }

    @Nullable
    private PermissionCondition getVendorWritePermission(int halPropId) {
        String halPropIdName = halPropIdToName(halPropId);
        synchronized (mLock) {
            PropertyPermissions propertyPermissions = mVendorHalPropIdToPermissions.get(halPropId);
            if (propertyPermissions == null) {
                Slogf.v(TAG, "no custom vendor write permission for: " + halPropIdName
                        + ", default to PERMISSION_VENDOR_EXTENSION");
                return SINGLE_PERMISSION_VENDOR_EXTENSION;
            }
            PermissionCondition writePermission = propertyPermissions.getWritePermission();
            if (writePermission == null) {
                Slogf.v(TAG, "vendor propId is not available for writing: " + halPropIdName);
            }
            return writePermission;
        }
    }

    /**
     * Checks if readPermission is granted for a HAL-level propertyId in a given context.
     *
     * @param halPropId HAL-level propertyId
     * @param context Context to check
     * @return readPermission is granted or not.
     */
    public boolean isReadable(Context context, int halPropId) {
        PermissionCondition readPermission = getReadPermission(halPropId);
        if (readPermission == null) {
            Slogf.v(TAG, "propId is not readable or is a system property but does not exist "
                    + "in PropertyPermissionInfo: " + halPropIdToName(halPropId));
            return false;
        }
        return readPermission.isMet(context);
    }

    /**
     * Checks if writePermission is granted for a HAL-level propertyId in a given context.
     *
     * @param halPropId HAL-level propertyId
     * @param context Context to check
     * @return writePermission is granted or not.
     */
    public boolean isWritable(Context context, int halPropId) {
        PermissionCondition writePermission = getWritePermission(halPropId);
        if (writePermission == null) {
            Slogf.v(TAG, "propId is not writable or is a system property but does not exist "
                    + "in PropertyPermissionInfo: " + halPropIdToName(halPropId));
            return false;
        }
        return writePermission.isMet(context);
    }

    /**
     * Checks if property ID is in the list of known IDs that PropertyHalService is interested it.
     */
    public boolean isSupportedProperty(int propId) {
        if (mUseRuntimeConfigFile) {
            return mHalPropIdToCarSvcConfig.get(propId) != null
                    || CarPropertyHelper.isVendorProperty(propId);
        }
        return mPropertyPermissionInfo.isSupportedProperty(propId);
    }

    /**
     * Overrides the permission map for vendor properties
     *
     * @param configArray the configArray for
     * {@link VehicleProperty#SUPPORT_CUSTOMIZE_VENDOR_PERMISSION}
     */
    public void customizeVendorPermission(int[] configArray) {
        if (configArray == null || configArray.length % 3 != 0) {
            throw new IllegalArgumentException(
                    "ConfigArray for SUPPORT_CUSTOMIZE_VENDOR_PERMISSION is wrong");
        }
        int index = 0;
        while (index < configArray.length) {
            int propId = configArray[index++];
            if (!CarPropertyHelper.isVendorProperty(propId)) {
                throw new IllegalArgumentException("Property Id: " + propId
                        + " is not in vendor range");
            }
            int readPermission = configArray[index++];
            int writePermission = configArray[index++];
            String readPermissionStr = PropertyPermissionInfo.toPermissionString(
                    readPermission, propId);
            String writePermissionStr = PropertyPermissionInfo.toPermissionString(
                    writePermission, propId);
            if (!mUseRuntimeConfigFile) {
                mPropertyPermissionInfo.addPermissions(
                        propId, readPermissionStr, writePermissionStr);
                continue;
            }
            synchronized (mLock) {
                if (mVendorHalPropIdToPermissions.get(propId) != null) {
                    Slogf.e(TAG, "propId is a vendor property that already exists in "
                            + "mVendorHalPropIdToPermissions and thus cannot have its "
                            + "permissions overwritten: " + halPropIdToName(propId));
                    continue;
                }

                PropertyPermissions.Builder propertyPermissionBuilder =
                        new PropertyPermissions.Builder();
                if (readPermissionStr != null) {
                    propertyPermissionBuilder.setReadPermission(
                            new SinglePermission(readPermissionStr));
                }
                if (writePermissionStr != null) {
                    propertyPermissionBuilder.setWritePermission(
                            new SinglePermission(writePermissionStr));
                }

                mVendorHalPropIdToPermissions.put(propId, propertyPermissionBuilder.build());
            }
        }
    }

    /**
     * Converts manager property ID to Vehicle HAL property ID.
     */
    public int managerToHalPropId(int mgrPropId) {
        if (!mUseRuntimeConfigFile) {
            return MGR_PROP_ID_TO_HAL_PROP_ID.getValue(mgrPropId, mgrPropId);
        }
        return mMgrPropIdToHalPropId.getValue(mgrPropId, mgrPropId);
    }

    /**
     * Converts Vehicle HAL property ID to manager property ID.
     */
    public int halToManagerPropId(int halPropId) {
        if (!mUseRuntimeConfigFile) {
            return MGR_PROP_ID_TO_HAL_PROP_ID.getKey(halPropId, halPropId);
        }
        return mMgrPropIdToHalPropId.getKey(halPropId, halPropId);
    }

    /**
     * Print out the name for a VHAL property Id.
     *
     * For debugging only.
     */
    public String halPropIdToName(int halPropId) {
        return VehiclePropertyIds.toString(halToManagerPropId(halPropId));
    }

    private void populateEnumSet() {
        Set<Integer> FUEL_TYPE =
                new HashSet<>(getIntegersFromDataEnums(FuelType.class));
        Set<Integer> EV_CONNECTOR_TYPE =
                new HashSet<>(getIntegersFromDataEnums(EvConnectorType.class));
        Set<Integer> PORT_LOCATION =
                new HashSet<>(getIntegersFromDataEnums(PortLocationType.class));
        Set<Integer> VEHICLE_SEAT =
                new HashSet<>(getIntegersFromDataEnums(VehicleAreaSeat.class));
        Set<Integer> OIL_LEVEL =
                new HashSet<>(getIntegersFromDataEnums(VehicleOilLevel.class));
        Set<Integer> VEHICLE_GEAR =
                new HashSet<>(getIntegersFromDataEnums(VehicleGear.class));
        Set<Integer> TURN_SIGNAL =
                new HashSet<>(getIntegersFromDataEnums(VehicleTurnSignal.class));
        Set<Integer> IGNITION_STATE =
                new HashSet<>(getIntegersFromDataEnums(VehicleIgnitionState.class));
        Set<Integer> VEHICLE_UNITS =
                new HashSet<>(getIntegersFromDataEnums(VehicleUnit.class));
        Set<Integer> SEAT_OCCUPANCY_STATE =
                new HashSet<>(getIntegersFromDataEnums(VehicleSeatOccupancyState.class));
        Set<Integer> VEHICLE_LIGHT_STATE =
                new HashSet<>(getIntegersFromDataEnums(VehicleLightState.class));
        Set<Integer> VEHICLE_LIGHT_SWITCH =
                new HashSet<>(getIntegersFromDataEnums(VehicleLightSwitch.class));
        Set<Integer> HVAC_FAN_DIRECTION =
                new HashSet<>(getIntegersFromDataEnums(CarHvacFanDirection.class));
        Set<Integer> ETC_CARD_TYPE =
                new HashSet<>(getIntegersFromDataEnums(ElectronicTollCollectionCardType.class));
        Set<Integer> ETC_CARD_STATUS =
                new HashSet<>(getIntegersFromDataEnums(ElectronicTollCollectionCardStatus.class));
        Set<Integer> EV_CHARGE_STATE =
                new HashSet<>(getIntegersFromDataEnums(EvChargeState.class));
        Set<Integer> EV_REGENERATIVE_BREAKING_STATE =
                new HashSet<>(getIntegersFromDataEnums(EvRegenerativeBrakingState.class));
        Set<Integer> EV_STOPPING_MODE =
                new HashSet<>(getIntegersFromDataEnums(EvStoppingMode.class));
        Set<Integer> TRAILER_PRESENT =
                new HashSet<>(getIntegersFromDataEnums(TrailerState.class));
        Set<Integer> GSR_COMP_TYPE =
                new HashSet<>(getIntegersFromDataEnums(GsrComplianceRequirementType.class));
        int LOCATION_CHARACTERIZATION =
                generateAllCombination(LocationCharacterization.class);
        Set<Integer> WINDSHIELD_WIPERS_STATE =
                new HashSet<>(getIntegersFromDataEnums(WindshieldWipersState.class));
        Set<Integer> WINDSHIELD_WIPERS_SWITCH =
                new HashSet<>(getIntegersFromDataEnums(WindshieldWipersSwitch.class));
        Set<Integer> EMERGENCY_LANE_KEEP_ASSIST_STATE =
                new HashSet<>(getIntegersFromDataEnums(
                        EmergencyLaneKeepAssistState.class, ErrorState.class));
        Set<Integer> CRUISE_CONTROL_TYPE =
                new HashSet<>(getIntegersFromDataEnums(
                        CruiseControlType.class, ErrorState.class));
        Set<Integer> CRUISE_CONTROL_STATE =
                new HashSet<>(getIntegersFromDataEnums(
                        CruiseControlState.class, ErrorState.class));
        Set<Integer> CRUISE_CONTROL_COMMAND =
                new HashSet<>(getIntegersFromDataEnums(CruiseControlCommand.class));
        Set<Integer> HANDS_ON_DETECTION_DRIVER_STATE =
                new HashSet<>(getIntegersFromDataEnums(
                        HandsOnDetectionDriverState.class, ErrorState.class));
        Set<Integer> HANDS_ON_DETECTION_WARNING =
                new HashSet<>(getIntegersFromDataEnums(
                        HandsOnDetectionWarning.class, ErrorState.class));
        Set<Integer> AUTOMATIC_EMERGENCY_BRAKING_STATE =
                new HashSet<>(getIntegersFromDataEnums(
                    AutomaticEmergencyBrakingState.class, ErrorState.class));
        Set<Integer> FORWARD_COLLISION_WARNING_STATE =
                new HashSet<>(getIntegersFromDataEnums(
                    ForwardCollisionWarningState.class, ErrorState.class));
        Set<Integer> BLIND_SPOT_WARNING_STATE =
                new HashSet<>(getIntegersFromDataEnums(
                    BlindSpotWarningState.class, ErrorState.class));
        Set<Integer> LANE_DEPARTURE_WARNING_STATE =
                new HashSet<>(getIntegersFromDataEnums(
                    LaneDepartureWarningState.class, ErrorState.class));
        Set<Integer> LANE_KEEP_ASSIST_STATE =
                new HashSet<>(getIntegersFromDataEnums(
                    LaneKeepAssistState.class, ErrorState.class));
        Set<Integer> LANE_CENTERING_ASSIST_COMMAND =
                new HashSet<>(getIntegersFromDataEnums(LaneCenteringAssistCommand.class));
        Set<Integer> LANE_CENTERING_ASSIST_STATE =
                new HashSet<>(getIntegersFromDataEnums(
                    LaneCenteringAssistState.class, ErrorState.class));

        // mHalPropIdToEnumSet should contain all properties which have @data_enum in
        // VehicleProperty.aidl
        mHalPropIdToEnumSet.put(VehicleProperty.INFO_FUEL_TYPE, FUEL_TYPE);
        mHalPropIdToEnumSet.put(VehicleProperty.INFO_EV_CONNECTOR_TYPE, EV_CONNECTOR_TYPE);
        mHalPropIdToEnumSet.put(VehicleProperty.INFO_FUEL_DOOR_LOCATION, PORT_LOCATION);
        mHalPropIdToEnumSet.put(VehicleProperty.INFO_DRIVER_SEAT, VEHICLE_SEAT);
        mHalPropIdToEnumSet.put(VehicleProperty.INFO_MULTI_EV_PORT_LOCATIONS, PORT_LOCATION);
        mHalPropIdToEnumSet.put(VehicleProperty.ENGINE_OIL_LEVEL, OIL_LEVEL);
        mHalPropIdToEnumSet.put(VehicleProperty.GEAR_SELECTION, VEHICLE_GEAR);
        mHalPropIdToEnumSet.put(VehicleProperty.CURRENT_GEAR, VEHICLE_GEAR);
        mHalPropIdToEnumSet.put(VehicleProperty.TURN_SIGNAL_STATE, TURN_SIGNAL);
        mHalPropIdToEnumSet.put(VehicleProperty.IGNITION_STATE, IGNITION_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.HVAC_TEMPERATURE_DISPLAY_UNITS, VEHICLE_UNITS);
        mHalPropIdToEnumSet.put(VehicleProperty.DISTANCE_DISPLAY_UNITS, VEHICLE_UNITS);
        mHalPropIdToEnumSet.put(VehicleProperty.FUEL_VOLUME_DISPLAY_UNITS, VEHICLE_UNITS);
        mHalPropIdToEnumSet.put(VehicleProperty.TIRE_PRESSURE_DISPLAY_UNITS, VEHICLE_UNITS);
        mHalPropIdToEnumSet.put(VehicleProperty.EV_BATTERY_DISPLAY_UNITS, VEHICLE_UNITS);
        mHalPropIdToEnumSet.put(VehicleProperty.VEHICLE_SPEED_DISPLAY_UNITS, VEHICLE_UNITS);
        mHalPropIdToEnumSet.put(VehicleProperty.SEAT_OCCUPANCY, SEAT_OCCUPANCY_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.HIGH_BEAM_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.HEADLIGHTS_STATE, VEHICLE_LIGHT_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.FOG_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.FRONT_FOG_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.REAR_FOG_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.HAZARD_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.CABIN_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.READING_LIGHTS_STATE, VEHICLE_LIGHT_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.EV_CHARGE_STATE, EV_CHARGE_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.EV_REGENERATIVE_BRAKING_STATE,
                EV_REGENERATIVE_BREAKING_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.HEADLIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        mHalPropIdToEnumSet.put(VehicleProperty.HIGH_BEAM_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        mHalPropIdToEnumSet.put(VehicleProperty.FOG_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        mHalPropIdToEnumSet.put(VehicleProperty.FRONT_FOG_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        mHalPropIdToEnumSet.put(VehicleProperty.REAR_FOG_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        mHalPropIdToEnumSet.put(VehicleProperty.HAZARD_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        mHalPropIdToEnumSet.put(VehicleProperty.CABIN_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        mHalPropIdToEnumSet.put(VehicleProperty.READING_LIGHTS_SWITCH, VEHICLE_LIGHT_SWITCH);
        mHalPropIdToEnumSet.put(VehicleProperty.ELECTRONIC_TOLL_COLLECTION_CARD_TYPE,
                ETC_CARD_TYPE);
        mHalPropIdToEnumSet.put(VehicleProperty.ELECTRONIC_TOLL_COLLECTION_CARD_STATUS,
                ETC_CARD_STATUS);
        mHalPropIdToEnumSet.put(VehicleProperty.TRAILER_PRESENT,
                TRAILER_PRESENT);
        mHalPropIdToEnumSet.put(
                VehicleProperty.GENERAL_SAFETY_REGULATION_COMPLIANCE_REQUIREMENT,
                GSR_COMP_TYPE);
        mHalPropIdToEnumSet.put(VehicleProperty.EV_STOPPING_MODE,
                EV_STOPPING_MODE);
        mHalPropIdToEnumSet.put(VehicleProperty.WINDSHIELD_WIPERS_STATE,
                WINDSHIELD_WIPERS_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.WINDSHIELD_WIPERS_SWITCH,
                WINDSHIELD_WIPERS_SWITCH);
        mHalPropIdToEnumSet.put(VehicleProperty.STEERING_WHEEL_LIGHTS_SWITCH,
                VEHICLE_LIGHT_SWITCH);
        mHalPropIdToEnumSet.put(VehicleProperty.STEERING_WHEEL_LIGHTS_STATE,
                VEHICLE_LIGHT_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.EMERGENCY_LANE_KEEP_ASSIST_STATE,
                EMERGENCY_LANE_KEEP_ASSIST_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.CRUISE_CONTROL_TYPE,
                CRUISE_CONTROL_TYPE);
        mHalPropIdToEnumSet.put(VehicleProperty.CRUISE_CONTROL_STATE,
                CRUISE_CONTROL_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.CRUISE_CONTROL_COMMAND,
                CRUISE_CONTROL_COMMAND);
        mHalPropIdToEnumSet.put(VehicleProperty.HANDS_ON_DETECTION_DRIVER_STATE,
                HANDS_ON_DETECTION_DRIVER_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.HANDS_ON_DETECTION_WARNING,
                HANDS_ON_DETECTION_WARNING);
        mHalPropIdToEnumSet.put(VehicleProperty.AUTOMATIC_EMERGENCY_BRAKING_STATE,
                AUTOMATIC_EMERGENCY_BRAKING_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.FORWARD_COLLISION_WARNING_STATE,
                FORWARD_COLLISION_WARNING_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.BLIND_SPOT_WARNING_STATE,
                BLIND_SPOT_WARNING_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.LANE_DEPARTURE_WARNING_STATE,
                LANE_DEPARTURE_WARNING_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.LANE_KEEP_ASSIST_STATE,
                LANE_KEEP_ASSIST_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.LANE_CENTERING_ASSIST_COMMAND,
                LANE_CENTERING_ASSIST_COMMAND);
        mHalPropIdToEnumSet.put(VehicleProperty.LANE_CENTERING_ASSIST_STATE,
                LANE_CENTERING_ASSIST_STATE);
        mHalPropIdToEnumSet.put(VehicleProperty.HVAC_FAN_DIRECTION_AVAILABLE,
                HVAC_FAN_DIRECTION);
        mHalPropIdToEnumSet.put(VehicleProperty.HVAC_FAN_DIRECTION, HVAC_FAN_DIRECTION);

        // mPropToValidBitFlag contains all properties which return values are combinations of bits
        mHalPropIdToValidBitFlag.put(VehicleProperty.LOCATION_CHARACTERIZATION,
                LOCATION_CHARACTERIZATION);
    }

    private static boolean checkValidBitFlag(HalPropValue propValue, int flagCombination) {
        for (int i = 0; i < propValue.getInt32ValuesSize(); i++) {
            int value = propValue.getInt32Value(i);
            if ((value & flagCombination) != value) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a car service JSON config file. Only exposed for testing.
     */
    @VisibleForTesting
    /* package */ SparseArray<CarSvcPropertyConfig> parseJsonConfig(InputStream configFile,
            String path) {
        String configString;
        try {
            configString = new String(configFile.readAllBytes());
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read from config file: " + path, e);
        }
        JSONObject configJsonObject;
        try {
            configJsonObject = new JSONObject(configString);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Config file: " + path
                    + " does not contain a valid JSONObject.", e);
        }
        JSONObject properties;
        SparseArray<CarSvcPropertyConfig> configs = new SparseArray<>();
        try {
            properties = configJsonObject.getJSONObject(JSON_FIELD_NAME_PROPERTIES);
            for (String propertyName : properties.keySet()) {
                JSONObject propertyObj = properties.getJSONObject(propertyName);
                CarSvcPropertyConfig config = new CarSvcPropertyConfig();
                if (propertyObj.optBoolean("deprecated")) {
                    continue;
                }
                config.propertyId = propertyObj.getInt("propertyId");
                int halPropId = config.propertyId;
                if (propertyObj.has("vhalPropertyId")) {
                    halPropId = propertyObj.getInt("vhalPropertyId");
                }
                config.halPropId = halPropId;
                config.propertyName = propertyName;
                config.description = propertyObj.getString("description");
                JSONArray enumJsonArray = propertyObj.optJSONArray("dataEnums");
                if (enumJsonArray != null) {
                    config.dataEnums = new ArraySet<Integer>();
                    for (int i = 0; i < enumJsonArray.length(); i++) {
                        config.dataEnums.add(enumJsonArray.getInt(i));
                    }
                }
                JSONArray flagJsonArray = propertyObj.optJSONArray("dataFlags");
                if (flagJsonArray != null) {
                    List<Integer> dataFlags = new ArrayList<>();
                    for (int i = 0; i < flagJsonArray.length(); i++) {
                        dataFlags.add(flagJsonArray.getInt(i));
                    }
                    config.validBitFlag = generateAllCombination(dataFlags);
                }
                config.permissions = parsePermission(propertyName, propertyObj);
                configs.put(config.halPropId, config);
            }
            return configs;
        } catch (JSONException e) {
            throw new IllegalArgumentException("Config file: " + path
                    + " has invalid JSON format.", e);
        }
    }

    private static PropertyPermissions parsePermission(String propertyName, JSONObject propertyObj)
            throws JSONException {
        PropertyPermissions.Builder builder = new PropertyPermissions.Builder();
        JSONObject jsonReadPermission = propertyObj.optJSONObject("readPermission");
        if (jsonReadPermission != null) {
            builder.setReadPermission(parsePermissionCondition(jsonReadPermission));
        }
        JSONObject jsonWritePermission = propertyObj.optJSONObject("writePermission");
        if (jsonWritePermission != null) {
            builder.setWritePermission(parsePermissionCondition(jsonWritePermission));
        }
        if (jsonReadPermission == null && jsonWritePermission == null) {
            throw new IllegalArgumentException(
                    "No read or write permission specified for: " + propertyName);
        }
        return builder.build();
    }

    private static PermissionCondition parsePermissionCondition(JSONObject permissionObj)
            throws JSONException {
        String type = permissionObj.getString("type");
        if (type.equals("single")) {
            return new SinglePermission(permissionObj.getString("value"));
        }
        if (!type.equals("anyOf") && !type.equals("allOf")) {
            throw new IllegalArgumentException("Unknown permission type: " + type
                    + ", only support single, anyOf or allOf");
        }
        JSONArray jsonSubPermissions = permissionObj.getJSONArray("value");
        PermissionCondition[] subPermissions = new PermissionCondition[jsonSubPermissions.length()];
        for (int i = 0; i < jsonSubPermissions.length(); i++) {
            subPermissions[i] = parsePermissionCondition(jsonSubPermissions.getJSONObject(i));
        }
        if (type.equals("anyOf")) {
            return new AnyOfPermissions(subPermissions);
        }
        return new AllOfPermissions(subPermissions);
    }

    private static boolean checkFormatForAllProperties(HalPropValue propValue) {
        int propId = propValue.getPropId();
        //Records sum size of int32values, floatValue, int64Values, bytes, String
        int sizeOfAllValue = propValue.getInt32ValuesSize() + propValue.getFloatValuesSize()
                + propValue.getInt64ValuesSize() + propValue.getByteValuesSize()
                + propValue.getStringValue().length();
        if (sizeOfAllValue == 0) {
            Slogf.e(TAG, "Property value is empty: " + propValue);
            return false;
        }
        switch (propId & VehiclePropertyType.MASK) {
            case VehiclePropertyType.BOOLEAN:
            case VehiclePropertyType.INT32:
                return sizeOfAllValue == 1 && propValue.getInt32ValuesSize() == 1;
            case VehiclePropertyType.FLOAT:
                return sizeOfAllValue == 1 && propValue.getFloatValuesSize() == 1;
            case VehiclePropertyType.INT64:
                return sizeOfAllValue == 1 && propValue.getInt64ValuesSize() == 1;
            case VehiclePropertyType.FLOAT_VEC:
                return sizeOfAllValue == propValue.getFloatValuesSize();
            case VehiclePropertyType.INT64_VEC:
                return sizeOfAllValue == propValue.getInt64ValuesSize();
            case VehiclePropertyType.INT32_VEC:
                return sizeOfAllValue == propValue.getInt32ValuesSize();
            case VehiclePropertyType.BYTES:
                return sizeOfAllValue == propValue.getByteValuesSize();
            case VehiclePropertyType.STRING:
                return sizeOfAllValue == propValue.getStringValue().length();
            default:
                throw new IllegalArgumentException("Unexpected property type for propId: "
                        + Integer.toHexString(propId));
        }
    }
    private static boolean checkDataEnum(HalPropValue propValue, Set<Integer> enums) {
        for (int i = 0; i < propValue.getInt32ValuesSize(); i++) {
            if (!enums.contains(propValue.getInt32Value(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> getIntegersFromDataEnums(Class... clazz) {
        List<Integer> integerList = new ArrayList<>(5);
        for (Class c: clazz) {
            Field[] fields = c.getDeclaredFields();
            for (Field f : fields) {
                if (f.getType() == int.class) {
                    try {
                        integerList.add(f.getInt(c));
                    } catch (IllegalAccessException | RuntimeException e) {
                        Slogf.w(TAG, "Failed to get value");
                    }
                }
            }
        }
        return integerList;
    }

    // Generate all combinations at once
    private static int generateAllCombination(Class clazz) {
        List<Integer> bitFlags = getIntegersFromDataEnums(clazz);
        return generateAllCombination(bitFlags);
    }

    private static int generateAllCombination(List<Integer> bitFlags) {
        int combination = bitFlags.get(0);
        for (int i = 1; i < bitFlags.size(); i++) {
            combination |= bitFlags.get(i);
        }
        return combination;
    }
}
