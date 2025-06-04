/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.automotive;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.car.Car;
import android.car.app.CarActivityManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;

import com.android.server.utils.Slogf;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.fullscreen.FullscreenTaskListener;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import javax.inject.Inject;

// TODO(b/395767437): Add tasks related to fullscreen and multi window mode outside the root task

/**
 * Repository for tasks.
 *
 * <p>This class is responsible for storing and retrieving tasks. It also provides methods for
 * updating the repository when tasks are created, destroyed, or changed. This class also updates
 * CarService when tasks are created, destroyed or changed.
 */
@WMSingleton
public class AutoTaskRepository {

    private static final String TAG = "AutoTaskRepository";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private final SparseArray<RootTaskStackInfo> mRootTaskStacks = new SparseArray<>();

    /**
     * Map of task id to surface control
     */
    private final SparseArray<SurfaceControl> mSurfaceControlMap = new SparseArray<>();

    private final SparseArray<Pair<ActivityManager.RunningTaskInfo, SurfaceControl>>
            mPendingTasks = new SparseArray<>();

    /**
     * Map of task id to task info for tasks that are not part of any root task.
     */
    private final LinkedHashMap<Integer,
            ActivityManager.RunningTaskInfo> mTaskStackWithoutRootTask = new LinkedHashMap<>();
    private final ArraySet<AutoAppTaskListener> mAutoTaskListeners = new ArraySet<>();

    private final Context mContext;
    private final ShellTaskOrganizer mShellTaskOrganizer;

    // TODO(b/401349206): Move the task reporting outside of the Task Repository
    private CarActivityManager mCarActivityManager;

    private boolean mIsCarReady = false;

    private final SparseArray<RootTaskStack> mPendingRootTasks = new SparseArray<>();

    private final ShellTaskOrganizer.TaskListener mMultiWindowTaskListener =
            new ShellTaskOrganizer.TaskListener() {
                @Override
                public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo,
                        SurfaceControl leash) {
                    AutoTaskRepository.this.onTaskAppeared(taskInfo, leash);
                }

                @Override
                public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
                    AutoTaskRepository.this.onTaskChanged(taskInfo);
                }

                @Override
                public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
                    AutoTaskRepository.this.onTaskVanished(taskInfo);
                }
            };

    @Inject
    AutoTaskRepository(Context context, ShellTaskOrganizer shellTaskOrganizer) {
        mShellTaskOrganizer = shellTaskOrganizer;
        mShellTaskOrganizer.addListenerForType(mMultiWindowTaskListener,
                ShellTaskOrganizer.TASK_LISTENER_TYPE_MULTI_WINDOW);
        mContext = context;
        // register task monitor only for User 0. It is possible that AutoTaskRepository is created
        // for other users if system UI runs any process on any other users.
        if (UserHandle.getCallingUserId() == UserHandle.USER_SYSTEM) {
            Car.createCar(context, /* handler= */ null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                    (car, ready) -> {
                        if (mIsCarReady) {
                            return;
                        }
                        mIsCarReady = ready;
                        if (ready) {
                            mCarActivityManager = (CarActivityManager) car.getCarManager(
                                    Car.CAR_ACTIVITY_SERVICE);
                            onCarServiceConnectedLocked();
                        }
                    });
        }
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "TaskRepository:");
        for (int i = 0; i < mRootTaskStacks.size(); i++) {
            int rootTaskStackId = mRootTaskStacks.keyAt(i);
            RootTaskStackInfo info = mRootTaskStacks.valueAt(i);
            pw.println(prefix + "  Root task Id: " + rootTaskStackId);
            for (ActivityManager.RunningTaskInfo task : info.getTaskStack()) {
                pw.println(prefix + "     task Id: " + task.taskId + " visible: " + task.isVisible
                        + " name: " + (task.topActivity != null ? task.topActivity.getClassName()
                        : ""));
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void onCarServiceConnectedLocked() {
        Slogf.i(TAG, "onCarServiceConnectedLocked. mPendingTasks count %d. mPendingTasks count %d",
                mPendingTasks.size(), mPendingRootTasks.size());

        mCarActivityManager.registerTaskMonitor();


        // TODO(b/392757141): Is this required? Do we need to query existing tasks and report
        // them on start?
        DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        Display[] displays = displayManager.getDisplays();
        for (Display display : displays) {
            List<ActivityManager.RunningTaskInfo> taskInfos = mShellTaskOrganizer.getRunningTasks(
                    display.getDisplayId());
            for (ActivityManager.RunningTaskInfo taskInfo : taskInfos) {
                if (!mPendingTasks.contains(taskInfo.taskId)) {
                    mPendingTasks.put(taskInfo.taskId, new Pair<>(taskInfo, null));
                }
            }
        }


        for (int i = 0; i < mPendingTasks.size(); i++) {
            mCarActivityManager.onTaskAppeared(mPendingTasks.valueAt(i).first,
                    mPendingTasks.valueAt(i).second);
        }

        for (int i = 0; i < mPendingRootTasks.size(); i++) {
            mCarActivityManager.onRootTaskAppeared(mPendingRootTasks.valueAt(i).getName(),
                    mPendingRootTasks.valueAt(i).getRootTaskInfo(),
                    mPendingRootTasks.valueAt(i).getRootTaskInfo().token.asBinder());
        }

        // TODO(b/400851144): handle Car Service crash if required
        mPendingTasks.clear();
        mPendingRootTasks.clear();
    }

    SurfaceControl getSurfaceControl(ActivityManager.RunningTaskInfo taskInfo) {
        return mSurfaceControlMap.get(taskInfo.taskId);
    }

    SurfaceControl getSurfaceControl(int taskId) {
        return mSurfaceControlMap.get(taskId);
    }

    List<ActivityManager.RunningTaskInfo> getTaskStack(RootTaskStack rootTaskStack) {
        if (!mRootTaskStacks.contains(rootTaskStack.getId())) return null;
        return mRootTaskStacks.get(rootTaskStack.getId()).getTaskStack();
    }

    // TODO(b/401349206): Refactor it. Save a mapping of task id and taskInfo and use that.
    ActivityManager.RunningTaskInfo getTaskInfo(int taskId) {
        if (mTaskStackWithoutRootTask.get(taskId) != null) {
            return mTaskStackWithoutRootTask.get(taskId);
        }

        for (int i = 0; i < mRootTaskStacks.size(); i++) {
            RootTaskStackInfo rootTaskStackInfo = mRootTaskStacks.valueAt(i);
            if (rootTaskStackInfo.getTaskInfo(taskId) != null) {
                return rootTaskStackInfo.getTaskInfo(taskId);
            }
        }

        return null;
    }

    /**
     * Returns RootTaskStack for root task stack Id.
     */
    RootTaskStack getRootTaskStack(int rootTaskStackId) {
        if (mRootTaskStacks.contains(rootTaskStackId)) {
            return mRootTaskStacks.get(rootTaskStackId).mRootTaskStack;
        }

        return null;
    }

    List<ActivityManager.RunningTaskInfo> getTaskStackWithoutRootTask() {
        return new ArrayList<>(mTaskStackWithoutRootTask.values());
    }

    void addOrUpdateTask(RootTaskStack rootTaskStack, ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl surfaceControl) {
        RootTaskStackInfo rootTaskStackInfo = mRootTaskStacks.get(rootTaskStack.getId());
        if (rootTaskStackInfo == null) {
            // Should not happen
            Slogf.e(TAG,
                    "addOrUpdateTask called for task %s, while RootTaskStack %s is not "
                            + "populated.", taskInfo, rootTaskStack);
            rootTaskStackInfo = new RootTaskStackInfo(rootTaskStack);
            mRootTaskStacks.put(rootTaskStack.getId(), rootTaskStackInfo);
        }

        rootTaskStackInfo.removeTask(taskInfo.taskId);
        rootTaskStackInfo.addOrUpdateTask(taskInfo.taskId, taskInfo);
        mSurfaceControlMap.append(taskInfo.taskId, surfaceControl);
    }

    void removeTask(RootTaskStack rootTaskStack, ActivityManager.RunningTaskInfo taskInfo) {
        RootTaskStackInfo rootTaskStackInfo = mRootTaskStacks.get(rootTaskStack.getId());
        if (rootTaskStackInfo == null) {
            // Should not happen
            Slogf.e(TAG,
                    "removeTask called for task %s, while RootTaskStack %s is not "
                            + "populated.", taskInfo, rootTaskStack);
            mSurfaceControlMap.remove(taskInfo.taskId);
            return;
        }

        rootTaskStackInfo.removeTask(taskInfo.taskId);
        mSurfaceControlMap.remove(taskInfo.taskId);
    }

    /**
     * Updates task repository when new root task is created
     *
     * @param rootTaskStack new root task stack
     */
    void onRootTaskStackCreated(RootTaskStack rootTaskStack) {
        if (DBG) {
            Slogf.d(TAG, "onRootTaskStackCreated. RootTask Id %d. RootTask Name %s",
                    rootTaskStack.getId(), rootTaskStack.getName());
        }
        mRootTaskStacks.put(rootTaskStack.getId(), new RootTaskStackInfo(rootTaskStack));
        if (mIsCarReady) {
            mCarActivityManager.onRootTaskAppeared(rootTaskStack.getName(),
                    rootTaskStack.getRootTaskInfo(),
                    rootTaskStack.getRootTaskInfo().token.asBinder());
        } else {
            mPendingRootTasks.put(rootTaskStack.getRootTaskInfo().taskId, rootTaskStack);
        }
        mSurfaceControlMap.append(rootTaskStack.getRootTaskInfo().taskId,
                rootTaskStack.getLeash());
    }

    /**
     * Updates task repository when a root task is destroyed
     *
     * @param rootTaskStack the root task destroyed
     */
    void onRootTaskStackDestroyed(RootTaskStack rootTaskStack) {
        if (DBG) {
            Slogf.d(TAG, "onRootTaskStackDestroyed. RootTask Id %d. RootTask Name %s",
                    rootTaskStack.getId(), rootTaskStack.getName());
        }
        mRootTaskStacks.remove(rootTaskStack.getId());

        if (mIsCarReady) {
            mCarActivityManager.onRootTaskVanished(rootTaskStack.getRootTaskInfo().taskId);
        } else {
            mPendingRootTasks.remove(rootTaskStack.getRootTaskInfo().taskId);
        }
        mSurfaceControlMap.remove(rootTaskStack.getRootTaskInfo().taskId);
    }

    /**
     * Updates the task repository when a new task appeared  in a root task
     *
     * @param rootTaskStack where task appeared.
     * @param task          the task appeared.
     * @param leash         the leash of the task appeared.
     */
    @SuppressLint("MissingPermission")
    void onTaskAppeared(RootTaskStack rootTaskStack, ActivityManager.RunningTaskInfo task,
            SurfaceControl leash) {
        if (DBG) {
            Slogf.d(TAG, "onTaskAppeared. RootTask Id %d. TaskId %d. Name %s",
                    rootTaskStack.getId(), task.getTaskId(), rootTaskStack.getName());
        }
        addOrUpdateTask(rootTaskStack, task, leash);

        if (mIsCarReady) {
            mCarActivityManager.onTaskAppeared(task, leash);
        } else {
            mPendingTasks.put(task.taskId, new Pair<>(task, leash));
        }

        for (AutoAppTaskListener listener: mAutoTaskListeners) {
            listener.onTaskAppeared(task);
        }
    }

    /**
     * Updates the task repository when a task info change
     *
     * @param rootTaskStack where task changed.
     * @param task          the task changed.
     */
    @SuppressLint("MissingPermission")
    void onTaskChanged(RootTaskStack rootTaskStack, ActivityManager.RunningTaskInfo task) {
        if (DBG) {
            Slogf.d(TAG, "onTaskChanged. RootTask Id %d. TaskId %d. Name %s",
                    rootTaskStack.getId(), task.getTaskId(), rootTaskStack.getName());
        }
        addOrUpdateTask(rootTaskStack, task, mSurfaceControlMap.get(task.taskId));

        if (mIsCarReady) {
            mCarActivityManager.onTaskInfoChanged(task);
        } else {
            mPendingTasks.put(task.taskId,
                    new Pair<>(task, mSurfaceControlMap.get(task.taskId)));
        }

        for (AutoAppTaskListener listener: mAutoTaskListeners) {
            listener.onTaskChanged(task);
        }
    }

    /**
     * Updates the task repository when a task is vanished
     *
     * @param rootTaskStack where task vanished.
     * @param task          the task vanished.
     */
    @SuppressLint("MissingPermission")
    void onTaskVanished(RootTaskStack rootTaskStack, ActivityManager.RunningTaskInfo task) {
        if (DBG) {
            Slogf.d(TAG, "onTaskDestroyed. RootTask Id %d. TaskId %d. Name %s",
                    rootTaskStack.getId(), task.getTaskId(), rootTaskStack.getName());
        }
        removeTask(rootTaskStack, task);
        if (mIsCarReady) {
            mCarActivityManager.onTaskVanished(task);
        } else {
            mPendingTasks.remove(task.taskId);
        }

        for (AutoAppTaskListener listener: mAutoTaskListeners) {
            listener.onTaskVanished(task);
        }
    }

    /**
     * Updates the task repository when a new task appeared.
     *
     * @param task          the task that appeared.
     * @param leash         the leash of the task that appeared.
     */
    @SuppressLint("MissingPermission")
    public void onTaskAppeared(ActivityManager.RunningTaskInfo task, SurfaceControl leash) {
        if (DBG) {
            Slogf.d(TAG, "onTaskAppeared. TaskId %d.", task.getTaskId());
        }
        mTaskStackWithoutRootTask.put(task.taskId, task);
        mSurfaceControlMap.put(task.taskId, leash);

        if (mIsCarReady) {
            mCarActivityManager.onTaskAppeared(task, leash);
        } else {
            mPendingTasks.put(task.taskId, new Pair<>(task, leash));
        }

        for (AutoAppTaskListener listener: mAutoTaskListeners) {
            listener.onTaskAppeared(task);
        }
    }

    /**
     * Updates the task repository when a task info change
     *
     * @param task          the task that changed.
     */
    @SuppressLint("MissingPermission")
    public void onTaskChanged(ActivityManager.RunningTaskInfo task) {
        if (DBG) {
            Slogf.d(TAG, "onTaskChanged. TaskId %d.", task.getTaskId());
        }

        mTaskStackWithoutRootTask.remove(task.taskId);
        mTaskStackWithoutRootTask.put(task.taskId, task);

        if (mIsCarReady) {
            mCarActivityManager.onTaskInfoChanged(task);
        } else {
            mPendingTasks.put(task.taskId,
                    new Pair<>(task, mSurfaceControlMap.get(task.taskId)));
        }

        for (AutoAppTaskListener listener: mAutoTaskListeners) {
            listener.onTaskChanged(task);
        }
    }

    /**
     * Updates the task repository when a task is vanished
     *
     * @param task          the task that vanished.
     */
    @SuppressLint("MissingPermission")
    public void onTaskVanished(ActivityManager.RunningTaskInfo task) {
        if (DBG) {
            Slogf.d(TAG, "onTaskDestroyed. TaskId %d.", task.getTaskId());
        }

        mTaskStackWithoutRootTask.remove(task.taskId);
        mSurfaceControlMap.remove(task.taskId);

        if (mIsCarReady) {
            mCarActivityManager.onTaskVanished(task);
        } else {
            mPendingTasks.remove(task.taskId);
        }

        for (AutoAppTaskListener listener: mAutoTaskListeners) {
            listener.onTaskVanished(task);
        }
    }

    // TODO(b/401349206): Expose this call once listener is moved to car-wm-shell
    void addAppTaskListener(AutoAppTaskListener autoAppTaskListener) {
        mAutoTaskListeners.add(autoAppTaskListener);
    }

    // TODO(b/401349206): Expose this call once listener is moved to car-wm-shell
    void removeAppTaskListener(AutoAppTaskListener autoAppTaskListener) {
        mAutoTaskListeners.remove(autoAppTaskListener);
    }

    // TODO(b/401349206): Expose this call once listener is moved to car-wm-shell
    interface AutoAppTaskListener {
        void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo);
        void onTaskChanged(ActivityManager.RunningTaskInfo taskInfo);
        void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo);
    }

    public static class AutoFullscreenTaskListener extends FullscreenTaskListener {

        private final AutoTaskRepository mAutoTaskRepository;

        public AutoFullscreenTaskListener(SyncTransactionQueue syncQueue,
                AutoTaskRepository autoTaskRepository) {
            super(syncQueue);
            mAutoTaskRepository = autoTaskRepository;
        }

        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
            mAutoTaskRepository.onTaskAppeared(taskInfo, leash);
        }

        @Override
        public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
            mAutoTaskRepository.onTaskChanged(taskInfo);
        }

        @Override
        public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
            mAutoTaskRepository.onTaskVanished(taskInfo);
        }
    }

    /**
     * Data class to hold the task stack for each root task.
     */
    static class RootTaskStackInfo {
        final RootTaskStack mRootTaskStack;
        // Using LinkedHashMap to keep the order in which task are inserted.
        private final LinkedHashMap<Integer,
                ActivityManager.RunningTaskInfo> mTaskStack = new LinkedHashMap<>();

        RootTaskStackInfo(RootTaskStack rootTaskStack) {
            mRootTaskStack = rootTaskStack;
        }

        void removeTask(int taskId) {
            mTaskStack.remove(taskId);
        }

        void addOrUpdateTask(int taskId, ActivityManager.RunningTaskInfo taskInfo) {
            mTaskStack.remove(taskId);
            mTaskStack.put(taskId, taskInfo);
        }

        List<ActivityManager.RunningTaskInfo> getTaskStack() {
            return new ArrayList<>(mTaskStack.values());
        }

        ActivityManager.RunningTaskInfo getTaskInfo(int taskId) {
            if (mRootTaskStack.getRootTaskInfo().taskId == taskId) {
                return mRootTaskStack.getRootTaskInfo();
            }
            return mTaskStack.get(taskId);
        }
    }
}
