/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.hal.fakevhal;

import android.annotation.Nullable;
import android.car.builtin.util.Slogf;
import android.hardware.automotive.vehicle.AccessForVehicleProperty;
import android.hardware.automotive.vehicle.ChangeModeForVehicleProperty;
import android.hardware.automotive.vehicle.EvStoppingMode;
import android.hardware.automotive.vehicle.PortLocationType;
import android.hardware.automotive.vehicle.RawPropValues;
import android.hardware.automotive.vehicle.TestVendorProperty;
import android.hardware.automotive.vehicle.VehicleAreaConfig;
import android.hardware.automotive.vehicle.VehicleAreaDoor;
import android.hardware.automotive.vehicle.VehicleAreaMirror;
import android.hardware.automotive.vehicle.VehicleAreaSeat;
import android.hardware.automotive.vehicle.VehicleAreaWheel;
import android.hardware.automotive.vehicle.VehicleAreaWindow;
import android.hardware.automotive.vehicle.VehicleHvacFanDirection;
import android.hardware.automotive.vehicle.VehicleLightState;
import android.hardware.automotive.vehicle.VehicleLightSwitch;
import android.hardware.automotive.vehicle.VehiclePropConfig;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.MalformedJsonException;
import android.util.Pair;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.internal.util.IntArray;
import com.android.car.internal.util.LongArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A JSON parser class to get configs and values from JSON config files.
 */
public final class FakeVhalConfigParser {

    private static final String TAG = CarLog.tagFor(FakeVhalConfigParser.class);

    // Defined VehiclePropertyAccess is always 0+
    private static final int ACCESS_NOT_SET = -1;

    private static final String ENUM_CLASS_DIRECTORY = "android.hardware.automotive.vehicle.";
    private static final String JSON_FIELD_NAME_ROOT = "properties";
    private static final String JSON_FIELD_NAME_PROPERTY_ID = "property";
    private static final String JSON_FIELD_NAME_DEFAULT_VALUE = "defaultValue";
    private static final String JSON_FIELD_NAME_AREAS = "areas";
    private static final String JSON_FIELD_NAME_CONFIG_ARRAY = "configArray";
    private static final String JSON_FIELD_NAME_CONFIG_STRING = "configString";
    private static final String JSON_FIELD_NAME_MIN_SAMPLE_RATE = "minSampleRate";
    private static final String JSON_FIELD_NAME_MAX_SAMPLE_RATE = "maxSampleRate";
    private static final String JSON_FIELD_NAME_AREA_ID = "areaId";
    private static final String JSON_FIELD_NAME_INT32_VALUES = "int32Values";
    private static final String JSON_FIELD_NAME_INT64_VALUES = "int64Values";
    private static final String JSON_FIELD_NAME_FLOAT_VALUES = "floatValues";
    private static final String JSON_FIELD_NAME_STRING_VALUE = "stringValue";
    private static final String JSON_FIELD_NAME_MIN_INT32_VALUE = "minInt32Value";
    private static final String JSON_FIELD_NAME_MAX_INT32_VALUE = "maxInt32Value";
    private static final String JSON_FIELD_NAME_MIN_FLOAT_VALUE = "minFloatValue";
    private static final String JSON_FIELD_NAME_MAX_FLOAT_VALUE = "maxFloatValue";
    private static final String JSON_FIELD_NAME_ACCESS = "access";
    private static final String JSON_FIELD_NAME_CHANGE_MODE = "changeMode";
    private static final String JSON_FIELD_NAME_COMMENT = "comment";
    // Following values are defined in PropertyUtils.h file
    // (hardware/interfaces/automotive/vehicle/aidl/impl/utils/common/include/PropertyUtils.h).
    private static final int DOOR_1_RIGHT = VehicleAreaDoor.ROW_1_RIGHT;
    private static final int DOOR_1_LEFT = VehicleAreaDoor.ROW_1_LEFT;
    private static final int DOOR_2_RIGHT = VehicleAreaDoor.ROW_2_RIGHT;
    private static final int DOOR_2_LEFT = VehicleAreaDoor.ROW_2_LEFT;
    private static final int DOOR_REAR = VehicleAreaDoor.REAR;
    private static final int WINDOW_1_LEFT = VehicleAreaWindow.ROW_1_LEFT;
    private static final int WINDOW_1_RIGHT = VehicleAreaWindow.ROW_1_RIGHT;
    private static final int WINDOW_2_LEFT = VehicleAreaWindow.ROW_2_LEFT;
    private static final int WINDOW_2_RIGHT = VehicleAreaWindow.ROW_2_RIGHT;
    private static final int WINDOW_ROOF_TOP_1 = VehicleAreaWindow.ROOF_TOP_1;
    private static final int SEAT_1_RIGHT = VehicleAreaSeat.ROW_1_RIGHT;
    private static final int SEAT_1_LEFT = VehicleAreaSeat.ROW_1_LEFT;
    private static final int SEAT_2_RIGHT = VehicleAreaSeat.ROW_2_RIGHT;
    private static final int SEAT_2_LEFT = VehicleAreaSeat.ROW_2_LEFT;
    private static final int SEAT_2_CENTER = VehicleAreaSeat.ROW_2_CENTER;
    private static final int WHEEL_REAR_RIGHT = VehicleAreaWheel.RIGHT_REAR;
    private static final int WHEEL_REAR_LEFT = VehicleAreaWheel.LEFT_REAR;
    private static final int WHEEL_FRONT_RIGHT = VehicleAreaWheel.RIGHT_FRONT;
    private static final int WHEEL_FRONT_LEFT = VehicleAreaWheel.LEFT_FRONT;
    private static final int CHARGE_PORT_FRONT_LEFT = PortLocationType.FRONT_LEFT;
    private static final int CHARGE_PORT_REAR_LEFT = PortLocationType.REAR_LEFT;
    private static final int FAN_DIRECTION_UNKNOWN = VehicleHvacFanDirection.UNKNOWN;
    private static final int FAN_DIRECTION_FLOOR = VehicleHvacFanDirection.FLOOR;
    private static final int FAN_DIRECTION_FACE = VehicleHvacFanDirection.FACE;
    private static final int FAN_DIRECTION_DEFROST = VehicleHvacFanDirection.DEFROST;
    private static final int FUEL_DOOR_REAR_LEFT = PortLocationType.REAR_LEFT;
    // TODO(b/241984846) Keep SEAT_2_CENTER from HVAC_LEFT here. May have a new design to handle
    //  HVAC zone ids.
    private static final int HVAC_FRONT_ROW = SEAT_1_LEFT | SEAT_1_RIGHT;
    private static final int HVAC_REAR_ROW = SEAT_2_LEFT | SEAT_2_CENTER | SEAT_2_RIGHT;
    private static final int HVAC_LEFT = SEAT_1_LEFT | SEAT_2_LEFT | SEAT_2_CENTER;
    private static final int HVAC_RIGHT = SEAT_1_RIGHT | SEAT_2_RIGHT;
    private static final int HVAC_ALL = HVAC_LEFT | HVAC_RIGHT;
    private static final int LIGHT_STATE_ON = VehicleLightState.ON;
    private static final int LIGHT_STATE_OFF = VehicleLightState.OFF;
    private static final int LIGHT_SWITCH_ON = VehicleLightSwitch.ON;
    private static final int LIGHT_SWITCH_OFF = VehicleLightSwitch.OFF;
    private static final int LIGHT_SWITCH_AUTO = VehicleLightSwitch.AUTOMATIC;
    private static final int EV_STOPPING_MODE_CREEP = EvStoppingMode.CREEP;
    private static final int EV_STOPPING_MODE_ROLL = EvStoppingMode.ROLL;
    private static final int EV_STOPPING_MODE_HOLD = EvStoppingMode.HOLD;
    private static final int MIRROR_DRIVER_LEFT_RIGHT = VehicleAreaMirror.DRIVER_LEFT
                                | VehicleAreaMirror.DRIVER_RIGHT;

    private static final Map<String, Integer> CONSTANTS_BY_NAME = Map.ofEntries(
            Map.entry("DOOR_1_RIGHT", DOOR_1_RIGHT),
            Map.entry("DOOR_1_LEFT", DOOR_1_LEFT),
            Map.entry("DOOR_2_RIGHT", DOOR_2_RIGHT),
            Map.entry("DOOR_2_LEFT", DOOR_2_LEFT),
            Map.entry("DOOR_REAR", DOOR_REAR),
            Map.entry("HVAC_ALL", HVAC_ALL),
            Map.entry("HVAC_LEFT", HVAC_LEFT),
            Map.entry("HVAC_RIGHT", HVAC_RIGHT),
            Map.entry("HVAC_FRONT_ROW", HVAC_FRONT_ROW),
            Map.entry("HVAC_REAR_ROW", HVAC_REAR_ROW),
            Map.entry("VENDOR_EXTENSION_INT_PROPERTY",
                    TestVendorProperty.VENDOR_EXTENSION_INT_PROPERTY),
            Map.entry("VENDOR_EXTENSION_BOOLEAN_PROPERTY",
                    TestVendorProperty.VENDOR_EXTENSION_BOOLEAN_PROPERTY),
            Map.entry("VENDOR_EXTENSION_STRING_PROPERTY",
                    TestVendorProperty.VENDOR_EXTENSION_STRING_PROPERTY),
            Map.entry("VENDOR_EXTENSION_FLOAT_PROPERTY",
                    TestVendorProperty.VENDOR_EXTENSION_FLOAT_PROPERTY),
            Map.entry("WINDOW_1_LEFT", WINDOW_1_LEFT),
            Map.entry("WINDOW_1_RIGHT", WINDOW_1_RIGHT),
            Map.entry("WINDOW_2_LEFT", WINDOW_2_LEFT),
            Map.entry("WINDOW_2_RIGHT", WINDOW_2_RIGHT),
            Map.entry("WINDOW_ROOF_TOP_1", WINDOW_ROOF_TOP_1),
            Map.entry("WINDOW_1_RIGHT_2_LEFT_2_RIGHT", WINDOW_1_RIGHT | WINDOW_2_LEFT
                    | WINDOW_2_RIGHT),
            Map.entry("SEAT_1_LEFT", SEAT_1_LEFT),
            Map.entry("SEAT_1_RIGHT", SEAT_1_RIGHT),
            Map.entry("SEAT_2_LEFT", SEAT_2_LEFT),
            Map.entry("SEAT_2_RIGHT", SEAT_2_RIGHT),
            Map.entry("SEAT_2_CENTER", SEAT_2_CENTER),
            Map.entry("SEAT_2_LEFT_2_RIGHT_2_CENTER", SEAT_2_LEFT | SEAT_2_RIGHT | SEAT_2_CENTER),
            Map.entry("WHEEL_REAR_RIGHT", WHEEL_REAR_RIGHT),
            Map.entry("WHEEL_REAR_LEFT", WHEEL_REAR_LEFT),
            Map.entry("WHEEL_FRONT_RIGHT", WHEEL_FRONT_RIGHT),
            Map.entry("WHEEL_FRONT_LEFT", WHEEL_FRONT_LEFT),
            Map.entry("CHARGE_PORT_FRONT_LEFT", CHARGE_PORT_FRONT_LEFT),
            Map.entry("CHARGE_PORT_REAR_LEFT", CHARGE_PORT_REAR_LEFT),
            Map.entry("FAN_DIRECTION_UNKNOWN", FAN_DIRECTION_UNKNOWN),
            Map.entry("FAN_DIRECTION_FLOOR", FAN_DIRECTION_FLOOR),
            Map.entry("FAN_DIRECTION_FACE", FAN_DIRECTION_FACE),
            Map.entry("FAN_DIRECTION_DEFROST", FAN_DIRECTION_DEFROST),
            Map.entry("FAN_DIRECTION_FACE_FLOOR", FAN_DIRECTION_FACE | FAN_DIRECTION_FLOOR),
            Map.entry("FAN_DIRECTION_FACE_DEFROST", FAN_DIRECTION_FACE | FAN_DIRECTION_DEFROST),
            Map.entry("FAN_DIRECTION_FLOOR_DEFROST", FAN_DIRECTION_FLOOR | FAN_DIRECTION_DEFROST),
            Map.entry("FAN_DIRECTION_FLOOR_DEFROST_FACE", FAN_DIRECTION_FLOOR
                    | FAN_DIRECTION_DEFROST | FAN_DIRECTION_FACE),
            Map.entry("FUEL_DOOR_REAR_LEFT", FUEL_DOOR_REAR_LEFT),
            Map.entry("LIGHT_STATE_ON", LIGHT_STATE_ON),
            Map.entry("LIGHT_STATE_OFF", LIGHT_STATE_OFF),
            Map.entry("LIGHT_SWITCH_ON", LIGHT_SWITCH_ON),
            Map.entry("LIGHT_SWITCH_OFF", LIGHT_SWITCH_OFF),
            Map.entry("LIGHT_SWITCH_AUTO", LIGHT_SWITCH_AUTO),
            Map.entry("EV_STOPPING_MODE_CREEP", EV_STOPPING_MODE_CREEP),
            Map.entry("EV_STOPPING_MODE_ROLL", EV_STOPPING_MODE_ROLL),
            Map.entry("EV_STOPPING_MODE_HOLD", EV_STOPPING_MODE_HOLD),
            Map.entry("MIRROR_DRIVER_LEFT_RIGHT", MIRROR_DRIVER_LEFT_RIGHT),
            Map.entry("ECHO_REVERSE_BYTES", TestVendorProperty.ECHO_REVERSE_BYTES),
            Map.entry("VENDOR_PROPERTY_FOR_ERROR_CODE_TESTING",
                    TestVendorProperty.VENDOR_PROPERTY_FOR_ERROR_CODE_TESTING),
            Map.entry("kMixedTypePropertyForTest", TestVendorProperty.MIXED_TYPE_PROPERTY_FOR_TEST),
            Map.entry("VENDOR_CLUSTER_NAVIGATION_STATE",
                    TestVendorProperty.VENDOR_CLUSTER_NAVIGATION_STATE),
            Map.entry("VENDOR_CLUSTER_REQUEST_DISPLAY",
                    TestVendorProperty.VENDOR_CLUSTER_REQUEST_DISPLAY),
            Map.entry("VENDOR_CLUSTER_SWITCH_UI", TestVendorProperty.VENDOR_CLUSTER_SWITCH_UI),
            Map.entry("VENDOR_CLUSTER_DISPLAY_STATE",
                    TestVendorProperty.VENDOR_CLUSTER_DISPLAY_STATE),
            Map.entry("VENDOR_CLUSTER_REPORT_STATE",
                    TestVendorProperty.VENDOR_CLUSTER_REPORT_STATE),
            Map.entry("PLACEHOLDER_PROPERTY_INT", TestVendorProperty.PLACEHOLDER_PROPERTY_INT),
            Map.entry("PLACEHOLDER_PROPERTY_FLOAT", TestVendorProperty.PLACEHOLDER_PROPERTY_FLOAT),
            Map.entry("PLACEHOLDER_PROPERTY_BOOLEAN",
                    TestVendorProperty.PLACEHOLDER_PROPERTY_BOOLEAN),
            Map.entry("PLACEHOLDER_PROPERTY_STRING",
                    TestVendorProperty.PLACEHOLDER_PROPERTY_STRING)
    );

    /**
     * Reads custom config files and parses the JSON root object whose field name is "properties".
     *
     * @param customConfigFile The custom config JSON file to parse from.
     * @return a list of {@link ConfigDeclaration} storing configs and values for each property.
     * @throws IOException if unable to read the config file.
     * @throws IllegalArgumentException if the config file content is not in expected format.
     */
    public SparseArray<ConfigDeclaration> parseJsonConfig(File customConfigFile) throws
            IOException, IllegalArgumentException {
        // Check if config file exists.
        if (!isFileValid(customConfigFile)) {
            Slogf.w(TAG, "Custom config file: %s is not a valid file.", customConfigFile.getPath());
            return new SparseArray<>(/* initialCapacity= */ 0);
        }

        FileInputStream customConfigFileStream = new FileInputStream(customConfigFile);
        if (customConfigFileStream.available() == 0) {
            Slogf.w(TAG, "Custom config file: %s is empty.", customConfigFile.getPath());
            return new SparseArray<>(/* initialCapacity= */ 0);
        }
        return parseJsonConfig(customConfigFileStream);
    }

    /**
     * Reads default config file from java resources which is in the type of input stream.
     *
     * @param configInputStream The {@link InputStream} to parse from.
     * @return a list of {@link ConfigDeclaration} storing configs and values for each property.
     * @throws IOException if unable to read the config file.
     * @throws IllegalArgumentException if file is not a valid expected JSON file.
     */
    public SparseArray<ConfigDeclaration> parseJsonConfig(InputStream configInputStream)
            throws IOException {
        SparseArray<ConfigDeclaration> allPropConfigs = new SparseArray<>();
        List<String> errors = new ArrayList<>();
        // A wrapper to be effective final. We need the wrapper to be passed into lambda. Only one
        // element is expected.
        List<Boolean> rootFound = new ArrayList<>();

        try (var reader = new JsonReader(new InputStreamReader(configInputStream, "UTF-8"))) {
            reader.setLenient(true);
            int parsed = parseObjectEntry(reader, (String fieldName) -> {
                if (!fieldName.equals(JSON_FIELD_NAME_ROOT)) {
                    reader.skipValue();
                    return;
                }

                rootFound.add(true);
                int propertyParsed = parseArrayEntry(reader, (int index) -> {
                    ConfigDeclaration propConfig = parseEachProperty(reader, index, errors);
                    if (propConfig == null) {
                        errors.add("Unable to parse property config at index " + index);
                        if (allPropConfigs.size() != 0) {
                            errors.add("Last successfully parsed property Id: "
                                    + allPropConfigs.valueAt(allPropConfigs.size() - 1)
                                            .getConfig().prop);
                        }
                        return;
                    }
                    allPropConfigs.put(propConfig.getConfig().prop, propConfig);
                });

                if (propertyParsed < 0) {
                    throw new IllegalArgumentException(JSON_FIELD_NAME_ROOT
                            + " field value is not a valid JSONArray.");
                }
            });
            if (parsed < 0) {
                throw new IllegalArgumentException(
                        "This file does not contain a valid JSONObject.");
            }
        } catch (IllegalStateException | MalformedJsonException e) {
            // Captures all unexpected json parsing error.
            throw new IllegalArgumentException("Invalid json syntax", e);
        }

        if (rootFound.size() == 0) {
            throw new IllegalArgumentException("Missing root field: " + JSON_FIELD_NAME_ROOT);
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }
        return allPropConfigs;
    }

    /**
     * Parses each property for its configs and values.
     *
     * @param reader The reader to parse.
     * @param index The property index.
     * @param errors A list to keep all errors.
     * @return a {@link ConfigDeclaration} instance, null if failed to parse.
     */
    @Nullable
    private ConfigDeclaration parseEachProperty(JsonReader reader, int index, List<String> errors)
            throws IOException {
        int initialErrorCount = errors.size();

        VehiclePropConfig vehiclePropConfig = new VehiclePropConfig();
        vehiclePropConfig.prop = VehicleProperty.INVALID;

        class Wrapper {
            boolean mIsAccessSet;
            boolean mIsChangeModeSet;
            RawPropValues mRawPropValues;
        }

        var wrapper = new Wrapper();
        SparseArray<RawPropValues> defaultValuesByAreaId = new SparseArray<>();

        try {
            int parsed = parseObjectEntry(reader, (String fieldName) -> {
                switch (fieldName) {
                    case JSON_FIELD_NAME_PROPERTY_ID:
                        vehiclePropConfig.prop = parseIntValue(reader, fieldName, errors);
                        break;
                    case JSON_FIELD_NAME_CONFIG_STRING:
                        vehiclePropConfig.configString = parseStringValue(reader, fieldName,
                            errors);
                        break;
                    case JSON_FIELD_NAME_MIN_SAMPLE_RATE:
                        vehiclePropConfig.minSampleRate = parseFloatValue(reader, fieldName,
                                errors);
                        break;
                    case JSON_FIELD_NAME_MAX_SAMPLE_RATE:
                        vehiclePropConfig.maxSampleRate = parseFloatValue(reader, fieldName,
                                errors);
                        break;
                    case JSON_FIELD_NAME_ACCESS:
                        vehiclePropConfig.access = parseIntValue(reader, fieldName, errors);
                        wrapper.mIsAccessSet = true;
                        break;
                    case JSON_FIELD_NAME_CHANGE_MODE:
                        vehiclePropConfig.changeMode = parseIntValue(reader, fieldName, errors);
                        wrapper.mIsChangeModeSet = true;
                        break;
                    case JSON_FIELD_NAME_CONFIG_ARRAY:
                        vehiclePropConfig.configArray = parseIntArrayValue(reader, fieldName,
                                errors);
                        break;
                    case JSON_FIELD_NAME_DEFAULT_VALUE:
                        wrapper.mRawPropValues = parseDefaultValue(reader, errors);
                        break;
                    case JSON_FIELD_NAME_AREAS:
                        vehiclePropConfig.areaConfigs = parseAreaConfigs(reader,
                                defaultValuesByAreaId, errors);
                        break;
                    case JSON_FIELD_NAME_COMMENT:
                        // The "comment" field is used for comment in the config files and is
                        // ignored by the parser.
                        reader.skipValue();
                        break;
                    default:
                        Slogf.w(TAG, "%s is an unknown field name. It didn't get parsed.",
                                fieldName);
                        reader.skipValue();
                }
            });
            if (parsed < 0) {
                errors.add(JSON_FIELD_NAME_ROOT
                        + " array has an invalid JSON element at index " + index);
                return null;
            }
            if (parsed == 0) {
                errors.add("The property object at index" + index + " is empty.");
                return null;
            }
        } catch (IllegalArgumentException e) {
            errors.add("Faild to parse property field, error: " + e.getMessage());
            return null;
        }

        if (vehiclePropConfig.prop == VehicleProperty.INVALID) {
            errors.add("PropId is required for any property.");
            return null;
        }

        int propertyId = vehiclePropConfig.prop;

        if (!wrapper.mIsAccessSet) {
            if (AccessForVehicleProperty.values.containsKey(propertyId)) {
                vehiclePropConfig.access = AccessForVehicleProperty.values
                        .get(propertyId);
            } else {
                errors.add("Access field is not set for this property: " + propertyId);
            }
        }

        if (!wrapper.mIsChangeModeSet) {
            if (ChangeModeForVehicleProperty.values.containsKey(vehiclePropConfig.prop)) {
                vehiclePropConfig.changeMode = ChangeModeForVehicleProperty.values
                        .get(propertyId);
            } else {
                errors.add("ChangeMode field is not set for this property: " + propertyId);
            }
        }

        // If area access is not set, set it to the global access.
        if (vehiclePropConfig.areaConfigs != null) {
            for (int i = 0; i < vehiclePropConfig.areaConfigs.length; i++) {
                if (vehiclePropConfig.areaConfigs[i].access == ACCESS_NOT_SET) {
                    vehiclePropConfig.areaConfigs[i].access = vehiclePropConfig.access;
                }
            }
        }

        if (vehiclePropConfig.areaConfigs == null || vehiclePropConfig.areaConfigs.length == 0) {
            VehicleAreaConfig areaConfig = new VehicleAreaConfig();
            areaConfig.areaId = 0;
            areaConfig.access = vehiclePropConfig.access;
            areaConfig.supportVariableUpdateRate = true;
            vehiclePropConfig.areaConfigs = new VehicleAreaConfig[]{areaConfig};
        }

        if (errors.size() > initialErrorCount) {
            return null;
        }

        return new ConfigDeclaration(vehiclePropConfig, wrapper.mRawPropValues,
                defaultValuesByAreaId);
    }

    /**
     * Parses area configs array.
     *
     * @param reader The reader to parse.
     * @param defaultValuesByAreaId The output default property value by specific area ID.
     * @param errors The list to store all errors.
     * @return a pair of configs and values for one area, null if failed to parse.
     */
    @Nullable
    private VehicleAreaConfig[] parseAreaConfigs(JsonReader reader,
            SparseArray<RawPropValues> defaultValuesByAreaId, List<String> errors)
            throws IOException {
        int initialErrorCount = errors.size();
        List<VehicleAreaConfig> areaConfigs = new ArrayList<>();
        int parsed = 0;

        try {
            parsed = parseArrayEntry(reader, (int index) -> {
                Pair<VehicleAreaConfig, RawPropValues> result = parseAreaConfig(reader, index,
                        errors);
                if (result != null) {
                    areaConfigs.add(result.first);
                    if (result.second != null) {
                        defaultValuesByAreaId.put(result.first.areaId, result.second);
                    }
                }
            });
        } catch (IllegalArgumentException e) {
            errors.add("Failed to parse " + JSON_FIELD_NAME_AREAS + ", error: " + e.getMessage());
            return null;
        }

        if (parsed < 0) {
            errors.add(JSON_FIELD_NAME_AREAS + " doesn't have a valid JSONArray value.");
            return null;
        }
        if (errors.size() > initialErrorCount) {
            return null;
        }
        return areaConfigs.toArray(new VehicleAreaConfig[areaConfigs.size()]);
    }

    /**
     * Parses area JSON config object.
     *
     * @param reader The reader to parse.
     * @param errors The list to store all errors.
     * @return a pair of configs and values for one area, null if failed to parse.
     */
    @Nullable
    private Pair<VehicleAreaConfig, RawPropValues> parseAreaConfig(JsonReader reader,
            int index, List<String> errors) throws IOException {
        int initialErrorCount = errors.size();
        VehicleAreaConfig areaConfig = new VehicleAreaConfig();

        class Wrapper {
            RawPropValues mDefaultValue;
            boolean mHasAreaId;
            boolean mIsAccessSet;
        }
        var wrapper = new Wrapper();
        int parsed = 0;

        try {
            parsed = parseObjectEntry(reader, (String fieldName) -> {
                switch (fieldName) {
                    case JSON_FIELD_NAME_ACCESS:
                        areaConfig.access = parseIntValue(reader, fieldName, errors);
                        wrapper.mIsAccessSet = true;
                        break;
                    case JSON_FIELD_NAME_AREA_ID:
                        areaConfig.areaId = parseIntValue(reader, fieldName, errors);
                        wrapper.mHasAreaId = true;
                        break;
                    case JSON_FIELD_NAME_MIN_INT32_VALUE:
                        areaConfig.minInt32Value = parseIntValue(reader, fieldName, errors);
                        break;
                    case JSON_FIELD_NAME_MAX_INT32_VALUE:
                        areaConfig.maxInt32Value = parseIntValue(reader, fieldName, errors);
                        break;
                    case JSON_FIELD_NAME_MIN_FLOAT_VALUE:
                        areaConfig.minFloatValue = parseFloatValue(reader, fieldName, errors);
                        break;
                    case JSON_FIELD_NAME_MAX_FLOAT_VALUE:
                        areaConfig.maxFloatValue = parseFloatValue(reader, fieldName, errors);
                        break;
                    case JSON_FIELD_NAME_DEFAULT_VALUE:
                        wrapper.mDefaultValue = parseDefaultValue(reader, errors);
                        break;
                    default:
                        Slogf.i(TAG, "%s is an unknown field name. It didn't get parsed.",
                                fieldName);
                        reader.skipValue();
                }
            });
        } catch (IllegalArgumentException e) {
            errors.add("Failed to parse areaConfig, error: " + e.getMessage());
            return null;
        }

        if (parsed < 0) {
            errors.add("Unable to get a JSONObject element for " + JSON_FIELD_NAME_AREAS
                    + " at index " + index);
            return null;
        }
        if (parsed == 0) {
            errors.add("The JSONObject element for " + JSON_FIELD_NAME_AREAS + " at index "
                    + index + " is empty.");
            return null;
        }

        if (!wrapper.mHasAreaId) {
            errors.add(areaConfig + " doesn't have areaId. AreaId is required.");
            return null;
        }

        if (errors.size() > initialErrorCount) {
            return null;
        }

        if (!wrapper.mIsAccessSet) {
            areaConfig.access = ACCESS_NOT_SET;
        }

        return Pair.create(areaConfig, wrapper.mDefaultValue);
    }

    /**
     * Parses the "defaultValue" field of a property object and area property object.
     *
     * @param reader The reader to parse.
     * @param errors The list to store all errors.
     * @return a {@link RawPropValues} object which stores defaultValue, null if failed to parse.
     */
    @Nullable
    private RawPropValues parseDefaultValue(JsonReader reader, List<String> errors)
            throws IOException {
        int initialErrorCount = errors.size();
        RawPropValues rawPropValues = new RawPropValues();
        int parsed = 0;

        try {
            parsed = parseObjectEntry(reader, (String fieldName) -> {
                switch (fieldName) {
                    case JSON_FIELD_NAME_INT32_VALUES: {
                        rawPropValues.int32Values = parseIntArrayValue(reader, fieldName,  errors);
                        break;
                    }
                    case JSON_FIELD_NAME_INT64_VALUES: {
                        rawPropValues.int64Values = parseLongArrayValue(reader, fieldName, errors);
                        break;
                    }
                    case JSON_FIELD_NAME_FLOAT_VALUES: {
                        rawPropValues.floatValues = parseFloatArrayValue(reader, fieldName, errors);
                        break;
                    }
                    case JSON_FIELD_NAME_STRING_VALUE: {
                        rawPropValues.stringValue = parseStringValue(reader, fieldName, errors);
                        break;
                    }
                    default:
                        Slogf.i(TAG, "%s is an unknown field name. It didn't get parsed.",
                                fieldName);
                        reader.skipValue();
                }
            });
        } catch (IllegalArgumentException e) {
            errors.add("Failed to parse " + JSON_FIELD_NAME_DEFAULT_VALUE + ", error: "
                     + e.getMessage());
            return null;
        }

        if (parsed < 0) {
            errors.add(JSON_FIELD_NAME_DEFAULT_VALUE + " doesn't have a valid JSONObject value");
            return null;
        }
        if (parsed == 0) {
            Slogf.w(TAG, JSON_FIELD_NAME_DEFAULT_VALUE + " is empty, assuming no overwrite");
            return null;
        }
        if (errors.size() > initialErrorCount) {
            return null;
        }
        return rawPropValues;
    }

    /**
     * Parses String Json value.
     *
     * @param reader The reader to parse.
     * @param fieldName Field name of JSON object name/value mapping.
     * @param errors The list to store all errors.
     * @return a string of parsed value, null if failed to parse.
     */
    @Nullable
    private String parseStringValue(JsonReader reader, String fieldName,
            List<String> errors) throws IOException {
        try {
            String result = nextStringAdvance(reader);
            if (result.equals("")) {
                errors.add(fieldName + " doesn't have a valid string value.");
                return null;
            }
            return result;
        } catch (Exception e) {
            errors.add(fieldName + " doesn't have a valid string value.");
            return null;
        }
    }

    /**
     * Parses int Json value.
     *
     * @param reader The reader to parse.
     * @param fieldName Field name of JSON object name/value mapping.
     * @param errors The list to store all errors.
     * @return a value as int, 0 if failed to parse.
     */
    private int parseIntValue(JsonReader reader,  String fieldName, List<String> errors)
            throws IOException {
        if (isString(reader)) {
            String constantValue = nextStringAdvance(reader);
            return parseConstantValue(constantValue, errors);
        }
        try {
            return nextIntAdvance(reader);
        } catch (Exception e) {
            errors.add(fieldName + " doesn't have a valid int value.");
            return 0;
        }
    }

    /**
     * Parses float Json value.
     *
     * @param reader The reader to parse.
     * @param fieldName Field name of JSON object name/value mapping.
     * @param errors The list to store all errors.
     * @return the parsed value as float, {@code 0f} if failed to parse.
     */
    private float parseFloatValue(JsonReader reader, String fieldName, List<String> errors)
            throws IOException {
        if (isString(reader)) {
            String constantValue = nextStringAdvance(reader);
            return (float) parseConstantValue(constantValue, errors);
        }
        try {
            return (float) nextDoubleAdvance(reader);
        } catch (Exception e) {
            errors.add(fieldName + " doesn't have a valid float value. " + e.getMessage());
            return 0f;
        }
    }

    /**
     * Parses enum class constant.
     *
     * @param stringValue The constant string to be parsed.
     * @param errors A list to keep all errors.
     * @return the int value of an enum constant, 0 if the constant format is invalid.
     */
    private int parseConstantValue(String stringValue, List<String> errors) {
        String[] propIdStrings = stringValue.split("::");
        if (propIdStrings.length != 2 || propIdStrings[0].isEmpty() || propIdStrings[1].isEmpty()) {
            errors.add(stringValue + " must in the form of <EnumClassName>::<ConstantName>.");
            return 0;
        }
        String enumClassName = ENUM_CLASS_DIRECTORY + propIdStrings[0];
        String constantName = propIdStrings[1];

        if (Objects.equals(propIdStrings[0], "Constants")) {
            if (CONSTANTS_BY_NAME.containsKey(constantName)) {
                return CONSTANTS_BY_NAME.get(constantName);
            }
            errors.add(constantName + " is not a valid constant name.");
            return 0;
        }

        Class enumClass;
        try {
            enumClass = Class.forName(enumClassName);
        } catch (ClassNotFoundException e) {
            errors.add(enumClassName + " is not a valid class name. " + e.getMessage());
            return 0;
        }
        Field[] fields = enumClass.getDeclaredFields();
        for (Field field : fields) {
            if (constantName.equals(field.getName())) {
                try {
                    return field.getInt(enumClass);
                } catch (Exception e) {
                    errors.add("Failed to get int value of " + enumClass + "." + constantName
                            + " " + e.getMessage());
                    return 0;
                }
            }
        }
        errors.add(enumClass + " doesn't have a constant field with name " + constantName);
        return 0;
    }

    /**
     * Parses a intger JSON array.
     *
     * @param reader The reader to parse.
     * @param fieldName Field name of JSON object name/value mapping.
     * @param errors The list to store all errors.
     * @return an int array of default values, null if failed to parse.
     */
    @Nullable
    private int[] parseIntArrayValue(JsonReader reader, String fieldName, List<String> errors)
            throws IOException {
        int initialErrorCount = errors.size();
        var values = new IntArray();

        try {
            int parsed = parseArrayEntry(reader, (int index) -> {
                if (isString(reader)) {
                    values.add(parseConstantValue(nextStringAdvance(reader), errors));
                    return;
                }
                try {
                    values.add(nextIntAdvance(reader));
                } catch (Exception e) {
                    errors.add(fieldName + " doesn't have a valid int value at index " + index + " "
                            + e.getMessage());
                }
            });
            if (parsed < 0) {
                errors.add(fieldName + " doesn't have a valid JSONArray value.");
                return null;
            }
        } catch (IllegalArgumentException e) {
            errors.add("Failed to parse field: " + fieldName + ", error: " + e.getMessage());
            return null;
        }

        if (errors.size() > initialErrorCount) {
            return null;
        }

        return values.toArray();
    }

    /**
     * Parses a long JSON array.
     *
     * @param reader The reader to parse.
     * @param fieldName Field name of JSON object name/value mapping.
     * @param errors The list to store all errors.
     * @return a long array of default values, null if failed to parse.
     */
    @Nullable
    private long[] parseLongArrayValue(JsonReader reader, String fieldName, List<String> errors)
            throws IOException {
        int initialErrorCount = errors.size();
        var values = new LongArray();

        try {
            int parsed = parseArrayEntry(reader, (int index) -> {
                if (isString(reader)) {
                    values.add(parseConstantValue(nextStringAdvance(reader), errors));
                    return;
                }
                try {
                    values.add(nextLongAdvance(reader));
                } catch (Exception e) {
                    errors.add(fieldName + " doesn't have a valid long value at index " + index
                            + " " + e.getMessage());
                }
            });
            if (parsed < 0) {
                errors.add(fieldName + " doesn't have a valid JSONArray value.");
                return null;
            }
        } catch (IllegalArgumentException e) {
            errors.add("Failed to parse field: " + fieldName + ", error: " + e.getMessage());
            return null;
        }

        if (errors.size() > initialErrorCount) {
            return null;
        }

        return values.toArray();
    }

    /**
     * Parses a float JSON array.
     *
     * @param reader The reader to parse.
     * @param fieldName Field name of JSON object name/value mapping.
     * @param errors The list to store all errors.
     * @return a float array of default value, null if failed to parse.
     */
    @Nullable
    private float[] parseFloatArrayValue(JsonReader reader, String fieldName, List<String> errors)
            throws IOException {
        int initialErrorCount = errors.size();
        List<Float> values = new ArrayList<>();

        try {
            int parsed = parseArrayEntry(reader, (int index) -> {
                if (isString(reader)) {
                    values.add((float) parseConstantValue(nextStringAdvance(reader), errors));
                    return;
                }
                try {
                    values.add((float) nextDoubleAdvance(reader));
                } catch (Exception e) {
                    errors.add(fieldName + " doesn't have a valid float value at index " + index
                            + " " + e.getMessage());
                }
            });
            if (parsed < 0) {
                errors.add(fieldName + " doesn't have a valid JSONArray value.");
                return null;
            }
        } catch (IllegalArgumentException e) {
            errors.add("Failed to parse field: " + fieldName + ", error: " + e.getMessage());
            return null;
        }

        if (errors.size() > initialErrorCount) {
            return null;
        }
        var valueArray = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            valueArray[i] = values.get(i);
        }
        return valueArray;
    }

    /**
     * Checks if the next token is a string.
     *
     * @param reader The reader.
     * @return {@code true} if the next token is a string.
     */
    private boolean isString(JsonReader reader) throws IOException {
        return reader.peek() == JsonToken.STRING;
    }

    /**
     * Checks if config file exists and has read permission.
     *
     * @param configFile Either default or custom config JSON file.
     * @return A boolean value to determine if config file is valid.
     */
    private boolean isFileValid(File configFile) {
        return configFile.exists() && configFile.isFile();
    }

    private interface RunanbleWithException {
        void run(String fieldName) throws IOException;
    }

    /**
     * Iterates through entries in a JSONObject.
     *
     * If reader is not currently parsing an object, the cursor will still be advanced.
     *
     * @param reader The reader.
     * @param forEachEntry The invokable for each entry.
     * @return How many entries are iterated or -1 if the reader is not currently parsing an object.
     */
    private static int parseObjectEntry(JsonReader reader, RunanbleWithException forEachEntry)
            throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue();
            return -1;
        }
        int i = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            forEachEntry.run(reader.nextName());
            i++;
        }
        reader.endObject();
        return i;
    }

    private interface RunanbleIndexWithException {
        void run(int index) throws IOException;
    }

    /**
     * Iterates through entries in a JSONArray.
     *
     * If reader is not currently parsing an array, the cursor will still be advanced.
     *
     * @param reader The reader.
     * @param forEachEntry The invokable for each entry.
     * @return How many entries are iterated or -1 if the reader is not currently parsing an array.
     */
    private static int parseArrayEntry(JsonReader reader, RunanbleIndexWithException forEachEntry)
            throws IOException {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue();
            return -1;
        }
        reader.beginArray();
        int i = 0;
        while (reader.hasNext()) {
            forEachEntry.run(i++);
        }
        reader.endArray();
        return i;
    }

    private static int nextIntAdvance(JsonReader reader) throws IOException {
        try {
            return reader.nextInt();
        } catch (RuntimeException | IOException e) {
            reader.skipValue();
            throw e;
        }
    }

    private static long nextLongAdvance(JsonReader reader) throws IOException {
        try {
            return reader.nextLong();
        } catch (RuntimeException | IOException e) {
            reader.skipValue();
            throw e;
        }
    }

    private static double nextDoubleAdvance(JsonReader reader) throws IOException {
        try {
            return reader.nextDouble();
        } catch (RuntimeException | IOException e) {
            reader.skipValue();
            throw e;
        }
    }

    private static String nextStringAdvance(JsonReader reader) throws IOException {
        try {
            return reader.nextString();
        } catch (RuntimeException | IOException e) {
            reader.skipValue();
            throw e;
        }
    }
}
