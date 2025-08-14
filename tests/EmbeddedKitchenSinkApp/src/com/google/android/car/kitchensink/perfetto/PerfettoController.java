/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.google.android.car.kitchensink.perfetto;

import android.app.StatsManager;
import android.content.Context;
import android.util.IndentingPrintWriter;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.os.StatsdConfigProto.StatsdConfig;

import perfetto.protos.TraceConfigOuterClass.TraceConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * A controller class for managing Perfetto field traces.
 *
 * <p>This class provides functionality to:
 * <ul>
 *     <li>Push Perfetto trace configurations (both default and custom) to statsd.
 *     <li>Trigger Perfetto trace collection.
 *     <li>Query the currently active Perfetto trace configuration.
 *     <li>Remove Perfetto trace configurations from statsd.
 * </ul>
 *
 * <p>It interacts with {@link android.app.StatsManager} to manage the configurations and uses
 * a custom hash generator to create unique IDs for configs, alarms, and subscriptions.
 */
public class PerfettoController {
    private static final String TAG = "PerfettoController";
    private static final String SAMPLE_KITCHENSINK_TRIGGER_NAME =
            "com.google.android.car.kitchensink.perfetto-sample.trigger.1";
    private static final String TRIGGER_COMMAND = "/system/bin/trigger_perfetto";

    private final Context mContext;

    private static final int TRIGGER_PERFETTO_TIMEOUT_MS = 10000;
    private long mConfigId;
    private long mAlarmId;
    private long mSubscriptionId;
    private StatsdConfig mLastPushedStatsdConfig;

    public PerfettoController(Context context) {
        mContext = context;
    }

    /**
     * Pushes a Perfetto field trace config to statsd.
     *
     * @param configPath A string that is either "default" to use the default config, or a file path
     *      to a custom config.
     * @param bufferSizeMultiplier Mulitplier to increase the in-memory buffer size.
     * @return {@code true} if the config was pushed successfully, {@code false} otherwise.
     */
    public boolean pushPerfettoFieldTraceConfig(String configPath, int bufferSizeMultiplier) {
        TraceConfig.Builder baseConfigBuilder = configPath.equals("default")
                ? getDefaultPerfettoFieldTraceConfig() : getTraceConfigFromFile(configPath);
        if (baseConfigBuilder == null) {
            return false;
        }
        return pushPerfettoFieldTraceConfigInternal(baseConfigBuilder, bufferSizeMultiplier);
    }

    /**
     * Returns the last statsd config that was pushed.
     *
     * @return The last pushed {@link StatsdConfig}.
     */
    public void printLastPushedStatsdConfig(IndentingPrintWriter writer) {
        if (mLastPushedStatsdConfig == null) {
            writer.println("No statsd config found");
            return;
        }
        writer.println("Printing last pushed statsd config...");
        printStatsdConfig(mLastPushedStatsdConfig.toBuilder(), writer);
    }

    /**
     * Activates the KitchenSink specific perfetto trigger.
     *
     * <p>This causes a perfetto field trace to be captured.
     *
     * @return {@code true} if the trigger command was issued successfully, {@code false} otherwise.
     */
    public boolean triggerPerfetto() {
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(TRIGGER_COMMAND, SAMPLE_KITCHENSINK_TRIGGER_NAME);
            Log.v(TAG, "Triggering " + String.join(" ", pb.command()));
            Process process = pb.start();
            if (!process.waitFor(TRIGGER_PERFETTO_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out waiting for perfetto trigger"
                        + SAMPLE_KITCHENSINK_TRIGGER_NAME);
                return false;
            }
            if (process.exitValue() != 0) {
                Log.w(TAG, "Failed to trigger " + SAMPLE_KITCHENSINK_TRIGGER_NAME + ", exit code: "
                        + process.exitValue());
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to trigger " + SAMPLE_KITCHENSINK_TRIGGER_NAME, e);
            throw new RuntimeException(e);
        }
        return true;
    }

    /**
     * Queries the currently pushed Perfetto field trace config.
     *
     * @param writer An {@link IndentingPrintWriter} to write the output to.
     * @return {@code true} if the query was successful, {@code false} otherwise.
     */
    public boolean queryPerfettoFieldTraceConfig(IndentingPrintWriter writer) {
        // TODO(b/406520911): Implement the query functionality in a separate CL.
        return false;
    }

    /**
     * Removes the Perfetto field trace configs.
     *
     * @return {@code true} if the config was removed successfully, {@code false} otherwise.
     */
    public boolean removePerfettoFieldTraceConfigs() {
        StatsManager statsManager =  mContext.getSystemService(StatsManager.class);
        if (statsManager == null) {
            Log.e(TAG, "Could not retrieve StatsManager");
            return false;
        }
        try {
            statsManager.removeConfig(mConfigId);
        } catch (StatsManager.StatsUnavailableException | NullPointerException e) {
            Log.e(TAG, "Removing statsd config failed", e);
            return false;
        }

        Log.d(TAG, "Removed perfetto field trace config with id: " + mConfigId);
        return true;
    }

    @Nullable
    private TraceConfig.Builder getTraceConfigFromFile(String configFilePath) {
        File file = new File(configFilePath);
        if (!file.exists()) {
            Log.e(TAG, "Provided perfetto trace config file does not exist: " + configFilePath);
            return null;
        }

        TraceConfig baseConfig = null;
        try (InputStream inputStream = new FileInputStream(configFilePath)) {
            baseConfig = TraceConfig.parseFrom(inputStream);
            if (baseConfig == null) {
                Log.e(TAG, "Failed to parse perfetto trace config file: " + configFilePath);
                return null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read perfetto trace config file: " + configFilePath, e);
            return null;
        }
        return baseConfig.toBuilder();
    }

    private boolean pushPerfettoFieldTraceConfigInternal(TraceConfig.Builder baseConfigBuilder,
            int bufferSizeMultiplier) {
        // TODO(b/406520911): Implement in a following commit.
        return false;
    }

    private TraceConfig.Builder getDefaultPerfettoFieldTraceConfig() {
        // TODO(b/406520911): Implement in a following commit.
        return null;
    }

    private void printStatsdConfig(
            StatsdConfig.Builder statsdConfigBuilder, IndentingPrintWriter writer) {
        // TODO(b/406520911): Implement in a following commit.
    }
}
