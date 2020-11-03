/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.google.android.car.kitchensink.users;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.UserInfo;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.google.android.car.kitchensink.R;

/**
 * Custom {@link android.view.View} that shows an Android user info.
 */
public final class UserInfoView extends LinearLayout {

    private static final String TAG = UserInfoView.class.getSimpleName();

    private EditText mUserIdEditText;
    private EditText mUserNameEditText;
    private EditText mUserTypeEditText;
    private EditText mUserFlagsEditText;

    public UserInfoView(Context context, AttributeSet attrs) {
        super(context, attrs);

        inflate(context, R.layout.user_info_view, this);
        mUserIdEditText = findViewById(R.id.user_id);
        mUserNameEditText = findViewById(R.id.user_name);
        mUserTypeEditText = findViewById(R.id.user_type);
        mUserFlagsEditText = findViewById(R.id.user_flags);
    }

    /**
     * Initializes the widget with the given user.
     */
    public void update(@NonNull UserInfo user) {
        Log.v(TAG, "initializing with " + user);
        String userId, userName, userType, userFlags;
        if (user == null) {
            userId = userName = userType = userFlags = "N/A";
        } else {
            userId = String.valueOf(user.id);
            userName = user.name;
            userType = user.userType;
            userFlags = UserInfo.flagsToString(user.flags);
        }
        mUserIdEditText.setText(userId);
        mUserNameEditText.setText(userName);
        mUserTypeEditText.setText(userType);
        mUserFlagsEditText.setText(userFlags);
    }
}
