/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.testdpc;

import android.annotation.StringRes;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public final class DpcActivity extends Activity {

    private static final String TAG = DpcActivity.class.getSimpleName();

    private Context mContext;

    private TextView mCurrentUserTitle;
    private TextView mThisUser;
    private TextView mAddUserRestriction;
    private Button mRebootButton;
    private Button mAddUserRestrictionButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = getApplicationContext();

        setContentView(R.layout.activity_main);

        mCurrentUserTitle = findViewById(R.id.current_user_title);
        mCurrentUserTitle.setText(R.string.current_user_title);

        mThisUser = findViewById(R.id.this_user);
        mThisUser.setText(Process.myUserHandle().toString());

        mRebootButton = findViewById(R.id.reboot);
        mRebootButton.setOnClickListener(this::uiReboot);

        mAddUserRestriction = findViewById(R.id.add_user_restriction_title);
        mAddUserRestriction.setText(R.string.add_user_restriction);

        mAddUserRestrictionButton = findViewById(R.id.add_user_restriction_button);
        mAddUserRestrictionButton.setOnClickListener(this::uiAddUserRestriction);
    }

    public void uiReboot(View v) {
        showToast(R.string.rebooting);
        // TODO(b/235235034): Add call to reboot from device owner
    }

    public void uiAddUserRestriction(View v) {
        showToast(R.string.adding_user_restriction);
        // TODO(b/235235034): Add call to addUserRestriction from this user
    }

    public void showToast(@StringRes int text) {
        Log.i(TAG, mContext.getString(text));
        Toast showReboot = Toast.makeText(mContext, text, Toast.LENGTH_LONG);
        showReboot.show();
    }
}
