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
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.car.testdpc.remotedpm.DevicePolicyManagerInterface;
import com.android.car.testdpc.remotedpm.LocalDevicePolicyManager;
import com.android.car.testdpc.remotedpm.RemoteDevicePolicyManager;

import com.google.errorprone.annotations.FormatMethod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class DpcFactory {

    private static final String TAG = DpcFactory.class.getSimpleName();
    private static final int SLEEP_TIME_MS = 100;
    private static final int SLEEP_MAX_MS = 10_000;

    private final Map<UserHandle, DevicePolicyManagerInterface> mDevicePolicyManagers;

    private final ComponentName mAdmin;
    private final Context mContext;
    private final DevicePolicyManager mDpm;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    public DpcFactory(Context context) {
        mAdmin = DpcReceiver.getComponentName(context);
        mContext = context;
        mDpm = mContext.getSystemService(DevicePolicyManager.class);
        mDevicePolicyManagers = new HashMap<>();

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

        mDevicePolicyManagers.putAll(targetUsers.stream()
                .collect(Collectors.toMap(
                        u -> u, u -> new RemoteDevicePolicyManager(mAdmin, mContext, u))
                ));

        mDevicePolicyManagers.put(Process.myUserHandle(),
                new LocalDevicePolicyManager(mAdmin, mContext));
    }

    public void addProfileOwnerDpm(UserHandle target) {
        mDevicePolicyManagers.put(target,
                new RemoteDevicePolicyManager(mAdmin, mContext, target));
    }

    public void removeProfileOwnerDpm(UserHandle target) {
        mDevicePolicyManagers.remove(target);
    }

    private static boolean isPO(Context context, DevicePolicyManager dpm) {
        return dpm.isProfileOwnerApp(context.getPackageName());
    }

    private static boolean isDO(Context context, DevicePolicyManager dpm) {
        return dpm.isDeviceOwnerApp(context.getPackageName());
    }

    public DevicePolicyManagerInterface getDevicePolicyManager(UserHandle target) {
        return mDevicePolicyManagers.get(target);
    }

    public List<UserHandle> getAllBoundUsers() {
        return new ArrayList<>(mDevicePolicyManagers.keySet());
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

            addProfileOwnerDpm(targetUser);

            waitForBound((RemoteDevicePolicyManager) mDevicePolicyManagers.get(targetUser));

            try {
                cmd.run();
            } catch (Exception e) {
                Log.e(TAG, "Could not execute command " + command, e);
                return;
            }

            // Stop user after knowing command has executed without exception
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
