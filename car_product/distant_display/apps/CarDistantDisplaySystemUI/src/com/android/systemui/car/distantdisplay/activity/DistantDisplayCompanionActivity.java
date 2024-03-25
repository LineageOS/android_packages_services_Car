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

package com.android.systemui.car.distantdisplay.activity;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.car.distantdisplay.common.TaskViewController;

import javax.inject.Inject;

public class DistantDisplayCompanionActivity extends Activity {
    private static final String KEY_COMPANION_MOVED_PACKAGE_NAME =
            "key_companion_moved_package_name";

    private final Context mContext;
    private final TaskViewController mTaskViewController;

    /**
     * Create new intent for the DistantDisplayCompanionActivity with the moved package name
     * provided as an extra.
     **/
    public static Intent createIntent(Context context, String movedPackageName) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(context, DistantDisplayCompanionActivity.class));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (movedPackageName != null) {
            intent.putExtra(KEY_COMPANION_MOVED_PACKAGE_NAME, movedPackageName);
        }
        return intent;
    }

    @Inject
    public DistantDisplayCompanionActivity(Context context, TaskViewController taskViewController) {
        mContext = context;
        mTaskViewController = taskViewController;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.car_distant_display_companion_activity);

        TextView textView = findViewById(R.id.activity_companion_message);
        if (textView != null) {
            textView.setText(
                    mContext.getString(R.string.distant_display_companion_message, getAppString()));
        }

        Button moveBackButton = findViewById(R.id.move_back_button);
        if (moveBackButton != null) {
            moveBackButton.setOnClickListener((v) -> {
                mTaskViewController.moveTaskFromDistantDisplay();
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }

    private String getAppString() {
        if (!getIntent().hasExtra(KEY_COMPANION_MOVED_PACKAGE_NAME)) {
            return getDefaultAppString();
        }
        String packageString = getIntent().getStringExtra(KEY_COMPANION_MOVED_PACKAGE_NAME);
        if (TextUtils.isEmpty(packageString)) {
            return getDefaultAppString();
        }
        PackageManager pm = mContext.getPackageManager();
        ApplicationInfo info;
        try {
            info = pm.getApplicationInfo(packageString, /* flags= */ 0);
        } catch (PackageManager.NameNotFoundException e) {
            return getDefaultAppString();
        }
        return String.valueOf(pm.getApplicationLabel(info));
    }

    private String getDefaultAppString() {
        return mContext.getString(R.string.distant_display_companion_message_default_app);
    }
}
