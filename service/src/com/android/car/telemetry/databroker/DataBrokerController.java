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

import android.os.Bundle;

import com.android.car.telemetry.TelemetryProto.MetricsConfig;
import com.android.car.telemetry.TelemetryProto.Publisher.PublisherCase;

import java.util.ArrayList;

/**
 * DataBrokerController instantiates the DataBroker and manages what Publishers
 * it can read from based current system states and policies.
 */
public class DataBrokerController {

    private MetricsConfig mMetricsConfig;
    private final DataBroker mDataBroker;
    private final ArrayList<PublisherCase> mAllowedPublishers = new ArrayList<>();

    public DataBrokerController() {
        mDataBroker = new DataBrokerImpl(this::onScriptResult);
        mAllowedPublishers.add(PublisherCase.VEHICLE_PROPERTY);
        mDataBroker.enablePublishers(mAllowedPublishers);
    }

    /**
     * Listens to script result from {@link DataBroker}.
     *
     * @param scriptResult the script result.
     */
    public void onScriptResult(Bundle scriptResult) {
        // TODO(b/187744195)
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
}
