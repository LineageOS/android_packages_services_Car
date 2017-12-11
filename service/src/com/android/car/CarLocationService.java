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

import android.car.hardware.CarSensorEvent;
import android.car.hardware.CarSensorManager;
import android.car.hardware.ICarSensorEventListener;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.RemoteException;
import android.util.AtomicFile;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.List;

/**
 * This service stores the last known location from {@link LocationManager} when a car is parked
 * and restores the location when the car is powered on.
 *
 * TODO(b/71756499) Move file I/O into a separate thread so that event dispatching is not blocked.
 */
public class CarLocationService implements CarServiceBase,
        CarPowerManagementService.PowerEventProcessingHandler {
    private static String TAG = "CarLocationService";
    private static String FILENAME = "location_cache.json";
    private static final boolean DBG = false;

    // Used internally for synchronization
    private final Object mLock = new Object();

    private final Context mContext;
    private final CarPowerManagementService mPowerManagementService;
    private final CarSensorService mCarSensorService;
    private final CarSensorEventListener mCarSensorEventListener;

    public CarLocationService(Context context,
            CarPowerManagementService powerManagementService, CarSensorService carSensorService) {
        logd("constructed");
        mContext = context;
        mPowerManagementService = powerManagementService;
        mCarSensorService = carSensorService;
        mCarSensorEventListener = new CarSensorEventListener();
    }

    @Override
    public void init() {
        logd("init");
        mPowerManagementService.registerPowerEventProcessingHandler(this);
        mCarSensorService.registerOrUpdateSensorListener(
                CarSensorManager.SENSOR_TYPE_IGNITION_STATE, 0, mCarSensorEventListener);
    }

    @Override
    public void release() {
        logd("release");
        mCarSensorService.unregisterSensorListener(CarSensorManager.SENSOR_TYPE_IGNITION_STATE,
                mCarSensorEventListener);
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("Context: " + mContext);
        writer.println("PowerManagementService: " + mPowerManagementService);
        writer.println("CarSensorService: " + mCarSensorService);
    }

    @Override
    public void onPowerOn(boolean displayOn) {
        logd("onPowerOn");
        loadLocation();
    }

    @Override
    public long onPrepareShutdown(boolean shuttingDown) {
        logd("onPrepareShutdown");
        return 0;
    }

    private void storeLocation() {
        LocationManager locationManager =
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (location == null) {
            logd("Not storing null location");
        } else {
            logd("Storing location: " + location);
            AtomicFile atomicFile = new AtomicFile(mContext.getFileStreamPath(FILENAME));
            FileOutputStream fos = null;
            try {
                synchronized (mLock) {
                    fos = atomicFile.startWrite();
                    JsonWriter jsonWriter = new JsonWriter(new OutputStreamWriter(fos, "UTF-8"));
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
                    jsonWriter.name("elapsedTime").value(location.getElapsedRealtimeNanos());
                    jsonWriter.name("time").value(location.getTime());
                    jsonWriter.endObject();
                    jsonWriter.close();
                    atomicFile.finishWrite(fos);
                }
            } catch (IOException e) {
                Log.e(TAG, "Unable to write to disk", e);
                atomicFile.failWrite(fos);
            }
        }
    }

    private void loadLocation() {
        LocationManager locationManager =
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        Location location = new Location((String) null);
        AtomicFile atomicFile = new AtomicFile(mContext.getFileStreamPath(FILENAME));
        try {
            synchronized (mLock) {
                FileInputStream fis = atomicFile.openRead();
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
                    } else if (name.equals("elapsedTime")) {
                        location.setElapsedRealtimeNanos(reader.nextLong());
                    } else if (name.equals("time")) {
                        location.setTime(reader.nextLong());
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
                fis.close();
            }
            if (location.isComplete()) {
                locationManager.injectLocation(location);
                logd("Loaded location: " + location);
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to read from disk", e);
        } catch (NumberFormatException | IllegalStateException e) {
            Log.e(TAG, "Unexpected format", e);
        }
    }

    @Override
    public int getWakeupTime() {
        return 0;
    }

    private static void logd(String msg) {
        if (DBG) {
            Log.d(TAG, msg);
        }
    }

    private class CarSensorEventListener extends ICarSensorEventListener.Stub {
        @Override
        public void onSensorChanged(List<CarSensorEvent> events) throws RemoteException {
            CarSensorEvent event = events.get(0);
            if (event.sensorType == CarSensorManager.SENSOR_TYPE_IGNITION_STATE) {
                logd("sensor ignition value: " + event.intValues[0]);
                if (event.intValues[0] == CarSensorEvent.IGNITION_STATE_OFF) {
                    logd("ignition off");
                    storeLocation();
                }
            }
        }
    }
}
