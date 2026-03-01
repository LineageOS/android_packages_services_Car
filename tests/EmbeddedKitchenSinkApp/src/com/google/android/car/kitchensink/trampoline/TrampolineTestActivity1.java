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
package com.google.android.car.kitchensink.trampoline;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.car.kitchensink.R;

public class TrampolineTestActivity1 extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.trampoline_test_activity);

        TextView activityName = findViewById(R.id.activity_name);
        activityName.setText("TrampolineTestActivity1");

        Button backButton = findViewById(R.id.back_button);
        backButton.setOnClickListener(v -> finish());

        if (getIntent().getBooleanExtra(TrampolineFragment.HOME_TRAMPOLINE_KEY, false)) {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
            return;
        }

        Intent intent = new Intent(this, TrampolineTestActivity2.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (getIntent().getBooleanExtra(TrampolineFragment.DOUBLE_TRAMPOLINE_KEY, false)) {
            intent.putExtra(TrampolineFragment.DOUBLE_TRAMPOLINE_KEY, true);
            if (getIntent().getBooleanExtra(TrampolineFragment.FINISH_KEY, false)) {
                intent.putExtra(TrampolineFragment.FINISH_KEY, true);
            }
        }
        startActivity(intent);
    }
}
