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
package com.android.car;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.drivingstate.CarDrivingStateEvent;
import android.car.drivingstate.CarUxRestrictionsConfiguration;
import android.car.hardware.CarPropertyValue;
import android.car.userlib.CarUserManagerHelper;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.JsonReader;
import android.util.JsonWriter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class CarUxRestrictionsManagerServiceTest {
    private CarUxRestrictionsManagerService mService;

    @Mock
    private CarDrivingStateService mMockDrivingStateService;
    @Mock
    private CarPropertyService mMockCarPropertyService;
    @Mock
    private CarUserManagerHelper mMockCarUserManagerHelper;

    private Context mSpyContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSpyContext = spy(InstrumentationRegistry.getTargetContext());

        setUpMockParkedState();

        mService = new CarUxRestrictionsManagerService(mSpyContext,
                mMockDrivingStateService, mMockCarPropertyService, mMockCarUserManagerHelper);
    }

    @After
    public void tearDown() throws Exception {
        mService = null;
    }

    @Test
    public void testSaveConfig_WriteStagedFile() throws Exception {
        // Reset spy context because service tries to access production config
        // during construction (due to calling loadConfig()).
        reset(mSpyContext);

        File staged = setupMockFile(CarUxRestrictionsManagerService.CONFIG_FILENAME_STAGED, null);
        CarUxRestrictionsConfiguration config = createEmptyConfig();

        assertTrue(mService.saveUxRestrictionsConfigurationForNextBoot(config));
        assertTrue(readFile(staged).equals(config));
        // Verify prod config file was not touched.
        verify(mSpyContext, never()).getFileStreamPath(
                CarUxRestrictionsManagerService.CONFIG_FILENAME_PRODUCTION);
    }

    @Test
    public void testSaveConfig_ReturnFalseOnException() throws Exception {
        File tempFile = File.createTempFile("uxr_test", null);
        doReturn(tempFile).when(mSpyContext).getFileStreamPath(anyString());

        CarUxRestrictionsConfiguration spyConfig = spy(createEmptyConfig());
        doThrow(new IOException()).when(spyConfig).writeJson(any(JsonWriter.class));

        assertFalse(mService.saveUxRestrictionsConfigurationForNextBoot(spyConfig));
    }

    @Test
    public void testSaveConfig_DoesNotAffectCurrentConfig() throws Exception {
        File tempFile = File.createTempFile("uxr_test", null);
        doReturn(tempFile).when(mSpyContext).getFileStreamPath(anyString());
        CarUxRestrictionsConfiguration spyConfig = spy(createEmptyConfig());

        CarUxRestrictionsConfiguration currentConfig = mService.getConfig();
        assertTrue(mService.saveUxRestrictionsConfigurationForNextBoot(spyConfig));

        verify(spyConfig).writeJson(any(JsonWriter.class));
        // Verify current config is untouched by address comparison.
        assertTrue(mService.getConfig() == currentConfig);
    }

    @Test
    public void testLoadConfig_UseDefaultConfigWhenNoSavedConfigFileNoXml() {
        // Prevent R.xml.car_ux_restrictions_map being returned.
        Resources spyResources = spy(mSpyContext.getResources());
        doReturn(spyResources).when(mSpyContext).getResources();
        doReturn(null).when(spyResources).getXml(anyInt());

        assertTrue(mService.loadConfig().equals(mService.createDefaultConfig()));
    }

    @Test
    public void testLoadConfig_UseXml() throws IOException, XmlPullParserException {
        CarUxRestrictionsConfiguration expected = CarUxRestrictionsConfigurationXmlParser.parse(
                mSpyContext, R.xml.car_ux_restrictions_map);

        CarUxRestrictionsConfiguration actual = mService.loadConfig();

        assertTrue(actual.equals(expected));
    }

    @Test
    public void testLoadConfig_UseProdConfig() throws IOException {
        CarUxRestrictionsConfiguration expected = createEmptyConfig();
        setupMockFile(CarUxRestrictionsManagerService.CONFIG_FILENAME_PRODUCTION, expected);

        CarUxRestrictionsConfiguration actual = mService.loadConfig();

        assertTrue(actual.equals(expected));
    }

    @Test
    public void testLoadConfig_PromoteStagedFileWhenParked() throws Exception {
        CarUxRestrictionsConfiguration expected = createEmptyConfig();
        // Staged file contains actual config. Ignore prod since it should be overwritten by staged.
        File staged = setupMockFile(CarUxRestrictionsManagerService.CONFIG_FILENAME_STAGED,
                expected);
        // Set up temp file for prod to avoid polluting other tests.
        setupMockFile(CarUxRestrictionsManagerService.CONFIG_FILENAME_PRODUCTION, null);

        CarUxRestrictionsConfiguration actual = mService.loadConfig();

        // Staged file should be moved as production.
        assertFalse(staged.exists());
        assertTrue(actual.equals(expected));
    }

    @Test
    public void testLoadConfig_NoPromoteStagedFileWhenMoving() throws Exception {
        CarUxRestrictionsConfiguration expected = createEmptyConfig();
        File staged = setupMockFile(CarUxRestrictionsManagerService.CONFIG_FILENAME_STAGED, null);
        // Prod file contains actual config. Ignore staged since it should not be promoted.
        setupMockFile(CarUxRestrictionsManagerService.CONFIG_FILENAME_PRODUCTION, expected);

        setUpMockDrivingState();
        CarUxRestrictionsConfiguration actual = mService.loadConfig();

        // Staged file should be untouched.
        assertTrue(staged.exists());
        assertTrue(actual.equals(expected));
    }

    private CarUxRestrictionsConfiguration createEmptyConfig() {
        return new CarUxRestrictionsConfiguration.Builder().build();
    }

    private void setUpMockParkedState() {
        when(mMockDrivingStateService.getCurrentDrivingState()).thenReturn(
                new CarDrivingStateEvent(CarDrivingStateEvent.DRIVING_STATE_PARKED, 0));

        CarPropertyValue<Float> speed = new CarPropertyValue<>(VehicleProperty.PERF_VEHICLE_SPEED,
                0, 0f);
        when(mMockCarPropertyService.getProperty(VehicleProperty.PERF_VEHICLE_SPEED, 0))
                .thenReturn(speed);
    }

    private void setUpMockDrivingState() {
        when(mMockDrivingStateService.getCurrentDrivingState()).thenReturn(
                new CarDrivingStateEvent(CarDrivingStateEvent.DRIVING_STATE_MOVING, 0));

        CarPropertyValue<Float> speed = new CarPropertyValue<>(VehicleProperty.PERF_VEHICLE_SPEED,
                0, 30f);
        when(mMockCarPropertyService.getProperty(VehicleProperty.PERF_VEHICLE_SPEED, 0))
                .thenReturn(speed);
    }

    private File setupMockFile(String filename, CarUxRestrictionsConfiguration config)
            throws IOException {
        File f = File.createTempFile("uxr_test", null);
        doReturn(f).when(mSpyContext).getFileStreamPath(filename);

        if (config != null) {
            try (JsonWriter writer = new JsonWriter(
                    new OutputStreamWriter(new FileOutputStream(f), "UTF-8"))) {
                config.writeJson(writer);
            }
        }
        return f;
    }

    private CarUxRestrictionsConfiguration readFile(File file) throws Exception {
        try (JsonReader reader = new JsonReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            return CarUxRestrictionsConfiguration.readJson(reader);
        }
    }
}
