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
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.MediaDescription;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.MediaSessionManager.OnActiveSessionsChangedListener;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;

import androidx.annotation.NonNull;

import com.android.systemui.R;
import com.android.systemui.car.distantdisplay.activity.MoveTaskReceiver;
import com.android.systemui.car.distantdisplay.common.DistantDisplayQcItem;
import com.android.systemui.car.distantdisplay.util.AppCategoryDetector;
import com.android.systemui.car.qc.DistantDisplayControlsUpdateListener;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import java.util.List;
import java.util.Optional;

import javax.inject.Inject;

/**
 * A Controller that observes to foreground tasks and media sessions to provide controls for distant
 * display quick controls panel.
 **/
@SysUISingleton
public class DistantDisplayController {
    public static final String TAG = DistantDisplayController.class.getSimpleName();

    public static final Intent DEFAULT_DISPLAY_INTENT = new Intent(
            MoveTaskReceiver.MOVE_ACTION).putExtra("move",
            MoveTaskReceiver.MOVE_FROM_DISTANT_DISPLAY);

    public static final Intent DISTANT_DISPLAY_INTENT = new Intent(
            MoveTaskReceiver.MOVE_ACTION).putExtra("move",
            MoveTaskReceiver.MOVE_TO_DISTANT_DISPLAY);
    private static final int INVALID_TASK_ID = -1;
    private final int mDistantDisplayId;
    private final PackageManager mPackageManager;
    private final MediaSessionManager mMediaSessionManager;
    private final Context mContext;
    private final UserTracker mUserTracker;

    /** key: displayId, value: taskId */
    private SparseIntArray mForegroundTasksOnDisplays = new SparseIntArray();

    /** key: taskId */
    private SparseArray<ActivityManager.RunningTaskInfo> mTasks = new SparseArray<>();

    private StatusChangeListener mStatusChangeListener;
    private Drawable mDistantDisplayDrawable;
    private Drawable mDefaultDisplayDrawable;

    private DistantDisplayControlsUpdateListener mDistantDisplayControlsUpdateListener;

    /**
     * Interface for listeners to register for status changes based on Media Session and TaskStack
     * Changes.
     **/
    public interface StatusChangeListener {
        /**
         * Callback triggered for display changes of the task.
         */
        void onDisplayChanged(int displayId);

        /**
         * Callback triggered for visibility changes.
         */
        void onVisibilityChanged(boolean visible);
    }


    private final TaskStackChangeListener mTaskStackChangeListener = new TaskStackChangeListener() {

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            logIfDebuggable("onTaskMovedToFront: " + taskInfo
                    + " token: " + taskInfo.token + " displayId: " + taskInfo.displayId);

            mForegroundTasksOnDisplays.put(taskInfo.displayId, taskInfo.taskId);
            mTasks.put(taskInfo.taskId, taskInfo);

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
    public DistantDisplayController(Context context, UserTracker userTracker) {
        mContext = context;
        mUserTracker = userTracker;
        mPackageManager = context.getPackageManager();
        mDistantDisplayId = mContext.getResources().getInteger(R.integer.config_distantDisplayId);
        mDistantDisplayDrawable = mContext.getResources().getDrawable(
                R.drawable.ic_distant_display_nav, /* theme= */ null);
        mDefaultDisplayDrawable = mContext.getResources().getDrawable(
                R.drawable.ic_default_display_nav, /* theme= */ null);
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        mMediaSessionManager.addOnActiveSessionsChangedListener(/* notificationListener= */ null,
                userTracker.getUserHandle(),
                mContext.getMainExecutor(), mOnActiveSessionsChangedListener);
        mUserTracker.addCallback(mUserChangedCallback, context.getMainExecutor());
        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackChangeListener);
    }

    /**
     * Sets a listener that needs to be notified of controls change available in status panel.
     **/
    public void setDistantDisplayControlStatusInfoListener(
            StatusChangeListener statusChangeListener) {
        mStatusChangeListener = statusChangeListener;
        updateButtonState();
    }

    /**
     * Removes a DistantDisplayControlStatusInfoListener.
     **/
    public void removeDistantDisplayControlStatusInfoListener() {
        mStatusChangeListener = null;
    }

    public void setDistantDisplayControlsUpdateListener(
            DistantDisplayControlsUpdateListener distantDisplayControlsUpdateListener) {
        mDistantDisplayControlsUpdateListener = distantDisplayControlsUpdateListener;
    }

    /**
     * @return A metadata of current app on distant/default display
     * {@link com.android.systemui.car.distantdisplay.common.DistantDisplayQcItem}
     */
    public DistantDisplayQcItem getMetadata() {
        Optional<MediaController> mediaControllerFromActiveMediaSession =
                getMediaControllerFromActiveMediaSession();
        if (mediaControllerFromActiveMediaSession.isEmpty()) return null;

        MediaDescription mediaDescription =
                mediaControllerFromActiveMediaSession.get().getMetadata().getDescription();
        CharSequence mediaTitle = mediaDescription.getTitle();
        DistantDisplayQcItem distantDisplayQcItem = new DistantDisplayQcItem.Builder()
                .setTitle((String) mediaTitle)
                .build();
        return distantDisplayQcItem;
    }

    /**
     * @return Controls to move content between display
     * {@link com.android.systemui.car.distantdisplay.common.DistantDisplayQcItem}
     */
    public DistantDisplayQcItem getControls() {
        if (canMoveAppFromDisplay(mDistantDisplayId)) {
            return new DistantDisplayQcItem.Builder()
                    .setTitle(mContext.getString(R.string.qc_bring_back_to_default_display_title))
                    .setIcon(mDefaultDisplayDrawable)
                    .setIntent(getBroadcastIntent(DEFAULT_DISPLAY_INTENT))
                    .build();
        } else if (canMoveAppFromDisplay(Display.DEFAULT_DISPLAY)) {
            return new DistantDisplayQcItem.Builder()
                    .setTitle(mContext.getString(R.string.qc_send_to_pano_title))
                    .setIcon(mDistantDisplayDrawable)
                    .setIntent(getBroadcastIntent(DISTANT_DISPLAY_INTENT))
                    .build();
        } else {
            return null;
        }
    }

    private Optional<MediaController> getMediaControllerFromActiveMediaSession() {

        ActivityManager.RunningTaskInfo mForegroundMediaTask;
        if (canMoveAppFromDisplay(mDistantDisplayId)) {
            mForegroundMediaTask = getForegroundTaskOnDisplay(mDistantDisplayId);
        } else if (canMoveAppFromDisplay(Display.DEFAULT_DISPLAY)) {
            mForegroundMediaTask = getForegroundTaskOnDisplay(Display.DEFAULT_DISPLAY);
        } else {
            mForegroundMediaTask = null;
        }

        if (mForegroundMediaTask == null) return Optional.empty();

        return mMediaSessionManager.getActiveSessionsForUser(/* notificationListener= */ null,
                        mUserTracker.getUserHandle())
                .stream()
                .filter(
                        mediaController -> mForegroundMediaTask.baseActivity.getPackageName()
                                .equals(mediaController.getPackageName()))
                .findFirst();
    }

    private PendingIntent getBroadcastIntent(Intent intent) {
        return PendingIntent.getBroadcastAsUser(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                mUserTracker.getUserHandle());
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

    private boolean canMoveAppFromDisplay(int displayId) {
        ActivityManager.RunningTaskInfo mForegroundTaskOnDisplay = getForegroundTaskOnDisplay(
                displayId);
        if (mForegroundTaskOnDisplay == null) return false;
        boolean isVideo = isVideoApp(mForegroundTaskOnDisplay.baseActivity.getPackageName());
        boolean hasMediaSession = hasActiveMediaSession(
                mForegroundTaskOnDisplay.baseActivity.getPackageName());
        return isVideo && hasMediaSession;
    }

    private ActivityManager.RunningTaskInfo getForegroundTaskOnDisplay(int displayId) {
        int taskId = mForegroundTasksOnDisplays.get(displayId, INVALID_TASK_ID);
        return taskId == INVALID_TASK_ID ? null : mTasks.get(taskId);
    }

    private void updateButtonState() {
        if (mStatusChangeListener == null) return;

        if (canMoveAppFromDisplay(mDistantDisplayId)) {
            mStatusChangeListener.onDisplayChanged(mDistantDisplayId);
            mStatusChangeListener.onVisibilityChanged(true);
        } else if (canMoveAppFromDisplay(Display.DEFAULT_DISPLAY)) {
            mStatusChangeListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
            mStatusChangeListener.onVisibilityChanged(true);
        } else {
            mStatusChangeListener.onVisibilityChanged(false);
        }

        if (mDistantDisplayControlsUpdateListener != null) {
            mDistantDisplayControlsUpdateListener.onControlsChanged();
        }
    }

    private static void logIfDebuggable(String message) {
        Log.d(TAG, message);
    }
}
