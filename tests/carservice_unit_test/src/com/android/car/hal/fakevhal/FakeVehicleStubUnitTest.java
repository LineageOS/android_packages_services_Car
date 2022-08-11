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

import android.hardware.automotive.vehicle.RawPropValues;
import android.hardware.automotive.vehicle.VehicleAreaWindow;
import android.hardware.automotive.vehicle.VehiclePropConfig;
import android.hardware.automotive.vehicle.VehicleProperty;
import android.util.SparseArray;

import com.android.car.VehicleStub;
import com.android.car.hal.HalPropConfig;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class FakeVehicleStubUnitTest {

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
        FakeVehicleStub mFakeVehicle = new FakeVehicleStub(mMockRealVehicleStub);

        HalPropConfig[] allPropConfig = mFakeVehicle.getAllPropConfigs();
        HalPropConfig propConfigInfo = getPropConfigByPropId(allPropConfig,
                VehicleProperty.INFO_MAKE);
        HalPropConfig propConfigWindow = getPropConfigByPropId(allPropConfig,
                VehicleProperty.WINDOW_POS);

        expect.that(mFakeVehicle.isValid()).isTrue();
        expect.that(propConfigInfo.getPropId()).isEqualTo(VehicleProperty.INFO_MAKE);
        expect.that(propConfigWindow.getAreaConfigs()[0].getAreaId())
                .isEqualTo(VehicleAreaWindow.ROW_1_LEFT);
        expect.that(propConfigWindow.getAreaConfigs()[0].getMaxInt32Value()).isEqualTo(10);
    }

    @Test
    public void testGetAllPropConfigsWithCustomConfigSamePropId() throws Exception {
        SparseArray<ConfigDeclaration> defaultParseResult = new FakeVhalConfigParser()
                .parseJsonConfig(FakeVhalConfigParser.class.getClassLoader()
                .getResourceAsStream("DefaultProperties.json"));
        when(mParser.parseJsonConfig(any(InputStream.class))).thenReturn(defaultParseResult);

        SparseArray<ConfigDeclaration> customParseResult = createParseResult(
                /* propId= */ VehicleProperty.TIRE_PRESSURE, /* maxSampleRate= */ 2.5f);
        when(mParser.parseJsonConfig(any(File.class))).thenReturn(customParseResult);
        List<String> mCustomConfigFileNames = Arrays.asList("custom_config_1");
        FakeVehicleStub mFakeVehicle = new FakeVehicleStub(mMockRealVehicleStub, mParser,
                mCustomConfigFileNames);
        HalPropConfig[] allPropConfig = mFakeVehicle.getAllPropConfigs();
        HalPropConfig propConfig = getPropConfigByPropId(
                allPropConfig, VehicleProperty.TIRE_PRESSURE);

        expect.that(defaultParseResult.size()).isEqualTo(allPropConfig.length);
        expect.that(propConfig.getPropId()).isEqualTo(VehicleProperty.TIRE_PRESSURE);
        expect.that(propConfig.getMaxSampleRate()).isEqualTo(2.5f);
    }

    @Test
    public void testGetAllPropConfigsWithCustomConfigDiffPropId() throws Exception {
        SparseArray<ConfigDeclaration> defaultParseResult = new FakeVhalConfigParser()
                .parseJsonConfig(FakeVhalConfigParser.class.getClassLoader()
                .getResourceAsStream("DefaultProperties.json"));
        when(mParser.parseJsonConfig(any(InputStream.class))).thenReturn(defaultParseResult);

        SparseArray<ConfigDeclaration> customParseResult = createParseResult(
                /* propId= */ 123, /* maxSampleRate= */ 5.0f);
        when(mParser.parseJsonConfig(any(File.class))).thenReturn(customParseResult);
        List<String> mCustomConfigFileNames = Arrays.asList("custom_config_1");
        FakeVehicleStub mFakeVehicle = new FakeVehicleStub(mMockRealVehicleStub, mParser,
                mCustomConfigFileNames);
        HalPropConfig[] allPropConfig = mFakeVehicle.getAllPropConfigs();
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
                /* propId= */ 123, /* maxSampleRate= */ 10.0f);
        when(mParser.parseJsonConfig(any(InputStream.class))).thenReturn(defaultParseResult);

        FakeVehicleStub mFakeVehicle = new FakeVehicleStub(mMockRealVehicleStub, mParser,
                new ArrayList<>());
        HalPropConfig[] allPropConfig = mFakeVehicle.getAllPropConfigs();

        expect.that(allPropConfig.length).isEqualTo(defaultParseResult.size());
    }

    private SparseArray<ConfigDeclaration> createParseResult(int propId, float maxSampleRate) {
        SparseArray<ConfigDeclaration> parseResult = new SparseArray<>();
        ConfigDeclaration propConfig = new ConfigDeclaration(
                createValueByPropIdAndRate(propId, maxSampleRate), new RawPropValues(),
                new SparseArray<>());
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

    private VehiclePropConfig createValueByPropIdAndRate(int propId, float rate) {
        VehiclePropConfig propConfigValue = new VehiclePropConfig();
        propConfigValue.prop = propId;
        propConfigValue.maxSampleRate = rate;
        return propConfigValue;
    }
}
