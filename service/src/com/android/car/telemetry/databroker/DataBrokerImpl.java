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

import android.util.ArrayMap;

import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.TelemetryProto.MetricsConfig;
import com.android.car.telemetry.publisher.LogcatPublisher;
import com.android.car.telemetry.publisher.Publisher;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the data path component of CarTelemetryService. Forwards the published data
 * from publishers to consumers subject to the Controller's decision.
 */
public class DataBrokerImpl implements DataBroker {

    // Publisher is created per data source type. Publishers are kept alive once created. This map
    // is used to check if a publisher already exists for a given type to prevent duplicate
    // instantiation.
    private final Map<TelemetryProto.Publisher.PublisherCase, Publisher> mPublisherMap =
            new ArrayMap<>();

    // Maps MetricsConfig's name to its subscriptions. This map is useful when removing a
    // MetricsConfig.
    private final Map<String, List<DataSubscriber>> mSubscriptionMap = new ArrayMap<>();

    private DataBrokerController.ScriptFinishedCallback mScriptFinishedCallback;

    @Override
    public boolean addMetricsConfiguration(MetricsConfig metricsConfig) {
        // if metricsConfig already exists, it should not be added again
        if (mSubscriptionMap.containsKey(metricsConfig.getName())) {
            return false;
        }
        // Create the subscribers for this metrics configuration
        List<DataSubscriber> dataSubscribers = new ArrayList<>();
        for (TelemetryProto.Subscriber subscriber : metricsConfig.getSubscribersList()) {
            // protobuf publisher to a concrete Publisher
            Publisher publisher = getOrCreatePublisherFromType(
                    subscriber.getPublisher().getPublisherCase());

            // create DataSubscriber from TelemetryProto.Subscriber
            DataSubscriber dataSubscriber = new DataSubscriber(metricsConfig, subscriber);
            dataSubscribers.add(dataSubscriber);

            publisher.addSubscriber(dataSubscriber); // add subscriber to receive data
        }
        mSubscriptionMap.put(metricsConfig.getName(), dataSubscribers);
        return true;
    }

    @Override
    public boolean removeMetricsConfiguration(MetricsConfig metricsConfig) {
        if (!mSubscriptionMap.containsKey(metricsConfig.getName())) {
            return false;
        }
        // get the subscriptions associated with this MetricsConfig, remove it from the map
        List<DataSubscriber> dataSubscribers = mSubscriptionMap.remove(metricsConfig.getName());
        // for each subscriber, remove it from publishers
        for (DataSubscriber subscriber : dataSubscribers) {
            Publisher publisher = getOrCreatePublisherFromType(
                    subscriber.getPublisherParam().getPublisherCase());
            publisher.removeSubscriber(subscriber);
            // TODO(b/187743369): remove related tasks from the queue
        }
        return true;
    }

    @Override
    public void setOnScriptFinishedCallback(DataBrokerController.ScriptFinishedCallback callback) {
        mScriptFinishedCallback = callback;
    }

    /**
     * Gets and returns a {@link com.android.car.telemetry.publisher.Publisher} if it exists in
     * the map, or creates one from the {@link com.android.car.telemetry.TelemetryProto.Publisher}'s
     * type.
     */
    private Publisher getOrCreatePublisherFromType(
            TelemetryProto.Publisher.PublisherCase type) {
        Publisher publisher = mPublisherMap.get(type);
        // check if publisher exists for this source
        if (publisher != null) {
            return publisher;
        }
        // TODO(b/187743369): use switch statement to create the correct publisher
        publisher = new LogcatPublisher();
        mPublisherMap.put(type, publisher);
        return publisher;
    }

    @VisibleForTesting
    Map<TelemetryProto.Publisher.PublisherCase, Publisher> getPublisherMap() {
        return mPublisherMap;
    }

    @VisibleForTesting
    Map<String, List<DataSubscriber>> getSubscriptionMap() {
        return mSubscriptionMap;
    }
}
