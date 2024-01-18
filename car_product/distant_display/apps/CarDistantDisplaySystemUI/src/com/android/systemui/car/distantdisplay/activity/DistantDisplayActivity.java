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
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.android.systemui.R;
import com.android.systemui.car.distantdisplay.common.TaskViewController;

/**
 * The Activity which plays the role of Distant Display testing.
 */
public class DistantDisplayActivity extends Activity {
    public static final String TAG = DistantDisplayActivity.class.getSimpleName();

    /** Creates an intent that can be used to launch this activity. */
    public static Intent createIntent(Context context) {
        Log.i(TAG, "createIntent");
        Intent intent = new Intent(context, DistantDisplayActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");
        setContentView(R.layout.car_distant_display_container);
        ViewGroup container = findViewById(R.id.taskview_container);
        TaskViewController controller = new TaskViewController(
                getApplicationContext(),
                getSystemService(DisplayManager.class),
                (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE));
        controller.initialize(container);
    }
}
