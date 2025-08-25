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

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

public class ActivityLaunchFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_launch_fragment, container, false);

        Button startSameTask = view.findViewById(R.id.start_activity_same_task);
        startSameTask.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), LaunchTestActivity1.class);
            startActivity(intent);
        });

        Button startNewTask = view.findViewById(R.id.start_activity_new_task);
        startNewTask.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), LaunchTestActivity1.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        Button startNewTaskAdjacent = view.findViewById(R.id.start_activity_new_task_adjacent);
        startNewTaskAdjacent.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), LaunchTestActivity1.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            startActivity(intent);
        });

        return view;
    }
}
