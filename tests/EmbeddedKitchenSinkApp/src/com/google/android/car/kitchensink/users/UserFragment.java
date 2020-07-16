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

import android.annotation.Nullable;
import android.app.AlertDialog;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;

/**
 * Shows information (and actions) about the current user.
 *
 * <p>Could / should be improved to:
 *
 * <ul>
 *   <li>Add more actions like renaming or deleting the user.
 *   <li>Add actions for other users (switch, create, remove etc).
 *   <li>Add option on how to execute tasks above (UserManager or CarUserManager).
 *   <li>Merge with UserRestrictions and ProfileUser fragments.
 * </ul>
 */
public final class UserFragment extends Fragment {

    private static final String TAG = UserFragment.class.getSimpleName();

    private final int mUserId = UserHandle.myUserId();
    private UserManager mUserManager;

    private EditText mUserIdEditText;
    private EditText mUserNameEditText;
    private EditText mUserInfoEditText;
    private CheckBox mIsAdminCheckBox;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.user, container, false);
    }

    public void onViewCreated(View view, Bundle savedInstanceState) {
        mUserManager = UserManager.get(getContext());

        mUserIdEditText = view.findViewById(R.id.user_id);
        mUserNameEditText = view.findViewById(R.id.user_name);
        mUserInfoEditText = view.findViewById(R.id.user_info);
        mIsAdminCheckBox = view.findViewById(R.id.is_admin);

        mIsAdminCheckBox.setOnClickListener((v) -> toggleAdmin());
        updateState();
    }

    private void toggleAdmin() {
        if (mIsAdminCheckBox.isChecked()) {
            new AlertDialog.Builder(getContext())
                    .setMessage("Promoting a user as admin is irreversible.\n\n Confirm?")
                    .setNegativeButton("No", (d, w) -> promoteCurrentUserAsAdmin(false))
                    .setPositiveButton("Yes", (d, w) -> promoteCurrentUserAsAdmin(true))
                    .show();
        } else {
            // Shouldn't be called
            Log.w(TAG, "Cannot un-set an admin user");
        }
    }

    private void promoteCurrentUserAsAdmin(boolean promote) {
        if (!promote) {
            Log.d(TAG, "NOT promoting user " + mUserId + " as admin");
        } else {
            Log.d(TAG, "Promoting user " + mUserId + " as admin");
            mUserManager.setUserAdmin(mUserId);
        }
        updateState();
    }

    private void updateState() {
        int userId = UserHandle.myUserId();
        boolean isAdmin = mUserManager.isAdminUser();
        UserInfo user = mUserManager.getUserInfo(mUserId);
        String name, userInfo;
        if (user == null) {
            userInfo = name = "N/A";
        } else {
            userInfo = user.toFullString();
            name = user.name;
        }
        Log.v(TAG, "updateState(): userId=" + mUserId + ", name=" + name + ", isAdmin=" + isAdmin
                + ", userInfo=" + userInfo);
        mUserIdEditText.setText(String.valueOf(mUserId));
        mUserNameEditText.setText(name);
        mUserInfoEditText.setText(userInfo);

        mIsAdminCheckBox.setChecked(isAdmin);
        mIsAdminCheckBox.setEnabled(!isAdmin); // there's no API to "un-admin a user"
    }
}
