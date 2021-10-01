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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityManager;
import android.app.IProcessObserver;
import android.app.TaskStackListener;
import android.car.builtin.util.Slogf;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Provide access to {@code android.app.IActivityManager} calls.
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class ActivityManagerHelper {

    /** Invalid task ID. */
    public static final int INVALID_TASK_ID = ActivityTaskManager.INVALID_TASK_ID;

    private static final String TAG = "CAR.AM";  // CarLog.TAG_AM

    private static Object sLock = new Object();

    private IActivityManager mAm;

    @GuardedBy("sLock")
    private static ActivityManagerHelper sActivityManagerBuiltIn;

    private ActivityManagerHelper() {
        mAm = ActivityManager.getService();
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
                            Slogf.w(TAG, "could not remove task " + taskId);
                        }
                    }
                }
            }
        } catch (RemoteException e) {
            throw logAndReThrow(e, "could not get stack info for user %d", userId);
        }
    }

    /**
     * Creates an ActivityOptions from the Bundle generated from ActivityOptions.
     * TODO(b/195598146): Replace the usage of this with {@code ActivityOptions.fromBundle}
     * as soon as it is exposed as ModuleApi.
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
        Slogf.e(TAG, msg, e);
        return new IllegalStateException(msg, e);
    }

    /**
     * Container to hold info on top task in an Activity stack
     */
    @Deprecated  // Will be replaced with TaskOrganizer based implementation
    public static class TopTaskInfoContainer {
        @Nullable public final ComponentName topActivity;
        public final int taskId;
        public final int rootTaskId;
        public final int userId;
        public final int rootTaskUserId;
        public final int displayId;
        public final int position;
        @Nullable public final ComponentName baseActivity;
        public final int[] childTaskIds;
        public final String[] childTaskNames;

        public TopTaskInfoContainer(ComponentName topActivity, int taskId, int userId,
                int rootTaskId, int rootTaskUserId, int displayId, int position,
                ComponentName baseActivity, int[] childTaskIds, String[] childTaskNames) {
            this.topActivity = topActivity;
            this.taskId = taskId;
            this.userId = userId;
            this.rootTaskId = rootTaskId;
            this.rootTaskUserId = rootTaskUserId;
            this.displayId = displayId;
            this.position = position;
            this.baseActivity = baseActivity;
            this.childTaskIds = childTaskIds;
            this.childTaskNames = childTaskNames;
        }

        public boolean isMatching(TopTaskInfoContainer taskInfo) {
            return taskInfo != null
                    && Objects.equals(this.topActivity, taskInfo.topActivity)
                    && this.taskId == taskInfo.taskId
                    && this.displayId == taskInfo.displayId
                    && this.position == taskInfo.position
                    && this.userId == taskInfo.userId;
        }

        @Override
        public String toString() {
            return String.format(
                    "TaskInfoContainer [topActivity=%s, taskId=%d, userId=%d, displayId=%d, "
                            + "position=%d]", topActivity, taskId, userId, displayId, position);
        }
    }

    private final Object mLock = new Object();

    @Deprecated  // Will be replaced with TaskOrganizer based implementation
    public interface OnTaskStackChangeListener {
        default void onTaskStackChanged() {}
    }

    @GuardedBy("mLock")
    private ArrayList<OnTaskStackChangeListener> mTaskStackChangeListeners = new ArrayList<>();

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() throws RemoteException {
            List<OnTaskStackChangeListener> listeners;
            synchronized (mLock) {
                listeners = mTaskStackChangeListeners;
            }
            for (int i = 0, size = listeners.size(); i < size; ++i) {
                listeners.get(i).onTaskStackChanged();
            }
        }
    };

    /**
     * Registers a listener to be called when the task stack is changed.
     * @param listener a listener to register
     */
    @Deprecated  // Will be replaced with TaskOrganizer based implementation
    public void registerTaskStackChangeListener(OnTaskStackChangeListener listener) {
        synchronized (mLock) {
            if (mTaskStackChangeListeners.isEmpty()) {
                try {
                    mAm.registerTaskStackListener(mTaskStackListener);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "Failed to register listener", e);
                    throw new RuntimeException(e);
                }
            }
            // Make a copy of listeners not to affect on-going listeners.
            mTaskStackChangeListeners =
                    (ArrayList<OnTaskStackChangeListener>) mTaskStackChangeListeners.clone();
            mTaskStackChangeListeners.add(listener);
        }
    }

    /**
     * Unregisters a listener.
     * @param listener a listener to unregister
     */
    @Deprecated  // Will be replaced with TaskOrganizer based implementation
    public void unregisterTaskStackChangeListener(OnTaskStackChangeListener listener) {
        synchronized (mLock) {
            // Make a copy of listeners not to affect on-going listeners.
            mTaskStackChangeListeners =
                    (ArrayList<OnTaskStackChangeListener>) mTaskStackChangeListeners.clone();
            mTaskStackChangeListeners.remove(listener);
            if (mTaskStackChangeListeners.isEmpty()) {
                try {
                    mAm.unregisterTaskStackListener(mTaskStackListener);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "Failed to unregister listener", e);
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * Gets the top visible tasks of each display.
     * @return A {@link SparseArray} that maps displayId to {@link TopTaskInfoContainer}
     */
    @Deprecated  // Will be replaced with TaskOrganizer based implementation
    @Nullable
    public SparseArray<TopTaskInfoContainer> getTopTasks() {
        List<ActivityTaskManager.RootTaskInfo> infos = getAllRootTaskInfos();
        if (infos == null) return null;

        SparseArray<TopTaskInfoContainer> topTasks = new SparseArray<>();
        for (int i = 0, size = infos.size(); i < size; ++i) {
            ActivityTaskManager.RootTaskInfo info = infos.get(i);
            int displayId = info.displayId;
            if (info.childTaskNames.length == 0
                    || !info.visible) { // empty stack or not shown
                continue;
            }
            TopTaskInfoContainer currentTopTaskInfo = topTasks.get(displayId);

            if (currentTopTaskInfo == null || info.position > currentTopTaskInfo.position) {
                TopTaskInfoContainer newTopTaskInfo = new TopTaskInfoContainer(
                        info.topActivity, info.childTaskIds[info.childTaskIds.length - 1],
                        info.childTaskUserIds[info.childTaskUserIds.length - 1],
                        info.taskId, info.userId, info.displayId, info.position, info.baseActivity,
                        info.childTaskIds, info.childTaskNames);
                topTasks.put(displayId, newTopTaskInfo);
                Slogf.i(TAG, "Updating top task to: " + newTopTaskInfo);
            }
        }
        return topTasks;
    }

    @Nullable
    private List<RootTaskInfo> getAllRootTaskInfos() {
        try {
            return mAm.getAllRootTaskInfos();
        } catch (RemoteException e) {
            Slogf.e(TAG, "Failed to getAllRootTaskInfos", e);
            return null;
        }
    }

    /**
     * Finds the root task of the given taskId.
     * @return a {@link Pair} of {@link Intent} and {@code userId} if the root task exists, or
     * {@code null}.
     */
    @Deprecated  // Will be replaced with TaskOrganizer based implementation
    public Pair<Intent, Integer> findRootTask(int taskId) {
        List<ActivityTaskManager.RootTaskInfo> infos = getAllRootTaskInfos();
        for (int i = 0, size = infos.size(); i < size; ++i) {
            ActivityTaskManager.RootTaskInfo info = infos.get(i);
            for (int j = 0; j < info.childTaskIds.length; ++j) {
                if (info.childTaskIds[j] == taskId) {
                    return new Pair<>(info.baseIntent, info.userId);
                }
            }
        }
        return null;
    }

    /**
     * Makes the root task of the given taskId focused.
     */
    public void setFocusedRootTask(int taskId) {
        try {
            mAm.setFocusedRootTask(taskId);
        } catch (RemoteException e) {
            Slogf.e(TAG, "Failed to setFocusedRootTask", e);
        }
    }

    /**
     * Removes the given task.
     */
    public boolean removeTask(int taskId) {
        try {
            return mAm.removeTask(taskId);
        } catch (RemoteException e) {
            Slogf.e(TAG, "Failed to removeTask", e);
        }
        return false;
    }

    public interface ProcessObserverCallback {
        default void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) {
        }
        default void onProcessDied(int pid, int uid) {}
    }

    @GuardedBy("mLock")
    private ArrayList<ProcessObserverCallback> mProcessObserverCallbacks = new ArrayList<>();

    private final IProcessObserver.Stub mProcessObserver = new IProcessObserver.Stub() {
        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities)
                throws RemoteException {
            List<ProcessObserverCallback> callbacks;
            synchronized (mLock) {
                callbacks = mProcessObserverCallbacks;
            }
            for (int i = 0, size = callbacks.size(); i < size; ++i) {
                callbacks.get(i).onForegroundActivitiesChanged(pid, uid, foregroundActivities);
            }
        }

        @Override
        public void onForegroundServicesChanged(int pid, int uid, int fgServiceTypes)
                throws RemoteException {
            // Not used
        }

        @Override
        public void onProcessDied(int pid, int uid) throws RemoteException {
            List<ProcessObserverCallback> callbacks;
            synchronized (mLock) {
                callbacks = mProcessObserverCallbacks;
            }
            for (int i = 0, size = callbacks.size(); i < size; ++i) {
                callbacks.get(i).onProcessDied(pid, uid);
            }
        }
    };

    /**
     * Registers a callback to be invoked when the process states are changed.
     * @param callback a callback to register
     */
    public void registerProcessObserverCallback(ProcessObserverCallback callback) {
        synchronized (mLock) {
            if (mProcessObserverCallbacks.isEmpty()) {
                try {
                    mAm.registerProcessObserver(mProcessObserver);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "Failed to register ProcessObserver", e);
                    throw new RuntimeException(e);
                }
            }
            // Make a copy of callbacks not to affect on-going callbacks.
            mProcessObserverCallbacks =
                    (ArrayList<ProcessObserverCallback>) mProcessObserverCallbacks.clone();
            mProcessObserverCallbacks.add(callback);
        }
    }

    /**
     * Unregisters the given callback.
     * @param callback a callback to unregister
     */
    public void unregisterProcessObserverCallback(ProcessObserverCallback callback) {
        synchronized (mLock) {
            // Make a copy of callbacks not to affect on-going callbacks.
            mProcessObserverCallbacks =
                    (ArrayList<ProcessObserverCallback>) mProcessObserverCallbacks.clone();
            mProcessObserverCallbacks.remove(callback);
            if (mProcessObserverCallbacks.isEmpty()) {
                try {
                    mAm.unregisterProcessObserver(mProcessObserver);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "Failed to unregister listener", e);
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * Same as {@link ActivityManager#checkComponentPermission(String, int, int, boolean).
     */
    public static int checkComponentPermission(@NonNull String permission, int uid, int owningUid,
            boolean exported) {
        return ActivityManager.checkComponentPermission(permission, uid, owningUid, exported);
    }
}
