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

import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarPropertyEventListener;
import android.car.user.CarUserManagerHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.automotive.vehicle.V2_0.VehicleIgnitionState;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.List;

/**
 * This service stores the last known location from {@link LocationManager} when a car is parked
 * and restores the location when the car is powered on.
 */
public class CarLocationService extends BroadcastReceiver implements CarServiceBase,
        CarPowerManagementService.PowerEventProcessingHandler {
    private static final String TAG = "CarLocationService";
    private static final String FILENAME = "location_cache.json";
    private static final boolean DBG = false;
    // The accuracy for the stored timestamp
    private static final long GRANULARITY_ONE_DAY_MS = 24 * 60 * 60 * 1000L;
    // The time-to-live for the cached location
    private static final long TTL_THIRTY_DAYS_MS = 30 * GRANULARITY_ONE_DAY_MS;
    // The maximum number of times to try injecting a location
    private static final int MAX_LOCATION_INJECTION_ATTEMPTS = 10;

    // Used internally for mHandlerThread synchronization
    private final Object mLock = new Object();

    private final Context mContext;
    private final CarPowerManagementService mCarPowerManagementService;
    private final CarPropertyService mCarPropertyService;
    private final CarPropertyEventListener mCarPropertyEventListener;
    private final CarUserManagerHelper mCarUserManagerHelper;
    private int mTaskCount = 0;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    public CarLocationService(Context context, CarPowerManagementService carPowerManagementService,
            CarPropertyService carPropertyService, CarUserManagerHelper carUserManagerHelper) {
        logd("constructed");
        mContext = context;
        mCarPowerManagementService = carPowerManagementService;
        mCarPropertyService = carPropertyService;
        mCarPropertyEventListener = new CarPropertyEventListener();
        mCarUserManagerHelper = carUserManagerHelper;
    }

    @Override
    public void init() {
        logd("init");
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED);
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        filter.addAction(LocationManager.GPS_ENABLED_CHANGE_ACTION);
        mContext.registerReceiver(this, filter);
        mCarPropertyService.registerListener(VehicleProperty.IGNITION_STATE, 0,
                mCarPropertyEventListener);
        mCarPowerManagementService.registerPowerEventProcessingHandler(this);
    }

    @Override
    public void release() {
        logd("release");
        mCarPropertyService.unregisterListener(VehicleProperty.IGNITION_STATE,
                mCarPropertyEventListener);
        mContext.unregisterReceiver(this);
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("Context: " + mContext);
        writer.println("CarPropertyService: " + mCarPropertyService);
        writer.println("MAX_LOCATION_INJECTION_ATTEMPTS: " + MAX_LOCATION_INJECTION_ATTEMPTS);
    }

    @Override
    public long onPrepareShutdown(boolean shuttingDown) {
        logd("onPrepareShutdown " + shuttingDown);
        asyncOperation(() -> storeLocation());
        return 100;
    }

    @Override
    public void onPowerOn(boolean displayOn) {
    }

    @Override
    public int getWakeupTime() {
        return 0;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        logd("onReceive " + intent);
        String action = intent.getAction();
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            // If the system user is not headless, then we can inject location as soon as the
            // system has completed booting.
            if (!mCarUserManagerHelper.isHeadlessSystemUser()) {
                logd("not headless on boot complete");
                asyncOperation(() -> loadLocation());
            }
        } else if (action == Intent.ACTION_USER_SWITCHED) {
            int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            logd("USER_SWITCHED: " + userHandle);
            if (mCarUserManagerHelper.isHeadlessSystemUser()
                    && userHandle > UserHandle.USER_SYSTEM) {
                asyncOperation(() -> loadLocation());
            }
        } else if (action == LocationManager.MODE_CHANGED_ACTION
                && shouldCheckLocationPermissions()) {
            LocationManager locationManager =
                    (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
            boolean locationEnabled = locationManager.isLocationEnabled();
            logd("isLocationEnabled(): " + locationEnabled);
            if (!locationEnabled) {
                asyncOperation(() -> deleteCacheFile());
            }
        } else if (action == LocationManager.GPS_ENABLED_CHANGE_ACTION
                && shouldCheckLocationPermissions()) {
            LocationManager locationManager =
                    (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
            boolean gpsEnabled =
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            logd("isProviderEnabled('gps'): " + gpsEnabled);
            if (!gpsEnabled) {
                asyncOperation(() -> deleteCacheFile());
            }
        }
    }

    /**
     * Tells whether or not we should check location permissions for the sake of deleting the
     * location cache file when permissions are lacking.  If the system user is headless but the
     * current user is still the system user, then we should not respond to a lack of location
     * permissions.
     */
    private boolean shouldCheckLocationPermissions() {
        return !(mCarUserManagerHelper.isHeadlessSystemUser()
                && mCarUserManagerHelper.isCurrentProcessSystemUser());
    }

    /**
     * Gets the last known location from the LocationManager and store it in a file.
     */
    private void storeLocation() {
        LocationManager locationManager =
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (location == null) {
            logd("Not storing null location");
            deleteCacheFile();
        } else {
            logd("Storing location: " + location);
            AtomicFile atomicFile = new AtomicFile(mContext.getFileStreamPath(FILENAME));
            FileOutputStream fos = null;
            try {
                fos = atomicFile.startWrite();
                try (JsonWriter jsonWriter = new JsonWriter(new OutputStreamWriter(fos, "UTF-8"))) {
                    jsonWriter.beginObject();
                    jsonWriter.name("provider").value(location.getProvider());
                    jsonWriter.name("latitude").value(location.getLatitude());
                    jsonWriter.name("longitude").value(location.getLongitude());
                    if (location.hasAltitude()) {
                        jsonWriter.name("altitude").value(location.getAltitude());
                    }
                    if (location.hasSpeed()) {
                        jsonWriter.name("speed").value(location.getSpeed());
                    }
                    if (location.hasBearing()) {
                        jsonWriter.name("bearing").value(location.getBearing());
                    }
                    if (location.hasAccuracy()) {
                        jsonWriter.name("accuracy").value(location.getAccuracy());
                    }
                    if (location.hasVerticalAccuracy()) {
                        jsonWriter.name("verticalAccuracy").value(
                                location.getVerticalAccuracyMeters());
                    }
                    if (location.hasSpeedAccuracy()) {
                        jsonWriter.name("speedAccuracy").value(
                                location.getSpeedAccuracyMetersPerSecond());
                    }
                    if (location.hasBearingAccuracy()) {
                        jsonWriter.name("bearingAccuracy").value(
                                location.getBearingAccuracyDegrees());
                    }
                    if (location.isFromMockProvider()) {
                        jsonWriter.name("isFromMockProvider").value(true);
                    }
                    long currentTime = location.getTime();
                    // Round the time down to only be accurate within one day.
                    jsonWriter.name("captureTime").value(
                            currentTime - currentTime % GRANULARITY_ONE_DAY_MS);
                    jsonWriter.endObject();
                }
                atomicFile.finishWrite(fos);
            } catch (IOException e) {
                Log.e(TAG, "Unable to write to disk", e);
                atomicFile.failWrite(fos);
            }
        }
    }

    /**
     * Reads a previously stored location and attempts to inject it into the LocationManager.
     */
    private void loadLocation() {
        Location location = readLocationFromCacheFile();
        logd("Read location from " + location.getTime());
        long currentTime = System.currentTimeMillis();
        if (location.getTime() + TTL_THIRTY_DAYS_MS < currentTime) {
            logd("Location expired.");
        } else {
            location.setTime(currentTime);
            long elapsedTime = SystemClock.elapsedRealtimeNanos();
            location.setElapsedRealtimeNanos(elapsedTime);
            if (location.isComplete()) {
                injectLocation(location, 1);
            }
        }
    }

    private Location readLocationFromCacheFile() {
        Location location = new Location((String) null);
        AtomicFile atomicFile = new AtomicFile(mContext.getFileStreamPath(FILENAME));
        try (FileInputStream fis = atomicFile.openRead()) {
            JsonReader reader = new JsonReader(new InputStreamReader(fis, "UTF-8"));
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.equals("provider")) {
                    location.setProvider(reader.nextString());
                } else if (name.equals("latitude")) {
                    location.setLatitude(reader.nextDouble());
                } else if (name.equals("longitude")) {
                    location.setLongitude(reader.nextDouble());
                } else if (name.equals("altitude")) {
                    location.setAltitude(reader.nextDouble());
                } else if (name.equals("speed")) {
                    location.setSpeed((float) reader.nextDouble());
                } else if (name.equals("bearing")) {
                    location.setBearing((float) reader.nextDouble());
                } else if (name.equals("accuracy")) {
                    location.setAccuracy((float) reader.nextDouble());
                } else if (name.equals("verticalAccuracy")) {
                    location.setVerticalAccuracyMeters((float) reader.nextDouble());
                } else if (name.equals("speedAccuracy")) {
                    location.setSpeedAccuracyMetersPerSecond((float) reader.nextDouble());
                } else if (name.equals("bearingAccuracy")) {
                    location.setBearingAccuracyDegrees((float) reader.nextDouble());
                } else if (name.equals("isFromMockProvider")) {
                    location.setIsFromMockProvider(reader.nextBoolean());
                } else if (name.equals("captureTime")) {
                    location.setTime(reader.nextLong());
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            deleteCacheFile();
        } catch (FileNotFoundException e) {
            Log.d(TAG, "Location cache file not found.");
        } catch (IOException e) {
            Log.e(TAG, "Unable to read from disk", e);
        } catch (NumberFormatException | IllegalStateException e) {
            Log.e(TAG, "Unexpected format", e);
        }
        return location;
    }

    private void deleteCacheFile() {
        logd("Deleting cache file");
        mContext.deleteFile(FILENAME);
    }

    /**
     * Attempts to inject the location multiple times in case the LocationManager was not fully
     * initialized or has not updated its handle to the current user yet.
     */
    private void injectLocation(Location location, int attemptCount) {
        LocationManager locationManager =
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        boolean success = locationManager.injectLocation(location);
        logd("Injected location " + location + " with result " + success + " on attempt "
                + attemptCount);
        if (success) {
            return;
        } else if (attemptCount <= MAX_LOCATION_INJECTION_ATTEMPTS) {
            asyncOperation(() -> {
                injectLocation(location, attemptCount + 1);
            }, 200 * attemptCount);
        } else {
            logd("No location injected.");
        }
    }

    @VisibleForTesting
    void asyncOperation(Runnable operation) {
        asyncOperation(operation, 0);
    }

    private void asyncOperation(Runnable operation, long delayMillis) {
        synchronized (mLock) {
            // Create a new HandlerThread if this is the first task to queue.
            if (++mTaskCount == 1) {
                mHandlerThread = new HandlerThread("CarLocationServiceThread");
                mHandlerThread.start();
                mHandler = new Handler(mHandlerThread.getLooper());
            }
        }
        mHandler.postDelayed(() -> {
            try {
                operation.run();
            } finally {
                synchronized (mLock) {
                    // Quit the thread when the task queue is empty.
                    if (--mTaskCount == 0) {
                        mHandler.getLooper().quit();
                        mHandler = null;
                        mHandlerThread = null;
                    }
                }
            }
        }, delayMillis);
    }

    private static void logd(String msg) {
        if (DBG) {
            Log.d(TAG, msg);
        }
    }

    private class CarPropertyEventListener extends ICarPropertyEventListener.Stub {
        @Override
        public void onEvent(List<CarPropertyEvent> events) throws RemoteException {
            for (CarPropertyEvent event : events) {
                if (event.getEventType() == CarPropertyEvent.PROPERTY_EVENT_PROPERTY_CHANGE) {
                    CarPropertyValue value = event.getCarPropertyValue();
                    if (value.getPropertyId() == VehicleProperty.IGNITION_STATE) {
                        int ignitionState = (Integer) value.getValue();
                        logd("property ignition value: " + ignitionState);
                        if (ignitionState == VehicleIgnitionState.OFF) {
                            logd("ignition off");
                            asyncOperation(() -> storeLocation());
                        }
                    }
                }
            }
        }
    }
}
