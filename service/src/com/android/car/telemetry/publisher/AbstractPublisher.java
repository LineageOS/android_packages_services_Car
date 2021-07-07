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

import com.android.car.telemetry.databroker.DataSubscriber;

/**
 * Abstract class for publishers. It is 1-1 with data source and manages sending data to
 * subscribers. Publisher stops itself when there are no subscribers.
 *
 * <p>Note that it doesn't map 1-1 to {@link com.android.car.telemetry.TelemetryProto.Publisher}
 * configuration. Single publisher instance can send data as several
 * {@link com.android.car.telemetry.TelemetryProto.Publisher} to subscribers.
 *
 * <p>Child classes must be thread-safe.
 */
public abstract class AbstractPublisher {
    /**
     * Adds a subscriber that listens for data produced by this publisher.
     *
     * @param subscriber a subscriber to receive data
     * @throws IllegalArgumentException if an invalid subscriber was provided.
     */
    public abstract void addDataSubscriber(DataSubscriber subscriber);

    /**
     * Removes the subscriber from the publisher. Publisher stops if necessary.
     *
     * @throws IllegalArgumentException if the subscriber was not found.
     */
    public abstract void removeDataSubscriber(DataSubscriber subscriber);

    /**
     * Removes all the subscribers from the publisher. The publisher may stop.
     */
    public abstract void removeAllDataSubscribers();

    /** Returns true if the publisher already has this data subscriber. */
    public abstract boolean hasDataSubscriber(DataSubscriber subscriber);
}
