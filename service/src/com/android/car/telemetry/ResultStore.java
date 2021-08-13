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

package com.android.car.telemetry;

import android.os.Handler;
import android.os.PersistableBundle;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * Disk storage for interim and final metrics statistics.
 */
class ResultStore {

    @VisibleForTesting
    static final String INTERIM_RESULT_DIR = "interim";
    @VisibleForTesting
    static final String FINAL_RESULT_DIR = "final";

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<String, PersistableBundle> mInterimResultCache = new ArrayMap<>();

    private final File mInterimResultDirectory;
    private final File mFinalResultDirectory;
    private final Handler mWorkerHandler; // for all non I/O operations
    private final Handler mIoHandler; // for all I/O operations

    ResultStore(Handler handler, Handler ioHandler, File rootDirectory) {
        mWorkerHandler = handler;
        mIoHandler = ioHandler;
        mInterimResultDirectory = new File(rootDirectory, INTERIM_RESULT_DIR);
        mFinalResultDirectory = new File(rootDirectory, FINAL_RESULT_DIR);
        mInterimResultDirectory.mkdirs();
        mFinalResultDirectory.mkdirs();
        // load results into memory to reduce the frequency of disk access
        synchronized (mLock) {
            loadInterimResultsIntoMemoryLocked();
        }
    }

    /** Reads interim results into memory for faster access. */
    @GuardedBy("mLock")
    private void loadInterimResultsIntoMemoryLocked() {
        for (File file : mInterimResultDirectory.listFiles()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                mInterimResultCache.put(
                        file.getName(),
                        PersistableBundle.readFromStream(fis));
            } catch (IOException e) {
                Slog.w(CarLog.TAG_TELEMETRY, "Failed to read result from disk.", e);
            }
        }
    }

    /**
     * Retrieves interim metrics for the given
     * {@link com.android.car.telemetry.TelemetryProto.MetricsConfig}.
     */
    PersistableBundle getInterimResult(String metricsConfigName) {
        synchronized (mLock) {
            return mInterimResultCache.get(metricsConfigName);
        }
    }

    /**
     * Stores interim metrics results in memory for the given
     * {@link com.android.car.telemetry.TelemetryProto.MetricsConfig}.
     */
    void putInterimResult(String metricsConfigName, PersistableBundle result) {
        synchronized (mLock) {
            mInterimResultCache.put(metricsConfigName, result);
        }
    }

    /**
     * Retrieves final metrics for the given
     * {@link com.android.car.telemetry.TelemetryProto.MetricsConfig}.
     *
     * @param metricsConfigName name of the MetricsConfig.
     * @param deleteResult      if true, the final result will be deleted from disk.
     * @param callback          for receiving the metrics output. If result does not exist, it will
     *                          receive a null value.
     */
    void getFinalResult(
            String metricsConfigName, boolean deleteResult, FinalResultCallback callback) {
        // I/O operations should happen on I/O thread
        mIoHandler.post(() -> {
            synchronized (mLock) {
                loadFinalResultLockedOnIoThread(metricsConfigName, deleteResult, callback);
            }
        });
    }

    @GuardedBy("mLock")
    private void loadFinalResultLockedOnIoThread(
            String metricsConfigName, boolean deleteResult, FinalResultCallback callback) {
        File file = new File(mFinalResultDirectory, metricsConfigName);
        // if no final result exists for this metrics config, return immediately
        if (!file.exists()) {
            mWorkerHandler.post(() -> callback.onFinalResult(metricsConfigName, null));
            return;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            PersistableBundle bundle = PersistableBundle.readFromStream(fis);
            // invoke callback on worker thread
            mWorkerHandler.post(() -> callback.onFinalResult(metricsConfigName, bundle));
        } catch (IOException e) {
            Slog.w(CarLog.TAG_TELEMETRY, "Failed to get final result from disk.", e);
            mWorkerHandler.post(() -> callback.onFinalResult(metricsConfigName, null));
        }
        if (deleteResult) {
            file.delete();
        }
    }

    /**
     * Stores final metrics in memory for the given
     * {@link com.android.car.telemetry.TelemetryProto.MetricsConfig}.
     */
    void putFinalResult(String metricsConfigName, PersistableBundle result) {
        synchronized (mLock) {
            mIoHandler.post(() -> {
                writeSingleResultToFileOnIoThread(mFinalResultDirectory, metricsConfigName, result);
                deleteSingleFileOnIoThread(mInterimResultDirectory, metricsConfigName);
            });
            mInterimResultCache.remove(metricsConfigName);
        }
    }

    /** Persist data to disk. */
    void shutdown() {
        mIoHandler.post(() -> {
            synchronized (mLock) {
                writeInterimResultsToFileLockedOnIoThread();
            }
        });
    }

    /** Writes interim results to disk. */
    @GuardedBy("mLock")
    private void writeInterimResultsToFileLockedOnIoThread() {
        mInterimResultCache.forEach((metricsConfigName, persistableBundle) ->
                writeSingleResultToFileOnIoThread(
                        mInterimResultDirectory, metricsConfigName, persistableBundle));
    }

    /**
     * Converts a {@link PersistableBundle} into byte array and saves the results to a file.
     */
    private void writeSingleResultToFileOnIoThread(
            File dir, String metricsConfigName, PersistableBundle result) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            result.writeToStream(outputStream);
            Files.write(
                    new File(dir, metricsConfigName).toPath(),
                    outputStream.toByteArray());
        } catch (IOException e) {
            Slog.w(CarLog.TAG_TELEMETRY, "Failed to write result to file", e);
        }
    }

    /** Deletes a the given file in the given directory if it exists. */
    private void deleteSingleFileOnIoThread(File interimResultDirectory, String metricsConfigName) {
        File file = new File(interimResultDirectory, metricsConfigName);
        if (file.exists()) {
            file.delete();
        }
    }

    /** Callback for receiving final metrics output. */
    interface FinalResultCallback {
        void onFinalResult(String metricsConfigName, PersistableBundle result);
    }

    // TODO(b/195422227): Implement deletion of stale data based on system time
    // TODO(b/195422227): Implement deletion of interim results after MetricsConfig is removed
}
