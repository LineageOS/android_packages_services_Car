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

import android.app.admin.DeviceAdminService;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;

import com.android.car.testdpc.remotedpm.DevicePolicyManagerInterface;
import com.android.car.testdpc.remotedpm.LocalDevicePolicyManager;
import com.android.car.testdpc.remotedpm.RemoteDevicePolicyManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service that handles calls to Device Policy Manager from shell.
 *
 * <p>This service handles binding of remote device policy managers to the current user
 * by checking if current user is device or profile owner. If it is either then it accordingly
 * binds and assigns all affiliated users to the appropriate {@code DevicePolicyManagerInterface}
 * implementation ({@code RemoteDevicePolicyManager}/{@code LocalDevicePolicyManager})
 */

public final class DpcService extends DeviceAdminService {

    private static final String TAG = DpcService.class.getSimpleName();
    private static final Set<String> AFFILIATION_IDS = Set.of("42");

    private ComponentName mAdmin;
    private Context mContext;
    private List<DevicePolicyManagerInterface> mPoInterfaces;
    private DevicePolicyManagerInterface mDoInterface;
    private DevicePolicyManager mDpm;

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d(TAG, "Service created (on user " + Process.myUserHandle() + ")");
        // Create PO/DO DPM and set each to local/remote based on UserHandle

        mContext = getApplicationContext();
        mAdmin = DpcReceiver.getComponentName(mContext);
        mDpm = mContext.getSystemService(DevicePolicyManager.class);

        mDoInterface = new LocalDevicePolicyManager(mContext);
        mPoInterfaces = new ArrayList<DevicePolicyManagerInterface>();

        if (isPO() || isDO()) {
            Log.i(TAG, "setAffiliationIds(" + mAdmin.flattenToShortString() + ", "
                    + AFFILIATION_IDS + ")");
            mDpm.setAffiliationIds(mAdmin, AFFILIATION_IDS);
            assignDevicePolicyManagers();
        }
    }

    private void assignDevicePolicyManagers() {
        List<UserHandle> targetUsers = mDpm.getBindDeviceAdminTargetUsers(DpcReceiver
                .getComponentName(mContext));
        Log.d(TAG, "targetUsers: " + targetUsers);

        if (mDpm.isDeviceOwnerApp(mContext.getPackageName())) {
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

    /* TODO(b/239466708): Add an onDestroy() method to unbind the services */

    /* TODO(b/239467077): Expand dump to add more metadata */
    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        Log.d(TAG, "dump(): "  + Arrays.toString(args));
        if (args != null && args.length > 0) {
            switch (args[0]) {
                case "cmd":
                    String[] cmdArgs = new String[args.length - 1];
                    System.arraycopy(args, 1, cmdArgs, 0, args.length - 1);
                    new DpcShellCommand(this, writer, cmdArgs, mPoInterfaces, mDoInterface).run();
                    return;
            }
        }
        super.dump(fd, writer, args);
    }

    public boolean isPO() {
        return mDpm.isProfileOwnerApp(mContext.getPackageName());
    }

    public boolean isDO() {
        return mDpm.isDeviceOwnerApp(mContext.getPackageName());
    }
}
