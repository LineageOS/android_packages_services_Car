/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car;

import com.android.car.internal.util.IndentingPrintWriter;

/**
 * Base class for all Car specific services.
 */

// Note: VehicleHal and CarStatsService will implement CarSystemService directly.
// All other Car services will implement CarServiceBase which is a "marker" interface that
// extends CarSystemService. This makes it easy for ICarImpl to handle dump differently
// for VehicleHal and CarStatsService.
public interface CarSystemService {

    /**
     * Initializes the service.
     *
     * <p>All necessary initialization should be done and service should be functional after this.
     *
     * <p>This is only invoked from the main thread. This might be called more than once but is only
     * called after the constructor or after a release. e.g., a flow might be constructor(),
     * init(), release(), init().
     */
    void init();

    /**
     * Called when all other CarSystemService init completes.
     */
    default void onInitComplete() {}

    /**
     * Releases all resources to stop the service.
     *
     * <p>This is only invoked from the main thread.
     *
     * <p>It is possible that requests may still come to the service. It is okay to return error
     * for all operations after release, but the service must not crash.
     */
    void release();

    /**
     * Destroy the service.
     *
     * <p>This is only invoked from the main thread.
     *
     * <p>This is only invoked once before the instance is no longer used. This function should be
     * used to clean up resources created during the constructor, for example, quit the handler
     * thread and wait for it to finish.
     */
    default void destroy() {}

    /** Dumps its state. */
    void dump(IndentingPrintWriter writer);
}
