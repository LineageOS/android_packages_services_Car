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
package com.google.android.car.kitchensink.mainline;

import android.car.Car;
import android.car.content.pm.CarPackageManager;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Displays info about Car Mainline APIs.
 */
public class CarMainlineFragment extends Fragment {

    private static final String TAG = CarMainlineFragment.class.getSimpleName();

    private TextView mAppTargetSdk;
    private TextView mAppCompilationSdk;
    // TODO(b/228506662): also add a ListView with existing apps
    private TextView mAppCarTargetMajorSdk;
    private TextView mAppCarTargetMinorSdk;
    private TextView mAndroidSdkCodename;
    private TextView mAndroidSdkVersion;
    private TextView mCarMajorVersion;
    private TextView mCarMinorVersion;

    private CarPackageManager mCarPm;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.mainline, container, false);

        mAppTargetSdk = view.findViewById(R.id.app_target_sdk);
        mAppCompilationSdk = view.findViewById(R.id.app_compilation_sdk);
        mAppCarTargetMajorSdk = view.findViewById(R.id.app_car_target_major_sdk);
        mAppCarTargetMinorSdk = view.findViewById(R.id.app_car_target_minor_sdk);
        mAndroidSdkCodename = view.findViewById(R.id.android_sdk_codename);
        mAndroidSdkVersion = view.findViewById(R.id.android_sdk_version);
        mCarMajorVersion = view.findViewById(R.id.car_major_version);
        mCarMinorVersion = view.findViewById(R.id.car_minor_version);

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();

        ApplicationInfo appInfo = getContext().getApplicationContext().getApplicationInfo();

        Car car = ((KitchenSinkActivity) getHost()).getCar();
        mCarPm = (CarPackageManager) car.getCarManager(Car.PACKAGE_SERVICE);

        mAppTargetSdk.setText(String.valueOf(appInfo.targetSdkVersion));
        mAppCompilationSdk.setText(String.valueOf(appInfo.compileSdkVersion));
        mAndroidSdkCodename.setText(String.valueOf(Build.VERSION.CODENAME));
        mAndroidSdkVersion.setText(String.valueOf(Build.VERSION.SDK_INT));
        mCarMajorVersion.setText(String.valueOf(Car.API_VERSION_MAJOR_INT));
        mCarMinorVersion.setText(String.valueOf(Car.API_VERSION_MINOR_INT));

        boolean isCarApiTQpr = Car.API_VERSION_MAJOR_INT == Build.VERSION_CODES.TIRAMISU
                && Car.API_VERSION_MINOR_INT > 0;
        // TODO(b/230004170): use >= Build.VERSION_CODES.U when available
        boolean isCarApiUOrHigher = Car.API_VERSION_MAJOR_INT > Build.VERSION_CODES.TIRAMISU;
        Log.v(TAG, "onStart(): isTQpr=" + isCarApiTQpr + ", isUOrHigher=" + isCarApiUOrHigher);
        if (isCarApiTQpr || isCarApiUOrHigher) {
            String ksPkg = getContext().getPackageName();
            mAppCarTargetMajorSdk.setText(String.valueOf(mCarPm.getTargetCarMajorVersion(ksPkg)));
            mAppCarTargetMinorSdk.setText(String.valueOf(mCarPm.getTargetCarMinorVersion(ksPkg)));
        } else {
            // TODO(b/228506662): install on device running T to make sure it works
            String unsupported = String.format("unsupported on (%s, %s)", Car.API_VERSION_MAJOR_INT,
                    Car.API_VERSION_MINOR_INT);
            mAppCarTargetMajorSdk.setText(unsupported);
            mAppCarTargetMinorSdk.setText(unsupported);
        }
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.printf("%smAppTargetSdk: %s\n", prefix, mAppTargetSdk.getText());
        writer.printf("%smAppCompilationSdk: %s\n", prefix, mAppCompilationSdk.getText());
        writer.printf("%smAppCarTargetMajorSdk: %s\n", prefix, mAppCarTargetMajorSdk.getText());
        writer.printf("%smAppCarTargetMinorSdk: %s\n", prefix, mAppCarTargetMinorSdk.getText());
        writer.printf("%smAndroidSdkCodename: %s\n", prefix, mAndroidSdkCodename.getText());
        writer.printf("%smAndroidSdkVersion: %s\n", prefix, mAndroidSdkVersion.getText());
        writer.printf("%smCarMajorVersion: %s\n", prefix, mCarMajorVersion.getText());
        writer.printf("%smCarMinorVersion: %s\n", prefix, mCarMinorVersion.getText());
    }
}
