/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.car;

import static android.car.content.pm.CarPackageManager.BLOCKING_INTENT_EXTRA_DISPLAY_ID;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.app.ActivityOptions;
import android.app.TaskInfo;
import android.car.builtin.app.ActivityManagerHelper;
import android.car.builtin.app.ActivityManagerHelper.OnTaskStackChangeListener;
import android.car.builtin.app.ActivityManagerHelper.ProcessObserverCallback;
import android.car.builtin.app.TaskInfoHelper;
import android.car.builtin.content.ContextHelper;
import android.car.builtin.util.Slogf;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.Display;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service to monitor AMS for new Activity or Service launching.
 */
public class SystemActivityMonitoringService implements CarServiceBase {
    public interface ActivityLaunchListener {
        /**
         * Notify launch of activity.
         *
         * @param topTask Task information for what is currently launched.
         */
        void onActivityLaunch(TaskInfo topTask);
    }

    private static final int INVALID_STACK_ID = -1;
    private final Context mContext;
    private final ActivityManagerHelper mAm;
    private final ProcessObserverCallback mProcessObserver = new ProcessObserver();

    private final HandlerThread mMonitorHandlerThread = CarServiceUtils.getHandlerThread(
            getClass().getSimpleName());
    private final ActivityMonitorHandler mHandler = new ActivityMonitorHandler(
            mMonitorHandlerThread.getLooper(), this);

    private final Object mLock = new Object();

    /** K: display id, V: top task */
    @GuardedBy("mLock")
    private final SparseArray<TaskInfo> mTopTasks = new SparseArray<>();
    /** K: uid, V : list of pid */
    @GuardedBy("mLock")
    private final Map<Integer, Set<Integer>> mForegroundUidPids = new ArrayMap<>();
    @GuardedBy("mLock")
    private ActivityLaunchListener mActivityLaunchListener;

    public SystemActivityMonitoringService(Context context) {
        mContext = context;
        mAm = ActivityManagerHelper.getInstance();
    }

    @Override
    public void init() {
        // Monitoring both listeners are necessary as there are cases where one listener cannot
        // monitor activity change.
        mAm.registerProcessObserverCallback(mProcessObserver);
        mAm.registerTaskStackChangeListener(mTaskListener);
        updateTasks();
    }

    @Override
    public void release() {
        mAm.unregisterTaskStackChangeListener(mTaskListener);
        mAm.unregisterProcessObserverCallback(mProcessObserver);
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("*SystemActivityMonitoringService*");
        writer.println(" Top Tasks per display:");
        synchronized (mLock) {
            for (int i = 0; i < mTopTasks.size(); i++) {
                int displayId = mTopTasks.keyAt(i);
                TaskInfo info = mTopTasks.valueAt(i);
                if (info != null) {
                    writer.println("display id " + displayId + ": "
                            + TaskInfoHelper.toString(info));
                }
            }
            writer.println(" Foreground uid-pids:");
            for (Integer key : mForegroundUidPids.keySet()) {
                Set<Integer> pids = mForegroundUidPids.get(key);
                if (pids == null) {
                    continue;
                }
                writer.println("uid:" + key + ", pids:" + Arrays.toString(pids.toArray()));
            }
        }
    }

    /**
     * Block the current task: Launch new activity with given Intent and finish the current task.
     *
     * @param currentTask task to finish
     * @param newActivityIntent Intent for new Activity
     */
    public void blockActivity(TaskInfo currentTask, Intent newActivityIntent) {
        mHandler.requestBlockActivity(currentTask, newActivityIntent);
    }

    public List<TaskInfo> getTopTasks() {
        synchronized (mLock) {
            int size = mTopTasks.size();
            List<TaskInfo> tasks = new ArrayList<>(size);
            for (int i = 0; i < size; ++i) {
                TaskInfo topTask = mTopTasks.valueAt(i);
                if (topTask == null) {
                    Slogf.e(CarLog.TAG_AM, "Top tasks contains null. Full content is: "
                            + mTopTasks.toString());
                    continue;
                }
                tasks.add(topTask);
            }
            return tasks;
        }
    }

    public boolean isInForeground(int pid, int uid) {
        synchronized (mLock) {
            Set<Integer> pids = mForegroundUidPids.get(uid);
            if (pids == null) {
                return false;
            }
            if (pids.contains(pid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Attempts to restart a task.
     *
     * <p>Restarts a task by sending an empty intent with flag
     * {@link Intent#FLAG_ACTIVITY_CLEAR_TASK} to its root activity. If the task does not exist, do
     * nothing.
     *
     * @param taskId id of task to be restarted.
     */
    public void restartTask(int taskId) {
        Pair<Intent /* baseIntent */, Integer /* userId */> rootTask = mAm.findRootTask(taskId);

        if (rootTask == null) {
            Slogf.e(CarLog.TAG_AM, "Could not find root activity with task id " + taskId);
            return;
        }

        // Clear the task the root activity is running in and start it in a new task.
        // Effectively restart root activity.
        rootTask.first.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);

        if (Log.isLoggable(CarLog.TAG_AM, Log.INFO)) {
            Slogf.i(CarLog.TAG_AM, "restarting root activity with user id " + rootTask.second);
        }
        mContext.startActivityAsUser(rootTask.first, UserHandle.of(rootTask.second));
    }

    public void registerActivityLaunchListener(ActivityLaunchListener listener) {
        synchronized (mLock) {
            mActivityLaunchListener = listener;
        }
    }

    private void updateTasks() {
        SparseArray<TaskInfo> topTasks = mAm.getTopTasks();
        if (topTasks == null) return;

        ActivityLaunchListener listener;
        synchronized (mLock) {
            mTopTasks.clear();
            listener = mActivityLaunchListener;

            // Assuming displays remains the same.
            for (int i = 0; i < topTasks.size(); i++) {
                TaskInfo topTask = topTasks.valueAt(i);

                int displayId = topTasks.keyAt(i);
                mTopTasks.append(displayId, topTask);
            }
        }
        if (listener != null) {
            for (int i = 0; i < topTasks.size(); i++) {
                TaskInfo topTask = topTasks.valueAt(i);

                if (Log.isLoggable(CarLog.TAG_AM, Log.INFO)) {
                    Slogf.i(CarLog.TAG_AM, "Notifying about top task: "
                            + TaskInfoHelper.toString(topTask));
                }
                listener.onActivityLaunch(topTask);
            }
        }
    }

    public TaskInfo getTaskInfoForTopActivity(ComponentName activity) {
        SparseArray<TaskInfo> topTasks = mAm.getTopTasks();
        for (int i = 0, size = topTasks.size(); i < size; ++i) {
            TaskInfo info = topTasks.valueAt(i);
            if (activity.equals(info.topActivity)) {
                return info;
            }
        }
        return null;
    }

    private void handleForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) {
        synchronized (mLock) {
            if (foregroundActivities) {
                Set<Integer> pids = mForegroundUidPids.get(uid);
                if (pids == null) {
                    pids = new ArraySet<Integer>();
                    mForegroundUidPids.put(uid, pids);
                }
                pids.add(pid);
            } else {
                doHandlePidGoneLocked(pid, uid);
            }
        }
    }

    private void handleProcessDied(int pid, int uid) {
        synchronized (mLock) {
            doHandlePidGoneLocked(pid, uid);
        }
    }

    @GuardedBy("mLock")
    private void doHandlePidGoneLocked(int pid, int uid) {
        Set<Integer> pids = mForegroundUidPids.get(uid);
        if (pids != null) {
            pids.remove(pid);
            if (pids.isEmpty()) {
                mForegroundUidPids.remove(uid);
            }
        }
    }

    /**
     * block the current task with the provided new activity.
     */
    private void handleBlockActivity(TaskInfo currentTask, Intent newActivityIntent) {
        int displayId = newActivityIntent.getIntExtra(BLOCKING_INTENT_EXTRA_DISPLAY_ID,
                Display.DEFAULT_DISPLAY);
        if (Log.isLoggable(CarLog.TAG_AM, Log.DEBUG)) {
            Slogf.d(CarLog.TAG_AM, "Launching blocking activity on display: " + displayId);
        }

        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        ContextHelper.startActivityAsUser(mContext, newActivityIntent, options.toBundle(),
                UserHandle.of(TaskInfoHelper.getUserId(currentTask)));
        // Now make stack with new activity focused.
        findTaskAndGrantFocus(newActivityIntent.getComponent());
    }

    private void findTaskAndGrantFocus(ComponentName activity) {
        TaskInfo taskInfo = getTaskInfoForTopActivity(activity);
        if (taskInfo != null) {
            mAm.setFocusedRootTask(taskInfo.taskId);
            return;
        }
        Slogf.i(CarLog.TAG_AM, "cannot give focus, cannot find Activity:" + activity);
    }

    private class ProcessObserver implements ProcessObserverCallback {
        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) {
            if (Log.isLoggable(CarLog.TAG_AM, Log.INFO)) {
                Slogf.i(CarLog.TAG_AM,
                        String.format("onForegroundActivitiesChanged uid %d pid %d fg %b",
                                uid, pid, foregroundActivities));
            }
            mHandler.requestForegroundActivitiesChanged(pid, uid, foregroundActivities);
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            mHandler.requestProcessDied(pid, uid);
        }
    }

    private final OnTaskStackChangeListener mTaskListener = new OnTaskStackChangeListener() {
        @Override
        public void onTaskStackChanged() {
            if (Log.isLoggable(CarLog.TAG_AM, Log.INFO)) {
                Slogf.i(CarLog.TAG_AM, "onTaskStackChanged");
            }
            mHandler.requestUpdatingTask();
        }
    };

    private static final class ActivityMonitorHandler extends Handler {
        private static final String TAG = ActivityMonitorHandler.class.getSimpleName();

        private static final int MSG_UPDATE_TASKS = 0;
        private static final int MSG_FOREGROUND_ACTIVITIES_CHANGED = 1;
        private static final int MSG_PROCESS_DIED = 2;
        private static final int MSG_BLOCK_ACTIVITY = 3;

        private final WeakReference<SystemActivityMonitoringService> mService;

        private ActivityMonitorHandler(Looper looper, SystemActivityMonitoringService service) {
            super(looper);
            mService = new WeakReference<SystemActivityMonitoringService>(service);
        }

        private void requestUpdatingTask() {
            Message msg = obtainMessage(MSG_UPDATE_TASKS);
            sendMessage(msg);
        }

        private void requestForegroundActivitiesChanged(int pid, int uid,
                boolean foregroundActivities) {
            Message msg = obtainMessage(MSG_FOREGROUND_ACTIVITIES_CHANGED, pid, uid,
                    Boolean.valueOf(foregroundActivities));
            sendMessage(msg);
        }

        private void requestProcessDied(int pid, int uid) {
            Message msg = obtainMessage(MSG_PROCESS_DIED, pid, uid);
            sendMessage(msg);
        }

        private void requestBlockActivity(TaskInfo currentTask,
                Intent newActivityIntent) {
            Message msg = obtainMessage(MSG_BLOCK_ACTIVITY,
                    new Pair<TaskInfo, Intent>(currentTask, newActivityIntent));
            sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            SystemActivityMonitoringService service = mService.get();
            if (service == null) {
                Slogf.i(TAG, "handleMessage null service");
                return;
            }
            switch (msg.what) {
                case MSG_UPDATE_TASKS:
                    service.updateTasks();
                    break;
                case MSG_FOREGROUND_ACTIVITIES_CHANGED:
                    service.handleForegroundActivitiesChanged(msg.arg1, msg.arg2,
                            (Boolean) msg.obj);
                    service.updateTasks();
                    break;
                case MSG_PROCESS_DIED:
                    service.handleProcessDied(msg.arg1, msg.arg2);
                    break;
                case MSG_BLOCK_ACTIVITY:
                    Pair<TaskInfo, Intent> pair = (Pair<TaskInfo, Intent>) msg.obj;
                    service.handleBlockActivity(pair.first, pair.second);
                    break;
            }
        }
    }
}
