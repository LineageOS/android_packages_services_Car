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

import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_ALREADY_EXISTS;
import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_NONE;
import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_UNKNOWN;
import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_VERSION_TOO_OLD;

import android.util.ArrayMap;
import android.util.Slog;

import com.android.car.CarLog;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This class is responsible for storing, retrieving, and deleting {@link
 * TelemetryProto.MetricsConfig}. All of the methods are blocking so the class should only be
 * accessed on the telemetry thread.
 */
class MetricsConfigStore {
    @VisibleForTesting
    static final String METRICS_CONFIG_DIR = "metrics_configs";

    private final File mConfigDirectory;
    private Map<String, TelemetryProto.MetricsConfig> mActiveConfigs;
    private Map<String, Integer> mNameVersionMap;

    MetricsConfigStore(File rootDirectory) {
        mConfigDirectory = new File(rootDirectory, METRICS_CONFIG_DIR);
        mConfigDirectory.mkdirs();
        mActiveConfigs = new ArrayMap<>();
        mNameVersionMap = new ArrayMap<>();
        // TODO(b/197336485): Add expiration date check for MetricsConfig
        for (File file : mConfigDirectory.listFiles()) {
            try {
                byte[] serializedConfig = Files.readAllBytes(file.toPath());
                TelemetryProto.MetricsConfig config =
                        TelemetryProto.MetricsConfig.parseFrom(serializedConfig);
                mActiveConfigs.put(config.getName(), config);
                mNameVersionMap.put(config.getName(), config.getVersion());
            } catch (IOException e) {
                // TODO(b/197336655): record failure
                file.delete();
            }
        }
    }

    /**
     * Returns all active {@link TelemetryProto.MetricsConfig} from disk.
     */
    List<TelemetryProto.MetricsConfig> getActiveMetricsConfigs() {
        return new ArrayList<>(mActiveConfigs.values());
    }

    /**
     * Stores the MetricsConfig if it is valid.
     *
     * @param metricsConfig the config to be persisted to disk.
     * @return true if the MetricsConfig should start receiving data, false otherwise.
     */
    int addMetricsConfig(TelemetryProto.MetricsConfig metricsConfig) {
        // TODO(b/198823862): Validate config version
        // TODO(b/197336485): Check expiration date for MetricsConfig
        int currentVersion = mNameVersionMap.getOrDefault(metricsConfig.getName(), -1);
        if (currentVersion > metricsConfig.getVersion()) {
            return ERROR_METRICS_CONFIG_VERSION_TOO_OLD;
        } else if (currentVersion == metricsConfig.getVersion()) {
            return ERROR_METRICS_CONFIG_ALREADY_EXISTS;
        }
        mActiveConfigs.put(metricsConfig.getName(), metricsConfig);
        mNameVersionMap.put(metricsConfig.getName(), metricsConfig.getVersion());
        try {
            Files.write(
                    new File(mConfigDirectory, metricsConfig.getName()).toPath(),
                    metricsConfig.toByteArray());
        } catch (IOException e) {
            // TODO(b/197336655): record failure
            Slog.w(CarLog.TAG_TELEMETRY, "Failed to write metrics config to disk", e);
            return ERROR_METRICS_CONFIG_UNKNOWN;
        }
        return ERROR_METRICS_CONFIG_NONE;
    }

    /** Deletes the MetricsConfig from disk. Returns the success status. */
    boolean deleteMetricsConfig(String metricsConfigName) {
        mActiveConfigs.remove(metricsConfigName);
        mNameVersionMap.remove(metricsConfigName);
        return new File(mConfigDirectory, metricsConfigName).delete();
    }

    void deleteAllMetricsConfigs() {
        mActiveConfigs.clear();
        for (File file : mConfigDirectory.listFiles()) {
            file.delete();
        }
    }
}
