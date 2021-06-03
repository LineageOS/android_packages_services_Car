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

import com.android.car.telemetry.TelemetryProto;

import java.util.List;

/**
 * Implementation of the data path component of CarTelemetryService. Forwards the published data
 * from publishers to consumers subject to the Controller's decision.
 */
public class DataBrokerImpl implements DataBroker {

    private final ScriptResultListener mScriptResultListener;

    public DataBrokerImpl(ScriptResultListener scriptResultListener) {
        mScriptResultListener = scriptResultListener;
    }

    @Override
    public void enablePublishers(List<TelemetryProto.Publisher.PublisherCase> allowedPublishers) {
        // TODO(b/187743369): implement
    }

    @Override
    public void addMetricsConfiguration(TelemetryProto.MetricsConfig metricsConfig) {
        // TODO(b/187743369): implement
    }

    @Override
    public void removeMetricsConfiguration(TelemetryProto.MetricsConfig metricsConfig) {
        // TODO(b/187743369): implement
    }
}
