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

/**
 * Provides an application interface to make cross-user calls to device policy managers on other,
 * bound users.
 * The class implements DevicePolicyManagerInterface so that it can be interchanged with
 * LocalDevicePolicyManager that is a mirror class which makes local calls to the same methods.
 */
public final class RemoteDevicePolicyManager implements DevicePolicyManagerInterface {
    private static final String TAG = RemoteDevicePolicyManager.class.getSimpleName();


    private final Context mContext;
    private final DevicePolicyManager mDpm;
    private boolean mBound;
    private UserHandle mTargetUserHandle;
    private final Object mLock = new Object();

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
                    Log.i(TAG, "onServiceConnected(ComponentName: " + componentName
                            + ", Binder: " + iBinder + ")");
                    synchronized (mLock) {
                        mRemoteDpm = IRemoteDevicePolicyManager.Stub.asInterface(iBinder);
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName componentName) {
                    Log.i(TAG, "onServiceDisconnected(ComponentName: " + componentName + ")");
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
            Log.e(TAG, "Cannot bind to user " + mTargetUserHandle, e);
            return false;
        }
    }

    /**
     * Reboots the device
     *
     * <p> Only works when bound with device owner and the users are affiliated </p>
     */
    public void reboot(ComponentName admin) {
        synchronized (mLock) {
            if (mRemoteDpm == null) {
                Log.i(TAG, "Unable to call reboot() as RemoteDpm object is null");
            }

            try {
                mRemoteDpm.reboot(admin);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception when calling reboot", e);
            }
        }
    }
}
