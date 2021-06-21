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
 * Interface for publishers. It's implemented per data source, data is sent to
 * {@link DataSubscriber}. Publisher stops itself when there are no subscribers.
 *
 * <p>Note that it doesn't map 1-1 to {@link TelemetryProto.Publisher} configuration. Single
 * publisher instance can send data as several {@link TelemetryProto.Publisher} to subscribers.
 */
public interface Publisher {
    /** Adds a subscriber that listens for data produced by this publisher. */
    /**
     * Adds a subscriber that listens for data produced by this publisher.
     * @param dataSubscriber a subscriber to receive data
     * @throws IllegalArgumentException if the subscriber was added before.
     */
    void addSubscriber(DataSubscriber dataSubscriber);

    /**  */
    /**
     * Removes a subscriber from the publisher.
     *
     * @param dataSubscriber to be removed
     * @throws IllegalArgumentException if the subscriber was not found.
     */
    void removeSubscriber(DataSubscriber dataSubscriber);
}
