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

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.hardware.automotive.vehicle.RawPropValues;
import android.hardware.automotive.vehicle.VehicleAreaConfig;
import android.hardware.automotive.vehicle.VehicleAreaDoor;
import android.hardware.automotive.vehicle.VehicleAreaSeat;
import android.hardware.automotive.vehicle.VehiclePropConfig;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.VehiclePropertyChangeMode;
import android.hardware.automotive.vehicle.VehicleSeatOccupancyState;
import android.util.SparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(AndroidJUnit4.class)
public class FakeVhalConfigParserUnitTest {
    private FakeVhalConfigParser mFakeVhalConfigParser;

    @Before
    public void setUp() {
        mFakeVhalConfigParser = new FakeVhalConfigParser();
    }

    @Test
    public void testConfigFileNotExist() throws Exception {
        File tempFile = new File("NotExist.json");

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("Missing required file");
    }

    @Test
    public void testConfigFileNotExistAndFileIsOptional() throws Exception {
        File tempFile = new File("NotExist.json");

        assertThat(mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ true))
                .isEmpty();
    }

    @Test
    public void testConfigFileHaveInvalidJsonObject() throws Exception {
        String fileContent = "This is a config file.";
        File tempFile = createTempFile(fileContent);

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ true));

        assertThat(thrown).hasMessageThat().contains("This file does not contain a valid "
                + "JSONObject.");
    }

    @Test
    public void testConfigFileRootIsNotArray() throws Exception {
        String jsonString = "{\"properties\": 123}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ true));

        assertThat(thrown).hasMessageThat().contains("field value is not a valid JSONArray.");
    }

    @Test
    public void testConfigFileRootHasElementIsNotJsonObject() throws Exception {
        String jsonString = "{\"properties\": [{}, 123]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ true));

        assertThat(thrown).hasMessageThat().contains("properties array has an invalid JSON element"
                + " at index 1");
    }

    @Test
    public void testParseEachPropertyJsonObjectIsEmpty() throws Exception {
        String jsonString = "{\"properties\": [{}]}";
        JSONObject jsonObject = new JSONObject(jsonString);
        Object propertyObject = jsonObject.optJSONArray("properties").optJSONObject(0);
        File tempFile = createTempFile(jsonObject.toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("The JSONObject " + propertyObject
                + " is empty.");
    }

    @Test
    public void testParsePropertyIdWithIntValue() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 123}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        int propId = mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false).get(0)
                .getConfig().prop;

        assertThat(propId).isEqualTo(123);
    }

    @Test
    public void testParsePropertyIdFieldNotExist() throws Exception {
        String jsonString = "{\"properties\": [{\"NotAPropIdFieldName\": 123}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains(" doesn't have propId. PropId is required.");
    }

    @Test
    public void testParsePropertyIdValueNull() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": NULL}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("property doesn't have a mapped value.");
    }

    @Test
    public void testParsePropertyIdWithWrongValueType() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 12.3f}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("property doesn't have a mapped int value.");
    }

    @Test
    public void testParsePropertyIdWithStringValue() throws Exception {
        String jsonString = "{\"properties\": "
                + "[{\"property\": \"VehicleProperty::INFO_FUEL_CAPACITY\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        int propId = mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false).get(0)
                .getConfig().prop;

        assertThat(propId).isEqualTo(VehicleProperty.INFO_FUEL_CAPACITY);
    }

    @Test
    public void testParsePropertyIdWithEmptyStringValue() throws Exception {
        String jsonString = "{\"properties\": " + "[{\"property\": \"\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("must in the form of "
                + "<EnumClassName>::<ConstantName>.");
    }

    @Test
    public void testParsePropertyIdWithIntStringValue() throws Exception {
        String jsonString = "{\"properties\": " + "[{\"property\": \"1234\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("must in the form of "
                + "<EnumClassName>::<ConstantName>.");
    }

    @Test
    public void testParsePropertyIdWithWrongStringFormat() throws Exception {
        String jsonString = "{\"properties\": "
                + "[{\"property\": \"VehicleProperty:INFO_FUEL_CAPACITY\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("must in the form of "
                + "<EnumClassName>::<ConstantName>.");
    }

    @Test
    public void testParsePropertyIdEnumClassNameNotExist() throws Exception {
        String jsonString = "{\"properties\": "
                + "[{\"property\": \"NotExistEnumClass::INFO_FUEL_CAPACITY\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("is not a valid class name.");
    }

    @Test
    public void testParsePropertyIdEnumClassFieldNameNotExist() throws Exception {
        String jsonString = "{\"properties\": "
                + "[{\"property\": \"VehicleProperty::NOT_EXIST\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("VehicleProperty doesn't have a constant field"
                + " with name NOT_EXIST");
    }

    @Test
    public void testParsePropertyIdEnumClassFieldTypeIsNotInt() throws Exception {
        String jsonString = "{\"properties\": "
                + "[{\"property\": \"IVehicle::INVALID_MEMORY_ID\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("Failed to get int value of interface "
                + "android.hardware.automotive.vehicle.IVehicle.INVALID_MEMORY_ID");
    }

    @Test
    public void testParsePropertyIdFromConstantsMap() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": \"Constants::DOOR_1_LEFT\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        int propId = mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false).get(0)
                .getConfig().prop;

        assertThat(propId).isEqualTo(VehicleAreaDoor.ROW_1_LEFT);
    }

    @Test
    public void testParsePropertyIdFromConstantsMapWithWrongConstantsName() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": \"Constants::ROW_1_LEFT\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("ROW_1_LEFT is not a valid constant name.");
    }

    @Test
    public void testParseStringValueIsEmpty() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 123, \"configString\": \"\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("configString doesn't have a mapped value.");
    }

    @Test
    public void testParseFloatValueIsEmptyString() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 123, \"minSampleRate\": \"\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains(" must in the form of "
                + "<EnumClassName>::<ConstantName>.");
    }

    @Test
    public void testParseFloatValueIsString() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 123, \"minSampleRate\": "
                + "\"VehicleUnit::FAHRENHEIT\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        float minSampleRate = mFakeVhalConfigParser.parseJsonConfig(tempFile,
                /* isFileOpt= */ false).get(0).getConfig().minSampleRate;

        assertThat(minSampleRate).isEqualTo(49f);
    }

    @Test
    public void testParseFloatValueIsNull() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 123, \"minSampleRate\": null}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("minSampleRate doesn't have a mapped float "
                + "value.");
    }

    @Test
    public void testParseFloatValueIsInt() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 123, \"minSampleRate\": 456}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        float minSampleRate = mFakeVhalConfigParser.parseJsonConfig(tempFile,
                /* isFileOpt= */ false).get(0).getConfig().minSampleRate;

        assertThat(minSampleRate).isEqualTo(456f);
    }

    @Test
    public void testParseAccessField() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": \"VehicleProperty::INFO_VIN\", "
                + "\"access\": \"VehiclePropertyAccess::READ\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        int access = mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false).get(0)
                .getConfig().access;

        assertThat(access).isEqualTo(1);
    }

    @Test
    public void testParseChangeModeField() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": "
                + "\"VehicleProperty::PERF_VEHICLE_SPEED_DISPLAY\", "
                + "\"changeMode\": \"VehiclePropertyChangeMode::CONTINUOUS\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        int changeMode = mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false)
                .get(0).getConfig().changeMode;

        assertThat(changeMode).isEqualTo(2);
    }

    @Test
    public void testParseConfigArrayValueIsNotArray() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"configArray\": 123}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("configArray doesn't have a mapped JSONArray "
                + "value.");
    }

    @Test
    public void testParseConfigArrayValueWithNullElement() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"configArray\": "
                + "[123, null]}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("[123,null] doesn't have a mapped int value "
                + "at index 1");
    }

    @Test
    public void testParseDefaultValueIsNull() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"defaultValue\": null}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("defaultValue doesn't have a mapped value.");
    }

    @Test
    public void testParseDefaultValueIsEmpty() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"defaultValue\": {}}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("The JSONObject {} is empty.");
    }

    @Test
    public void testParseDefaultValueIntValuesIsNull() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"defaultValue\": "
                + "{\"int32Values\": null}}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("Failed to parse the field name: int32Values "
                + "for defaultValueObject: {\"int32Values\":null}");
    }

    @Test
    public void testParseDefaultValueParseLongValues() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"defaultValue\": "
                + "{\"int64Values\": [0, 100000, 200000]}}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        long[] longValues = mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false)
                .get(0).getInitialValue().int64Values;
        long[] expectLongValues = {0, 100000, 200000};
        assertThat(longValues).isEqualTo(expectLongValues);
    }

    @Test
    public void testParseDefaultValueFloat() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"defaultValue\": "
                + "{\"floatValues\": [2.3, \"VehicleUnit::FAHRENHEIT\"]}}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        RawPropValues rawPropValues = mFakeVhalConfigParser.parseJsonConfig(tempFile,
                /* isFileOpt= */ false).get(0).getInitialValue();
        RawPropValues expectRawPropertyValues = new RawPropValues();
        expectRawPropertyValues.floatValues = new float[]{2.3f, 49.0f};
        assertThat(rawPropValues).isEqualTo(expectRawPropertyValues);
    }

    @Test
    public void testParseDefaultValueIntValuesString() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"defaultValue\": "
                + "{\"int32Values\": [\"VehicleSeatOccupancyState::VACANT\"]}}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());
        RawPropValues expectRawPropertyValues = new RawPropValues();
        expectRawPropertyValues.int32Values = new int[]{VehicleSeatOccupancyState.VACANT};

        RawPropValues rawPropValues = mFakeVhalConfigParser.parseJsonConfig(tempFile,
                /* isFileOpt= */ false).get(0).getInitialValue();

        assertThat(rawPropValues).isEqualTo(expectRawPropertyValues);

    }

    @Test
    public void testParseAreaConfigValueIsNotArray() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"areas\": {}}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("areas doesn't have a mapped array value.");
    }

    @Test
    public void testParseAreaConfigValueWithNullElement() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"areas\": [null]}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("Unable to get a JSONObject element for areas "
                + "at index 0");
    }

    @Test
    public void testParseAreaConfigValueWithEmptyElement() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"areas\": [{}]}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("The JSONObject {} is empty.");
    }

    @Test
    public void testParseAreaConfigValueElementHasNoAreaId() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"areas\": "
                + "[{\"minInt32Value\": 0}]}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, /* isFileOpt= */ false));

        assertThat(thrown).hasMessageThat().contains("{\"minInt32Value\":0} doesn't have areaId. "
                + "AreaId is required.");
    }

    @Test
    public void testParseAreaConfigValue() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"areas\": "
                + "[{\"areaId\": 1, \"minInt32Value\": 0, \"maxInt32Value\": 10, "
                + "\"defaultValue\": {\"int32Values\": [0]}}]}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());
        VehiclePropConfig vehiclePropConfig = new VehiclePropConfig();
        vehiclePropConfig.prop = 286261504;
        VehicleAreaConfig vehicleAreaConfig = new VehicleAreaConfig();
        vehicleAreaConfig.areaId = 1;
        vehicleAreaConfig.minInt32Value = 0;
        vehicleAreaConfig.maxInt32Value = 10;
        vehiclePropConfig.areaConfigs = new VehicleAreaConfig[]{vehicleAreaConfig};
        RawPropValues areaRawPropValues = new RawPropValues();
        areaRawPropValues.int32Values = new int[]{0};
        SparseArray<RawPropValues> areaValuesByAreaId = new SparseArray<>();
        areaValuesByAreaId.put(vehicleAreaConfig.areaId, areaRawPropValues);
        ConfigDeclaration expectConfigDeclaration = new ConfigDeclaration(vehiclePropConfig,
                new RawPropValues(), areaValuesByAreaId);

        ConfigDeclaration configDeclaration = mFakeVhalConfigParser.parseJsonConfig(tempFile,
                /* isFileOpt= */ false).get(0);

        assertThat(expectConfigDeclaration).isEqualTo(configDeclaration);
    }

    @Test
    public void testParseJsonConfig() throws Exception {
        // Create a JSON config object with all field values set.
        String jsonString = "{\"properties\": [{"
                + "             \"property\": "
                + "               \"VehicleProperty::WHEEL_TICK\","
                + "               \"defaultValue\": {"
                + "                 \"int64Values\": [0, 100000, 200000, 300000, 400000]"
                + "               },"
                + "               \"areas\": [{"
                + "                 \"defaultValue\": {"
                + "                   \"int32Values\": [\"VehicleSeatOccupancyState::VACANT\"],"
                + "                   \"floatValues\": [15000.0],"
                + "                   \"stringValue\": \"Toy Vehicle\""
                + "                 },"
                + "                 \"minInt32Value\": 0,"
                + "                 \"maxInt32Value\": 10,"
                + "                 \"areaId\": \"Constants::SEAT_1_LEFT\""
                + "               },{"
                + "                 \"defaultValue\": {"
                + "                   \"int32Values\": [0]"
                + "                 },"
                + "                   \"areaId\": \"Constants::SEAT_1_RIGHT\""
                + "               }],"
                + "            \"configArray\": [15, 50000, 50000, 50000, 50000],"
                + "            \"configString\": \"configString\","
                + "            \"maxSampleRate\": 10.0,"
                + "            \"minSampleRate\": 1.0,"
                + "            \"access\": \"VehiclePropertyAccess::READ\","
                + "            \"changeMode\": \"VehiclePropertyChangeMode::STATIC\""
                + "        }]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());
        // Create prop config object
        VehiclePropConfig vehiclePropConfig = new VehiclePropConfig();
        vehiclePropConfig.prop = VehicleProperty.WHEEL_TICK;
        // Create area config object
        VehicleAreaConfig vehicleAreaConfig1 = new VehicleAreaConfig();
        vehicleAreaConfig1.areaId = VehicleAreaSeat.ROW_1_LEFT;
        vehicleAreaConfig1.minInt32Value = 0;
        vehicleAreaConfig1.maxInt32Value = 10;
        VehicleAreaConfig vehicleAreaConfig2 = new VehicleAreaConfig();
        vehicleAreaConfig2.areaId = VehicleAreaSeat.ROW_1_RIGHT;
        vehiclePropConfig.areaConfigs = new VehicleAreaConfig[]{vehicleAreaConfig1,
                vehicleAreaConfig2};
        vehiclePropConfig.configString = "configString";
        vehiclePropConfig.configArray = new int[]{15, 50000, 50000, 50000, 50000};
        vehiclePropConfig.minSampleRate = 1.0f;
        vehiclePropConfig.maxSampleRate = 10.0f;
        vehiclePropConfig.access = VehiclePropertyAccess.READ;
        vehiclePropConfig.changeMode = VehiclePropertyChangeMode.STATIC;
        // Create default prop value object.
        RawPropValues defaultRawPropValues = new RawPropValues();
        defaultRawPropValues.int64Values = new long[]{0, 100000, 200000, 300000, 400000};
        // Create area prop value objects.
        RawPropValues areaRawPropValues1 = new RawPropValues();
        areaRawPropValues1.int32Values = new int[]{VehicleSeatOccupancyState.VACANT};
        areaRawPropValues1.floatValues = new float[]{15000.0f};
        areaRawPropValues1.stringValue = "Toy Vehicle";
        RawPropValues areaRawPropValues2 = new RawPropValues();
        areaRawPropValues2.int32Values = new int[]{0};
        // Create map from areaId to areaValue.
        SparseArray<RawPropValues> areaValuesByAreaId = new SparseArray<>();
        areaValuesByAreaId.put(vehicleAreaConfig1.areaId, areaRawPropValues1);
        areaValuesByAreaId.put(vehicleAreaConfig2.areaId, areaRawPropValues2);
        // Create expected ConfigDeclaration object.
        ConfigDeclaration expectConfigDeclaration = new ConfigDeclaration(vehiclePropConfig,
                defaultRawPropValues, areaValuesByAreaId);

        ConfigDeclaration configDeclaration = mFakeVhalConfigParser.parseJsonConfig(tempFile,
                /* isFileOpt= */ false).get(0);

        assertThat(expectConfigDeclaration).isEqualTo(configDeclaration);
    }

    private File createTempFile(String fileContent) throws Exception {
        File tempFile = File.createTempFile("DefaultProperties", ".json");
        tempFile.deleteOnExit();
        FileOutputStream os = new FileOutputStream(tempFile);
        os.write(fileContent.getBytes());
        return tempFile;
    }
}
