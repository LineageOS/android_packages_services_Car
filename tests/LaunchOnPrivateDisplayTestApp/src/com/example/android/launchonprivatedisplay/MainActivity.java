/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.example.android.launchonprivatedisplay;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;


/**
 * Activity which would launch an activity on the private display.
 */
public final class MainActivity extends Activity {

    private static final String NAMESPACE_KEY = "com.android.car.app.private_display";
    private static final String LAUNCH_ON_PRIVATE_DISPLAY =
            NAMESPACE_KEY + ".launch_on_private_display";
    private static final ComponentName LAUNCHED_ACTIVITY_COMPONENT_NAME = new ComponentName(
            "com.example.android.launchonprivatedisplay",
            "com.example.android.launchonprivatedisplay.ActivityForPrivateDisplay");
    private Button mButton;
    private Spinner mSpinner;
    private String[] mDisplayUniqueIds;
    private String[] mDisplayNames;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // TODO(b/344585656): Add an edit text to specify the activity name
        // TODO(b/344585656): Improve spinner text size
        setContentView(R.layout.main_activity);

        mButton = findViewById(R.id.button_view);
        mSpinner = findViewById(R.id.spinner_view);

        mDisplayUniqueIds = getResources().getStringArray(R.array.distant_display_unique_ids);
        mDisplayNames = getResources().getStringArray(R.array.distant_display_names);

        createSpinnerView();

        mSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // no-op
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });

        mButton.setOnClickListener(v -> {
            int selectedItemPosition = mSpinner.getSelectedItemPosition();
            String selectedOption = mDisplayUniqueIds[selectedItemPosition];

            final Intent intent = new Intent();
            intent.setComponent(LAUNCHED_ACTIVITY_COMPONENT_NAME);
            Bundle bundle = new Bundle();
            bundle.putString(LAUNCH_ON_PRIVATE_DISPLAY, selectedOption);
            ActivityOptions options = ActivityOptions.makeBasic();
            intent.putExtras(bundle);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent, options.toBundle());
        });
    }

    private void createSpinnerView() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, mDisplayNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpinner.setAdapter(adapter);
    }
}
