/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.car.distantdisplay.common;

import static android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.UserHandle;
import android.util.Log;
import android.view.LayoutInflater;

import androidx.annotation.Nullable;

import com.android.car.ui.utils.CarUxRestrictionsUtil;
import com.android.systemui.R;
import com.android.systemui.car.distantdisplay.activity.MoveTaskReceiver;
import com.android.systemui.car.distantdisplay.activity.RootTaskViewWallpaperActivity;
import com.android.systemui.car.distantdisplay.util.AppCategoryDetector;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

/**
 * TaskView Controller that manages the implementation details for task views on a distant display.
 * <p>
 * This is also a common class used between two different technical implementation where in one
 * TaskViews are hosted in a window created by systemUI Vs in another where TaskView are created
 * via an activity.
 */
public class TaskViewController {
    public static final String TAG = TaskViewController.class.getSimpleName();
    private static final boolean DEBUG = Build.IS_ENG || Build.IS_USERDEBUG;
    private static final int DEFAULT_DISPLAY_ID = 0;
    private static final int INVALID_TASK_ID = -1;

    private Context mContext;
    private DisplayManager mDisplayManager;
    private int mDistantDisplayId;
    private int mBaseIntentForTopTaskId;
    private int mDistantDisplayRootWallpaperTaskId = INVALID_TASK_ID;
    private Intent mBaseIntentForTopTask;
    private MediaSessionManager mMediaSessionManager;
    private OrderedHashSet<Integer> mDistantDisplayForegroundTask;
    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener
            mOnUxRestrictionsChangedListener = carUxRestrictions -> {
        int uxr = carUxRestrictions.getActiveRestrictions();
        if (isVideoRestricted(uxr)) {
            // pull back the video from DD
            changeDisplayForTask(MoveTaskReceiver.MOVE_FROM_DISTANT_DISPLAY);
        }
    };

    private final TaskStackChangeListener mTaskStackChangeLister = new TaskStackChangeListener() {

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            logIfDebuggable("onTaskMovedToFront: displayId: " + taskInfo.displayId + ", " + taskInfo
                    + " token: " + taskInfo.token);

            if (taskInfo.displayId == DEFAULT_DISPLAY_ID) {
                mBaseIntentForTopTaskId = taskInfo.taskId;
                mBaseIntentForTopTask = taskInfo.baseIntent;
            } else if (taskInfo.displayId == mDistantDisplayId) {
                if (taskInfo.baseIntent.getComponent().getPackageName().equals(
                        mContext.getPackageName())) {
                    mDistantDisplayRootWallpaperTaskId = taskInfo.taskId;
                    return;
                }
                mDistantDisplayForegroundTask.add(taskInfo.taskId);
            }
        }

        @Override
        public void onTaskDisplayChanged(int taskId, int newDisplayId) {
            logIfDebuggable(
                    "onTaskDisplayChanged: taskId: " + taskId + " newDisplayId: " + newDisplayId);
            if (newDisplayId == mDistantDisplayId && (mDistantDisplayRootWallpaperTaskId != taskId
                    && mDistantDisplayRootWallpaperTaskId != INVALID_TASK_ID)) {
                mDistantDisplayForegroundTask.add(taskId);
            } else if (newDisplayId == DEFAULT_DISPLAY_ID) {
                mBaseIntentForTopTaskId = taskId;
            }
        }
    };

    public TaskViewController(
            Context context, DisplayManager displayManager, LayoutInflater inflater) {
        mContext = context;
        mDisplayManager = displayManager;
        mDistantDisplayForegroundTask = new OrderedHashSet<Integer>();
        mDistantDisplayId = mContext.getResources().getInteger(R.integer.config_distantDisplayId);
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);

        CarUxRestrictionsUtil carUxRestrictionsUtil = CarUxRestrictionsUtil.getInstance(context);
        carUxRestrictionsUtil.register(mOnUxRestrictionsChangedListener);
    }

    /**
     * Initializes and setups the TaskViews. This method accepts a container with the base layout
     * then attaches the surface view to it. There are 2 surface views created.
     */
    public void initialize(int distantDisplayId) {
        mDistantDisplayId = distantDisplayId;

        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackChangeLister);

        if (DEBUG) {
            Log.i(TAG, "Setup adb debugging : ");
            setupDebuggingThroughAdb();
            launchActivity(mDistantDisplayId,
                    RootTaskViewWallpaperActivity.createIntent(mContext));
        }
    }

    private void setupDebuggingThroughAdb() {
        IntentFilter filter = new IntentFilter(MoveTaskReceiver.MOVE_ACTION);
        MoveTaskReceiver receiver = new MoveTaskReceiver();
        receiver.registerOnChangeDisplayForTask(this::changeDisplayForTask);
        mContext.registerReceiverAsUser(receiver, UserHandle.of(ActivityManager.getCurrentUser()),
                filter, null, null,
                Context.RECEIVER_EXPORTED);
    }

    private boolean isVideoRestricted(int uxr) {
        return ((uxr & UX_RESTRICTIONS_NO_VIDEO) == UX_RESTRICTIONS_NO_VIDEO);
    }

    private void changeDisplayForTask(String movement) {
        Log.i(TAG, "Handling movement command : " + movement);
        if (movement.equals(MoveTaskReceiver.MOVE_FROM_DISTANT_DISPLAY)
                && !mDistantDisplayForegroundTask.isEmpty()) {
            int lastTask = mDistantDisplayForegroundTask.getLast();
            moveTaskToDisplay(lastTask, DEFAULT_DISPLAY_ID);
            mDistantDisplayForegroundTask.remove(lastTask);
        } else if (movement.equals(MoveTaskReceiver.MOVE_TO_DISTANT_DISPLAY)) {
            if (isVideoApp(mBaseIntentForTopTask.getComponent().getPackageName())) {
                startActivityOnDisplay(mBaseIntentForTopTask, mDistantDisplayId);
            }
        }
    }

    private void moveTaskToDisplay(int taskId, int displayId) {
        try {
            ActivityTaskManager.getService().moveRootTaskToDisplay(taskId, displayId);
        } catch (Exception e) {
            Log.e(TAG, "Error moving task " + taskId + " to display " + displayId, e);
        }
    }

    private void startActivityOnDisplay(Intent intent, int displayId) {
        if (intent == null) {
            return;
        }
        ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext, /* enterResId=*/
                0, /* exitResId= */ 0);
        options.setLaunchDisplayId(displayId);
        logIfDebuggable("starting task" + intent + "on display " + displayId);
        mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
    }


    private boolean isVideoApp(@Nullable String packageName) {
        if (packageName == null) {
            Log.w(TAG, "package name is null");
            return false;
        }

        Context userContext = mContext.createContextAsUser(
                UserHandle.of(ActivityManager.getCurrentUser()), /* flags= */ 0);
        return AppCategoryDetector.isVideoApp(userContext.getPackageManager(),
                packageName);
    }

    /**
     * Provides a DisplayManager.
     * This method will be called from the SurfaceHolderCallback to create a virtual display.
     */
    public DisplayManager getDisplayManager() {
        return mDisplayManager;
    }

    private void launchActivity(int displayId, Intent intent) {
        ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext, 0, 0);
        options.setLaunchDisplayId(displayId);
        mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
    }

    private static void logIfDebuggable(String message) {
        if (DEBUG) {
            Log.d(TAG, message);
        }
    }
}
