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

import android.car.telemetry.MetricsConfigKey;
import android.os.Parcel;
import android.os.PersistableBundle;

import com.android.car.telemetry.TelemetryProto;

/**
 * A wrapper class containing all the necessary information to invoke the ScriptExecutor API. It
 * is enqueued into the priority queue where it pends execution by {@link DataBroker}.
 * It implements the {@link Comparable} interface so it has a natural ordering by priority and
 * creation timestamp in the priority queue.
 * The object can be accessed from any thread. See {@link DataSubscriber} for thread-safety.
 */
public class ScriptExecutionTask implements Comparable<ScriptExecutionTask> {
    // Key to the calculated bundle size, which doesn't contain implementation-dependent overhead
    // and other allocations. It's approximately 10% smaller than the actual size of binder parcel.
    public static final String APPROX_BUNDLE_SIZE_BYTES_KEY = "approx_bundle_size_bytes";

    /**
     * Binder transaction size limit is 1MB for all binders per process, so for large script input
     * file pipe will be used to transfer the data to script executor instead of binder call. This
     * is the input size threshold above which piping is used.
     */
    private static final int LARGE_SCRIPT_INPUT_SIZE_BYTES = 20 * 1024; // 20 kb

    private final long mTimestampMillis;
    private final DataSubscriber mSubscriber;
    private final PersistableBundle mData;
    private final boolean mIsLargeData;

    ScriptExecutionTask(DataSubscriber subscriber, PersistableBundle data,
            long elapsedRealtimeMillis) {
        mTimestampMillis = elapsedRealtimeMillis;
        mSubscriber = subscriber;
        mData = data;
        if (mData.containsKey(APPROX_BUNDLE_SIZE_BYTES_KEY)) {
            mIsLargeData =
                    mData.getInt(APPROX_BUNDLE_SIZE_BYTES_KEY) > LARGE_SCRIPT_INPUT_SIZE_BYTES;
            mData.remove(APPROX_BUNDLE_SIZE_BYTES_KEY);
        } else {
            Parcel parcel = Parcel.obtain();
            parcel.writePersistableBundle(mData);
            mIsLargeData = parcel.dataSize() > LARGE_SCRIPT_INPUT_SIZE_BYTES;
            parcel.recycle();
        }
    }

    /** Returns the priority of the task. */
    public int getPriority() {
        return mSubscriber.getPriority();
    }

    /** Returns the creation timestamp of the task. */
    public long getCreationTimestampMillis() {
        return mTimestampMillis;
    }

    public TelemetryProto.MetricsConfig getMetricsConfig() {
        return mSubscriber.getMetricsConfig();
    }

    public String getHandlerName() {
        return mSubscriber.getHandlerName();
    }

    public PersistableBundle getData() {
        return mData;
    }

    /**
     * Indicates whether the task is associated with MetricsConfig specified by its key.
     */
    public boolean isAssociatedWithMetricsConfig(MetricsConfigKey key) {
        return mSubscriber.getMetricsConfig().getName().equals(key.getName())
                && mSubscriber.getMetricsConfig().getVersion() == key.getVersion();
    }

    /**
     * Returns the script input data size in bytes.
     */
    public boolean isLargeData() {
        return mIsLargeData;
    }

    @Override
    public int compareTo(ScriptExecutionTask other) {
        if (getPriority() < other.getPriority()) {
            return -1;
        } else if (getPriority() > other.getPriority()) {
            return 1;
        }
        // if equal priority, compare creation timestamps
        if (getCreationTimestampMillis() < other.getCreationTimestampMillis()) {
            return -1;
        }
        return 1;
    }
}
