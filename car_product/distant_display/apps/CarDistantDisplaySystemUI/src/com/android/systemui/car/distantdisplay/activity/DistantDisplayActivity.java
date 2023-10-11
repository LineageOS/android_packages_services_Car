/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.car.distantdisplay.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.android.systemui.R;
import com.android.systemui.car.distantdisplay.common.TaskViewController;

public class DistantDisplayActivity extends Activity {
    public static final String TAG = DistantDisplayActivity.class.getSimpleName();


    /** Creates an intent that can be used to launch this activity. */
    public static Intent createIntent(Context context) {
        Intent intent = new Intent(context, DistantDisplayActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(TaskViewController.getTaskViewContainerLayoutResId());
        ViewGroup container = findViewById(R.id.taskview_container);
        TaskViewController controller =
                new TaskViewController(container.getContext());
        controller.initialize(container, true);
    }
}
