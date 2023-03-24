/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.native.telemetry;

import android.native.telemetry.INativeTelemetryReportListener;
import android.native.telemetry.INativeTelemetryReportReadyListener;

/**
 * Internal binder interface for {@code CarTelemetryService}, used by {@code CarTelemetryManager}.
 *
 * @hide
 */
interface INativeTelemetryService {

    /**
     * Adds telemetry MetricsConfigs to CarTelemetryService. Status code is sent to
     * CarTelemetryManager via ResultReceiver.
     */
    void addMetricsConfig(in String metricsConfigName, in byte[] metricsConfig);

    /**
     * Removes a MetricsConfig based on the name. This will also remove outputs produced by the
     * MetricsConfig.
     */
    void removeMetricsConfig(in String metricsConfigName);

    /**
     * Removes all MetricsConfigs. This will also remove all MetricsConfig outputs.
     */
    void removeAllMetricsConfigs();

    /**
     * Sends finished telemetry reports or errors associated with the given name using the
     * {@code INativeTelemetryServiceListener}.
     */
    void getFinishedReport(in String metricsConfigName, in INativeTelemetryReportListener listener);

    /**
     * Registers a listener for receiving notifications when a report or telemetry error is ready.
     */
    void setReportReadyListener(in INativeTelemetryReportReadyListener listener);

    /**
     * Clears listener to stop receiving notifications when a report or telemetry error is ready.
     */
    void clearReportReadyListener();
}