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
package com.google.android.car.kitchensink.activitylaunch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import com.google.android.car.kitchensink.R;

public class LaunchTestActivity1 extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.launch_test_activity_1);

        Button startSameTask = findViewById(R.id.start_activity_same_task);
        startSameTask.setOnClickListener(v -> {
            Intent intent = new Intent(this, LaunchTestActivity2.class);
            startActivity(intent);
        });

        Button startNewTask = findViewById(R.id.start_activity_new_task);
        startNewTask.setOnClickListener(v -> {
            Intent intent = new Intent(this, LaunchTestActivity2.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        Button startNewTaskAdjacent = findViewById(R.id.start_activity_new_task_adjacent);
        startNewTaskAdjacent.setOnClickListener(v -> {
            Intent intent = new Intent(this, LaunchTestActivity2.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            startActivity(intent);
        });

        Button backButton = findViewById(R.id.back_button);
        backButton.setOnClickListener(v -> finish());
    }
}
