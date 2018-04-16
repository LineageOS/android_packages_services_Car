/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.car.pm;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

import com.android.car.R;

/**
 * Default activity that will be launched when the current foreground activity is not allowed.
 * Additional information on blocked Activity will be passed as extra in Intent
 * via {@link #INTENT_KEY_BLOCKED_ACTIVITY} key.
 */
public class ActivityBlockingActivity extends Activity {
    public static final String INTENT_KEY_BLOCKED_ACTIVITY = "blocked_activity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocking);

        String blockedActivity = getIntent().getStringExtra(INTENT_KEY_BLOCKED_ACTIVITY);

        TextView blockedTitle = findViewById(R.id.activity_blocked_title);
        blockedTitle.setText(getString(R.string.activity_blocked_string,
                findBlockedApplicationLabel(blockedActivity)));
    }

    /**
     * Returns the application label of blockedActivity. If that fails, the original activity will
     * be returned.
     */
    private String findBlockedApplicationLabel(String blockedActivity) {
        String label = blockedActivity;
        // Attempt to update blockedActivity name to application label.
        try {
            ApplicationInfo applicationInfo = getPackageManager().getApplicationInfo(
                    ComponentName.unflattenFromString(blockedActivity).getPackageName(), 0);
            CharSequence appLabel = getPackageManager().getApplicationLabel(applicationInfo);
            if (appLabel != null) {
                label = appLabel.toString();
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return label;
    }
}
