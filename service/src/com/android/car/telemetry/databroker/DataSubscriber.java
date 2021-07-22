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
import android.os.SystemClock;

import com.android.car.telemetry.TelemetryProto;

/**
 * Subscriber class that receives published data and schedules tasks for execution.
 * The class is thread-safe as long as
 * {@link com.android.car.telemetry.TelemetryProto.MetricsConfig} does not change during runtime.
 * TODO(b/187743369): thread-safety can change if priority can be updated in runtime. Update
 *                    javadoc once priority is concretely defined.
 *                    Must be thread-safe, as #push() method may be called by multiple threads.
 */
public class DataSubscriber {

    private final int mPriority;
    private final DataBroker mDataBroker;
    private final TelemetryProto.MetricsConfig mMetricsConfig;
    private final TelemetryProto.Subscriber mSubscriber;

    public DataSubscriber(
            DataBroker dataBroker,
            TelemetryProto.MetricsConfig metricsConfig,
            TelemetryProto.Subscriber subscriber,
            int priority) {
        mDataBroker = dataBroker;
        mMetricsConfig = metricsConfig;
        mSubscriber = subscriber;
        mPriority = priority;
    }

    /** Returns the handler function name for this subscriber. */
    public String getHandlerName() {
        return mSubscriber.getHandler();
    }

    /**
     * Returns the publisher param {@link com.android.car.telemetry.TelemetryProto.Publisher} that
     * contains the data source and the config.
     */
    public TelemetryProto.Publisher getPublisherParam() {
        return mSubscriber.getPublisher();
    }

    /**
     * Creates a {@link ScriptExecutionTask} and pushes it to the priority queue where the task
     * will be pending execution.
     *
     * <p>This method is thread-safe and doesn't block.
     */
    public void push(Bundle data) {
        ScriptExecutionTask task = new ScriptExecutionTask(
                this, data, SystemClock.elapsedRealtime());
        mDataBroker.addTaskToQueue(task); // thread-safe
    }

    /** Returns the {@link com.android.car.telemetry.TelemetryProto.MetricsConfig}. */
    public TelemetryProto.MetricsConfig getMetricsConfig() {
        return mMetricsConfig;
    }

    /** Returns the priority of subscriber. */
    public int getPriority() {
        return mPriority;
    }
}
