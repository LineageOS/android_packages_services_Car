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

package com.google.android.car.kitchensink.telemetry;

import static com.android.car.telemetry.TelemetryProto.StatsPublisher.SystemMetric.PROCESS_MEMORY_STATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.car.telemetry.CarTelemetryManager;
import android.car.telemetry.MetricsConfigKey;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.android.car.telemetry.TelemetryProto;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class CarTelemetryTestFragment extends Fragment {

    /** Hello World test from vehicle property publisher by injecting gear change. */
    private static final String LUA_SCRIPT_ON_GEAR_CHANGE =
            "function onGearChange(published_data, state)\n"
                    + "    result = {data = \"Hello World!\"}\n"
                    + "    on_script_finished(result)\n"
                    + "end\n";
    private static final TelemetryProto.Publisher VEHICLE_PROPERTY_PUBLISHER =
            TelemetryProto.Publisher.newBuilder()
                    .setVehicleProperty(
                            TelemetryProto.VehiclePropertyPublisher.newBuilder()
                                    .setVehiclePropertyId(VehicleProperty.GEAR_SELECTION)
                                    .setReadRate(0f)
                                    .build()
                    ).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_ON_GEAR_CHANGE_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("my_metrics_config")
                    .setVersion(1)
                    .setScript(LUA_SCRIPT_ON_GEAR_CHANGE)
                    .addSubscribers(
                            TelemetryProto.Subscriber.newBuilder()
                                    .setHandler("onGearChange")
                                    .setPublisher(VEHICLE_PROPERTY_PUBLISHER)
                                    .setPriority(100)) // low priority
                    .build();
    private static final MetricsConfigKey ON_GEAR_CHANGE_KEY_V1 = new MetricsConfigKey(
            METRICS_CONFIG_ON_GEAR_CHANGE_V1.getName(),
            METRICS_CONFIG_ON_GEAR_CHANGE_V1.getVersion());

    /** Memory metrics test. */
    private static final String LUA_SCRIPT_ON_PROCESS_MEMORY_STATE = new StringBuilder()
            .append("function calculateAverage(tbl)\n")
            .append("    sum = 0\n")
            .append("    size = 0\n")
            .append("    for _, value in ipairs(tbl) do\n")
            .append("        sum = sum + value\n")
            .append("        size = size + 1\n")
            .append("    end\n")
            .append("    return sum/size\n")
            .append("end\n")
            .append("function onProcessMemory(published_data, state)\n")
            .append("    result = {}\n")
            .append("    result.page_fault_avg = calculateAverage(published_data.page_fault)\n")
            .append("    result.major_page_fault_avg = calculateAverage("
                    + "published_data.page_major_fault)\n")
            .append("    result.oom_adj_score_avg = calculateAverage("
                    + "published_data.oom_adj_score)\n")
            .append("    result.rss_in_bytes_avg = calculateAverage(published_data.rss_in_bytes)\n")
            .append("    result.swap_in_bytes_avg = calculateAverage("
                    + "published_data.swap_in_bytes)\n")
            .append("    result.cache_in_bytes_avg = calculateAverage("
                    + "published_data.cache_in_bytes)\n")
            .append("    on_script_finished(result)\n")
            .append("end\n")
            .toString();

    private static final TelemetryProto.Publisher PROCESS_MEMORY_PUBLISHER =
            TelemetryProto.Publisher.newBuilder()
                    .setStats(
                            TelemetryProto.StatsPublisher.newBuilder()
                                    .setSystemMetric(PROCESS_MEMORY_STATE)
                    ).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_PROCESS_MEMORY_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("process_memory_metrics_config")
                    .setVersion(1)
                    .setScript(LUA_SCRIPT_ON_PROCESS_MEMORY_STATE)
                    .addSubscribers(
                            TelemetryProto.Subscriber.newBuilder()
                                    .setHandler("onProcessMemory")
                                    .setPublisher(PROCESS_MEMORY_PUBLISHER)
                                    .setPriority(0)) // high priority
                    .build();
    private static final MetricsConfigKey PROCESS_MEMORY_KEY_V1 = new MetricsConfigKey(
            METRICS_CONFIG_PROCESS_MEMORY_V1.getName(),
            METRICS_CONFIG_PROCESS_MEMORY_V1.getVersion());

    private final Executor mExecutor = Executors.newSingleThreadExecutor();

    private CarTelemetryManager mCarTelemetryManager;
    private CarTelemetryResultsListenerImpl mListener;
    private KitchenSinkActivity mActivity;
    private TextView mOutputTextView;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        mActivity = (KitchenSinkActivity) getActivity();
        mCarTelemetryManager = mActivity.getCarTelemetryManager();
        mListener = new CarTelemetryResultsListenerImpl();
        mCarTelemetryManager.setListener(mExecutor, mListener);
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.car_telemetry_test, container, false);
        mOutputTextView = view.findViewById(R.id.output_textview);
        view.findViewById(R.id.send_on_gear_change_config)
                .setOnClickListener(this::onSendGearChangeConfigBtnClick);
        view.findViewById(R.id.remove_on_gear_change_config)
                .setOnClickListener(this::onRemoveGearChangeConfigBtnClick);
        view.findViewById(R.id.get_on_gear_change_report)
                .setOnClickListener(this::onGetGearChangeReportBtnClick);
        view.findViewById(R.id.send_on_process_memory_config)
                .setOnClickListener(this::onSendProcessMemoryConfigBtnClick);
        view.findViewById(R.id.remove_on_process_memory_config)
                .setOnClickListener(this::onRemoveProcessMemoryConfigBtnClick);
        view.findViewById(R.id.get_on_process_memory_report)
                .setOnClickListener(this::onGetProcessMemoryReportBtnClick);
        view.findViewById(R.id.show_mem_info_btn).setOnClickListener(this::onShowMemInfoBtnClick);
        return view;
    }

    private void showOutput(String s) {
        mActivity.runOnUiThread(() -> {
            String now = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            mOutputTextView.setText(
                    now + " : " + s + "\n" + mOutputTextView.getText());
        });
    }

    private void onSendGearChangeConfigBtnClick(View view) {
        showOutput("Sending MetricsConfig that listen for gear change...");
        mCarTelemetryManager.addMetricsConfig(ON_GEAR_CHANGE_KEY_V1,
                METRICS_CONFIG_ON_GEAR_CHANGE_V1.toByteArray());
    }

    private void onRemoveGearChangeConfigBtnClick(View view) {
        showOutput("Removing MetricsConfig that listens for gear change...");
        mCarTelemetryManager.removeMetricsConfig(ON_GEAR_CHANGE_KEY_V1);
    }

    private void onGetGearChangeReportBtnClick(View view) {
        showOutput("Fetching report for on_gear_change... If nothing shows up within 5 seconds, "
                + "there is no result yet");
        mCarTelemetryManager.sendFinishedReports(ON_GEAR_CHANGE_KEY_V1);
    }

    private void onSendProcessMemoryConfigBtnClick(View view) {
        showOutput("Sending MetricsConfig that listens for process memory state...");
        mCarTelemetryManager.addMetricsConfig(PROCESS_MEMORY_KEY_V1,
                METRICS_CONFIG_PROCESS_MEMORY_V1.toByteArray());
    }

    private void onRemoveProcessMemoryConfigBtnClick(View view) {
        showOutput("Removing MetricsConfig that listens for process memory state...");
        mCarTelemetryManager.removeMetricsConfig(PROCESS_MEMORY_KEY_V1);
    }

    private void onGetProcessMemoryReportBtnClick(View view) {
        showOutput("Fetching report for process memory state... If nothing shows up within 5 "
                + "seconds, there is no result yet");
        mCarTelemetryManager.sendFinishedReports(PROCESS_MEMORY_KEY_V1);
    }

    /** Gets a MemoryInfo object for the device's current memory status. */
    private ActivityManager.MemoryInfo getAvailableMemory() {
        ActivityManager activityManager = getActivity().getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo;
    }

    private void onShowMemInfoBtnClick(View view) {
        // Use android's "alloc-stress" system tool to create an artificial memory pressure.
        ActivityManager.MemoryInfo info = getAvailableMemory();
        showOutput("MemoryInfo availMem=" + (info.availMem / 1024 / 1024) + "/"
                + (info.totalMem / 1024 / 1024) + "mb, isLowMem=" + info.lowMemory
                + ", threshold=" + (info.threshold / 1024 / 1024) + "mb");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    /**
     * Implementation of the {@link CarTelemetryManager.CarTelemetryResultsListener}. They update
     * the view to show the outputs from the APIs of {@link CarTelemetryManager}.
     * The callbacks are executed in {@link mExecutor}.
     */
    private final class CarTelemetryResultsListenerImpl
            implements CarTelemetryManager.CarTelemetryResultsListener {

        @Override
        public void onResult(@NonNull MetricsConfigKey key, @NonNull byte[] result) {
            PersistableBundle bundle;
            try (ByteArrayInputStream bis = new ByteArrayInputStream(result)) {
                bundle = PersistableBundle.readFromStream(bis);
            } catch (IOException e) {
                bundle = null;
            }
            showOutput("Result for " + key.getName() + ": " + bundle.toString());
        }

        @Override
        public void onError(@NonNull MetricsConfigKey key, @NonNull byte[] error) {
            try {
                TelemetryProto.TelemetryError telemetryError =
                        TelemetryProto.TelemetryError.parseFrom(error);
                showOutput("Error for " + key.getName() + ": " + telemetryError);
            } catch (InvalidProtocolBufferException e) {
                showOutput("Unable to parse error result for MetricsConfig " + key.getName()
                        + ": " + e.getMessage());
            }
        }

        @Override
        public void onAddMetricsConfigStatus(@NonNull MetricsConfigKey key, int statusCode) {
            showOutput("Add MetricsConfig status for " + key.getName() + ": "
                    + statusCodeToString(statusCode));
        }

        private String statusCodeToString(int statusCode) {
            switch (statusCode) {
                case CarTelemetryManager.ERROR_METRICS_CONFIG_NONE:
                    return "SUCCESS";
                case CarTelemetryManager.ERROR_METRICS_CONFIG_ALREADY_EXISTS:
                    return "ERROR ALREADY_EXISTS";
                case CarTelemetryManager.ERROR_METRICS_CONFIG_VERSION_TOO_OLD:
                    return "ERROR VERSION_TOO_OLD";
                case CarTelemetryManager.ERROR_METRICS_CONFIG_PARSE_FAILED:
                    return "ERROR PARSE_FAILED";
                case CarTelemetryManager.ERROR_METRICS_CONFIG_SIGNATURE_VERIFICATION_FAILED:
                    return "ERROR SIGNATURE_VERIFICATION_FAILED";
                default:
                    return "ERROR UNKNOWN";
            }
        }
    }
}
