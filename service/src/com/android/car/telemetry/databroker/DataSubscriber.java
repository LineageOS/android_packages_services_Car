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

import android.os.PersistableBundle;
import android.os.SystemClock;

import com.android.car.telemetry.TelemetryProto;

/**
 * Subscriber class that receives published data and schedules tasks for execution.
 * All methods of this class must be accessed on telemetry thread.
 *
 * <p>TODO(b/187743369): implement equals() and hash() functions, as they are used in publishers
 *                       to check equality of subscribers.
 */
public class DataSubscriber {

    private final DataBroker mDataBroker;
    private final TelemetryProto.MetricsConfig mMetricsConfig;
    private final TelemetryProto.Subscriber mSubscriber;

    public DataSubscriber(
            DataBroker dataBroker,
            TelemetryProto.MetricsConfig metricsConfig,
            TelemetryProto.Subscriber subscriber) {
        mDataBroker = dataBroker;
        mMetricsConfig = metricsConfig;
        mSubscriber = subscriber;
    }

    /** Returns the handler function name for this subscriber. */
    public String getHandlerName() {
        return mSubscriber.getHandler();
    }

    /**
     * Returns the publisher param {@link TelemetryProto.Publisher} that
     * contains the data source and the config.
     */
    public TelemetryProto.Publisher getPublisherParam() {
        return mSubscriber.getPublisher();
    }

    /**
     * Creates a {@link ScriptExecutionTask} and pushes it to the priority queue where the task
     * will be pending execution.
     */
    public void push(PersistableBundle data) {
        ScriptExecutionTask task = new ScriptExecutionTask(
                this, data, SystemClock.elapsedRealtime());
        mDataBroker.addTaskToQueue(task);
    }

    /** Returns the {@link TelemetryProto.MetricsConfig}. */
    public TelemetryProto.MetricsConfig getMetricsConfig() {
        return mMetricsConfig;
    }

    /** Returns the {@link TelemetryProto.Subscriber}. */
    public TelemetryProto.Subscriber getSubscriber() {
        return mSubscriber;
    }

    /** Returns the priority of subscriber. */
    public int getPriority() {
        return mSubscriber.getPriority();
    }
}
