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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.hardware.CarSensorEvent;
import android.car.hardware.CarSensorManager;
import android.car.hardware.ICarSensorEventListener;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.test.filters.SmallTest;
import android.test.AndroidTestCase;

import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This class contains unit tests for the {@link CarLocationService}.
 * It tests that {@link LocationManager}'s last known location is stored in and loaded from a JSON
 * file upon appropriate system events.
 *
 * The following mocks are used:
 * 1. {@link Context} provides files and a mocked {@link LocationManager}.
 * 2. {@link LocationManager} provides dummy {@link Location}s.
 * 3. {@link CarPowerManagementService} registers a handler for power events.
 * 4. {@link CarSensorService} registers a handler for sensor events and sends ignition-off events.
 */
@SmallTest
public class CarLocationServiceTest extends AndroidTestCase {
    private static String TAG = "CarLocationServiceTest";
    private static String TEST_FILENAME = "location_cache_test.json";
    private CarLocationService mCarLocationService;
    private Context mContext;
    @Mock private Context mockContext;
    @Mock private LocationManager mockLocationManager;
    @Mock private CarPowerManagementService mockPowerManagementService;
    @Mock private CarSensorService mockCarSensorService;

    /**
     * Initialize all of the objects with the @Mock annotation.
     *
     * @throws Exception
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mContext = getContext();
    }

    @Override
    protected void tearDown() throws Exception {
        if (mCarLocationService != null) {
            mCarLocationService.release();
        }
        getContext().deleteFile(TEST_FILENAME);
        super.tearDown();
    }

    /**
     * Test that the {@link CarLocationService} has the permissions necessary to call the
     * {@link LocationManager} injectLocation API.
     *
     * Note that this test will never fail even if the relevant permissions are removed from the
     * manifest since {@link CarService} runs in a system process.
     */
    public void testCarLocationServiceShouldHavePermissions() {
        int fineLocationCheck =
                mContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION);
        int locationHardwareCheck =
                mContext.checkSelfPermission(android.Manifest.permission.LOCATION_HARDWARE);
        assertEquals(PackageManager.PERMISSION_GRANTED, fineLocationCheck);
        assertEquals(PackageManager.PERMISSION_GRANTED, locationHardwareCheck);
    }

    /**
     * Test that the {@link CarLocationService} registers to receive power events and ignition
     * sensor events upon initialization.
     */
    public void testRegistersToReceiveEvents() {
        mCarLocationService = new CarLocationService(mockContext, mockPowerManagementService,
                mockCarSensorService);
        mCarLocationService.init();
        verify(mockPowerManagementService).registerPowerEventProcessingHandler(mCarLocationService);
        verify(mockCarSensorService).registerOrUpdateSensorListener(
                eq(CarSensorManager.SENSOR_TYPE_IGNITION_STATE), eq(0), ArgumentMatchers.any());
    }

    /**
     * Test that the {@link CarLocationService} parses a location from a JSON serialization and then
     * injects it into the {@link LocationManager} upon power up.
     *
     * @throws IOException
     */
    public void testLoadsLocationOnPowerOn() throws IOException {
        long currentTime = System.currentTimeMillis();
        long elapsedTime = SystemClock.elapsedRealtimeNanos();
        String json = "{\"provider\": \"gps\", \"latitude\": 16.7666, \"longitude\": 3.0026,"
                + "\"accuracy\":12.3, \"time\": " + currentTime + ", \"elapsedTime\": "
                + elapsedTime + "}";

        FileOutputStream fos = mContext.openFileOutput(TEST_FILENAME, Context.MODE_PRIVATE);
        fos.write(json.getBytes());
        fos.close();
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                .thenReturn(null);
        when(mockContext.getFileStreamPath("location_cache.json"))
                .thenReturn(mContext.getFileStreamPath(TEST_FILENAME));
        mCarLocationService = new CarLocationService(mockContext, mockPowerManagementService,
                mockCarSensorService);

        mCarLocationService.onPowerOn(false);

        ArgumentCaptor<Location> argument = ArgumentCaptor.forClass(Location.class);
        verify(mockLocationManager).injectLocation(argument.capture());
        Location location = argument.getValue();
        assertEquals("gps", location.getProvider());
        assertEquals(16.7666, location.getLatitude());
        assertEquals(3.0026, location.getLongitude());
        assertEquals(12.3f, location.getAccuracy());
        assertEquals(currentTime, location.getTime());
        assertEquals(elapsedTime, location.getElapsedRealtimeNanos());
    }

    /**
     * Test that the {@link CarLocationService} does not inject a location if there is no location
     * cache file.
     *
     * @throws FileNotFoundException
     */
    public void testDoesNotLoadLocationWhenNoFileExists() throws FileNotFoundException {
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                .thenReturn(null);
        when(mockContext.getFileStreamPath("location_cache.json"))
                .thenReturn(mContext.getFileStreamPath(TEST_FILENAME));
        mCarLocationService = new CarLocationService(mockContext, mockPowerManagementService,
                mockCarSensorService);
        mCarLocationService.onPowerOn(false);
        verify(mockLocationManager, never()).injectLocation(any());
    }

    /**
     * Test that the {@link CarLocationService} handles an incomplete JSON file gracefully.
     *
     * @throws IOException
     */
    public void testDoesNotLoadLocationFromIncompleteFile() throws IOException {
        String json = "{\"provider\": \"gps\", \"latitude\": 16.7666, \"longitude\": 3.0026,";
        FileOutputStream fos = mContext.openFileOutput(TEST_FILENAME, Context.MODE_PRIVATE);
        fos.write(json.getBytes());
        fos.close();
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                .thenReturn(null);
        when(mockContext.getFileStreamPath("location_cache.json"))
                .thenReturn(mContext.getFileStreamPath(TEST_FILENAME));
        mCarLocationService = new CarLocationService(mockContext, mockPowerManagementService,
                mockCarSensorService);
        mCarLocationService.onPowerOn(false);
        verify(mockLocationManager, never()).injectLocation(any());
    }

    /**
     * Test that the {@link CarLocationService} handles a corrupt JSON file gracefully.
     *
     * @throws IOException
     */
    public void testDoesNotLoadLocationFromCorruptFile() throws IOException {
        String json = "{\"provider\":\"latitude\":16.7666,\"longitude\": \"accuracy\":1.0}";
        FileOutputStream fos = mContext.openFileOutput(TEST_FILENAME, Context.MODE_PRIVATE);
        fos.write(json.getBytes());
        fos.close();
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                .thenReturn(null);
        when(mockContext.getFileStreamPath("location_cache.json"))
                .thenReturn(mContext.getFileStreamPath(TEST_FILENAME));
        mCarLocationService = new CarLocationService(mockContext, mockPowerManagementService,
                mockCarSensorService);
        mCarLocationService.onPowerOn(false);
        verify(mockLocationManager, never()).injectLocation(any());
    }

    /**
     * Test that the {@link CarLocationService} does not inject a location that is missing
     * accuracy.
     *
     * @throws IOException
     */
    public void testDoesNotLoadIncompleteLocation() throws IOException {
        String json = "{\"provider\": \"gps\", \"latitude\": 16.7666, \"longitude\": 3.0026}";
        FileOutputStream fos = mContext.openFileOutput(TEST_FILENAME, Context.MODE_PRIVATE);
        fos.write(json.getBytes());
        fos.close();
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                .thenReturn(null);
        when(mockContext.getFileStreamPath("location_cache.json"))
                .thenReturn(mContext.getFileStreamPath(TEST_FILENAME));
        mCarLocationService = new CarLocationService(mockContext, mockPowerManagementService,
                mockCarSensorService);
        mCarLocationService.onPowerOn(false);
        verify(mockLocationManager, never()).injectLocation(any());
    }

    /**
     * Test that the {@link CarLocationService} stores the {@link LocationManager}'s last known
     * location in a JSON file upon ignition-off events.
     *
     * @throws IOException
     * @throws RemoteException
     */
    public void testStoresLocationOnIgnitionOff() throws IOException, RemoteException {
        long currentTime = System.currentTimeMillis();
        long elapsedTime = SystemClock.elapsedRealtimeNanos();
        Location timbuktu = new Location(LocationManager.GPS_PROVIDER);
        timbuktu.setLatitude(16.7666);
        timbuktu.setLongitude(3.0026);
        timbuktu.setAccuracy(13.75f);
        timbuktu.setTime(currentTime);
        timbuktu.setElapsedRealtimeNanos(elapsedTime);
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                .thenReturn(timbuktu);
        when(mockContext.getFileStreamPath("location_cache.json"))
                .thenReturn(mContext.getFileStreamPath(TEST_FILENAME));
        mCarLocationService = new CarLocationService(mockContext, mockPowerManagementService,
                mockCarSensorService);

        mCarLocationService.init();
        ArgumentCaptor<ICarSensorEventListener> argument =
                ArgumentCaptor.forClass(ICarSensorEventListener.class);
        verify(mockCarSensorService).registerOrUpdateSensorListener(
                eq(CarSensorManager.SENSOR_TYPE_IGNITION_STATE), eq(0), argument.capture());
        ICarSensorEventListener carSensorEventListener = argument.getValue();
        CarSensorEvent ignitionOff = new CarSensorEvent(CarSensorManager.SENSOR_TYPE_IGNITION_STATE,
                currentTime, 0, 1, 0);
        ignitionOff.intValues[0] = CarSensorEvent.IGNITION_STATE_OFF;
        List<CarSensorEvent> events = new ArrayList<>(Arrays.asList(ignitionOff));
        carSensorEventListener.onSensorChanged(events);

        verify(mockLocationManager).getLastKnownLocation(LocationManager.GPS_PROVIDER);
        FileInputStream fis = mContext.openFileInput(TEST_FILENAME);
        String actualContents = new BufferedReader(new InputStreamReader(fis)).lines()
                .parallel().collect(Collectors.joining("\n"));
        String expectedContents = "{\"provider\":\"gps\",\"latitude\":16.7666,\"longitude\":"
                + "3.0026,\"accuracy\":13.75,\"elapsedTime\":" + elapsedTime + ",\"time\":"
                + currentTime + "}";
        assertEquals(expectedContents, actualContents);
    }
}
