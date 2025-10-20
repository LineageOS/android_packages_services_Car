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

import android.os.Bundle;
import android.util.IndentingPrintWriter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;


import java.io.StringWriter;

/**
 * A UI fragment for controlling Perfetto field tracing.
 *
 * <p>This fragment allows users to:
 * <ul>
 *     <li>Push a default Perfetto field trace configuration.
 *     <li>Trigger a KitchenSink specific perfetto event.
 *     <li>Query the current Perfetto field trace configuration.
 *     <li>Remove any existing Perfetto field trace configuration.
 * </ul>
 * <p>The status of these operations is displayed in a {@link TextView}.
 */
public class PerfettoFragment extends Fragment {

    private PerfettoController mPerfettoController;
    private TextView mStatusView;
    private String mStatusPrefix;
    private ScrollView mScrollView;
    private ImageView mFabView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPerfettoController = new PerfettoController(getContext());
        mStatusPrefix = getString(R.string.perfetto_status_prefix);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.perfetto_fragment, container, false);

        mStatusView = view.findViewById(R.id.tv_status);
        mScrollView = view.findViewById(R.id.scroll_view);
        mFabView = view.findViewById(R.id.fab_scroll_to_top);

        Button pushButton = view.findViewById(R.id.btn_push_default_config);
        pushButton.setOnClickListener(v -> pushDefaultFieldTraceConfig());

        Button triggerButton = view.findViewById(R.id.btn_trigger_perfetto);
        triggerButton.setOnClickListener(v -> triggerEvent());

        Button queryButton = view.findViewById(R.id.btn_query_config);
        queryButton.setOnClickListener(v -> queryFieldTraceConfig());

        Button removeButton = view.findViewById(R.id.btn_remove_config);
        removeButton.setOnClickListener(v -> removeFieldTraceConfig());

        mFabView.setOnClickListener(v -> mScrollView.smoothScrollTo(0, 0));

        mScrollView.setOnScrollChangeListener(
                (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (scrollY > 0) {
                    mFabView.setVisibility(View.VISIBLE);
                } else {
                    mFabView.setVisibility(View.GONE);
                }
            });

        return view;
    }

    /**
     * Pushes a default Perfetto field trace configuration.
     *
     * <p>Updates the status view with the result of the operation.
     */
    private void pushDefaultFieldTraceConfig() {
        if (!mPerfettoController.pushFieldTraceConfig("default", /*bufferSizeMultiplier=*/1)) {
            updateStatus(getString(R.string.perfetto_push_fail));
            return;
        }
        StringWriter stringWriter = new StringWriter();
        IndentingPrintWriter writer = new IndentingPrintWriter(stringWriter);
        mPerfettoController.printLastPushedStatsdConfig(writer);
        updateStatus(getString(R.string.perfetto_push_success) + stringWriter.toString());
    }

    /**
     * Triggers a KitchenSink specific perfetto event.
     *
     * <p>Updates the status view with the result of the operation.
     */
    private void triggerEvent() {
        updateStatus(mPerfettoController.triggerEvent()
                ? getString(R.string.perfetto_trigger_success)
                : getString(R.string.perfetto_trigger_fail));
    }

    /**
     * Queries the current Perfetto field trace configuration.
     *
     * <p>Updates the status view with the result of the operation.
     */
    private void queryFieldTraceConfig() {
        StringWriter stringWriter = new StringWriter();
        IndentingPrintWriter writer = new IndentingPrintWriter(stringWriter);
        boolean success = mPerfettoController.queryFieldTraceConfig(writer);
        if (success) {
            updateStatus(getString(R.string.perfetto_query_success) + stringWriter.toString());
        } else {
            updateStatus(getString(R.string.perfetto_query_fail_not_implemented));
        }
    }

    /**
     * Removes the latest Perfetto field trace configuration.
     *
     * <p>Updates the status view with the result of the operation.
     */
    private void removeFieldTraceConfig() {
        updateStatus(mPerfettoController.removeFieldTraceConfig()
                ? getString(R.string.perfetto_remove_success)
                : getString(R.string.perfetto_remove_fail));
    }

    /**
     * Updates the status {@link TextView} with the given message.
     *
     * @param message The message to display in the status view.
     */
    private void updateStatus(String message) {
        mStatusView.setText(mStatusPrefix + " " + message);
    }
}
