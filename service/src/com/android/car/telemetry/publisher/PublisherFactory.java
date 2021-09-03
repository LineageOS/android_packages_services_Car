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

package com.android.car.telemetry.publisher;

import android.content.SharedPreferences;
import android.os.Handler;

import com.android.car.CarPropertyService;
import com.android.car.telemetry.TelemetryProto;

/**
 * Factory class for Publishers. It's expected to have a single factory instance. Must be called
 * from the telemetry thread.
 */
public class PublisherFactory {
    private final Object mLock = new Object();
    private final CarPropertyService mCarPropertyService;
    private final Handler mTelemetryHandler;
    private final StatsManagerProxy mStatsManager;
    private final SharedPreferences mSharedPreferences;
    private VehiclePropertyPublisher mVehiclePropertyPublisher;
    private StatsPublisher mStatsPublisher;

    public PublisherFactory(
            CarPropertyService carPropertyService,
            Handler handler,
            StatsManagerProxy statsManager,
            SharedPreferences sharedPreferences) {
        mCarPropertyService = carPropertyService;
        mTelemetryHandler = handler;
        mStatsManager = statsManager;
        mSharedPreferences = sharedPreferences;
    }

    /** Returns publisher by given type. */
    public AbstractPublisher getPublisher(
            TelemetryProto.Publisher.PublisherCase type) {
        // No need to optimize locks, as this method is infrequently called.
        synchronized (mLock) {
            switch (type.getNumber()) {
                case TelemetryProto.Publisher.VEHICLE_PROPERTY_FIELD_NUMBER:
                    if (mVehiclePropertyPublisher == null) {
                        mVehiclePropertyPublisher = new VehiclePropertyPublisher(
                                mCarPropertyService, mTelemetryHandler);
                    }
                    return mVehiclePropertyPublisher;
                // TODO(b/189142577): add cartelemetry publisher here
                case TelemetryProto.Publisher.STATS_FIELD_NUMBER:
                    if (mStatsPublisher == null) {
                        mStatsPublisher = new StatsPublisher(mStatsManager, mSharedPreferences);
                    }
                    return mStatsPublisher;
                default:
                    throw new IllegalArgumentException(
                            "Publisher type " + type + " is not supported");
            }
        }
    }
}
