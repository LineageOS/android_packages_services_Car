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

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.UserHandle;
import android.util.Log;

import com.android.car.testdpc.remotedpm.DevicePolicyManagerInterface;
import com.android.car.testdpc.remotedpm.LocalDevicePolicyManager;
import com.android.car.testdpc.remotedpm.RemoteDevicePolicyManager;

import java.util.List;
import java.util.stream.Collectors;

public final class DpcFactory {

    private static final String TAG = DpcFactory.class.getSimpleName();

    private List<DevicePolicyManagerInterface> mPoInterfaces;
    private DevicePolicyManagerInterface mDoInterface;

    private final Context mContext;
    private final DevicePolicyManager mDpm;

    public DpcFactory(Context context) {

        mContext = context;
        mDpm = mContext.getSystemService(DevicePolicyManager.class);

        if (isPO(mContext, mDpm) || isDO(mContext, mDpm)) {
            assignDevicePolicyManagers();
        }
    }

    private void assignDevicePolicyManagers() {
        List<UserHandle> targetUsers = mDpm.getBindDeviceAdminTargetUsers(DpcReceiver
                .getComponentName(mContext));
        Log.d(TAG, "targetUsers: " + targetUsers);

        if (isDO(mContext, mDpm)) {
            mDoInterface = new LocalDevicePolicyManager(mContext);
            mPoInterfaces = targetUsers.stream()
                    .map((u) -> new RemoteDevicePolicyManager(mContext, u))
                    .collect(Collectors.toList());
        } else {
            // Add all remote dpms to a list
            mPoInterfaces = targetUsers.stream()
                    .filter((u) -> !(u.equals(UserHandle.SYSTEM)))
                    .map((u) -> new RemoteDevicePolicyManager(mContext, u))
                    .collect(Collectors.toList());
            // Add local dpm to the same list
            mPoInterfaces.add(new LocalDevicePolicyManager(mContext));
            // Find the device owner userhandle and use it to set that to DoDpm
            mDoInterface = new RemoteDevicePolicyManager(mContext, targetUsers.stream()
                    .filter((u) -> u.equals(UserHandle.SYSTEM))
                    .findFirst()
                    .get());
        }
    }

    private static boolean isPO(Context context, DevicePolicyManager dpm) {
        return dpm.isProfileOwnerApp(context.getPackageName());
    }

    private static boolean isDO(Context context, DevicePolicyManager dpm) {
        return dpm.isDeviceOwnerApp(context.getPackageName());
    }

    public DevicePolicyManagerInterface getDoInterface() {
        return mDoInterface;
    }

    public List<DevicePolicyManagerInterface> getPoInterfaces() {
        return mPoInterfaces;
    }
}
