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
import android.car.builtin.os.TraceHelper;
import android.car.builtin.util.Slogf;
import android.car.feature.FeatureFlags;
import android.car.feature.FeatureFlagsImpl;
import android.content.Context;
import android.hardware.automotive.vehicle.VehiclePropertyStatus;
import android.hardware.automotive.vehicle.VehiclePropertyType;
import android.os.Trace;
import android.util.ArraySet;
import android.util.JsonReader;
import android.util.SparseArray;

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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A singleton class to define which AIDL HAL property IDs are used by PropertyHalService.
 * This class binds the read and write permissions to the property ID.
 */
public class PropertyHalServiceConfigs {
    private static final long TRACE_TAG = TraceHelper.TRACE_TAG_CAR_SERVICE;
    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static PropertyHalServiceConfigs sPropertyHalServiceConfigs;
    private static final PermissionCondition SINGLE_PERMISSION_VENDOR_EXTENSION =
            new SinglePermission(PERMISSION_VENDOR_EXTENSION);
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

    private static final String VIC_FLAG_NAME = "FLAG_ANDROID_VIC_VEHICLE_PROPERTIES";

    private final FeatureFlags mFeatureFlags;

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
    /* package */ PropertyHalServiceConfigs(@Nullable FeatureFlags featureFlags) {
        Trace.traceBegin(TRACE_TAG, "initialize PropertyHalServiceConfigs");
        if (featureFlags == null) {
            mFeatureFlags = new FeatureFlagsImpl();
        } else {
            mFeatureFlags = featureFlags;
        }
        try (InputStream defaultConfigInputStream = this.getClass().getClassLoader()
                    .getResourceAsStream(CONFIG_RESOURCE_NAME)) {
            mHalPropIdToCarSvcConfig = parseJsonConfig(defaultConfigInputStream,
                    "defaultResource");
        } catch (IOException e) {
            String errorMsg = "failed to open/close resource input stream for: "
                    + CONFIG_RESOURCE_NAME;
            Slogf.e(TAG, errorMsg, e);
            throw new IllegalStateException(errorMsg, e);
        }
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
        Trace.traceEnd(TRACE_TAG);
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
     * Gets a list of all system VHAL property IDs. For unit test only.
     */
    @VisibleForTesting
    /* package */ List<Integer> getAllSystemHalPropIds() {
        List<Integer> halPropIds = new ArrayList<>();
        for (int i = 0; i < mHalPropIdToCarSvcConfig.size(); i++) {
            halPropIds.add(mHalPropIdToCarSvcConfig.keyAt(i));
        }
        return halPropIds;
    }

    /**
     * Gets the singleton instance for {@link PropertyHalServiceConfigs}.
     */
    public static PropertyHalServiceConfigs getInstance() {
        synchronized (sLock) {
            if (sPropertyHalServiceConfigs == null) {
                sPropertyHalServiceConfigs = new PropertyHalServiceConfigs(
                        /* featureFlags= */ null);
            }
            return sPropertyHalServiceConfigs;
        }
    }

    /**
     * Create a new instance for {@link PropertyHalServiceConfigs}.
     */
    @VisibleForTesting
    public static PropertyHalServiceConfigs newConfigs() {
        return new PropertyHalServiceConfigs(/* featureFlags= */ null);
    }

    /**
     * Returns all possible supported enum values for the {@code halPropId}. If property does not
     * support an enum, then it returns {@code null}.
     */
    @Nullable
    public Set<Integer> getAllPossibleSupportedEnumValues(int halPropId) {
        if (!mHalPropIdToCarSvcConfig.contains(halPropId)) {
            return null;
        }
        Set<Integer> dataEnums = mHalPropIdToCarSvcConfig.get(halPropId).dataEnums;
        return dataEnums != null ? Collections.unmodifiableSet(dataEnums) : null;
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

    /**
     * Gets readPermission using a HAL-level propertyId.
     *
     * @param halPropId HAL-level propertyId
     * @return PermissionCondition object that represents halPropId's readPermission
     */
    @Nullable
    public PermissionCondition getReadPermission(int halPropId) {
        if (CarPropertyHelper.isVendorOrBackportedProperty(halPropId)) {
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
        if (CarPropertyHelper.isVendorOrBackportedProperty(halPropId)) {
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
            Slogf.v(TAG, "propId is not readable: " + halPropIdToName(halPropId));
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
            Slogf.v(TAG, "propId is not writable: " + halPropIdToName(halPropId));
            return false;
        }
        return writePermission.isMet(context);
    }

    /**
     * Checks if property ID is in the list of known IDs that PropertyHalService is interested it.
     */
    public boolean isSupportedProperty(int propId) {
        return mHalPropIdToCarSvcConfig.get(propId) != null
                || CarPropertyHelper.isVendorOrBackportedProperty(propId);
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
            if (!CarPropertyHelper.isVendorOrBackportedProperty(propId)) {
                throw new IllegalArgumentException("Property Id: " + propId
                        + " is not in vendor range");
            }
            int readPermission = configArray[index++];
            int writePermission = configArray[index++];
            String readPermissionStr = PropertyPermissionInfo.toPermissionString(
                    readPermission, propId);
            String writePermissionStr = PropertyPermissionInfo.toPermissionString(
                    writePermission, propId);
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
        return mMgrPropIdToHalPropId.getValue(mgrPropId, mgrPropId);
    }

    /**
     * Converts Vehicle HAL property ID to manager property ID.
     */
    public int halToManagerPropId(int halPropId) {
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
        try {
            SparseArray<CarSvcPropertyConfig> configs = new SparseArray<>();
            try (var reader = new JsonReader(new InputStreamReader(configFile, "UTF-8"))) {
                reader.setLenient(true);
                parseObjectEntry(reader, () -> {
                    if (!reader.nextName().equals(JSON_FIELD_NAME_PROPERTIES)) {
                        reader.skipValue();
                        return;
                    }
                    parseObjectEntry(reader, () -> {
                        String propertyName = reader.nextName();
                        CarSvcPropertyConfig config;
                        try {
                            config = readPropertyObject(propertyName, reader);
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("Invalid json config for property: "
                                     + propertyName + ", error: " + e);
                        }
                        if (config == null) {
                            return;
                        }
                        configs.put(config.halPropId, config);
                    });
                });
            }
            return configs;
        } catch (IllegalStateException | IOException e) {
            throw new IllegalArgumentException("Config file: " + path
                    + " does not contain a valid JSON object.", e);
        }
    }

    private @Nullable CarSvcPropertyConfig readPropertyObject(
            String propertyName, JsonReader reader) throws IOException {
        String featureFlag = null;
        boolean deprecated = false;
        int propertyId = 0;
        int vhalPropertyId = 0;
        String description = null;
        ArraySet<Integer> dataEnums = new ArraySet<Integer>();
        List<Integer> dataFlags = new ArrayList<>();
        PermissionCondition readPermission = null;
        PermissionCondition writePermission = null;
        // Starts parsing each field.
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "featureFlag":
                    featureFlag = reader.nextString();
                    break;
                case "deprecated":
                    deprecated = reader.nextBoolean();
                    break;
                case "propertyId":
                    propertyId = reader.nextInt();
                    break;
                case "vhalPropertyId":
                    vhalPropertyId = reader.nextInt();
                    break;
                case "description":
                    description = reader.nextString();
                    break;
                case "dataEnums":
                    reader.beginArray();
                    while (reader.hasNext()) {
                        dataEnums.add(reader.nextInt());
                    }
                    reader.endArray();
                    break;
                case "dataFlags":
                    reader.beginArray();
                    while (reader.hasNext()) {
                        dataFlags.add(reader.nextInt());
                    }
                    reader.endArray();
                    break;
                case "readPermission":
                    try {
                        readPermission = parsePermissionCondition(reader);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(
                                "Failed to parse read permissions for property: " + propertyName
                                + ", error: " + e);
                    }
                    break;
                case "writePermission":
                    try {
                        writePermission = parsePermissionCondition(reader);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(
                                "Failed to parse write permissions for property: " + propertyName
                                + ", error: " + e);
                    }
                    break;
                default:
                    reader.skipValue();
            }
        }
        reader.endObject();

        // Finished parsing each field, now check whether the required fields are present and
        // assign them to config.
        if (deprecated) {
            return null;
        }
        if (featureFlag != null) {
            if (featureFlag.equals(VIC_FLAG_NAME)) {
                if (!mFeatureFlags.androidVicVehicleProperties()) {
                    Slogf.w(TAG, "The required feature flag for property: "
                            + propertyName + " is not enabled, so its config is ignored");
                    return null;
                }
            } else {
                throw new IllegalArgumentException("Unknown feature flag: "
                        + featureFlag + " for property: " + propertyName);
            }
        }
        CarSvcPropertyConfig config = new CarSvcPropertyConfig();
        config.propertyName = propertyName;
        if (description == null) {
            throw new IllegalArgumentException("Missing required description field for property: "
                    + propertyName);
        }
        config.description = description;
        if (propertyId == 0) {
            throw new IllegalArgumentException("Missing required propertyId field for property: "
                    + propertyName);
        }
        config.propertyId = propertyId;
        if (vhalPropertyId != 0) {
            config.halPropId = vhalPropertyId;
        } else {
            config.halPropId = propertyId;
        }
        if (!dataEnums.isEmpty()) {
            config.dataEnums = dataEnums;
        }
        if (!dataFlags.isEmpty()) {
            config.validBitFlag = generateAllCombination(dataFlags);
        }
        if (readPermission == null && writePermission == null) {
            throw new IllegalArgumentException(
                    "No read or write permission specified for: " + propertyName);
        }
        var builder = new PropertyPermissions.Builder();
        if (readPermission != null) {
            builder.setReadPermission(readPermission);
        }
        if (writePermission != null) {
            builder.setWritePermission(writePermission);
        }
        config.permissions = builder.build();
        return config;
    }

    private interface RunanbleWithException {
        void run() throws IOException;
    }

    private static void parseObjectEntry(JsonReader reader, RunanbleWithException forEachEntry)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            forEachEntry.run();
        }
        reader.endObject();
    }

    private static PermissionCondition parsePermissionCondition(JsonReader reader)
            throws IOException {
        // we only have one type, use a list to be effective-final.
        List<String> types = new ArrayList<>();
        List<PermissionCondition> permissions = new ArrayList<>();
        parseObjectEntry(reader, () -> {
            String name = reader.nextName();
            switch (name) {
                case "type":
                    types.add(reader.nextString());
                    break;
                case "value":
                    try {
                        permissions.add(new SinglePermission(reader.nextString()));
                    } catch (IllegalStateException e) {
                        // The value field is not a string, then it must be an array.
                        reader.beginArray();
                        while (reader.hasNext()) {
                            permissions.add(parsePermissionCondition(reader));
                        }
                        reader.endArray();
                    }
                    break;
                default:
                    reader.skipValue();
            }
        });
        if (types.size() == 0) {
            throw new IllegalArgumentException("Missing type field for permission");
        }
        String type = types.get(0);
        if (permissions.size() < 1) {
            throw new IllegalArgumentException("Missing valid value field for permission");
        }
        if (type.equals("single")) {
            return permissions.get(0);
        }
        if (type.equals("anyOf")) {
            return new AnyOfPermissions(permissions.toArray(new PermissionCondition[0]));
        }
        if (type.equals("allOf")) {
            return new AllOfPermissions(permissions.toArray(new PermissionCondition[0]));
        }
        throw new IllegalArgumentException("Invalid permission type: " + type);
    }

    private static boolean checkFormatForAllProperties(HalPropValue propValue) {
        int propId = propValue.getPropId();
        int vehiclePropertyType = propId & VehiclePropertyType.MASK;

        // Records sum size of int32values, floatValue, int64Values, bytes, String
        int sizeOfAllValue = propValue.getInt32ValuesSize() + propValue.getFloatValuesSize()
                + propValue.getInt64ValuesSize() + propValue.getByteValuesSize()
                + propValue.getStringValue().length();
        if (sizeOfAllValue == 0
                && vehiclePropertyType != VehiclePropertyType.FLOAT_VEC
                && vehiclePropertyType != VehiclePropertyType.INT64_VEC
                && vehiclePropertyType != VehiclePropertyType.INT32_VEC) {
            Slogf.e(TAG, "Property value is empty: " + propValue);
            return false;
        }

        switch (vehiclePropertyType) {
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

    private static int generateAllCombination(List<Integer> bitFlags) {
        int combination = bitFlags.get(0);
        for (int i = 1; i < bitFlags.size(); i++) {
            combination |= bitFlags.get(i);
        }
        return combination;
    }
}
