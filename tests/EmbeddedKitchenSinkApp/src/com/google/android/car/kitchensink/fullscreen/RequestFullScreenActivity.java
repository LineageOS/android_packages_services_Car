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

package com.google.android.car.kitchensink.fullscreen;

import android.app.Activity;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.car.kitchensink.R;

public class RequestFullScreenActivity extends Activity {
    public static final String TAG = "RequestFullScreen";

    private TextView mStatus;

    private class FullScreenCallback implements OutcomeReceiver<Void, Throwable> {
        @Override
        public void onResult(Void result) {
            mStatus.setText("Full screen request successful");
            Log.i(TAG, "Fullscreen request successful");
        }

        @Override
        public void onError(Throwable error) {
            mStatus.setText("Full screen request failed: " + error.getMessage());
            Log.w(TAG, "Fullscreen request failed", error);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.request_full_screen_activity);

        mStatus = findViewById(R.id.request_status);
        Button request = findViewById(R.id.request_button);
        request.setOnClickListener(v -> {
            requestFullscreenMode(FULLSCREEN_MODE_REQUEST_ENTER, new FullScreenCallback());
        });
        Button back = findViewById(R.id.back_button);
        back.setOnClickListener(v -> {
            finish();
        });
    }
}
