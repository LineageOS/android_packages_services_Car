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

package com.google.android.car.kitchensink.appshare;

import android.car.Car;
import android.car.user.CarUserManager;
import android.car.user.UserLifecycleEventFilter;
import android.content.Context;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Proof of concept for application sharing in concurrent multi user.
 */
public final class AppSharingFragment extends Fragment{
    private static final String TAG = AppSharingFragment.class.getSimpleName();

    private UserManager mUserManager;
    private CarUserManager mCarUserManager;
    private PackageManager mPackageManager;
    private PackageInstaller mPackageInstaller;
    private UserInfo mTargetUser;
    private String mPackageToInstall;

    private Spinner mUserSpinner;
    private Spinner mPackageSpinner;
    private Button mInstallButton;
    private TextView mResult;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUserManager = getContext().getSystemService(UserManager.class);
        mPackageManager = getActivity().getPackageManager();

        Car car = ((KitchenSinkActivity) getHost()).getCar();
        mCarUserManager = car.getCarManager(CarUserManager.class);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstance) {
        View view = inflater.inflate(R.layout.app_sharing, container, false);
        mUserSpinner = view.findViewById(R.id.user_spinner);
        mPackageSpinner = view.findViewById(R.id.package_spinner);
        mInstallButton = view.findViewById(R.id.install_button);
        mResult = view.findViewById(R.id.result);

        mInstallButton.setOnClickListener(vw -> installPackage());
        mUserSpinner.setOnItemSelectedListener(new UserSelector());
        mPackageSpinner.setOnItemSelectedListener(new PackageSelector());

        UserLifecycleEventFilter filter = new UserLifecycleEventFilter.Builder()
                .addEventType(CarUserManager.USER_LIFECYCLE_EVENT_TYPE_CREATED)
                .addEventType(CarUserManager.USER_LIFECYCLE_EVENT_TYPE_REMOVED)
                .build();
        mCarUserManager.addListener(
                getContext().getMainExecutor(), filter, event -> refreshUsers());
        refreshUsers();

        return view;
    }

    private void refreshUsers() {
        initUserSpinner();
        refreshPackages();
    }

    private void refreshPackages() {
        mResult.setText("");
        initPackageSpinner();

        // Sets the package installer for the currently selected target user.
        String message = "Failed to create user context for user " + mTargetUser.id;
        try {
            Context userContext = getContext().createContextAsUser(
                    UserHandle.of(mTargetUser.id), /* flags= */ 0);
            message = "Failed to create package installer for user " + mTargetUser.id;
            mPackageInstaller = userContext.getPackageManager().getPackageInstaller();
        } catch (Exception e) {
            Log.e(TAG, message, e);
            mResult.setText(message);
        }
    }

    /** Installs {@code mPackageToInstall} for {@code mTargetUser}. **/
    private void installPackage() {
        String message = "Failed to install " + mPackageToInstall + " for user " + mTargetUser.id;
        try {
            mPackageInstaller.installExistingPackage(mPackageToInstall,
                    PackageManager.INSTALL_REASON_USER, /* statusReceiver= */ null);
            message = "Successfully installed " + mPackageToInstall + " for user " + mTargetUser.id;
        } catch (Exception e) {
            message = "Failed to install " + mPackageToInstall + " for user " + mTargetUser.id;
            Log.e(TAG, message, e);
        }

        mResult.setText(message);
    }

    /**
     * Gets the packages installed for the context user but not installed for the target user.
     **/
    private List<String> getPackagesForSharing() {
        int contextUser = getContext().getUser().getIdentifier();
        List<String> packages = new ArrayList<>();
        try {
            packages = mPackageManager
                    .getInstalledPackagesAsUser(/* flags= */ 0, contextUser)
                    .stream().map(pkg -> pkg.packageName).collect(Collectors.toList());
            Log.d(TAG, "Total " + packages.size() + " packages found for context user "
                    + contextUser);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get packages for context user: " + contextUser);
        }
        List<String> targetUserPackages = new ArrayList<>();
        try {
            targetUserPackages = mPackageManager
                    .getInstalledPackagesAsUser(/* flags= */ 0, mTargetUser.id)
                    .stream().map(pkg -> pkg.packageName).collect(Collectors.toList());
            Log.d(TAG, "Total " + targetUserPackages.size()
                    + " Packages found for target user: " + mTargetUser.id);
        } catch (Exception e) {
            Log.e(TAG, "failed to get packages for target user: " + mTargetUser.id);
        }

        // Only return the packages that are not installed for the target user.
        packages.removeAll(targetUserPackages);
        Log.d(TAG, "Found " + packages.size() + " packages not installed for target user: "
                + mTargetUser.id);

        return packages;
    }

    /** Initializes the user spinner with the existing full users. */
    private void initUserSpinner() {
        List<UserInfo> users = mUserManager.getUsers();
        users.removeIf(u -> !u.isFull());
        if (mTargetUser == null) {
            mTargetUser = users.get(0);
        }
        ArrayAdapter<UserInfo> userArrayAdapter = new ArrayAdapter<>(
                getContext(), android.R.layout.simple_spinner_item, users);
        userArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mUserSpinner.setAdapter(userArrayAdapter);
    }

    /** Initializes the package spinner with the packages installed for the context user. **/
    private void initPackageSpinner() {
        List<String> packages = getPackagesForSharing();
        ArrayAdapter<String> packageArrayAdapter = new ArrayAdapter<>(
                getContext(), android.R.layout.simple_spinner_item, packages);
        packageArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mPackageSpinner.setAdapter(packageArrayAdapter);
    }

    private final class UserSelector implements AdapterView.OnItemSelectedListener {
        @Override
        public void onItemSelected(AdapterView<?> parent, View currentView, int position, long id) {
            mTargetUser = (UserInfo) parent.getItemAtPosition(position);

            refreshPackages();
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    private final class PackageSelector implements AdapterView.OnItemSelectedListener {
        @Override
        public void onItemSelected(AdapterView<?> parent, View currentView, int position, long id) {
            String selected = (String) parent.getItemAtPosition(position);
            if (!selected.equals(mPackageToInstall)) {
                mResult.setText("");
                mPackageToInstall = selected;
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }
}
