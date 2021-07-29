/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car;

import android.annotation.Nullable;
import android.car.builtin.util.Slogf;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.IndentingPrintWriter;

import com.android.car.admin.PerUserCarDevicePolicyService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Proxy service for PerUserCarServiceImpl */
public class PerUserCarService extends ServiceProxy {
    private static final boolean DBG = false;
    private static final String TAG = PerUserCarService.class.getSimpleName();

    private @Nullable PerUserCarDevicePolicyService mPerUserCarDevicePolicyService;

    public PerUserCarService() {
        super(UpdatablePackageDependency.PER_USER_CAR_SERVICE_IMPL_CLASS);
    }

    @Override
    public void onCreate() {
        super.onCreate(); // necessary for impl side execution
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)) {
            mPerUserCarDevicePolicyService = PerUserCarDevicePolicyService.getInstance(this);
            mPerUserCarDevicePolicyService.onCreate();
        } else if (DBG) {
            Slogf.d(TAG, "Not setting PerUserCarDevicePolicyService because device doesn't have %s",
                    PackageManager.FEATURE_DEVICE_ADMIN);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mPerUserCarDevicePolicyService != null) {
            mPerUserCarDevicePolicyService.onDestroy();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(fd, writer, args);
        try (IndentingPrintWriter pw = new IndentingPrintWriter(writer)) {
            if (mPerUserCarDevicePolicyService != null) {
                pw.println("PerUserCarDevicePolicyService");
                pw.increaseIndent();
                mPerUserCarDevicePolicyService.dump(pw);
                pw.decreaseIndent();
            } else {
                pw.println("PerUserCarDevicePolicyService not needed");
            }
            pw.println();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // keep it alive.
        return START_STICKY;
    }
}
