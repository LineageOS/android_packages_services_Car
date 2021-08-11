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

package android.car.builtin.app;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityManager;
import android.car.builtin.util.Slog;
import android.os.Bundle;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;

/**
 * Provide access to {@code android.app.IActivityManager} calls.
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class ActivityManagerHelper {

    private static final String TAG = "ActivityManagerHelper";

    private static Object sLock = new Object();

    private IActivityManager mAm;

    @GuardedBy("sLock")
    private static ActivityManagerHelper sActivityManagerBuiltIn;

    private ActivityManagerHelper() {
        mAm =  ActivityManager.getService();
    }

    /**
     * Get instance of {@code IActivityManagerBuiltIn}
     */
    @NonNull
    public static ActivityManagerHelper getInstance() {
        synchronized (sLock) {
            if (sActivityManagerBuiltIn == null) {
                sActivityManagerBuiltIn = new ActivityManagerHelper();
            }

            return sActivityManagerBuiltIn;
        }
    }

    /**
     * Check {@code android.app.IActivityManager.startUserInBackground}.
     *
     * @throws IllegalStateException if ActivityManager binder throws RemoteException
     */
    public boolean startUserInBackground(@UserIdInt int userId) {
        try {
            return mAm.startUserInBackground(userId);
        } catch (RemoteException e) {
            Slog.e(TAG, "error while startUserInBackground " + userId, e);
            throw new IllegalStateException(e);
        }
    }

    /**
     * Check {@code android.app.IActivityManager.stopUserWithDelayedLocking}.
     *
     * @throws IllegalStateException if ActivityManager binder throws RemoteException
     */
    public int stopUserWithDelayedLocking(@UserIdInt int userId, boolean force) {
        try {
            return mAm.stopUserWithDelayedLocking(userId, force, null);
        } catch (RemoteException e) {
            Slog.e(TAG, "error while stopUserWithDelayedLocking " + userId, e);
            throw new IllegalStateException(e);
        }
    }

    /**
     * Check {@code android.app.IActivityManager.unlockUser}.
     *
     * @throws IllegalStateException if ActivityManager binder throws RemoteException
     */
    public boolean unlockUser(@UserIdInt int userId) {
        try {
            return mAm.unlockUser(userId, null, null, null);
        } catch (RemoteException e) {
            Slog.e(TAG, "error while unlocking user " + userId, e);
            throw new IllegalStateException(e);
        }
    }

    /**
     * Stops all task for the user.
     *
     * @throws IllegalStateException if ActivityManager binder throws RemoteException
     */
    public void stopAllTasksForUser(@UserIdInt int userId) {
        try {
            for (RootTaskInfo info : mAm.getAllRootTaskInfos()) {
                for (int i = 0; i < info.childTaskIds.length; i++) {
                    if (info.childTaskUserIds[i] == userId) {
                        int taskId = info.childTaskIds[i];
                        if (!mAm.removeTask(taskId)) {
                            Slog.w(TAG, "could not remove task " + taskId);
                        }
                    }
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "could not get stack info", e);
            throw new IllegalStateException(e);
        }
    }

    /**
     *  Creates an ActivityOptions from the Bundle generated from ActivityOptions.
     *  TODO(b/195598146): Replace the usage of this with {@code ActivityOptions.fromBundle}
     *  as soon as it is exposed as ModuleApi.
     */
    @NonNull
    public static ActivityOptions createActivityOptions(@NonNull Bundle bOptions) {
        return new ActivityOptions(bOptions);
    }
}
