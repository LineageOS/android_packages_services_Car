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
package com.google.android.car.kitchensink.admin;

import android.annotation.Nullable;
import android.app.AlertDialog;
import android.car.Car;
import android.car.admin.CarDevicePolicyManager;
import android.car.admin.RemoveUserResult;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;
import com.google.android.car.kitchensink.users.ExistingUsersView;
import com.google.android.car.kitchensink.users.UserInfoView;

/**
 * Test UI for {@link CarDevicePolicyManager}.
 */
public final class DevicePolicyFragment extends Fragment {

    private static final String TAG = DevicePolicyFragment.class.getSimpleName();

    private UserManager mUserManager;
    private CarDevicePolicyManager mCarDevicePolicyManager;

    // Current user
    private UserInfoView mCurrentUser;

    // Existing users
    private ExistingUsersView mCurrentUsers;

    private Button mRemoveUserButton;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.device_policy, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        mUserManager = UserManager.get(getContext());
        Car car = ((KitchenSinkActivity) getHost()).getCar();
        mCarDevicePolicyManager = (CarDevicePolicyManager) car
                .getCarManager(Car.CAR_DEVICE_POLICY_SERVICE);

        mCurrentUser = view.findViewById(R.id.current_user);
        mCurrentUsers = view.findViewById(R.id.current_users);
        mRemoveUserButton = view.findViewById(R.id.remove_user);

        mRemoveUserButton.setOnClickListener((v) -> removeUser());

        updateState();
    }

    private void updateState() {
        // Current user
        int userId = UserHandle.myUserId();
        UserInfo user = mUserManager.getUserInfo(userId);
        Log.v(TAG, "updateState(): currentUser= " + user);
        mCurrentUser.update(user);

        // Existing users
        mCurrentUsers.updateState();
    }

    private void removeUser() {
        int userId = mCurrentUsers.getSelectedUserId();
        Log.i(TAG, "Remove user: " + userId);
        RemoveUserResult result = mCarDevicePolicyManager.removeUser(UserHandle.of(userId));
        updateState();

        if (result.isSuccess()) {
            showMessage("User %d removed", userId);
        } else {
            showMessage("Failed to remove user %d: %s", userId, result);
        }
    }

    private void showMessage(String pattern, Object... args) {
        String message = String.format(pattern, args);
        Log.v(TAG, "showMessage(): " + message);
        new AlertDialog.Builder(getContext()).setMessage(message).show();
    }
}
