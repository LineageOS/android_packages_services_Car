/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.google.android.car.garagemode.testapp;

import android.app.Activity;
import android.app.job.JobInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.Spinner;

public class MainActivity extends Activity implements AdapterView.OnItemSelectedListener {
    private static final Logger LOG = new Logger("GarageModeTestApp");


    private String mNetworkRequirement;
    private int mJobDurationSelected;
    private int mGarageModeDurationSelected;

    private CheckBox mRequirePersisted;
    private CheckBox mRequireIdleness;
    private CheckBox mRequireCharging;

    private JobSchedulerWrapper mJobSchedulerWrapper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        populateNetworkTypeSpinner();
        populateJobDurationSpinner();
        populateGarageModeSpinner();

        mRequirePersisted = findViewById(R.id.requirePersistedCheckbox);
        mRequireIdleness = findViewById(R.id.requireIdlenessCheckbox);
        mRequireCharging = findViewById(R.id.requireChargingCheckbox);

        mJobSchedulerWrapper = new JobSchedulerWrapper(
                this,
                (ListView) findViewById(R.id.jobsListView));

        ((Button) findViewById(R.id.addJobBtn)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                LOG.d("Adding a job...");
                mJobSchedulerWrapper.scheduleAJob(
                        mJobDurationSelected,
                        parseNetworkRequirement(),
                        mRequireCharging.isChecked(),
                        mRequireIdleness.isChecked());
            }
        });
    }

    private int parseNetworkRequirement() {
        if (mNetworkRequirement.equals("NONE")) {
            return JobInfo.NETWORK_TYPE_NONE;
        }
        if (mNetworkRequirement.equals("UNMETERED")) {
            return JobInfo.NETWORK_TYPE_UNMETERED;
        }
        if (mNetworkRequirement.equals("ANY")) {
            return JobInfo.NETWORK_TYPE_ANY;
        }
        return JobInfo.NETWORK_BYTES_UNKNOWN;
    }

    private void populateGarageModeSpinner() {
        populateSpinner(
                (Spinner) findViewById(R.id.garageModeDuration),
                ArrayAdapter.createFromResource(
                        this,
                        R.array.duration_list,
                        android.R.layout.simple_spinner_item));
    }

    private void populateJobDurationSpinner() {
        populateSpinner(
                (Spinner) findViewById(R.id.jobDuration),
                ArrayAdapter.createFromResource(
                        this,
                        R.array.duration_list,
                        android.R.layout.simple_spinner_item));
    }

    private void populateNetworkTypeSpinner() {
        populateSpinner(
                (Spinner) findViewById(R.id.networkType),
                ArrayAdapter.createFromResource(
                        this,
                        R.array.network_types_list,
                        android.R.layout.simple_spinner_item));
    }

    private void populateSpinner(Spinner spinner, ArrayAdapter adapter) {
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        String value = (String) parent.getItemAtPosition(pos);
        switch (parent.getId()) {
            case R.id.networkType:
                applyNetworkTypeRequirement(value);
                break;
            case R.id.jobDuration:
                applyJobDuration(value);
                break;
            case R.id.garageModeDuration:
                applyGarageModeDuration(value);
                break;
        }
    }

    private String stringDump() {
        String s = "";
        s += "Network Type: " + mNetworkRequirement + "\n";
        s += "Job Duration: " + mJobDurationSelected + "\n";
        s += "GarageMode Duration: " + mGarageModeDurationSelected + "\n";
        return s;
    }

    private void applyGarageModeDuration(String value) {
        String metric = value.split(" ")[1];
        mGarageModeDurationSelected = Integer.parseInt(value.split(" ")[0]);
        if (metric.startsWith("minute")) {
            mGarageModeDurationSelected *= 60;
        }
        if (metric.startsWith("hour")) {
            mGarageModeDurationSelected *= 3600;
        }
    }

    private void applyJobDuration(String value) {
        String metric = value.split(" ")[1];
        mJobDurationSelected = Integer.parseInt(value.split(" ")[0]);
        if (metric.startsWith("minute")) {
            mJobDurationSelected *= 60;
        }
        if (metric.startsWith("hour")) {
            mJobDurationSelected *= 3600;
        }
    }

    private void applyNetworkTypeRequirement(String value) {
        mNetworkRequirement = value;
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {

    }
}
