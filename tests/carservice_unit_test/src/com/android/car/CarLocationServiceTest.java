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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.hardware.CarSensorEvent;
import android.car.hardware.CarSensorManager;
import android.car.hardware.ICarSensorEventListener;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.test.filters.SmallTest;
import android.test.AndroidTestCase;

import com.android.internal.util.ArrayUtils;

import org.mockito.ArgumentCaptor;
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
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * This class contains unit tests for the {@link CarLocationService}.
 * It tests that {@link LocationManager}'s last known location is stored in and loaded from a JSON
 * file upon appropriate system events.
 *
 * The following mocks are used:
 * 1. {@link Context} provides files and a mocked {@link LocationManager}.
 * 2. {@link LocationManager} provides dummy {@link Location}s.
 * 3. {@link CarSensorService} registers a handler for sensor events and sends ignition-off events.
 */
@SmallTest
public class CarLocationServiceTest extends AndroidTestCase {
    private static String TAG = "CarLocationServiceTest";
    private static String TEST_FILENAME = "location_cache_test.json";
    private CarLocationService mCarLocationService;
    private Context mContext;
    private CountDownLatch mLatch;
    @Mock private Context mockContext;
    @Mock private LocationManager mockLocationManager;
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
        mLatch = new CountDownLatch(1);
        mCarLocationService = new CarLocationService(mockContext, mockCarSensorService) {
            @Override
            void asyncOperation(Runnable operation) {
                super.asyncOperation(() -> {
                    operation.run();
                    mLatch.countDown();
                });
            }
        };
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
     * Test that the {@link CarLocationService} registers to receive the locked boot completed
     * intent and ignition sensor events upon initialization.
     */
    public void testRegistersToReceiveEvents() {
        ArgumentCaptor<IntentFilter> argument = ArgumentCaptor.forClass(IntentFilter.class);
        mCarLocationService.init();
        verify(mockContext).registerReceiver(eq(mCarLocationService), argument.capture());
        IntentFilter intentFilter = argument.getValue();
        assertEquals(3, intentFilter.countActions());
        String[] actions = {intentFilter.getAction(0), intentFilter.getAction(1),
                intentFilter.getAction(2)};
        assertTrue(ArrayUtils.contains(actions, Intent.ACTION_LOCKED_BOOT_COMPLETED));
        assertTrue(ArrayUtils.contains(actions, LocationManager.MODE_CHANGED_ACTION));
        assertTrue(ArrayUtils.contains(actions, LocationManager.GPS_ENABLED_CHANGE_ACTION));
        verify(mockCarSensorService).registerOrUpdateSensorListener(
                eq(CarSensorManager.SENSOR_TYPE_IGNITION_STATE), eq(0), any());
    }

    /**
     * Test that the {@link CarLocationService} unregisters its event receivers.
     */
    public void testUnregistersEventReceivers() {
        mCarLocationService.release();
        verify(mockContext).unregisterReceiver(mCarLocationService);
        verify(mockCarSensorService).unregisterSensorListener(
                eq(CarSensorManager.SENSOR_TYPE_IGNITION_STATE), any());
    }

    /**
     * Test that the {@link CarLocationService} parses a location from a JSON serialization and then
     * injects it into the {@link LocationManager} upon boot complete.
     *
     * @throws IOException
     * @throws InterruptedException
     */
    public void testLoadsLocation() throws IOException, InterruptedException {
        long currentTime = System.currentTimeMillis();
        long elapsedTime = SystemClock.elapsedRealtimeNanos();
        long pastTime = currentTime - 60000;
        long pastElapsedTime = elapsedTime - 60000000000L;
        writeCacheFile("{\"provider\": \"gps\", \"latitude\": 16.7666, \"longitude\": 3.0026,"
                + "\"accuracy\":12.3, \"captureTime\": " + pastTime + ", \"elapsedTime\": "
                + pastElapsedTime + "}");
        ArgumentCaptor<Location> argument = ArgumentCaptor.forClass(Location.class);
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.injectLocation(argument.capture())).thenReturn(true);
        when(mockContext.getFileStreamPath("location_cache.json"))
                .thenReturn(mContext.getFileStreamPath(TEST_FILENAME));

        mCarLocationService.onReceive(mockContext, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
        mLatch.await();

        Location location = argument.getValue();
        assertEquals("gps", location.getProvider());
        assertEquals(16.7666, location.getLatitude());
        assertEquals(3.0026, location.getLongitude());
        assertEquals(12.3f, location.getAccuracy());
        assertTrue(location.getTime() >= currentTime);
        assertTrue(location.getElapsedRealtimeNanos() >= elapsedTime);
    }

    /**
     * Test that the {@link CarLocationService} does not inject a location if there is no location
     * cache file.
     *
     * @throws FileNotFoundException
     * @throws InterruptedException
     */
    public void testDoesNotLoadLocationWhenNoFileExists()
            throws FileNotFoundException, InterruptedException {
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                .thenReturn(null);
        when(mockContext.getFileStreamPath("location_cache.json"))
                .thenReturn(mContext.getFileStreamPath(TEST_FILENAME));
        mCarLocationService.onReceive(mockContext, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
        mLatch.await();
        verify(mockLocationManager, never()).injectLocation(any());
    }

    /**
     * Test that the {@link CarLocationService} handles an incomplete JSON file gracefully.
     *
     * @throws IOException
     * @throws InterruptedException
     */
    public void testDoesNotLoadLocationFromIncompleteFile() throws IOException,
            InterruptedException {
        writeCacheFile("{\"provider\": \"gps\", \"latitude\": 16.7666, \"longitude\": 3.0026,");
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                .thenReturn(null);
        when(mockContext.getFileStreamPath("location_cache.json"))
                .thenReturn(mContext.getFileStreamPath(TEST_FILENAME));
        mCarLocationService.onReceive(mockContext, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
        mLatch.await();
        verify(mockLocationManager, never()).injectLocation(any());
    }

    /**
     * Test that the {@link CarLocationService} handles a corrupt JSON file gracefully.
     *
     * @throws IOException
     * @throws InterruptedException
     */
    public void testDoesNotLoadLocationFromCorruptFile() throws IOException, InterruptedException {
        writeCacheFile("{\"provider\":\"latitude\":16.7666,\"longitude\": \"accuracy\":1.0}");
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                .thenReturn(null);
        when(mockContext.getFileStreamPath("location_cache.json"))
                .thenReturn(mContext.getFileStreamPath(TEST_FILENAME));
        mCarLocationService.onReceive(mockContext, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
        mLatch.await();
        verify(mockLocationManager, never()).injectLocation(any());
    }

    /**
     * Test that the {@link CarLocationService} does not inject a location that is missing
     * accuracy.
     *
     * @throws IOException
     * @throws InterruptedException
     */
    public void testDoesNotLoadIncompleteLocation() throws IOException, InterruptedException {
        writeCacheFile("{\"provider\": \"gps\", \"latitude\": 16.7666, \"longitude\": 3.0026}");
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                .thenReturn(null);
        when(mockContext.getFileStreamPath("location_cache.json"))
                .thenReturn(mContext.getFileStreamPath(TEST_FILENAME));
        mCarLocationService.onReceive(mockContext, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
        mLatch.await();
        verify(mockLocationManager, never()).injectLocation(any());
    }

    /**
     * Test that the {@link CarLocationService} stores the {@link LocationManager}'s last known
     * location in a JSON file upon ignition-off events.
     *
     * @throws IOException
     * @throws RemoteException
     * @throws InterruptedException
     */
    public void testStoresLocation() throws IOException, RemoteException, InterruptedException {
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
        sendIgnitionOffEvent();
        mLatch.await();
        verify(mockLocationManager).getLastKnownLocation(LocationManager.GPS_PROVIDER);
        String actualContents = readCacheFile();
        String expectedContents = "{\"provider\":\"gps\",\"latitude\":16.7666,\"longitude\":"
                + "3.0026,\"accuracy\":13.75,\"elapsedTime\":" + elapsedTime + ",\"captureTime\":"
                + currentTime + "}";
        assertEquals(expectedContents, actualContents);
    }

    /**
     * Test that the {@link CarLocationService} does not store null locations.
     *
     * @throws Exception
     */
    public void testDoesNotStoreNullLocation() throws Exception {
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
                .thenReturn(null);
        when(mockContext.getFileStreamPath("location_cache.json"))
                .thenReturn(mContext.getFileStreamPath(TEST_FILENAME));
        sendIgnitionOffEvent();
        mLatch.await();
        verify(mockLocationManager).getLastKnownLocation(LocationManager.GPS_PROVIDER);
        verify(mockContext).deleteFile("location_cache.json");
    }

    /**
     * Test that the {@link CarLocationService} deletes location_cache.json when location is
     * disabled.
     *
     * @throws Exception
     */
    public void testDeletesCacheFileWhenLocationIsDisabled() throws Exception {
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.isLocationEnabled()).thenReturn(false);
        mCarLocationService.init();
        mCarLocationService.onReceive(mockContext, new Intent(LocationManager.MODE_CHANGED_ACTION));
        mLatch.await();
        verify(mockLocationManager, times(1)).isLocationEnabled();
        verify(mockContext).deleteFile("location_cache.json");
    }

    /**
     * Test that the {@link CarLocationService} deletes location_cache.json when the GPS location
     * provider is disabled.
     *
     * @throws Exception
     */
    public void testDeletesCacheFileWhenTheGPSProviderIsDisabled() throws Exception {
        when(mockContext.getSystemService(Context.LOCATION_SERVICE))
                .thenReturn(mockLocationManager);
        when(mockLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)).thenReturn(false);
        mCarLocationService.init();
        mCarLocationService.onReceive(mockContext,
                new Intent(LocationManager.GPS_ENABLED_CHANGE_ACTION));
        mLatch.await();
        verify(mockLocationManager, times(1))
                .isProviderEnabled(LocationManager.GPS_PROVIDER);
        verify(mockContext).deleteFile("location_cache.json");
    }

    private void writeCacheFile(String json) throws IOException {
        FileOutputStream fos = mContext.openFileOutput(TEST_FILENAME, Context.MODE_PRIVATE);
        fos.write(json.getBytes());
        fos.close();
    }

    private String readCacheFile() throws IOException {
        FileInputStream fis = mContext.openFileInput(TEST_FILENAME);
        String json = new BufferedReader(new InputStreamReader(fis)).lines()
                .parallel().collect(Collectors.joining("\n"));
        fis.close();
        return json;
    }

    private void sendIgnitionOffEvent() throws RemoteException {
        mCarLocationService.init();
        ArgumentCaptor<ICarSensorEventListener> argument =
                ArgumentCaptor.forClass(ICarSensorEventListener.class);
        verify(mockCarSensorService).registerOrUpdateSensorListener(
                eq(CarSensorManager.SENSOR_TYPE_IGNITION_STATE), eq(0), argument.capture());
        ICarSensorEventListener carSensorEventListener = argument.getValue();
        CarSensorEvent ignitionOff = new CarSensorEvent(CarSensorManager.SENSOR_TYPE_IGNITION_STATE,
                System.currentTimeMillis(), 0, 1, 0);
        ignitionOff.intValues[0] = CarSensorEvent.IGNITION_STATE_OFF;
        List<CarSensorEvent> events = new ArrayList<>(Arrays.asList(ignitionOff));
        carSensorEventListener.onSensorChanged(events);
    }
}
