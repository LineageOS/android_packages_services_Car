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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.LongSparseArray;

import androidx.annotation.Nullable;

import com.android.internal.os.StatsdConfigProto.Alarm;
import com.android.internal.os.StatsdConfigProto.PerfettoDetails;
import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.internal.os.StatsdConfigProto.Subscription;

import com.google.android.car.kitchensink.R;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import perfetto.protos.TraceConfigOuterClass.TraceConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
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
    private static final String TRACE_UNIQUE_SESSION_NAME =
            "com.google.android.car.kitchensink.perfetto-session";
    private static final String REPORT_SERVICE_CLASS_NAME =
            PerfettoReportService.class.getCanonicalName();
    private static final String SAMPLE_KITCHENSINK_TRIGGER_NAME =
            "com.google.android.car.kitchensink.perfetto-sample.trigger.1";
    private static final String BUGREPORT_FILENAME = "kitchensink_perfetto_aot_trace.pftrace";
    private static final String TRIGGER_COMMAND = "/system/bin/trigger_perfetto";
    private static final String BASE_TRIGGER_CONFIG = "base_perfetto_trigger_config.pb";
    private static final String DEFAULT_TRACE_CONFIG = "default_perfetto_trace_config.pb";
    private static final String DEFAULT_A13_TRACE_CONFIG = "default_perfetto_trace_config_a13.pb";
    private static final String DEFAULT_A14_TRACE_CONFIG = "default_perfetto_trace_config_a14.pb";
    private static final String TRACE_FILES_ROOT_DIR = "perfetto_traces";
    private static final String TRACE_FILES_PREFIX = "perfetto_";
    private static final String TRACE_FILES_SUFFIX = ".trace";

    private static final long TRACE_RESTART_PERIOD_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long TRACE_RESTART_OFFSET_MS = TimeUnit.MINUTES.toMillis(1);
    private static final int TRIGGER_TIMEOUT_MS = (int) TimeUnit.DAYS.toMillis(1);
    private static final int FLUSH_TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(3);
    private static final int INCREMENTAL_STATE_CLEAR_PERIOD_MS = (int) TimeUnit.SECONDS.toMillis(1);

    private static final HashFunction HASH_FUNCTION = Hashing.sha256();
    private static final long CONFIG_ID =
            HASH_FUNCTION.hashUnencodedChars("kitchensink_perfetto_aot_config").asLong();
    private static final long ALARM_ID =
            HASH_FUNCTION.hashUnencodedChars("kitchensink_perfetto_aot_alarm").asLong();
    private static final long SUBSCRIPTION_ID =
            HASH_FUNCTION.hashUnencodedChars("kitchensink_perfetto_aot_subscription").asLong();

    private final Context mContext;

    private static final int TRIGGER_PERFETTO_TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(10);
    private StatsdConfig mLastPushedStatsdConfig;

    /**
     * Creates a trace file path with the given UUID for storing a new trace.
     */
    public static File createTraceFile(Context context, String uuid) throws IOException {
        File traceDir = new File(context.getFilesDir(), TRACE_FILES_ROOT_DIR);
        if (!traceDir.exists()) {
            traceDir.mkdirs();
        }
        String fileName = TRACE_FILES_PREFIX + uuid + TRACE_FILES_SUFFIX;
        File traceFile = new File(traceDir, fileName);
        boolean created = traceFile.createNewFile();
        if (!created) {
            throw new IllegalStateException("Failed to create file");
        }
        return traceFile;
    }

    /**
     * Lists all available trace files.
     */
    @Nullable
    public static File[] listTraceFiles(Context context) {
        File traceDir = new File(context.getFilesDir(), TRACE_FILES_ROOT_DIR);
        if (!traceDir.exists()) {
            return null;
        }
        return traceDir.listFiles((dir, name) -> name.startsWith("perfetto_")
                && name.endsWith(".trace"));
    }

    /**
     * Deletes old Perfetto traces from the disk.
     */
    public static void deleteOldTraces(Context context) {
        Log.d(TAG, "Checking for old traces");
        File[] traceFiles = listTraceFiles(context);
        if (traceFiles == null || traceFiles.length == 0) {
            return;
        }
        long retentionPeriodMs = TimeUnit.DAYS.toMillis(
                context.getResources().getInteger(R.integer.perfetto_trace_retention_days));

        for (File traceFile : traceFiles) {
            if (traceFile.lastModified() < (System.currentTimeMillis() - retentionPeriodMs)) {
                if (traceFile.delete()) {
                    Log.d(TAG, "Deleted old trace file: " + traceFile.getName());
                } else {
                    Log.w(TAG, "Failed to delete old trace file: " + traceFile.getName());
                }
            }
        }
    }

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
    public boolean pushFieldTraceConfig(String configPath, int bufferSizeMultiplier) {
        TraceConfig.Builder baseConfigBuilder = configPath.equals("default")
                ? getDefaultFieldTraceConfig() : getTraceConfigFromFile(configPath);
        if (baseConfigBuilder == null) {
            return false;
        }
        return pushFieldTraceConfigInternal(baseConfigBuilder, bufferSizeMultiplier);
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
    public boolean triggerEvent() {
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
    public boolean queryFieldTraceConfig(IndentingPrintWriter writer) {
        // TODO(b/406520911): Implement the query functionality in a separate CL.
        return false;
    }

    /**
     * Removes the Perfetto field trace config.
     *
     * @return {@code true} if the config was removed successfully, {@code false} otherwise.
     */
    public boolean removeFieldTraceConfig() {
        StatsManager statsManager =  mContext.getSystemService(StatsManager.class);
        if (statsManager == null) {
            Log.e(TAG, "Could not retrieve StatsManager");
            return false;
        }
        try {
            statsManager.removeConfig(CONFIG_ID);
        } catch (StatsManager.StatsUnavailableException | NullPointerException e) {
            Log.e(TAG, "Removing statsd config failed", e);
            return false;
        }

        Log.d(TAG, "Removed perfetto field trace config with id: " + CONFIG_ID);
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

    private boolean pushFieldTraceConfigInternal(TraceConfig.Builder baseConfigBuilder,
            int bufferSizeMultiplier) {
        if (baseConfigBuilder == null) {
            Log.e(TAG, "Trace config builder is null");
            return false;
        }
        TraceConfig traceConfig =
                baseConfigBuilder
                        .mergeFrom(
                                buildBaseFieldTraceConfig(getMaxBufferSizeKb(baseConfigBuilder,
                                    bufferSizeMultiplier), mContext.getPackageName()))
                        .build();
        if (!pushTraceConfig(traceConfig)) {
            return false;
        }
        return true;
    }

    private int getMaxBufferSizeKb(TraceConfig.Builder traceConfigBuilder,
            int bufferSizeMultiplier) {
        int maxBufferSizeKb = 0;
        for (TraceConfig.BufferConfig bufferConfig : traceConfigBuilder.getBuffersList()) {
            if (bufferConfig.getFillPolicy() != TraceConfig.BufferConfig.FillPolicy.UNSPECIFIED) {
                maxBufferSizeKb += bufferConfig.getSizeKb();
            }
        }
        return maxBufferSizeKb * bufferSizeMultiplier;
    }

    private TraceConfig.Builder getDefaultFieldTraceConfig() {
        TraceConfig traceConfig = getTraceConfigFromAsset(getDefaultTraceConfigFileName());
        if (traceConfig == null) {
            Log.e(TAG, "Failed to read default perfetto trace config from resource");
            return null;
        }
        TraceConfig triggerConfig = getTraceConfigFromAsset(BASE_TRIGGER_CONFIG);
        if (triggerConfig == null) {
            Log.e(TAG, "Failed to read perfetto trigger config from resource");
            return null;
        }
        return traceConfig.toBuilder().mergeFrom(triggerConfig);
    }

    private TraceConfig getTraceConfigFromAsset(String assetName) {
        try (InputStream inputStream = mContext.getAssets().open(assetName)) {
            return TraceConfig.parseFrom(inputStream);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read perfetto trace config from asset: " + assetName, e);
            return null;
        }
    }

    private String getDefaultTraceConfigFileName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return DEFAULT_TRACE_CONFIG;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return DEFAULT_A14_TRACE_CONFIG;
        } else {
            return DEFAULT_A13_TRACE_CONFIG;
        }
    }

    private boolean pushTraceConfig(TraceConfig traceConfig) {
        StatsManager statsManager =  mContext.getSystemService(StatsManager.class);
        if (statsManager == null) {
            Log.e(TAG, "Could not retrieve StatsManager");
            return false;
        }

        StatsdConfig statsdConfig =
                StatsdConfig.newBuilder()
                        .setId(CONFIG_ID)
                        .addAlarm(
                                Alarm.newBuilder()
                                        .setId(ALARM_ID)
                                        // Regularly try (re)starting the trace. If a KitchenSink
                                        // triggered trace is already running, this will correctly
                                        // fail gracefully because only one trace with a given
                                        // `TraceConfig.unique_session_name` can be running at
                                        // any time.
                                        .setPeriodMillis(TRACE_RESTART_PERIOD_MS)
                                        // Add an offset to avoid starting the trace immediately
                                        // after the previous trace is stopped.
                                        // Otherwise, the second session may be invoked too quickly
                                        // leading to the trace is not started.
                                        .setOffsetMillis(TRACE_RESTART_OFFSET_MS))
                        .addSubscription(
                                Subscription.newBuilder()
                                        .setId(SUBSCRIPTION_ID)
                                        .setRuleType(Subscription.RuleType.ALARM)
                                        .setRuleId(ALARM_ID)
                                        // Attach the TraceConfig.
                                        .setPerfettoDetails(
                                                PerfettoDetails.newBuilder()
                                                        .setTraceConfig(
                                                                ByteString.copyFrom(
                                                                        traceConfig
                                                                                .toByteArray()))))
                        .addAllowedLogSource("AID_SYSTEM")
                        .build();

        try {
            // 1. This will result in "ConfigManager This is a duplicate config" if we have already
            // set the config and we are not making any changes to it, but that is not a problem.
            // 2. This API call requires the caller to have android.permission.DUMP and
            // android.permission.PACKAGE_USAGE_STATS permission. These permissions are granted
            // to KitchenSink.
            statsManager.addConfig(CONFIG_ID, statsdConfig.toByteArray());
        } catch (StatsManager.StatsUnavailableException | NullPointerException e) {
            // In addition to StatsUnavailableException, StatsManager can also throw
            // NullPointerException internally. This seems to be caused by SELinux blocking access
            // to statsd when the app is *not* installed as a system-privileged app.
            Log.e(TAG, "Setting statsd config failed", e);
            return false;
        }
        mLastPushedStatsdConfig = statsdConfig;
        Log.d(TAG, "Pushed perfetto field trace config with id: " + CONFIG_ID);
        return true;
    }

    // Base configuration for the integration of Perfetto with statsd and trigger_perfetto.
    private static TraceConfig buildBaseFieldTraceConfig(int maxBufferSizeKb,
                                                         String reporterPackageName) {
        return TraceConfig.newBuilder()
                .setUniqueSessionName(TRACE_UNIQUE_SESSION_NAME)
                // Configure this trace to be stopped by trigger_perfetto.
                .setTriggerConfig(
                        TraceConfig.TriggerConfig.newBuilder()
                                // This will stop the trace session on receiving the trigger.
                                // However, the trace collection is restarted once every 10 minutes
                                // {@link TRACE_RESTART_PERIOD_MS}.
                                .setTriggerMode(TraceConfig.TriggerConfig.TriggerMode.STOP_TRACING)
                                // This is an arbitrary large timeout. The trace collection is
                                // restarted once every 10 minutes {@link TRACE_RESTART_PERIOD_MS}.
                                // This is a large timeout to avoid the current trace session from
                                // being stopped before it is restarted.
                                .setTriggerTimeoutMs(TRIGGER_TIMEOUT_MS)
                                .addTriggers(
                                        TraceConfig.TriggerConfig.Trigger.newBuilder()
                                                .setName(SAMPLE_KITCHENSINK_TRIGGER_NAME))
                                .setUseCloneSnapshotIfAvailable(true))
                .setCompressionType(TraceConfig.CompressionType.COMPRESSION_TYPE_DEFLATE)
                // Reduce the global tracing buffer memory footprint, so the overall memory overhead
                // is minimal. The actual trace config should consider this limit when setting
                // the data sources and their buffer sizes. Otherwise, the trace may be truncated
                // and may not contain any useful data.
                .setGuardrailOverrides(
                        TraceConfig.GuardrailOverrides.newBuilder()
                                .setMaxTracingBufferSizeKb(maxBufferSizeKb))
                .setFlushTimeoutMs(FLUSH_TIMEOUT_MS)
                .setBuiltinDataSources(
                        TraceConfig.BuiltinDataSource.newBuilder()
                                .setPreferSuspendClockForSnapshot(true))
                .setIncrementalStateConfig(
                        TraceConfig.IncrementalStateConfig.newBuilder()
                                .setClearPeriodMs(INCREMENTAL_STATE_CLEAR_PERIOD_MS))
                .setBugreportScore(5)
                .setBugreportFilename(BUGREPORT_FILENAME)
                .setAndroidReportConfig(
                        TraceConfig.AndroidReportConfig.newBuilder()
                                .setReporterServicePackage(reporterPackageName)
                                .setReporterServiceClass(REPORT_SERVICE_CLASS_NAME))
                .build();
    }

    private void printStatsdConfig(
            StatsdConfig.Builder statsdConfigBuilder, IndentingPrintWriter writer) {
        List<Subscription> subscriptions = statsdConfigBuilder.getSubscriptionList();
        statsdConfigBuilder.clearSubscription();

        LongSparseArray<TraceConfig> traceConfigs = new LongSparseArray<>(subscriptions.size());
        for (Subscription subscription : subscriptions) {
            Subscription.Builder subscriptionBuilder = subscription.toBuilder();
            PerfettoDetails.Builder perfettoDetailsBuilder =
                    subscriptionBuilder.getPerfettoDetails().toBuilder();
            try {
                traceConfigs.append(
                        subscriptionBuilder.getId(),
                        TraceConfig.parseFrom(perfettoDetailsBuilder.getTraceConfig()));
            } catch (InvalidProtocolBufferException e) {
                Log.e(
                        TAG,
                        "Failed to parse trace config for subscription id "
                                + subscriptionBuilder.getId(),
                        e);
            }
            perfettoDetailsBuilder.clearTraceConfig();
            subscriptionBuilder.setPerfettoDetails(perfettoDetailsBuilder);
            statsdConfigBuilder.addSubscription(subscriptionBuilder);
        }

        writer.increaseIndent();
        writer.println("=======================================================================");
        writer.println("StatsdConfig: \n" + statsdConfigBuilder.build().toString());

        writer.increaseIndent();
        writer.println("------------------------------------");
        writer.println("TraceConfigs from all subscriptions");
        writer.println("------------------------------------");
        for (int i = 0; i < traceConfigs.size(); i++) {
            writer.println(
                    "TraceConfig from subscription id '"
                            + traceConfigs.keyAt(i)
                            + "'\n"
                            + traceConfigs.valueAt(i).toString());
        }
        writer.decreaseIndent();
        writer.println("=======================================================================\n");
        writer.decreaseIndent();
    }

    /**
     * A {@link BroadcastReceiver} that deletes old Perfetto traces on boot.
     */
    public static class BootCompletedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                Log.d(TAG, "Boot completed, deleting old traces");
                deleteOldTraces(context);
            }
        }
    }
}
