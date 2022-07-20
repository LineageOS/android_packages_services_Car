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

package com.android.car.fakevhal;

import android.annotation.Nullable;
import android.car.builtin.util.Slogf;
import android.hardware.automotive.vehicle.RawPropValues;
import android.hardware.automotive.vehicle.VehicleAreaConfig;
import android.hardware.automotive.vehicle.VehiclePropConfig;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.util.Pair;
import android.util.SparseArray;

import com.android.car.CarLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * A JSON parser class to get configs and values from JSON config files.
 */
public final class FakeVhalConfigParser {

    private static final String TAG = CarLog.tagFor(FakeVhalConfigParser.class);
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

    /**
     * Reads config file and parses the JSON root object whose field name is "properties".
     *
     * @param configFile The JSON file to parse from.
     * @param isFileOpt Whether the config file is optional.
     * @return a list of {@link ConfigDeclaration} storing configs and values for each property.
     * @throws IOException if unable to read the config file.
     * @throws IllegalArgumentException if file is invalid JSON or when a JSONException is caught.
     */
    public List<ConfigDeclaration> parseJsonConfig(File configFile, boolean isFileOpt) throws
            IOException {
        // Check if config file exists.
        if (!isFileValid(configFile)) {
            if (!isFileOpt) {
                throw new IllegalArgumentException("Missing required file at "
                            + configFile.toPath());
            }
            return new ArrayList<>();
        }

        // Read config file.
        byte[] allBytesInFile = Files.readAllBytes(configFile.toPath());

        // Parse JSON root object.
        JSONObject configJsonObject;
        JSONArray configJsonArray;
        try {
            configJsonObject = new JSONObject(new String(allBytesInFile));
        } catch (JSONException e) {
            throw new IllegalArgumentException("This file does not contain a valid JSONObject.", e);
        }
        try {
            configJsonArray = configJsonObject.getJSONArray(JSON_FIELD_NAME_ROOT);
        } catch (JSONException e) {
            throw new IllegalArgumentException(JSON_FIELD_NAME_ROOT + " field value is not a valid "
                + "JSONArray.", e);
        }

        List<ConfigDeclaration> allPropConfigs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        // Parse each property.
        for (int i = 0; i < configJsonArray.length(); i++) {
            JSONObject propertyObject = configJsonArray.optJSONObject(i);
            if (propertyObject == null) {
                errors.add(JSON_FIELD_NAME_ROOT + " array has an invalid JSON element at index "
                        + i);
                continue;
            }
            ConfigDeclaration propConfig = parseEachProperty(propertyObject, errors);
            if (propConfig == null) {
                errors.add("Unable to parse JSON object: " + propertyObject + " at index " + i);
                if (!allPropConfigs.isEmpty()) {
                    errors.add("Last successfully parsed property Id: "
                            + allPropConfigs.get(allPropConfigs.size() - 1).getConfig().prop);
                }
                continue;
            }
            allPropConfigs.add(propConfig);
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }
        return allPropConfigs;
    }

    /**
     * Parses each property for its configs and values.
     *
     * @param propertyObject A JSONObject which stores all configs and values of a property.
     * @param errors A list to keep all errors.
     * @return a {@link ConfigDeclaration} instance, null if failed to parse.
     */
    @Nullable
    private ConfigDeclaration parseEachProperty(JSONObject propertyObject, List<String> errors) {
        int initialErrorCount = errors.size();
        List<String> fieldNames = getFieldNames(propertyObject);

        if (fieldNames == null) {
            errors.add("The JSONObject " + propertyObject + " is empty.");
            return null;
        }

        VehiclePropConfig vehiclePropConfig = new VehiclePropConfig();
        vehiclePropConfig.prop = VehicleProperty.INVALID;
        List<VehicleAreaConfig> areaConfigs = new ArrayList<>();
        RawPropValues rawPropValues = new RawPropValues();
        SparseArray<RawPropValues> defaultValuesByAreaId = new SparseArray<>();

        for (int i = 0; i < fieldNames.size(); i++) {
            String fieldName = fieldNames.get(i);
            switch (fieldName) {
                case JSON_FIELD_NAME_PROPERTY_ID:
                    vehiclePropConfig.prop = parseIntValue(propertyObject, fieldName, errors);
                    break;
                case JSON_FIELD_NAME_CONFIG_STRING:
                    vehiclePropConfig.configString = parseStringValue(propertyObject, fieldName,
                        errors);
                    break;
                case JSON_FIELD_NAME_MIN_SAMPLE_RATE:
                    vehiclePropConfig.minSampleRate = parseFloatValue(propertyObject, fieldName,
                        errors);
                    break;
                case JSON_FIELD_NAME_MAX_SAMPLE_RATE:
                    vehiclePropConfig.maxSampleRate = parseFloatValue(propertyObject, fieldName,
                        errors);
                    break;
                case JSON_FIELD_NAME_ACCESS:
                    vehiclePropConfig.access = parseIntValue(propertyObject, fieldName, errors);
                    break;
                case JSON_FIELD_NAME_CHANGE_MODE:
                    vehiclePropConfig.changeMode = parseIntValue(propertyObject, fieldName, errors);
                    break;
                case JSON_FIELD_NAME_CONFIG_ARRAY:
                    JSONArray configArray = propertyObject.optJSONArray(fieldName);
                    if (configArray == null) {
                        errors.add(fieldName + " doesn't have a mapped JSONArray value.");
                        continue;
                    }
                    vehiclePropConfig.configArray = parseIntArrayValue(configArray, errors);
                    break;
                case JSON_FIELD_NAME_DEFAULT_VALUE:
                    JSONObject defaultValueObject = propertyObject.optJSONObject(fieldName);
                    if (defaultValueObject == null) {
                        errors.add(fieldName + " doesn't have a mapped value");
                        continue;
                    }
                    rawPropValues = parseDefaultValue(defaultValueObject, errors);
                    break;
                case JSON_FIELD_NAME_AREAS:
                    JSONArray areas = propertyObject.optJSONArray(fieldName);
                    if (areas == null) {
                        errors.add(fieldName + " doesn't have a mapped array value.");
                        continue;
                    }
                    for (int j = 0; j < areas.length(); j++) {
                        JSONObject areaObject = areas.optJSONObject(j);
                        if (areaObject == null) {
                            errors.add("Unable to get a JSONObject element for " + fieldName
                                    + " at index " + j);
                            continue;
                        }
                        Pair<VehicleAreaConfig, RawPropValues> result =
                                parseAreaConfig(areaObject, errors);
                        if (result != null) {
                            areaConfigs.add(result.first);
                            if (result.second != null) {
                                defaultValuesByAreaId.put(result.first.areaId, result.second);
                            }
                        }
                    }
                    vehiclePropConfig.areaConfigs = areaConfigs.toArray(new VehicleAreaConfig[0]);
                    break;
                default:
                    Slogf.i(TAG, fieldName + " is an unknown field name. It didn't get parsed.");
            }
        }

        if (vehiclePropConfig.prop == VehicleProperty.INVALID) {
            errors.add(propertyObject + " doesn't have propId. PropId is required.");
            return null;
        }

        if (errors.size() > initialErrorCount) {
            return null;
        }

        return new ConfigDeclaration(vehiclePropConfig, rawPropValues, defaultValuesByAreaId);
    }

    /**
     * Parses area JSON config object.
     *
     * @param areaObject A JSONObject of field name "areas".
     * @param errors The list to store all errors.
     * @return a pair of configs and values for one area, null if failed to parse.
     */
    @Nullable
    private Pair<VehicleAreaConfig, RawPropValues> parseAreaConfig(JSONObject areaObject,
            List<String> errors) {
        int initialErrorCount = errors.size();
        List<String> fieldNames = getFieldNames(areaObject);

        if (fieldNames == null) {
            errors.add("The JSONObject " + areaObject + " is empty.");
            return null;
        }

        VehicleAreaConfig areaConfig = new VehicleAreaConfig();
        RawPropValues defaultValue = null;
        boolean hasAreaId = false;

        for (int i = 0; i < fieldNames.size(); i++) {
            String fieldName = fieldNames.get(i);
            switch (fieldName) {
                case JSON_FIELD_NAME_AREA_ID:
                    areaConfig.areaId = parseIntValue(areaObject, fieldName, errors);
                    hasAreaId = true;
                    break;
                case JSON_FIELD_NAME_MIN_INT32_VALUE:
                    areaConfig.minInt32Value = parseIntValue(areaObject, fieldName, errors);
                    break;
                case JSON_FIELD_NAME_MAX_INT32_VALUE:
                    areaConfig.maxInt32Value = parseIntValue(areaObject, fieldName, errors);
                    break;
                case JSON_FIELD_NAME_MIN_FLOAT_VALUE:
                    areaConfig.minFloatValue = parseFloatValue(areaObject, fieldName, errors);
                    break;
                case JSON_FIELD_NAME_MAX_FLOAT_VALUE:
                    areaConfig.maxFloatValue = parseFloatValue(areaObject, fieldName, errors);
                    break;
                case JSON_FIELD_NAME_DEFAULT_VALUE:
                    defaultValue = parseDefaultValue(areaObject.optJSONObject(fieldName), errors);
                    break;
                default:
                    Slogf.i(TAG, fieldName + " is an unknown field name."
                            + " It didn't get parsed.");
            }
        }

        if (!hasAreaId) {
            errors.add(areaObject + " doesn't have areaId. AreaId is required.");
            return null;
        }

        if (errors.size() > initialErrorCount) {
            return null;
        }

        return Pair.create(areaConfig, defaultValue);
    }

    /**
     * Parses the "defaultValue" field of a property object and area property object.
     *
     * @param defaultValue The defaultValue JSONObject to be parsed.
     * @param errors The list to store all errors.
     * @return a {@link RawPropValues} object which stores defaultValue, null if failed to parse.
     */
    @Nullable
    private RawPropValues parseDefaultValue(JSONObject defaultValue, List<String> errors) {
        int initialErrorCount = errors.size();
        List<String> fieldNames = getFieldNames(defaultValue);

        if (fieldNames == null) {
            errors.add("The JSONObject " + defaultValue + " is empty.");
            return null;
        }

        RawPropValues rawPropValues = new RawPropValues();

        for (int i = 0; i < fieldNames.size(); i++) {
            String fieldName = fieldNames.get(i);
            switch (fieldName) {
                case JSON_FIELD_NAME_INT32_VALUES: {
                    JSONArray int32Values = defaultValue.optJSONArray(fieldName);
                    if (int32Values == null) {
                        errors.add("Failed to parse the field name: " + fieldName + " for "
                                + "defaultValueObject: " + defaultValue);
                        continue;
                    }
                    rawPropValues.int32Values = parseIntArrayValue(int32Values, errors);
                    break;
                }
                case JSON_FIELD_NAME_INT64_VALUES: {
                    JSONArray int64Values = defaultValue.optJSONArray(fieldName);
                    if (int64Values == null) {
                        errors.add("Failed to parse the field name: " + fieldName + " for "
                                + "defaultValueObject: " + defaultValue);
                        continue;
                    }
                    rawPropValues.int64Values = parseLongArrayValue(int64Values, errors);
                    break;
                }
                case JSON_FIELD_NAME_FLOAT_VALUES: {
                    JSONArray floatValues = defaultValue.optJSONArray(fieldName);
                    if (floatValues == null) {
                        errors.add("Failed to parse the field name: " + fieldName + " for "
                                + "defaultValueObject: " + defaultValue);
                        continue;
                    }
                    rawPropValues.floatValues = parseFloatArrayValue(floatValues, errors);
                    break;
                }
                case JSON_FIELD_NAME_STRING_VALUE: {
                    rawPropValues.stringValue = parseStringValue(defaultValue, fieldName, errors);
                    break;
                }
                default:
                    Slogf.i(TAG, fieldName + " is an unknown field name. "
                            + "It didn't get parsed.");
            }
        }

        if (errors.size() > initialErrorCount) {
            return null;
        }
        return rawPropValues;
    }

    /**
     * Parses String Json value.
     *
     * @param parentObject The JSONObject will be parsed.
     * @param fieldName Field name of JSON object name/value mapping.
     * @param errors The list to store all errors.
     * @return a string of parsed value, null if failed to parse.
     */
    @Nullable
    private String parseStringValue(JSONObject parentObject, String fieldName,
            List<String> errors) {
        String value = parentObject.optString(fieldName);
        if (value.equals("")) {
            errors.add(fieldName + " doesn't have a mapped value.");
            return null;
        }
        return value;
    }

    /**
     * Parses int Json value.
     *
     * @param parentObject The JSONObject will be parsed.
     * @param fieldName Field name of JSON object name/value mapping.
     * @param errors The list to store all errors.
     * @return a value as int, 0 if failed to parse.
     */
    private int parseIntValue(JSONObject parentObject, String fieldName, List<String> errors) {
        if (isString(parentObject, fieldName)) {
            String constantValue;
            try {
                constantValue = parentObject.getString(fieldName);
                return parseConstantValue(constantValue, errors);
            } catch (JSONException e) {
                errors.add(fieldName + " doesn't have a mapped string value. " + e.getMessage());
                return 0;
            }
        }
        try {
            return parentObject.getInt(fieldName);
        } catch (JSONException e) {
            errors.add(fieldName + " doesn't have a mapped int value. " + e.getMessage());
            return 0;
        }
    }

    /**
     * Parses float Json value.
     *
     * @param parentObject The JSONObject will be parsed.
     * @param fieldName Field name of JSON object name/value mapping.
     * @param errors The list to store all errors.
     * @return the parsed value as float, {@code 0f} if failed to parse.
     */
    private float parseFloatValue(JSONObject parentObject, String fieldName, List<String> errors) {
        if (isString(parentObject, fieldName)) {
            String constantValue;
            try {
                constantValue = parentObject.getString(fieldName);
            } catch (JSONException e) {
                errors.add(fieldName + " doesn't have a mapped string value. " + e.getMessage());
                return 0f;
            }
            return (float) parseConstantValue(constantValue, errors);
        }
        try {
            return (float) parentObject.getDouble(fieldName);
        } catch (JSONException e) {
            errors.add(fieldName + " doesn't have a mapped float value. " + e.getMessage());
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
        String enumClassName = propIdStrings[0];
        String constantName = propIdStrings[1];

        // TODO(b/238808998) Handle Constants enum class and add
        //  Map<"DOOR_1_LEFT", VehicleAreaDoor::ROW_1_LEFT>
        Class enumClass;
        try {
            enumClass = Class.forName(enumClassName);
        } catch (ClassNotFoundException e) {
            errors.add(enumClassName + " is not a valid class name. " + e.getMessage());
            return 0;
        }
        Field[] fields = enumClass.getDeclaredFields();
        List<String> constants = new ArrayList<>();
        for (int i = 0; i < fields.length; i++) {
            constants.add(fields[i].getName());
        }
        if (!constants.contains(constantName)) {
            errors.add(constantName + " is not valid.");
            return 0;
        }
        return Enum.valueOf(enumClass, constantName).ordinal();
    }

    /**
     * Parses int values in a {@link JSONArray}.
     *
     * @param values The JSON array to be parsed.
     * @param errors The list to store all errors.
     * @return an int array of default values, null if failed to parse.
     */
    @Nullable
    private int[] parseIntArrayValue(JSONArray values, List<String> errors) {
        int initialErrorCount = errors.size();
        int[] valueArray = new int[values.length()];

        for (int i = 0; i < values.length(); i++) {
            if (isString(values, i)) {
                String stringValue = values.optString(i);
                valueArray[i] = parseConstantValue(stringValue, errors);
            } else {
                try {
                    valueArray[i] = values.getInt(i);
                } catch (JSONException e) {
                    errors.add(values + " doesn't have a mapped int value at index " + i + " "
                            + e.getMessage());
                }
            }
        }

        if (errors.size() > initialErrorCount) {
            return null;
        }

        return valueArray;
    }

    /**
     * Parses long values in a {@link JSONArray}.
     *
     * @param values The JSON array to be parsed.
     * @param errors The list to store all errors.
     * @return a long array of default values, null if failed to parse.
     */
    @Nullable
    private long[] parseLongArrayValue(JSONArray values, List<String> errors) {
        int initialErrorCount = errors.size();
        long[] valueArray = new long[values.length()];

        for (int i = 0; i < values.length(); i++) {
            if (isString(values, i)) {
                String stringValue = values.optString(i);
                valueArray[i] = parseConstantValue(stringValue, errors);
            } else {
                try {
                    valueArray[i] = values.getLong(i);
                } catch (JSONException e) {
                    errors.add(values + " doesn't have a mapped long value at index " + i + " "
                            + e.getMessage());
                }
            }
        }

        if (errors.size() > initialErrorCount) {
            return null;
        }

        return valueArray;
    }

    /**
     * Parses float values in a {@link JSONArray}.
     *
     * @param values The JSON array to be parsed.
     * @param errors The list to store all errors.
     * @return a float array of default value, null if failed to parse.
     */
    @Nullable
    private float[] parseFloatArrayValue(JSONArray values, List<String> errors) {
        int initialErrorCount = errors.size();
        float[] valueArray = new float[values.length()];

        for (int i = 0; i < values.length(); i++) {
            if (isString(values, i)) {
                String stringValue = values.optString(i);
                valueArray[i] = parseConstantValue(stringValue, errors);
            } else {
                try {
                    valueArray[i] = (float) values.getDouble(i);
                } catch (JSONException e) {
                    errors.add(values + " doesn't have a mapped float value at index " + i + " "
                            + e.getMessage());
                }
            }
        }

        if (errors.size() > initialErrorCount) {
            return null;
        }
        return valueArray;
    }

    /**
     * Checks if parentObject contains field and the field is a String.
     *
     * @param parentObject The JSONObject containing the field.
     * @param fieldName The name for the JSON field.
     * @return {@code true} if parent object contains this field and the value is string.
     */
    private boolean isString(JSONObject parentObject, String fieldName) {
        return parentObject.opt(fieldName) != null && parentObject.opt(fieldName).getClass()
                == String.class;
    }

    /**
     * Checks if the JSON array contains the index and the element at the index is a String.
     *
     * @param jsonArray The JSON array to be checked.
     * @param index The index of the JSON array element which will be checked.
     * @return {@code true} if the JSON array has value at index and the value is string.
     */
    private boolean isString(JSONArray jsonArray, int index) {
        return jsonArray.opt(index) != null && jsonArray.opt(index).getClass() == String.class;
    }

    /**
     * Gets all field names of a {@link JSONObject}.
     *
     * @param jsonObject The JSON object to read field names from.
     * @return a list of all the field names of an JSONObject, null if the object is empty.
     */
    @Nullable
    private List<String> getFieldNames(JSONObject jsonObject) {
        JSONArray names = jsonObject.names();

        if (names == null) {
            return null;
        }

        List<String> fieldNames = new ArrayList<>();
        for (int i = 0; i < names.length(); i++) {
            String fieldName = names.optString(i);
            if (fieldName != null) {
                fieldNames.add(fieldName);
            }
        }
        return fieldNames;
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
}
