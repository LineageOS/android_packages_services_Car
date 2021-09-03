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

import android.util.Slog;

import com.android.car.CarLog;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is responsible for storing, retrieving, and deleting {@link
 * TelemetryProto.MetricsConfig}. All of the methods are blocking so the class should only be
 * accessed on the telemetry thread.
 */
class MetricsConfigStore {
    @VisibleForTesting
    static final String METRICS_CONFIG_DIR = "metrics_configs";

    private final File mConfigDirectory;

    MetricsConfigStore(File rootDirectory) {
        mConfigDirectory = new File(rootDirectory, METRICS_CONFIG_DIR);
        mConfigDirectory.mkdirs();
    }

    /**
     * Returns all active {@link TelemetryProto.MetricsConfig} from disk.
     */
    List<TelemetryProto.MetricsConfig> getActiveMetricsConfigs() {
        // TODO(b/197336485): Add expiration date check for MetricsConfig
        List<TelemetryProto.MetricsConfig> activeConfigs = new ArrayList<>();
        for (File file : mConfigDirectory.listFiles()) {
            try {
                byte[] serializedConfig = Files.readAllBytes(file.toPath());
                activeConfigs.add(TelemetryProto.MetricsConfig.parseFrom(serializedConfig));
            } catch (IOException e) {
                // TODO(b/197336655): record failure
                file.delete();
            }
        }
        return activeConfigs;
    }

    /**
     * Stores the MetricsConfig if it is valid.
     *
     * @param metricsConfig the config to be persisted to disk.
     * @return true if the MetricsConfig should start receiving data, false otherwise.
     */
    boolean addMetricsConfig(TelemetryProto.MetricsConfig metricsConfig) {
        // TODO(b/197336485): Check version and expiration date for MetricsConfig
        try {
            Files.write(
                    new File(mConfigDirectory, metricsConfig.getName()).toPath(),
                    metricsConfig.toByteArray());
        } catch (IOException e) {
            // TODO(b/197336655): record failure
            Slog.w(CarLog.TAG_TELEMETRY, "Failed to write metrics config to disk", e);
        }
        return true;
    }

    /** Deletes the MetricsConfig from disk. Returns the success status. */
    boolean deleteMetricsConfig(String metricsConfigName) {
        return new File(mConfigDirectory, metricsConfigName).delete();
    }
}
