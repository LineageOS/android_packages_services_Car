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

package com.android.car.telemetry.databroker;

import com.android.car.telemetry.TelemetryProto.MetricsConfig;

/**
 * DataBrokerController instantiates the DataBroker and manages what Publishers
 * it can read from based current system states and policies.
 */
public class DataBrokerController {

    private MetricsConfig mMetricsConfig;
    private final DataBroker mDataBroker;

    /**
     * Interface for receiving notification that script finished.
     */
    public interface ScriptFinishedCallback {
        /**
         * Listens to script finished event.
         *
         * @param configName the name of the config whose script finished.
         */
        void onScriptFinished(String configName);
    }

    /**
     * Interface for receiving notification about metric config changes.
     */
    public interface MetricsConfigCallback {
        /**
         * Listens to new metrics config event.
         *
         * @param metricsConfig the new metrics config.
         */
        void onNewMetricsConfig(MetricsConfig metricsConfig);
    }

    public DataBrokerController(DataBroker dataBroker) {
        mDataBroker = dataBroker;
        mDataBroker.setOnScriptFinishedCallback(this::onScriptFinished);
    }

    /**
     * Listens to new {@link MetricsConfig}.
     *
     * @param metricsConfig the metrics config.
     */
    public void onNewMetricsConfig(MetricsConfig metricsConfig) {
        mMetricsConfig = metricsConfig;
        mDataBroker.addMetricsConfiguration(mMetricsConfig);
    }

    /**
     * Listens to script finished event from {@link DataBroker}.
     *
     * @param configName the name of the config of the finished script.
     */
    public void onScriptFinished(String configName) {
        // TODO(b/187744195): remove finished config from config store
    }
}
