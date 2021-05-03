/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.car.telemetry;

import static android.car.telemetry.CarTelemetryManager.ERROR_NONE;

import android.annotation.NonNull;
import android.car.Car;
import android.car.telemetry.CarTelemetryManager.AddManifestError;
import android.car.telemetry.ICarTelemetryService;
import android.car.telemetry.ICarTelemetryServiceListener;
import android.car.telemetry.ManifestKey;
import android.content.Context;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.car.CarServiceBase;

/**
 * CarTelemetryService manages OEM telemetry collection, processing and communication
 * with a data upload service.
 */
public class CarTelemetryService extends ICarTelemetryService.Stub implements CarServiceBase {

    private static final boolean DEBUG = false;
    private static final String TAG = CarTelemetryService.class.getSimpleName();

    private final Context mContext;

    private ICarTelemetryServiceListener mListener;

    public CarTelemetryService(Context context) {
        mContext = context;
    }

    @Override
    public void init() {
        // nothing to do
    }

    @Override
    public void release() {
        // nothing to do
    }

    @Override
    public void dump(IndentingPrintWriter writer) {
        writer.println("Car Telemetry service");
    }

    /**
     * Registers a listener with CarTelemetryService for the service to send data to cloud app.
     */
    @Override
    public void setListener(@NonNull ICarTelemetryServiceListener listener) {
        // TODO(b/184890506): verify that only a hardcoded app can set the listener
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "setListener");
        if (DEBUG) {
            Slog.d(TAG, "Setting the listener for car telemetry service");
        }
        mListener = listener;
    }

    /**
     * Clears the listener registered with CarTelemetryService.
     */
    @Override
    public void clearListener() {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "setListener");
        if (DEBUG) {
            Slog.d(TAG, "Clearing listener");
        }
        mListener = null;
    }

    /**
     * Allows client to send telemetry manifests.
     *
     * @param key      the unique key to identify the manifest.
     * @param manifest the serialized bytes of a Manifest object.
     * @return {@link AddManifestError} the error code.
     */
    @Override
    public @AddManifestError int addManifest(@NonNull ManifestKey key, @NonNull byte[] manifest) {
        // TODO(b/184087869): Implement
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "setListener");
        if (DEBUG) {
            Slog.d(TAG, "Adding manifest to car telemetry service");
        }
        return ERROR_NONE;
    }

    /**
     * Removes a manifest based on the key.
     */
    @Override
    public boolean removeManifest(@NonNull ManifestKey key) {
        // TODO(b/184087869): Implement
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "setListener");
        if (DEBUG) {
            Slog.d(TAG, "Removing manifest from car telemetry service");
        }
        return true;
    }

    /**
     * Removes all manifests.
     */
    @Override
    public void removeAllManifests() {
        // TODO(b/184087869): Implement
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "setListener");
        if (DEBUG) {
            Slog.d(TAG, "Removing all manifest from car telemetry service");
        }
    }

    /**
     * Sends script results associated with the given key using the
     * {@link ICarTelemetryServiceListener}.
     */
    @Override
    public void sendFinishedReports(@NonNull ManifestKey key) {
        // TODO(b/184087869): Implement
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "setListener");
        if (DEBUG) {
            Slog.d(TAG, "Flushing reports for a manifest");
        }
    }

    /**
     * Sends all script results associated using the {@link ICarTelemetryServiceListener}.
     */
    @Override
    public void sendAllFinishedReports() {
        // TODO(b/184087869): Implement
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "setListener");
        if (DEBUG) {
            Slog.d(TAG, "Flushing all reports");
        }
    }

    /**
     * Sends all errors using the {@link ICarTelemetryServiceListener}.
     */
    @Override
    public void sendScriptExecutionErrors() {
        // TODO(b/184087869): Implement
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "setListener");
        if (DEBUG) {
            Slog.d(TAG, "Flushing script execution errors");
        }
    }
}
