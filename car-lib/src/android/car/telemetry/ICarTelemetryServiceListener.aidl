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

package android.car.telemetry;

import java.util.List;

/**
 * Binder interface implemented by {@code CarTelemetryManager}. Enables sending results from
 * {@code CarTelemetryService} to {@code CarTelemetryManager}.
 *
 * @hide
 */
oneway interface ICarTelemetryServiceListener {

    /**
     * Called by {@code CarTelemetryService} when there is a list of data to send to the
     * cloud app.
     *
     * @param data the serialized bytes of a message, e.g. the script execution results
     * or errors.
     */
    void onDataReceived(in byte[] data);
}