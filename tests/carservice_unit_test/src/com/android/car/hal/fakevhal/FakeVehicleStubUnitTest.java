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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.hardware.automotive.vehicle.FuelType;
import android.hardware.automotive.vehicle.RawPropValues;
import android.hardware.automotive.vehicle.StatusCode;
import android.hardware.automotive.vehicle.VehicleAreaConfig;
import android.hardware.automotive.vehicle.VehicleAreaDoor;
import android.hardware.automotive.vehicle.VehicleAreaSeat;
import android.hardware.automotive.vehicle.VehicleAreaWindow;
import android.hardware.automotive.vehicle.VehiclePropConfig;
import android.hardware.automotive.vehicle.VehiclePropValue;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.hardware.automotive.vehicle.VehiclePropertyAccess;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.util.SparseArray;

import com.android.car.VehicleStub;
import com.android.car.hal.HalPropConfig;
import com.android.car.hal.HalPropValue;
import com.android.car.hal.HalPropValueBuilder;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class FakeVehicleStubUnitTest {

    private static final int DOOR_1_LEFT = VehicleAreaDoor.ROW_1_LEFT;
    private static final int WINDOW_1_LEFT = VehicleAreaWindow.ROW_1_LEFT;
    private static final int SEAT_1_LEFT = VehicleAreaSeat.ROW_1_LEFT;

    @Mock
    private VehicleStub mMockRealVehicleStub;
    @Mock
    private FakeVhalConfigParser mParser;

    @Rule
    public final Expect expect = Expect.create();

    @Before
    public void setup() throws Exception {
        when(mMockRealVehicleStub.isValid()).thenReturn(true);
    }

    @Test
    public void testGetAllPropConfigsWithoutCustomConfig() throws Exception {
        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub);

        HalPropConfig[] allPropConfig = fakeVehicleStub.getAllPropConfigs();
        HalPropConfig propConfigInfo = getPropConfigByPropId(allPropConfig,
                VehicleProperty.INFO_MAKE);
        HalPropConfig propConfigWindow = getPropConfigByPropId(allPropConfig,
                VehicleProperty.WINDOW_POS);

        expect.that(fakeVehicleStub.isValid()).isTrue();
        expect.that(propConfigInfo.getPropId()).isEqualTo(VehicleProperty.INFO_MAKE);
        expect.that(propConfigWindow.getAreaConfigs()[0].getAreaId())
                .isEqualTo(VehicleAreaWindow.ROW_1_LEFT);
        expect.that(propConfigWindow.getAreaConfigs()[0].getMaxInt32Value()).isEqualTo(10);
    }

    @Test
    public void testGetAllPropConfigsWithCustomConfigHasExistingPropId() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\": \"VehicleProperty::TIRE_PRESSURE\","
                + "\"defaultValue\": {\"floatValues\": [200.0]}, \"maxSampleRate\": 2.5}]}";
        List<String> customFileList = createFilenameList(jsonString);

        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);
        HalPropConfig[] allPropConfig = fakeVehicleStub.getAllPropConfigs();
        HalPropConfig propConfig = getPropConfigByPropId(allPropConfig,
                VehicleProperty.TIRE_PRESSURE);

        expect.that(propConfig.getPropId()).isEqualTo(VehicleProperty.TIRE_PRESSURE);
        expect.that(propConfig.getMaxSampleRate()).isEqualTo(2.5f);
    }

    @Test
    public void testGetAllPropConfigsWithCustomConfigHasNonExistingPropId() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\": 123,"
                + "\"defaultValue\": {\"floatValues\": [200.0]}, "
                + "\"access\": \"VehiclePropertyAccess::READ_WRITE\","
                + "\"changeMode\": \"VehiclePropertyChangeMode::STATIC\","
                + "\"maxSampleRate\": 5.0}]}";
        List<String> customFileList = createFilenameList(jsonString);

        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);
        HalPropConfig[] allPropConfig = fakeVehicleStub.getAllPropConfigs();
        HalPropConfig propConfig = getPropConfigByPropId(allPropConfig, 123);

        expect.that(propConfig.getPropId()).isEqualTo(123);
        expect.that(propConfig.getMaxSampleRate()).isEqualTo(5.0f);
        expect.that(allPropConfig.length).isEqualTo(
                new FakeVehicleStub(mMockRealVehicleStub).getAllPropConfigs().length + 1);
    }

    @Test
    public void testParseConfigFilesThrowException() throws Exception {
        when(mParser.parseJsonConfig(any(InputStream.class))).thenThrow(
                new IllegalArgumentException("This file does not contain a valid JSONObject."));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new FakeVehicleStub(mMockRealVehicleStub, mParser, new ArrayList<>()));

        expect.that(thrown).hasMessageThat().contains("This file does not contain a valid "
                + "JSONObject.");
    }

    @Test
    public void testCustomFileNotExist() throws Exception {
        SparseArray<ConfigDeclaration> defaultParseResult = createParseResult(
                /* propId= */ 123, /* maxSampleRate= */ 10.0f,
                /* access= */ VehiclePropertyAccess.NONE, /* areaId= */ 0);
        when(mParser.parseJsonConfig(any(InputStream.class))).thenReturn(defaultParseResult);

        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub, mParser,
                new ArrayList<>());
        HalPropConfig[] allPropConfig = fakeVehicleStub.getAllPropConfigs();

        expect.that(allPropConfig.length).isEqualTo(defaultParseResult.size());
    }

    @Test
    public void testGetMethodPropIdNotSupported() throws Exception {
        // Mock config files parsing results to be empty.
        when(mParser.parseJsonConfig(any(InputStream.class))).thenReturn(new SparseArray<>());
        when(mParser.parseJsonConfig(any(File.class))).thenReturn(new SparseArray<>());
        // Create a request prop value.
        HalPropValue requestPropValue = new HalPropValueBuilder(/* isAidl= */ true)
                .build(/* propId= */ VehicleProperty.INFO_FUEL_TYPE, /* areaId= */ 0);
        // Create a FakeVehicleStub instance.
        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub, mParser,
                new ArrayList<>());

        ServiceSpecificException thrown = assertThrows(ServiceSpecificException.class,
                () -> fakeVehicleStub.get(requestPropValue));


        expect.that(thrown.errorCode).isEqualTo(StatusCode.INVALID_ARG);
        expect.that(thrown).hasMessageThat().contains("The propId: "
                + VehicleProperty.INFO_FUEL_TYPE + " is not supported.");
    }

    @Test
    public void testGetMethodNoReadPermission() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\": "
                + "\"VehicleProperty::ANDROID_EPOCH_TIME\","
                + "\"access\": \"VehiclePropertyAccess::WRITE\"}]}";
        List<String> customFileList = createFilenameList(jsonString);
        // Create a request prop value.
        HalPropValue requestPropValue = new HalPropValueBuilder(/* isAidl= */ true)
                .build(/* propId= */ VehicleProperty.ANDROID_EPOCH_TIME, /* areaId= */ 0);
        // Create a FakeVehicleStub instance.
        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);

        ServiceSpecificException thrown = assertThrows(ServiceSpecificException.class,
                () -> fakeVehicleStub.get(requestPropValue));

        expect.that(thrown.errorCode).isEqualTo(StatusCode.ACCESS_DENIED);
        expect.that(thrown).hasMessageThat().contains("doesn't have read permission");
    }

    @Test
    public void testGetMethodPropIdIsGlobalHasNoDefaultValueAreaIdIgnored() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\": "
                + "\"VehicleProperty::VEHICLE_MAP_SERVICE\"}]}";
        List<String> customFileList = createFilenameList(jsonString);
        // Create a request prop value.
        HalPropValue requestPropValue = new HalPropValueBuilder(/* isAidl= */ true)
                .build(/* propId= */ VehicleProperty.VEHICLE_MAP_SERVICE, /* areaId= */ 123);
        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);

        ServiceSpecificException thrown = assertThrows(ServiceSpecificException.class,
                () -> fakeVehicleStub.get(requestPropValue));

        // For global properties, if default value is not defined, will throw
        // ServiceSpecificException with StatusCode.NOT_AVAILABLE.
        expect.that(thrown.errorCode).isEqualTo(StatusCode.NOT_AVAILABLE);
        expect.that(thrown).hasMessageThat().contains("propId: "
                + VehicleProperty.VEHICLE_MAP_SERVICE + " has no property value.");
    }

    @Test
    public void testGetMethodPropIdIsGlobalHasDefaultValueAreaIdIgnored() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\": \"VehicleProperty::INFO_FUEL_TYPE\","
                + "\"defaultValue\": {\"int32Values\": [\"FuelType::FUEL_TYPE_UNLEADED\"]}}]}";
        List<String> customFileList = createFilenameList(jsonString);
        // Create a request prop value.
        HalPropValue requestPropValue = new HalPropValueBuilder(/* isAidl= */ true)
                .build(/* propId= */ VehicleProperty.INFO_FUEL_TYPE, /* areaId= */ 123);
        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);

        HalPropValue propValue = fakeVehicleStub.get(requestPropValue);

        // For global properties, if default value exists, will return the default value.
        expect.that(propValue.getAreaId()).isEqualTo(0);
        expect.that(propValue.getInt32ValuesSize()).isEqualTo(1);
        expect.that(propValue.getInt32Value(0)).isEqualTo(FuelType.FUEL_TYPE_UNLEADED);
    }

    @Test
    public void testGetMethodPropIdHasAreasHasNoDefaultValueHasNoAreaValue() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\": \"VehicleProperty::WINDOW_POS\","
                + "\"areas\": [{\"areaId\": \"Constants::WINDOW_1_LEFT\", \"minInt32Value\": 0,"
                + "\"maxInt32Value\": 10}]}]}";
        List<String> customFileList = createFilenameList(jsonString);
        // Create a request prop value.
        HalPropValue requestPropValue = new HalPropValueBuilder(/* isAidl= */ true)
                .build(/* propId= */ VehicleProperty.WINDOW_POS, /* areaId= */ WINDOW_1_LEFT);
        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);

        ServiceSpecificException thrown = assertThrows(ServiceSpecificException.class,
                () -> fakeVehicleStub.get(requestPropValue));

        // For properties have areas, if neither default prop value nor area value exists, then
        // throw a ServiceSpecificException of StatusCode.NOT_AVAILABLE.
        expect.that(thrown.errorCode).isEqualTo(StatusCode.NOT_AVAILABLE);
        expect.that(thrown).hasMessageThat().contains("propId: "
                + VehicleProperty.WINDOW_POS + ", areaId: " + WINDOW_1_LEFT + " has no property "
                + "value");
    }

    @Test
    public void testGetMethodPropIdHasAreasHasDefaultValueHasNoAreaValue() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\": \"VehicleProperty::WINDOW_POS\","
                + "\"defaultValue\": {\"int32Values\": [0]},\"areas\": [{\"areaId\":"
                + "\"Constants::WINDOW_1_LEFT\", \"minInt32Value\": 0, \"maxInt32Value\": 10}]}]}";
        List<String> customFileList = createFilenameList(jsonString);
        // Create a request prop value.
        HalPropValue requestPropValue = new HalPropValueBuilder(/* isAidl= */ true)
                .build(/* propId= */ VehicleProperty.WINDOW_POS, /* areaId= */ WINDOW_1_LEFT);
        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);

        HalPropValue propValue = fakeVehicleStub.get(requestPropValue);

        // For properties have areas, expect that default prop value will override area value when
        // default prop value is set but the area value is not defined.
        expect.that(propValue.getAreaId()).isEqualTo(WINDOW_1_LEFT);
        expect.that(propValue.getInt32ValuesSize()).isEqualTo(1);
        expect.that(propValue.getInt32Value(0)).isEqualTo(0);
    }

    @Test
    public void testGetMethodPropIdHasAreasHasAreaValue() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\": \"VehicleProperty::DOOR_LOCK\","
                + "\"areas\": [{\"defaultValue\": {\"int32Values\": [1]}, \"areaId\":"
                + "\"Constants::DOOR_1_LEFT\"}]}]}";
        List<String> customFileList = createFilenameList(jsonString);
        // Create a request prop value.
        HalPropValue requestPropValue = new HalPropValueBuilder(/* isAidl= */ true)
                .build(VehicleProperty.DOOR_LOCK, DOOR_1_LEFT);
        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);

        HalPropValue propValue = fakeVehicleStub.get(requestPropValue);

        // For properties have areas, get method returns the area value if it exists.
        expect.that(propValue.getPropId()).isEqualTo(VehicleProperty.DOOR_LOCK);
        expect.that(propValue.getInt32Value(0)).isEqualTo(1);
    }

    @Test
    public void testGetMethodPropIdHasAreasAreaIdNotSupport() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\":"
                + "\"VehicleProperty::SEAT_BELT_BUCKLED\","
                + "\"defaultValue\": {\"int32Values\": [0]},"
                + "\"areas\": [{\"areaId\": \"Constants::SEAT_1_LEFT\"}]}]}";
        List<String> customFileList = createFilenameList(jsonString);
        // Create a request prop value.
        HalPropValue requestPropValue = new HalPropValueBuilder(/* isAidl= */ true)
                .build(/* propId= */ VehicleProperty.SEAT_BELT_BUCKLED, /* areaId= */ 0);
        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);

        ServiceSpecificException thrown = assertThrows(ServiceSpecificException.class,
                () -> fakeVehicleStub.get(requestPropValue));

        expect.that(thrown.errorCode).isEqualTo(StatusCode.INVALID_ARG);
        expect.that(thrown).hasMessageThat().contains("areaId: 0 is not supported");
    }

    @Test
    public void testSetMethodPropConfigNotExist() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\":"
                + "\"VehicleProperty::SEAT_BELT_BUCKLED\","
                + "\"defaultValue\": {\"int32Values\": [0]},"
                + "\"areas\": [{\"areaId\": \"Constants::SEAT_1_LEFT\"}]}]}";
        List<String> customFileList = createFilenameList(jsonString);
        // Create a request prop value.
        HalPropValue requestPropValue = new HalPropValueBuilder(/* isAdil= */ true)
                .build(/* propId= */ 123456, /* areaId= */ 0);
        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);

        ServiceSpecificException thrown = assertThrows(ServiceSpecificException.class,
                () -> fakeVehicleStub.set(requestPropValue));

        expect.that(thrown).hasMessageThat().contains("The propId: 123456 is not supported.");
    }

    @Test
    public void testSetMethodPropDefaultValueExist() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\":"
                + "\"VehicleProperty::INFO_FUEL_CAPACITY\","
                + "\"defaultValue\": {\"floatValues\": [100.0]},"
                + "\"access\": \"VehiclePropertyAccess::READ_WRITE\"}]}";
        List<String> customFileList = createFilenameList(jsonString);
        // Create a request prop value.
        RawPropValues rawPropValues = new RawPropValues();
        rawPropValues.floatValues = new float[]{10};
        HalPropValue requestPropValue = buildHalPropValue(
                /* propId= */ VehicleProperty.INFO_FUEL_CAPACITY, /* areaId= */ 0,
                SystemClock.elapsedRealtimeNanos(), rawPropValues);

        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);
        HalPropValue oldPropValue = fakeVehicleStub.get(requestPropValue);
        fakeVehicleStub.set(requestPropValue);
        HalPropValue updatedPropValue = fakeVehicleStub.get(requestPropValue);

        expect.that(updatedPropValue.getFloatValue(0)).isEqualTo(10.0f);
        expect.that(updatedPropValue.getFloatValue(0)).isNotEqualTo(oldPropValue.getFloatValue(0));
        expect.that(updatedPropValue.getTimestamp()).isNotEqualTo(oldPropValue.getTimestamp());
    }

    @Test
    public void testSetMethodPropDefaultValueNotExist() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\":"
                + "\"VehicleProperty::INFO_FUEL_CAPACITY\","
                + "\"access\": \"VehiclePropertyAccess::READ_WRITE\"}]}";
        List<String> customFileList = createFilenameList(jsonString);
        // Create a request prop value.
        RawPropValues rawPropValues = new RawPropValues();
        rawPropValues.int32Values = new int[]{32};
        HalPropValue requestPropValue = buildHalPropValue(
                /* propId= */ VehicleProperty.INFO_FUEL_CAPACITY, /* areaId= */ 0,
                SystemClock.elapsedRealtimeNanos(), rawPropValues);

        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);
        ServiceSpecificException thrown = assertThrows(ServiceSpecificException.class,
                () -> fakeVehicleStub.get(requestPropValue));

        expect.that(thrown).hasMessageThat().contains("has no property value");

        fakeVehicleStub.set(requestPropValue);
        HalPropValue updatedPropValue = fakeVehicleStub.get(requestPropValue);

        expect.that(updatedPropValue.getInt32Value(0)).isEqualTo(32);
    }

    @Test
    public void testSetMethodValueOutOfRange() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\":"
                + "\"VehicleProperty::SEAT_BELT_HEIGHT_POS\","
                + "\"defaultValue\": {\"int32Values\": [10]},"
                + "\"areas\": [{\"areaId\": \"Constants::SEAT_1_LEFT\","
                + "\"minInt32Value\": 0, \"maxInt32Value\": 10}]}]}";
        List<String> customFileList = createFilenameList(jsonString);
        // Create a request prop value.
        RawPropValues rawPropValues = new RawPropValues();
        rawPropValues.int32Values = new int[]{15};
        HalPropValue requestPropValue = buildHalPropValue(
                /* propId= */ VehicleProperty.SEAT_BELT_HEIGHT_POS, /* areaId= */ SEAT_1_LEFT,
                SystemClock.elapsedRealtimeNanos(), rawPropValues);

        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);
        ServiceSpecificException thrown = assertThrows(ServiceSpecificException.class,
                () -> fakeVehicleStub.set(requestPropValue));

        expect.that(thrown.errorCode).isEqualTo(StatusCode.INVALID_ARG);
        expect.that(thrown).hasMessageThat().contains("The set value is outside the range");
    }

    @Test
    public void testSetMethodAreaConfigHasNoLimit() throws Exception {
        // Create a custom config file.
        String jsonString = "{\"properties\": [{\"property\":"
                + "\"VehicleProperty::SEAT_BELT_BUCKLED\","
                + "\"defaultValue\": {\"int32Values\": [0]},"
                + "\"areas\": [{\"areaId\": \"Constants::SEAT_1_LEFT\"}]}]}";
        List<String> customFileList = createFilenameList(jsonString);
        // Create a request prop value.
        FakeVehicleStub fakeVehicleStub = new FakeVehicleStub(mMockRealVehicleStub,
                new FakeVhalConfigParser(), customFileList);
        RawPropValues rawPropValues = new RawPropValues();
        rawPropValues.int32Values = new int[]{32};
        HalPropValue requestPropValue = buildHalPropValue(
                /* propId= */ VehicleProperty.SEAT_BELT_BUCKLED, /* areaId= */ SEAT_1_LEFT,
                SystemClock.elapsedRealtimeNanos(), rawPropValues);

        HalPropValue oldPropValue = fakeVehicleStub.get(requestPropValue);
        fakeVehicleStub.set(requestPropValue);
        HalPropValue updatedPropValue = fakeVehicleStub.get(requestPropValue);

        expect.that(oldPropValue.getInt32Value(0)).isEqualTo(0);
        expect.that(updatedPropValue.getInt32Value(0)).isEqualTo(32);
    }

    private HalPropValue buildHalPropValue(int propId, int areaId, long timestamp,
            RawPropValues rawPropValues) {
        VehiclePropValue propValue = new VehiclePropValue();
        propValue.prop = propId;
        propValue.areaId = areaId;
        propValue.timestamp = timestamp;
        propValue.value = rawPropValues;
        return new HalPropValueBuilder(/* isAidl= */ true).build(propValue);
    }

    private SparseArray<ConfigDeclaration> createParseResult(int propId, float maxSampleRate,
            int access, int areaId) {
        SparseArray<ConfigDeclaration> parseResult = new SparseArray<>();
        ConfigDeclaration propConfig = new ConfigDeclaration(
                createConfig(propId, maxSampleRate, access, areaId), null, new SparseArray<>());
        parseResult.put(propId, propConfig);
        return parseResult;
    }

    private HalPropConfig getPropConfigByPropId(HalPropConfig[] propConfigs, int propId) {
        for (int i = 0; i < propConfigs.length; i++) {
            if (propConfigs[i].getPropId() == propId) {
                return propConfigs[i];
            }
        }
        return null;
    }

    private VehiclePropConfig createConfig(int propId, float rate, int access, int areaId) {
        VehiclePropConfig propConfigValue = new VehiclePropConfig();
        propConfigValue.prop = propId;
        propConfigValue.access = access;
        VehicleAreaConfig vehicleAreaConfig = new VehicleAreaConfig();
        vehicleAreaConfig.areaId = areaId;
        propConfigValue.areaConfigs = new VehicleAreaConfig[]{vehicleAreaConfig};
        propConfigValue.maxSampleRate = rate;
        return propConfigValue;
    }

    private List<String> createFilenameList(String fileContent) throws Exception {
        List<String> customFileList = new ArrayList<>();
        File tempFile = File.createTempFile("custom_config_", ".json");
        tempFile.deleteOnExit();
        FileOutputStream os = new FileOutputStream(tempFile);
        os.write(fileContent.getBytes());
        customFileList.add(tempFile.getPath());
        return customFileList;
    }
}
