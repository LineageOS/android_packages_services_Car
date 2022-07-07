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

package com.android.car.testdpc.remotedpm;

import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.car.testdpc.DpcReceiver;
import com.android.internal.annotations.GuardedBy;

import com.google.errorprone.annotations.FormatMethod;

/**
 {@code DevicePolicyManagerInterface} implementation that executes the device policies in another
 user, using a custom binder service for IPC.

 <p>In a "real world" app, it would be used by the device owner user running on background to apply
 policies on other users, while in a "test concept" app it could be used by the UI (running in the
 current user) to apply policies in the device owner user or for managed profile owner users to send
 commands to the device owner user.

 */
public final class RemoteDevicePolicyManager implements DevicePolicyManagerInterface {
    private static final String TAG = RemoteDevicePolicyManager.class.getSimpleName();


    private final Context mContext;
    private final DevicePolicyManager mDpm;
    private final Object mLock = new Object();

    private boolean mBound;
    private UserHandle mTargetUserHandle;

    /**
     * Service used to call cross-user calls to other user device policy managers
     */
    @Nullable
    @GuardedBy("mLock")
    private IRemoteDevicePolicyManager mRemoteDpm;

    private ServiceConnection mServiceConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                    Log.i(TAG, "onServiceConnected(ComponentName: " + componentName.toShortString()
                            + ", Binder: " + iBinder + ")");
                    synchronized (mLock) {
                        mRemoteDpm = IRemoteDevicePolicyManager.Stub.asInterface(iBinder);
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName componentName) {
                    Log.i(TAG, "onServiceDisconnected(ComponentName: "
                            + componentName.toShortString() + ")");
                    synchronized (mLock) {
                        mRemoteDpm = null;
                    }
                }
            };

    public RemoteDevicePolicyManager(Context context, UserHandle target) {
        mContext = context;
        mDpm = context.getSystemService(DevicePolicyManager.class);

        mTargetUserHandle = target;

        mBound = bindRemoteDpm();

        if (!mBound) {
            Log.i(TAG, "Binding failed.");
            throw new IllegalStateException("Binding Failed");
        }

        Log.i(TAG, "Binding successful with " + mTargetUserHandle + "? " + mBound);
    }

    private boolean bindRemoteDpm() {
        try {
            boolean success =
                    mDpm.bindDeviceAdminServiceAsUser(
                            DpcReceiver.getComponentName(mContext),
                            new Intent(mContext, RemoteDevicePolicyManagerService.class),
                            mServiceConnection,
                            Context.BIND_AUTO_CREATE,
                            mTargetUserHandle);
            return success;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "Cannot bind to user mTargetUserHandle: " + mTargetUserHandle, e);
            return false;
        }
    }

    @Override
    public UserHandle getUser() {
        return mTargetUserHandle;
    }

    /**
     * Reboots the device
     *
     * <p>Only works when bound with device owner and the users are affiliated </p>
     */
    @Override
    public void reboot(ComponentName admin) {
        IRemoteDevicePolicyManager remoteDpm;
        remoteDpm = getBoundRemoteDpmLocked();
        run(() -> remoteDpm.reboot(admin), "reboot(%s)", admin);
    }

    @Override
    public void addUserRestriction(ComponentName admin, String key) {
        IRemoteDevicePolicyManager remoteDpm;
        remoteDpm = getBoundRemoteDpmLocked();
        run(() -> remoteDpm.addUserRestriction(admin, key),
                "reboot(%s, %s)", admin, key);
    }

    @FormatMethod
    private void run(RemoteRunnable cmd, String cmdFormat, Object... cmdArgs) {
        String cmdString = String.format(cmdFormat, cmdArgs);
        Log.i(TAG, "running " + cmdString + " on user " + mTargetUserHandle);
        try {
            cmd.run();
        } catch (RuntimeException | RemoteException e) {
            Log.e(TAG, "Failure running " + cmdString, e);
            throw new RuntimeException("Failed to run " + cmdString, e);
        }
    }

    private IRemoteDevicePolicyManager getBoundRemoteDpmLocked() {
        synchronized (this.mLock) {
            if (mRemoteDpm == null) {
                throw new IllegalStateException("RemoteDpm was not bound");
            }
            return mRemoteDpm;
        }
    }
}
