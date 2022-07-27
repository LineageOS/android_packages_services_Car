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

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.hardware.automotive.vehicle.VehicleAreaDoor;
import android.hardware.automotive.vehicle.VehicleProperty;

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
                () -> mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("Missing required file");
    }

    @Test
    public void testConfigFileNotExistAndFileIsOptional() throws Exception {
        File tempFile = new File("NotExist.json");

        assertThat(mFakeVhalConfigParser.parseJsonConfig(tempFile, true)).isEmpty();
    }

    @Test
    public void testConfigFileHaveInvalidJsonObject() throws Exception {
        String fileContent = "This is a config file.";
        File tempFile = createTempFile(fileContent);

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> mFakeVhalConfigParser.parseJsonConfig(tempFile, true));

        assertThat(thrown).hasMessageThat()
            .contains("This file does not contain a valid JSONObject.");
    }

    @Test
    public void testConfigFileRootIsNotArray() throws Exception {
        String jsonString = "{\"properties\": 123}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, true));

        assertThat(thrown).hasMessageThat().contains("field value is not a valid JSONArray.");
    }

    @Test
    public void testConfigFileRootHasElementIsNotJsonObject() throws Exception {
        String jsonString = "{\"properties\": [{}, 123]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, true));

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
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("The JSONObject " + propertyObject
                + " is empty.");
    }

    @Test
    public void testParsePropertyIdWithIntValue() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 123}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        int propId = mFakeVhalConfigParser.parseJsonConfig(tempFile, false).get(0).getConfig().prop;

        assertThat(propId).isEqualTo(123);
    }

    @Test
    public void testParsePropertyIdFieldNotExist() throws Exception {
        String jsonString = "{\"properties\": [{\"NotAPropIdFieldName\": 123}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains(" doesn't have propId. PropId is required.");
    }

    @Test
    public void testParsePropertyIdValueNull() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": null}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("property doesn't have a mapped int value.");
    }

    @Test
    public void testParsePropertyIdWithWrongValueType() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 12.3f}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        // TODO(b/240578933) Current parseIntValue method accepts float value and auto converts it
        //  to int. Will fix this bug in a separate CL.
        int propId = mFakeVhalConfigParser.parseJsonConfig(tempFile, false).get(0).getConfig().prop;

        assertThat(propId).isEqualTo(12);
    }

    @Test
    public void testParsePropertyIdWithStringValue() throws Exception {
        String jsonString = "{\"properties\": "
                + "[{\"property\": \"VehicleProperty::INFO_FUEL_CAPACITY\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        int propId = mFakeVhalConfigParser.parseJsonConfig(tempFile, false).get(0).getConfig().prop;

        assertThat(propId).isEqualTo(VehicleProperty.INFO_FUEL_CAPACITY);
    }

    @Test
    public void testParsePropertyIdWithEmptyStringValue() throws Exception {
        String jsonString = "{\"properties\": " + "[{\"property\": \"\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("must in the form of "
                + "<EnumClassName>::<ConstantName>.");
    }

    @Test
    public void testParsePropertyIdWithIntStringValue() throws Exception {
        String jsonString = "{\"properties\": " + "[{\"property\": \"1234\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("must in the form of "
                + "<EnumClassName>::<ConstantName>.");
    }

    @Test
    public void testParsePropertyIdWithWrongStringFormat() throws Exception {
        String jsonString = "{\"properties\": "
                + "[{\"property\": \"VehicleProperty:INFO_FUEL_CAPACITY\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("must in the form of "
                + "<EnumClassName>::<ConstantName>.");
    }

    @Test
    public void testParsePropertyIdEnumClassNameNotExist() throws Exception {
        String jsonString = "{\"properties\": "
                + "[{\"property\": \"NotExistEnumClass::INFO_FUEL_CAPACITY\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("is not a valid class name.");
    }

    @Test
    public void testParsePropertyIdEnumClassFieldNameNotExist() throws Exception {
        String jsonString = "{\"properties\": "
                + "[{\"property\": \"VehicleProperty::NOT_EXIST\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("VehicleProperty doesn't have a constant field"
                + " with name NOT_EXIST");
    }

    @Test
    public void testParsePropertyIdEnumClassFieldTypeIsNotInt() throws Exception {
        String jsonString = "{\"properties\": "
                + "[{\"property\": \"IVehicle::INVALID_MEMORY_ID\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("Failed to get int value of interface "
                + "android.hardware.automotive.vehicle.IVehicle.INVALID_MEMORY_ID");
    }

    @Test
    public void testParsePropertyIdFromConstantsMap() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": \"Constants::DOOR_1_LEFT\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        int propId = mFakeVhalConfigParser.parseJsonConfig(tempFile, false).get(0).getConfig().prop;

        assertThat(propId).isEqualTo(VehicleAreaDoor.ROW_1_LEFT);
    }

    @Test
    public void testParsePropertyIdFromConstantsMapWithWrongConstantsName() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": \"Constants::ROW_1_LEFT\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("ROW_1_LEFT is not a valid constant name.");
    }

    @Test
    public void testParseStringValueIsEmpty() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 123, \"configString\": \"\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("configString doesn't have a mapped value.");
    }

    @Test
    public void testParseFloatValueIsEmptyString() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 123, \"minSampleRate\": \"\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains(" must in the form of "
                + "<EnumClassName>::<ConstantName>.");
    }

    @Test
    public void testParseFloatValueIsString() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 123, \"minSampleRate\": "
                + "\"VehicleUnit::FAHRENHEIT\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        float minSampleRate = mFakeVhalConfigParser.parseJsonConfig(tempFile, false).get(0)
                .getConfig().minSampleRate;

        assertThat(minSampleRate).isEqualTo(49f);
    }

    @Test
    public void testParseFloatValueIsNull() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 123, \"minSampleRate\": null}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("minSampleRate doesn't have a mapped float "
                + "value.");
    }

    @Test
    public void testParseFloatValueIsInt() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 123, \"minSampleRate\": 456}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        float minSampleRate = mFakeVhalConfigParser.parseJsonConfig(tempFile, false).get(0)
                .getConfig().minSampleRate;

        assertThat(minSampleRate).isEqualTo(456f);
    }

    @Test
    public void testParseAccessField() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": \"VehicleProperty::INFO_VIN\", "
                + "\"access\": \"VehiclePropertyAccess::READ\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        int access = mFakeVhalConfigParser.parseJsonConfig(tempFile, false).get(0)
                .getConfig().access;

        assertThat(access).isEqualTo(1);
    }

    @Test
    public void testParseChangeModeField() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": "
                + "\"VehicleProperty::PERF_VEHICLE_SPEED_DISPLAY\", "
                + "\"changeMode\": \"VehiclePropertyChangeMode::CONTINUOUS\"}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        int changeMode = mFakeVhalConfigParser.parseJsonConfig(tempFile, false).get(0)
                .getConfig().changeMode;

        assertThat(changeMode).isEqualTo(2);
    }

    @Test
    public void testParseConfigArrayValueIsNotArray() throws Exception {
        String jsonString = "{\"properties\": [{\"property\": 286261504, \"configArray\": 123}]}";
        File tempFile = createTempFile(new JSONObject(jsonString).toString());

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class, () ->
                mFakeVhalConfigParser.parseJsonConfig(tempFile, false));

        assertThat(thrown).hasMessageThat().contains("configArray doesn't have a mapped JSONArray "
                + "value.");
    }

    private File createTempFile(String fileContent) throws Exception {
        File tempFile = File.createTempFile("DefaultProperties", ".json");
        tempFile.deleteOnExit();
        FileOutputStream os = new FileOutputStream(tempFile);
        os.write(fileContent.getBytes());
        return tempFile;
    }
}
