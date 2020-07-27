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
import android.annotation.Nullable;
import android.app.AlertDialog;
import android.car.Car;
import android.car.user.CarUserManager;
import android.car.user.UserCreationResult;
import android.car.user.UserRemovalResult;
import android.car.user.UserSwitchResult;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;

import androidx.fragment.app.Fragment;

import com.android.internal.infra.AndroidFuture;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;

import java.util.List;
import java.util.concurrent.TimeUnit;

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

    private static final long TIMEOUT_MS = 5_000;

    private final int mUserId = UserHandle.myUserId();
    private UserManager mUserManager;
    private CarUserManager mCarUserManager;

    // Current user
    private EditText mUserIdEditText;
    private EditText mUserNameEditText;
    private EditText mUserTypeEditText;
    private EditText mUserFlagsEditText;
    private CheckBox mIsAdminCheckBox;

    // Existing users
    private UsersSpinner mUsersSpinner;
    private Button mSwitchUserButton;
    private Button mRemoveUserButton;
    private EditText mNewUserNameText;
    private CheckBox mNewUserIsAdminCheckBox;
    private CheckBox mNewUserIsGuestCheckBox;
    private EditText mNewUserExtraFlagsText;
    private Button mCreateUserButton;
    private EditText mSelectedUserTypeText;
    private EditText mSelectedUserFlagsText;


    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.user, container, false);
    }

    public void onViewCreated(View view, Bundle savedInstanceState) {
        mUserManager = UserManager.get(getContext());
        Car car = ((KitchenSinkActivity) getHost()).getCar();
        mCarUserManager = (CarUserManager) car.getCarManager(Car.CAR_USER_SERVICE);

        mUserIdEditText = view.findViewById(R.id.user_id);
        mUserNameEditText = view.findViewById(R.id.user_name);
        mUserTypeEditText = view.findViewById(R.id.user_type);
        mUserFlagsEditText = view.findViewById(R.id.user_flags);
        mIsAdminCheckBox = view.findViewById(R.id.is_admin);

        mUsersSpinner = view.findViewById(R.id.existing_users);
        mSwitchUserButton = view.findViewById(R.id.switch_user);
        mRemoveUserButton = view.findViewById(R.id.remove_user);
        mNewUserNameText = view.findViewById(R.id.new_user_name);
        mNewUserIsAdminCheckBox = view.findViewById(R.id.new_user_is_admin);
        mNewUserIsGuestCheckBox = view.findViewById(R.id.new_user_is_guest);
        mNewUserExtraFlagsText = view.findViewById(R.id.new_user_flags);
        mCreateUserButton = view.findViewById(R.id.create_user);
        mSelectedUserTypeText = view.findViewById(R.id.selected_user_type);
        mSelectedUserFlagsText = view.findViewById(R.id.selected_user_flags);

        mIsAdminCheckBox.setOnClickListener((v) -> toggleAdmin());
        mSwitchUserButton.setOnClickListener((v) -> switchUser());
        mRemoveUserButton.setOnClickListener((v) -> removeUser());
        mCreateUserButton.setOnClickListener((v) -> createUser());

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

    private void createUser() {
        String name = mNewUserNameText.getText().toString();
        if (TextUtils.isEmpty(name)) {
            name = null;
        }
        int flags = 0;
        boolean isGuest = mNewUserIsGuestCheckBox.isChecked();
        AndroidFuture<UserCreationResult> future;
        if (isGuest) {
            Log.i(TAG, "Create guest: " + name);
            future = mCarUserManager.createGuest(name);
        } else {
            if (mNewUserIsAdminCheckBox.isChecked()) {
                flags |= UserInfo.FLAG_ADMIN;
            }
            String extraFlags = mNewUserExtraFlagsText.getText().toString();
            if (!TextUtils.isEmpty(extraFlags)) {
                try {
                    flags |= Integer.parseInt(extraFlags);
                } catch (RuntimeException e) {
                    Log.e(TAG, "createUser(): non-numeric flags " + extraFlags);
                }
            }
            Log.v(TAG, "Create user: name=" + name + ", flags=" + UserInfo.flagsToString(flags));
            future = mCarUserManager.createUser(name, UserManager.USER_TYPE_FULL_SECONDARY, flags);
        }
        UserCreationResult result = getResult(future);
        updateState();
        StringBuilder message = new StringBuilder();
        if (result == null) {
            message.append("Timed out creating user");
        } else {
            if (result.isSuccess()) {
                message.append("User created: ").append(result.getUser().toFullString());
            } else {
                int status = result.getStatus();
                message.append("Failed with code ").append(status).append('(')
                        .append(UserCreationResult.statusToString(status)).append(')');
            }
            String error = result.getErrorMessage();
            if (error != null) {
                message.append("\nError message: ").append(error);
            }
        }
        showMessage(message.toString());
    }

    private void removeUser() {
        int userId = mUsersSpinner.getSelectedUserId();
        Log.i(TAG, "Remove user: " + userId);
        UserRemovalResult result = getResult(mCarUserManager.removeUser(userId));
        updateState();

        if (result.isSuccess()) {
            showMessage("User %d removed", userId);
        } else {
            showMessage("Failed to remove user %d: %s", userId,
                    UserRemovalResult.statusToString(result.getStatus()));
        }
    }

    private void switchUser() {
        int userId = mUsersSpinner.getSelectedUserId();
        Log.i(TAG, "Switch user: " + userId);
        AndroidFuture<UserSwitchResult> future = mCarUserManager.switchUser(userId);
        UserSwitchResult result = getResult(future);
        updateState();

        StringBuilder message = new StringBuilder();
        if (result == null) {
            message.append("Timed out switching user");
        } else {
            int status = result.getStatus();
            if (result.isSuccess()) {
                message.append("Switched to user ").append(userId).append(" (status=")
                        .append(UserSwitchResult.statusToString(status)).append(')');
            } else {
                message.append("Failed with code ").append(status).append('(')
                        .append(UserSwitchResult.statusToString(status)).append(')');
            }
            String error = result.getErrorMessage();
            if (error != null) {
                message.append("\nError message: ").append(error);
            }
        }
        showMessage(message.toString());
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
        // Current user
        int userId = UserHandle.myUserId();
        boolean isAdmin = mUserManager.isAdminUser();
        UserInfo user = mUserManager.getUserInfo(mUserId);
        String userName, userType, userFlags;
        if (user == null) {
            userName = userType = userFlags = "N/A";
        } else {
            userName = user.name;
            userType = user.userType;
            userFlags = UserInfo.flagsToString(user.flags);
        }
        Log.v(TAG, "updateState(): userId=" + mUserId + ", name=" + userName + ", type=" + userType
                + ", flags=" + userFlags + ", isAdmin=" + isAdmin);
        mUserIdEditText.setText(String.valueOf(mUserId));
        mUserNameEditText.setText(userName);
        mUserTypeEditText.setText(userType);
        mUserFlagsEditText.setText(userFlags);

        mIsAdminCheckBox.setChecked(isAdmin);
        mIsAdminCheckBox.setEnabled(!isAdmin); // there's no API to "un-admin a user"

        // Existing users
        List<UserInfo> allUsers = mUserManager.getUsers(/* excludeDying= */ true);
        Log.v(TAG, allUsers.size() + " users: " + allUsers);
        mUsersSpinner.setOnUserSelectedListener((u) -> onUserSelected(u));
        mUsersSpinner.init(allUsers);
    }

    private void onUserSelected(@NonNull UserInfo user) {
        mSelectedUserTypeText.setText(user.userType);
        mSelectedUserFlagsText.setText(UserInfo.flagsToString(user.flags));
    }

    private void showMessage(String pattern, Object... args) {
        String message = String.format(pattern, args);
        Log.v(TAG, "showMessage(): " + message);
        new AlertDialog.Builder(getContext()).setMessage(message).show();
    }

    @Nullable
    private static <T> T getResult(AndroidFuture<T> future) {
        future.whenCompleteAsync((r, e) -> {
            if (e != null) {
                Log.e(TAG, "You have no future!", e);
                return;
            }
            Log.v(TAG, "The future is here: " + r);
        }, Runnable::run);

        T result = null;
        try {
            result = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result == null) {
                Log.e(TAG, "Timeout (" + TIMEOUT_MS + "ms) waiting for future " + future);
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted waiting for future " + future, e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Exception getting future " + future, e);
        }
        return result;
    }
}
