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

import java.util.concurrent.Callable;

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
     * See {@code android.app.IActivityManager.startUserInBackground}.
     *
     * @throws IllegalStateException if ActivityManager binder throws RemoteException
     */
    public boolean startUserInBackground(@UserIdInt int userId) {
        return runRemotely(() -> mAm.startUserInBackground(userId),
                "error while startUserInBackground %d", userId);
    }

    /**
     * See {@code android.app.IActivityManager.startUserInForegroundWithListener}.
     *
     * @throws IllegalStateException if ActivityManager binder throws RemoteException
     */
    public boolean startUserInForeground(@UserIdInt int userId) {
        return runRemotely(
                () -> mAm.startUserInForegroundWithListener(userId, /* listener= */ null),
                "error while startUserInForeground %d", userId);
    }

    /**
     * See {@code android.app.IActivityManager.stopUserWithDelayedLocking}.
     *
     * @throws IllegalStateException if ActivityManager binder throws RemoteException
     */
    public int stopUserWithDelayedLocking(@UserIdInt int userId, boolean force) {
        return runRemotely(
                () -> mAm.stopUserWithDelayedLocking(userId, force, /* callback= */ null),
                "error while stopUserWithDelayedLocking %d", userId);
    }

    /**
     * Check {@code android.app.IActivityManager.unlockUser}.
     *
     * @throws IllegalStateException if ActivityManager binder throws RemoteException
     */
    public boolean unlockUser(@UserIdInt int userId) {
        return runRemotely(() -> mAm.unlockUser(userId,
                /* token= */ null, /* secret= */ null, /* listener= */ null),
                "error while unlocking user %d", userId);
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
            throw logAndReThrow(e, "could not get stack info for user %d", userId);
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

    private <T> T runRemotely(Callable<T> callable, String format, Object...args) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw logAndReThrow(e, format, args);
        }
    }

    private RuntimeException logAndReThrow(Exception e, String format, Object...args) {
        String msg = String.format(format, args);
        Slog.e(TAG, msg, e);
        return new IllegalStateException(msg, e);
    }
}
