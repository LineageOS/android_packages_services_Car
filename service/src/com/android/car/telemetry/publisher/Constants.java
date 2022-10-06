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

package com.android.car.telemetry.publisher;

/**
 * Container class for all constants that are related to transfer of data within
 * {@link com.android.car.telemetry.CarTelemetryService} and between {@link
 * com.android.car.telemetry.CarTelemetryService} and
 * {@link com.android.car.scriptexecutor.ScriptExecutor}
 * by using {@link android.os.PersistableBundle} objects.
 *
 * Each publisher is responsible for populating {@link android.os.PersistableBundle} instances with
 * data. This file keeps names of keys used in the bundles by various publishers all in one place.
 * This measure should allow clients of data to understand structure of {@link
 * android.os.PersistableBundle} objects without having to look for places where the objects are
 * populated.
 */
public final class Constants {
    public static final String CAR_TELEMETRYD_BUNDLE_KEY_ID = "id";
    public static final String CAR_TELEMETRYD_BUNDLE_KEY_CONTENT = "content";

    private Constants() {
    }
}
