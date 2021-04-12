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

import android.annotation.NonNull;
import android.car.Car;
import android.car.telemetry.ICarTelemetryService;
import android.car.telemetry.ICarTelemetryServiceListener;
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
        // TODO(b/150978930): verify that only a hardcoded app can set the listener
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
}
