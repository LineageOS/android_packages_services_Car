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
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.car.testdpc.remotedpm.DevicePolicyManagerInterface;
import com.android.car.testdpc.remotedpm.LocalDevicePolicyManager;
import com.android.car.testdpc.remotedpm.RemoteDevicePolicyManager;

import com.google.errorprone.annotations.FormatMethod;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class DpcFactory {

    private static final String TAG = DpcFactory.class.getSimpleName();
    private static final int SLEEP_TIME_MS = 100;
    private static final int SLEEP_MAX_MS = 10_000;

    private List<DevicePolicyManagerInterface> mPoInterfaces;
    private DevicePolicyManagerInterface mDoInterface;

    private final ComponentName mAdmin;
    private final Context mContext;
    private final DevicePolicyManager mDpm;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    public DpcFactory(Context context) {
        mAdmin = DpcReceiver.getComponentName(context);
        mContext = context;
        mDpm = mContext.getSystemService(DevicePolicyManager.class);

        mHandlerThread = new HandlerThread("DpcHelperThread");
        mHandlerThread.start();

        mHandler = new Handler(mHandlerThread.getLooper());

        if (isPO(mContext, mDpm) || isDO(mContext, mDpm)) {
            assignDevicePolicyManagers();
        }
    }

    private void assignDevicePolicyManagers() {
        List<UserHandle> targetUsers = mDpm.getBindDeviceAdminTargetUsers(mAdmin);
        Log.d(TAG, "targetUsers: " + targetUsers);

        if (isDO(mContext, mDpm)) {
            mDoInterface = new LocalDevicePolicyManager(mAdmin, mContext);
            mPoInterfaces = targetUsers.stream()
                    .map((u) -> new RemoteDevicePolicyManager(mAdmin, mContext, u))
                    .collect(Collectors.toList());
        } else {
            // Add all remote dpms to a list
            mPoInterfaces = targetUsers.stream()
                    .filter((u) -> !(u.equals(UserHandle.SYSTEM)))
                    .map((u) -> new RemoteDevicePolicyManager(mAdmin, mContext, u))
                    .collect(Collectors.toList());
            // Add local dpm to the same list
            mPoInterfaces.add(new LocalDevicePolicyManager(mAdmin, mContext));
            // Find the device owner userhandle and use it to set that to DoDpm
            mDoInterface = new RemoteDevicePolicyManager(mAdmin, mContext, targetUsers.stream()
                    .filter((u) -> u.equals(UserHandle.SYSTEM))
                    .findFirst()
                    .get());
        }
    }

    public void addProfileOwnerDpm(UserHandle target) {
        if (mPoInterfaces.stream().filter((u) -> u.getUser().equals(target))
                .findFirst().isPresent()) {
            return;
        }
        mHandler.post(
                () -> mPoInterfaces.add(new RemoteDevicePolicyManager(mAdmin, mContext, target))
        );
    }

    public void removeProfileOwnerDpm(UserHandle target) {
        Optional<DevicePolicyManagerInterface> poInterface = mPoInterfaces.stream()
                .filter((u) -> u.getUser().equals(target)).findFirst();
        if (!poInterface.isPresent()) {
            return;
        }
        Log.i(TAG, "Remove User: " + poInterface.get());
        mPoInterfaces.remove(poInterface.get());
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

    /**
     * Executes commands for stopped users
     *
     * <p> This will execute commands passed in as Runnables and will attempt to run them
     * from a stopped user by staring it, waiting for success, running the command, then stopping
     * the user.
     */
    @FormatMethod
    public void runOnOfflineUser(Runnable cmd, UserHandle targetUser,
            String cmdFormat, Object... args) {

        if (!isDO(mContext, mDpm)) {
            throw new IllegalStateException("Caller is not device owner.");
        }

        String command = String.format(cmdFormat, args);
        Log.d(TAG, "Running " + command + " on user " + targetUser);

        mHandler.post(() -> {
            // start user
            int status = mDpm.startUserInBackground(mAdmin, targetUser);
            boolean successStart = status == UserManager.USER_OPERATION_SUCCESS;
            Log.d(TAG, "User started? " + successStart);

            /*
             * TODO(b/242105770): take advantage of the binder callback
             * by just re-establishing binding during start service
             */

            RemoteDevicePolicyManager targetDpm =
                    new RemoteDevicePolicyManager(mAdmin, mContext, targetUser);
            mPoInterfaces.add(targetDpm);

            waitForBound(targetDpm);

            try {
                cmd.run();
            } catch (Exception e) {
                Log.e(TAG, "Could not execute command " + command, e);
                return;
            }

            // Stop user knowing command has executed without exception
            int statusStop = mDpm.stopUser(mAdmin, targetUser);
            boolean succeedStop = statusStop == UserManager.USER_OPERATION_SUCCESS;
            removeProfileOwnerDpm(targetUser);
            Log.d(TAG, "User stopped? " + succeedStop);
        });
    }

    private void waitForBound(RemoteDevicePolicyManager targetDpm) {
        int totalTime = 0;
        while (!targetDpm.isBound()) {
            try {
                Thread.sleep(SLEEP_TIME_MS);
                totalTime += SLEEP_TIME_MS;
                if (totalTime > SLEEP_MAX_MS) {
                    Log.i(TAG, "Reached max sleep time, no longer attempting to bind.");
                    break;
                }
                Log.i(TAG, "sleeping for " + totalTime + " seconds.");
                Log.i(TAG, "Is user bound? " + targetDpm.isBound());
            } catch (InterruptedException e) {
                Log.e(TAG, "Thread sleep was interrupted.", e);
                Thread.currentThread().interrupt();
            }
        }
    }
}
