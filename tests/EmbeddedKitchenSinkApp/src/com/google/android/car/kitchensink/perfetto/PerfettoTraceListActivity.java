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

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;

import java.io.File;

/**
 * An activity that shows a list of saved Perfetto traces.
 *
 * <p>This activity is always launched as the system user to be able to read the trace files
 * saved in the system user's context.
 */
public class PerfettoTraceListActivity extends Activity {
    private static final String TAG = "PerfettoTraceList";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int myUserId = UserHandle.myUserId();
        Log.i(TAG, "onCreate userid " + myUserId);
        if (myUserId != UserHandle.USER_SYSTEM) {
            Log.i(TAG, "onCreate re-starting self as user 0");
            Intent selfIntent = new Intent(this, PerfettoTraceListActivity.class);
            startActivityAsUser(selfIntent, UserHandle.SYSTEM);
            finish();
            return;
        }

        PerfettoController.deleteOldTraces(this);

        setContentView(R.layout.perfetto_trace_list_activity);
        Button backButton = findViewById(R.id.back_button);

        backButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, KitchenSinkActivity.class);
            intent.putExtra("select", "perfetto");
            startActivityAsUser(intent, UserHandle.of(ActivityManager.getCurrentUser()));
            finish();
        });

        showTraceList();
    }

    private void showTraceList() {
        ListView listView = findViewById(R.id.trace_list);
        File[] traceFiles = PerfettoController.listTraceFiles(this);
        if (traceFiles == null || traceFiles.length == 0) {
            String[] noFiles = {getString(R.string.perfetto_trace_list_no_files)};
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_1, noFiles);
            listView.setAdapter(adapter);
            return;
        }

        String[] traceFileNames = new String[traceFiles.length];
        for (int i = 0; i < traceFiles.length; i++) {
            traceFileNames[i] = traceFiles[i].getName();
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, traceFileNames);
        listView.setAdapter(adapter);
    }
}
