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

package com.android.systemui.car.systembar;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.MediaSessionManager.OnActiveSessionsChangedListener;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.View;

import androidx.annotation.NonNull;

import com.android.systemui.R;
import com.android.systemui.car.distantdisplay.activity.MoveTaskReceiver;
import com.android.systemui.car.distantdisplay.util.AppCategoryDetector;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import java.util.List;

import javax.inject.Inject;

/** Controls a DistantDisplay view in system icons. */
public class DistantDisplayButtonController {
    public static final String TAG = "DistantDisplayButtonController";

    public static final Intent DEFAULT_DISPLAY_INTENT = new Intent(
            MoveTaskReceiver.MOVE_ACTION).putExtra("move",
            MoveTaskReceiver.MOVE_FROM_DISTANT_DISPLAY);
    public static final Intent DISTANT_DISPLAY_INTENT = new Intent(
            MoveTaskReceiver.MOVE_ACTION).putExtra("move",
            MoveTaskReceiver.MOVE_TO_DISTANT_DISPLAY);
    private static final int INVALID_TASK_ID = -1;

    private final int mDistantDisplayId;
    private final MediaSessionManager mMediaSessionManager;
    private final Context mContext;
    private final UserTracker mUserTracker;

    private DistantDisplayButton mDistantDisplayButton;

    /** key: displayId, value: taskId */
    private SparseIntArray mForegroundTasksOnDisplays = new SparseIntArray();

    /** key: taskId */
    private SparseArray<ActivityManager.RunningTaskInfo> mTasks = new SparseArray<>();

    private final TaskStackChangeListener mTaskStackChangeListener = new TaskStackChangeListener() {

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            logIfDebuggable("onTaskMovedToFront: " + taskInfo
                    + " token: " + taskInfo.token + " displayId: " + taskInfo.displayId);

            mForegroundTasksOnDisplays.put(taskInfo.displayId, taskInfo.taskId);
            mTasks.put(taskInfo.taskId, taskInfo);


            logIfDebuggable("Calling update button state in onTaskMovedToFront");
            updateButtonState();
        }

        @Override
        public void onTaskDisplayChanged(int taskId, int newDisplayId) {
            logIfDebuggable("onTaskDisplayChanged: " + taskId + " displayId: " + newDisplayId);

            // Index of old display id associated to task id.
            int oldDisplayIdForTaskIndex = mForegroundTasksOnDisplays.indexOfValue(taskId);
            if (oldDisplayIdForTaskIndex >= 0) {
                mForegroundTasksOnDisplays.removeAt(oldDisplayIdForTaskIndex);
            }
            mForegroundTasksOnDisplays.put(newDisplayId, taskId);
            logIfDebuggable("Calling update button state in onTaskDisplayChanged");
            updateButtonState();
        }

        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) {
            logIfDebuggable("onTaskCreated: " + taskId + " ComponentName: " + componentName);
        }

        @Override
        public void onTaskRemoved(int taskId) {
            mTasks.remove(taskId);
            logIfDebuggable("Task id: " + taskId + " removed from task stack");
        }
    };

    private final OnActiveSessionsChangedListener mOnActiveSessionsChangedListener =
            controllers -> {
                updateButtonState();
            };

    private final UserTracker.Callback mUserChangedCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, @NonNull Context userContext) {
            mMediaSessionManager.addOnActiveSessionsChangedListener(null,
                    mUserTracker.getUserHandle(),
                    mContext.getMainExecutor(), mOnActiveSessionsChangedListener);
        }

        @Override
        public void onBeforeUserSwitching(int newUser) {
            mMediaSessionManager.removeOnActiveSessionsChangedListener(
                    mOnActiveSessionsChangedListener);
        }
    };

    @Inject
    public DistantDisplayButtonController(Context context, UserTracker userTracker) {
        mContext = context;
        mUserTracker = userTracker;
        mDistantDisplayId = mContext.getResources().getInteger(R.integer.config_distantDisplayId);
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackChangeListener);
    }

    /**
     * Find the DistantDisplayButton from a view and sets button state.
     */
    public void addDistantDisplayButtonView(View view) {
        if (mDistantDisplayButton != null) return;
        mDistantDisplayButton = view.findViewById(R.id.distant_display_nav);

        if (mDistantDisplayButton == null) return;

        mMediaSessionManager.addOnActiveSessionsChangedListener(null, mUserTracker.getUserHandle(),
                mContext.getMainExecutor(), mOnActiveSessionsChangedListener);
        mUserTracker.addCallback(mUserChangedCallback, mContext.getMainExecutor());
        updateButtonState();
    }

    private boolean hasActiveMediaSession(String packageName) {
        List<MediaController> activeSessionsForUser = mMediaSessionManager.getActiveSessionsForUser(
                null, mUserTracker.getUserHandle());
        if (activeSessionsForUser.isEmpty()) return false;
        return activeSessionsForUser.stream().anyMatch(
                controller -> controller.getPackageName().equals(packageName));
    }

    private boolean isVideoApp(String packageName) {
        return AppCategoryDetector.isVideoApp(mUserTracker.getUserContext().getPackageManager(),
                packageName);
    }

    boolean canMoveAppFromDisplay(int displayId) {
        int taskId = mForegroundTasksOnDisplays.get(displayId, INVALID_TASK_ID);
        if (taskId == INVALID_TASK_ID) return false;
        ActivityManager.RunningTaskInfo mForegroundTaskOnDisplay = mTasks.get(taskId);
        if (mForegroundTaskOnDisplay == null) return false;
        boolean isVideo = isVideoApp(mForegroundTaskOnDisplay.baseActivity.getPackageName());
        boolean hasMediaSession = hasActiveMediaSession(
                mForegroundTaskOnDisplay.baseActivity.getPackageName());
        return isVideo && hasMediaSession;
    }

    private void updateButtonState() {
        if (mDistantDisplayButton == null) return;

        mContext.getMainExecutor().execute(() -> {
            if (canMoveAppFromDisplay(mDistantDisplayId)) {
                mDistantDisplayButton.setVisibility(View.VISIBLE);
                mDistantDisplayButton.setOnClickListener(
                        mDistantDisplayButton.getButtonClickListener(DEFAULT_DISPLAY_INTENT));
            } else if (canMoveAppFromDisplay(Display.DEFAULT_DISPLAY)) {
                mDistantDisplayButton.setVisibility(View.VISIBLE);
                mDistantDisplayButton.setOnClickListener(
                        mDistantDisplayButton.getButtonClickListener(DISTANT_DISPLAY_INTENT));
            } else {
                mDistantDisplayButton.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Clean up the controller by removing callbacks and unregistering listeners .
     */
    public void removeAll() {
        mUserTracker.removeCallback(mUserChangedCallback);
        mMediaSessionManager.removeOnActiveSessionsChangedListener(
                mOnActiveSessionsChangedListener);
        mDistantDisplayButton = null;
    }

    private static void logIfDebuggable(String message) {
        Log.d(TAG, message);
    }

}
