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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.telemetry.CarTelemetryManager;
import android.car.telemetry.MetricsConfigKey;
import android.hardware.automotive.vehicle.V2_0.VehicleProperty;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.android.car.telemetry.TelemetryProto;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class CarTelemetryTestFragment extends Fragment {
    private static final String LUA_SCRIPT_ON_GEAR_CHANGE =
            "function onGearChange(state)\n"
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
    private static final TelemetryProto.Subscriber VEHICLE_PROPERTY_SUBSCRIBER =
            TelemetryProto.Subscriber.newBuilder()
                    .setHandler("onGearChange")
                    .setPublisher(VEHICLE_PROPERTY_PUBLISHER)
                    .setPriority(0)
                    .build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_ON_GEAR_CHANGE_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("my_metrics_config")
                    .setVersion(1)
                    .setScript(LUA_SCRIPT_ON_GEAR_CHANGE)
                    .addSubscribers(VEHICLE_PROPERTY_SUBSCRIBER)
                    .build();
    private static final MetricsConfigKey KEY_V1 = new MetricsConfigKey(
            METRICS_CONFIG_ON_GEAR_CHANGE_V1.getName(),
            METRICS_CONFIG_ON_GEAR_CHANGE_V1.getVersion());

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
        Button sendGearConfigBtn = view.findViewById(R.id.send_on_gear_change_config);
        Button getGearReportBtn = view.findViewById(R.id.get_on_gear_change_report);
        Button removeGearConfigBtn = view.findViewById(R.id.remove_on_gear_change_config);

        sendGearConfigBtn.setOnClickListener(this::onSendGearChangeConfigBtnClick);
        removeGearConfigBtn.setOnClickListener(this::onRemoveGearChangeConfigBtnClick);
        getGearReportBtn.setOnClickListener(this::onGetGearChangeReportBtnClick);

        return view;
    }

    private void showOutput(String s) {
        mActivity.runOnUiThread(() -> mOutputTextView.setText(s));
    }

    private void onSendGearChangeConfigBtnClick(View view) {
        showOutput("Sending MetricsConfig that listen for gear change...");
        mCarTelemetryManager.addMetricsConfig(KEY_V1,
                METRICS_CONFIG_ON_GEAR_CHANGE_V1.toByteArray());
    }

    private void onRemoveGearChangeConfigBtnClick(View view) {
        showOutput("Removing MetricsConfig that listens for gear change...");
        mCarTelemetryManager.removeMetricsConfig(KEY_V1);
    }

    private void onGetGearChangeReportBtnClick(View view) {
        showOutput("Fetching report... If nothing shows up after a few seconds, "
                + "then no result exists");
        mCarTelemetryManager.sendFinishedReports(KEY_V1);
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
            showOutput("Result is " + bundle.toString());
        }

        @Override
        public void onError(@NonNull MetricsConfigKey key, @NonNull byte[] error) {
        }

        @Override
        public void onAddMetricsConfigStatus(@NonNull MetricsConfigKey key, int statusCode) {
            showOutput("Add MetricsConfig status: " + statusCode);
        }

        @Override
        public void onRemoveMetricsConfigStatus(@NonNull MetricsConfigKey key, boolean success) {
            showOutput("Remove MetricsConfig status: " + success);
        }
    }
}
