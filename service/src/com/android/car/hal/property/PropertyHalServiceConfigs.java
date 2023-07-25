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

import android.annotation.Nullable;
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
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.car.CarLog;
import com.android.car.hal.HalPropValue;
import com.android.car.hal.property.PropertyPermissionInfo.PermissionCondition;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A singleton class to define which AIDL HAL property IDs are used by PropertyHalService.
 * This class binds the read and write permissions to the property ID.
 */
public class PropertyHalServiceConfigs {
    private static final boolean USE_RUNTIME_CONFIG_FILE = false;
    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static PropertyHalServiceConfigs sPropertyHalServiceConfigs;

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
    // Enums are used as return value in Vehicle HAL.
    private static final Set<Integer> FUEL_TYPE =
            new HashSet<>(getIntegersFromDataEnums(FuelType.class));
    private static final Set<Integer> EV_CONNECTOR_TYPE =
            new HashSet<>(getIntegersFromDataEnums(EvConnectorType.class));
    private static final Set<Integer> PORT_LOCATION =
            new HashSet<>(getIntegersFromDataEnums(PortLocationType.class));
    private static final Set<Integer> VEHICLE_SEAT =
            new HashSet<>(getIntegersFromDataEnums(VehicleAreaSeat.class));
    private static final Set<Integer> OIL_LEVEL =
            new HashSet<>(getIntegersFromDataEnums(VehicleOilLevel.class));
    private static final Set<Integer> VEHICLE_GEAR =
            new HashSet<>(getIntegersFromDataEnums(VehicleGear.class));
    private static final Set<Integer> TURN_SIGNAL =
            new HashSet<>(getIntegersFromDataEnums(VehicleTurnSignal.class));
    private static final Set<Integer> IGNITION_STATE =
            new HashSet<>(getIntegersFromDataEnums(VehicleIgnitionState.class));
    private static final Set<Integer> VEHICLE_UNITS =
            new HashSet<>(getIntegersFromDataEnums(VehicleUnit.class));
    private static final Set<Integer> SEAT_OCCUPANCY_STATE =
            new HashSet<>(getIntegersFromDataEnums(VehicleSeatOccupancyState.class));
    private static final Set<Integer> VEHICLE_LIGHT_STATE =
            new HashSet<>(getIntegersFromDataEnums(VehicleLightState.class));
    private static final Set<Integer> VEHICLE_LIGHT_SWITCH =
            new HashSet<>(getIntegersFromDataEnums(VehicleLightSwitch.class));
    private static final Set<Integer> HVAC_FAN_DIRECTION =
            new HashSet<>(getIntegersFromDataEnums(CarHvacFanDirection.class));
    private static final Set<Integer> ETC_CARD_TYPE =
            new HashSet<>(getIntegersFromDataEnums(ElectronicTollCollectionCardType.class));
    private static final Set<Integer> ETC_CARD_STATUS =
            new HashSet<>(getIntegersFromDataEnums(ElectronicTollCollectionCardStatus.class));
    private static final Set<Integer> EV_CHARGE_STATE =
            new HashSet<>(getIntegersFromDataEnums(EvChargeState.class));
    private static final Set<Integer> EV_REGENERATIVE_BREAKING_STATE =
            new HashSet<>(getIntegersFromDataEnums(EvRegenerativeBrakingState.class));
    private static final Set<Integer> EV_STOPPING_MODE =
            new HashSet<>(getIntegersFromDataEnums(EvStoppingMode.class));
    private static final Set<Integer> TRAILER_PRESENT =
            new HashSet<>(getIntegersFromDataEnums(TrailerState.class));
    private static final Set<Integer> GSR_COMP_TYPE =
            new HashSet<>(getIntegersFromDataEnums(GsrComplianceRequirementType.class));
    private static final int LOCATION_CHARACTERIZATION =
            generateAllCombination(LocationCharacterization.class);
    private static final Set<Integer> WINDSHIELD_WIPERS_STATE =
            new HashSet<>(getIntegersFromDataEnums(WindshieldWipersState.class));
    private static final Set<Integer> WINDSHIELD_WIPERS_SWITCH =
            new HashSet<>(getIntegersFromDataEnums(WindshieldWipersSwitch.class));
    private static final Set<Integer> EMERGENCY_LANE_KEEP_ASSIST_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                    EmergencyLaneKeepAssistState.class, ErrorState.class));
    private static final Set<Integer> CRUISE_CONTROL_TYPE =
            new HashSet<>(getIntegersFromDataEnums(
                    CruiseControlType.class, ErrorState.class));
    private static final Set<Integer> CRUISE_CONTROL_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                    CruiseControlState.class, ErrorState.class));
    private static final Set<Integer> CRUISE_CONTROL_COMMAND =
            new HashSet<>(getIntegersFromDataEnums(CruiseControlCommand.class));
    private static final Set<Integer> HANDS_ON_DETECTION_DRIVER_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                    HandsOnDetectionDriverState.class, ErrorState.class));
    private static final Set<Integer> HANDS_ON_DETECTION_WARNING =
            new HashSet<>(getIntegersFromDataEnums(
                    HandsOnDetectionWarning.class, ErrorState.class));
    private static final Set<Integer> AUTOMATIC_EMERGENCY_BRAKING_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                AutomaticEmergencyBrakingState.class, ErrorState.class));
    private static final Set<Integer> FORWARD_COLLISION_WARNING_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                ForwardCollisionWarningState.class, ErrorState.class));
    private static final Set<Integer> BLIND_SPOT_WARNING_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                BlindSpotWarningState.class, ErrorState.class));
    private static final Set<Integer> LANE_DEPARTURE_WARNING_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                LaneDepartureWarningState.class, ErrorState.class));
    private static final Set<Integer> LANE_KEEP_ASSIST_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                LaneKeepAssistState.class, ErrorState.class));
    private static final Set<Integer> LANE_CENTERING_ASSIST_COMMAND =
            new HashSet<>(getIntegersFromDataEnums(LaneCenteringAssistCommand.class));
    private static final Set<Integer> LANE_CENTERING_ASSIST_STATE =
            new HashSet<>(getIntegersFromDataEnums(
                LaneCenteringAssistState.class, ErrorState.class));

    private final SparseArray<Set<Integer>> mHalPropIdToEnumSet = new SparseArray<>();

    private PropertyHalServiceConfigs(boolean useRuntimeConfigFile) {
        mUseRuntimeConfigFile = useRuntimeConfigFile;
        if (mUseRuntimeConfigFile) {
            // TODO(293354967): Support runtime config file.
            return;
        }

        populateEnumSet();
        // mPropToValidBitFlag contains all properties which return values are combinations of bits
        mHalPropIdToValidBitFlag.put(VehicleProperty.LOCATION_CHARACTERIZATION,
                LOCATION_CHARACTERIZATION);

    }

    public static PropertyHalServiceConfigs getInstance() {
        return getInstance(USE_RUNTIME_CONFIG_FILE);
    }

    /**
     * Get the singleton instance for {@link PropertyHalServiceConfigs}.
     */
    public static PropertyHalServiceConfigs getInstance(boolean useRuntimeConfigFile) {
        synchronized (sLock) {
            if (sPropertyHalServiceConfigs == null) {
                sPropertyHalServiceConfigs = new PropertyHalServiceConfigs(useRuntimeConfigFile);
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
            Slogf.e(TAG, "Property value" + propValue + "has an invalid data format");
            return false;
        }
        if (mHalPropIdToEnumSet.contains(propId)) {
            return checkDataEnum(propValue);
        }
        if (mHalPropIdToValidBitFlag.indexOfKey(propId) >= 0) {
            return checkValidBitFlag(propValue);
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
        return mPropertyPermissionInfo.getReadPermission(halPropId);
    }

    /**
     * Gets writePermission using a HAL-level propertyId.
     *
     * @param halPropId HAL-level propertyId
     * @return PermissionCondition object that represents halPropId's writePermission
     */
    @Nullable
    public PermissionCondition getWritePermission(int halPropId) {
        return mPropertyPermissionInfo.getWritePermission(halPropId);
    }

    /**
     * Checks if readPermission is granted for a HAL-level propertyId in a given context.
     *
     * @param halPropId HAL-level propertyId
     * @param context Context to check
     * @return readPermission is granted or not.
     */
    public boolean isReadable(Context context, int halPropId) {
        return mPropertyPermissionInfo.isReadable(context, halPropId);
    }

    /**
     * Checks if writePermission is granted for a HAL-level propertyId in a given context.
     *
     * @param halPropId HAL-level propertyId
     * @param context Context to check
     * @return writePermission is granted or not.
     */
    public boolean isWritable(Context context, int halPropId) {
        return mPropertyPermissionInfo.isWritable(context, halPropId);
    }

    /**
     * Checks if property ID is in the list of known IDs that PropertyHalService is interested it.
     */
    public boolean isSupportedProperty(int propId) {
        return mPropertyPermissionInfo.isSupportedProperty(propId);
    }

    /**
     * Overrides the permission map for vendor properties
     *
     * @param configArray the configArray for
     * {@link VehicleProperty#SUPPORT_CUSTOMIZE_VENDOR_PERMISSION}
     */
    public void customizeVendorPermission(int[] configArray) {
        mPropertyPermissionInfo.customizeVendorPermission(configArray);
    }

    private void populateEnumSet() {
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
    }

    private boolean checkValidBitFlag(HalPropValue propValue) {
        int flagCombination = mHalPropIdToValidBitFlag.get(propValue.getPropId());
        for (int i = 0; i < propValue.getInt32ValuesSize(); i++) {
            int value = propValue.getInt32Value(i);
            if ((value & flagCombination) != value) {
                return false;
            }
        }
        return true;
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
    private boolean checkDataEnum(HalPropValue propValue) {
        int propId = propValue.getPropId();
        Set<Integer> validValue = mHalPropIdToEnumSet.get(propId);
        for (int i = 0; i < propValue.getInt32ValuesSize(); i++) {
            if (!validValue.contains(propValue.getInt32Value(i))) {
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
