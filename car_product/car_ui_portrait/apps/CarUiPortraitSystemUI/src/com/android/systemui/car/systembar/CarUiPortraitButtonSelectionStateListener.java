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

package com.android.systemui.car.systembar;

import static com.android.systemui.car.displayarea.DisplayAreaComponent.DISPLAY_AREA_VISIBILITY_CHANGED;
import static com.android.systemui.car.displayarea.DisplayAreaComponent.INTENT_EXTRA_IS_DISPLAY_AREA_VISIBLE;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import androidx.annotation.GuardedBy;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.android.systemui.car.displayarea.TaskCategoryManager;

class CarUiPortraitButtonSelectionStateListener extends ButtonSelectionStateListener {

    private CarUiPortraitButtonSelectionStateController mPortraitButtonStateController;
    private final TaskCategoryManager mTaskCategoryManager;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private boolean mIsAppGridOnTop;
    @GuardedBy("mLock")
    private boolean mIsNotificationOnTop;
    @GuardedBy("mLock")
    private boolean mIsRecentOnTop;
    @GuardedBy("mLock")
    private boolean mIsPanelVisible;

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            super.onTaskMovedToFront(taskInfo);
            updateTaskStates(taskInfo);
            setButtonsSelected();
        }

        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo taskInfo,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible)
                throws RemoteException {
            super.onActivityRestartAttempt(taskInfo, homeTaskVisible, clearedTask, wasVisible);
            updateTaskStates(taskInfo);
            setButtonsSelected();
        }
    };

    CarUiPortraitButtonSelectionStateListener(Context context,
            ButtonSelectionStateController carSystemButtonController,
            TaskCategoryManager taskCategoryManager) {
        super(carSystemButtonController);
        if (mButtonSelectionStateController
                instanceof CarUiPortraitButtonSelectionStateController) {
            mPortraitButtonStateController =
                    (CarUiPortraitButtonSelectionStateController) carSystemButtonController;
        }
        mTaskCategoryManager = taskCategoryManager;

        ActivityTaskManager.getInstance().registerTaskStackListener(
                mTaskStackListener);

        BroadcastReceiver displayAreaVisibilityReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mPortraitButtonStateController == null) {
                    return;
                }
                synchronized (mLock) {
                    mIsPanelVisible = intent.getBooleanExtra(INTENT_EXTRA_IS_DISPLAY_AREA_VISIBLE,
                            false);
                    setButtonsSelected();
                }

            }
        };

        LocalBroadcastManager.getInstance(context.getApplicationContext()).registerReceiver(
                displayAreaVisibilityReceiver,
                new IntentFilter(DISPLAY_AREA_VISIBILITY_CHANGED));
    }

    private void setButtonsSelected() {
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (mLock) {
                mPortraitButtonStateController.setAppGridButtonSelected(
                        mIsAppGridOnTop && mIsPanelVisible);
                mPortraitButtonStateController.setNotificationButtonSelected(
                        mIsNotificationOnTop && mIsPanelVisible);
                mPortraitButtonStateController.setRecentsButtonSelected(
                        mIsRecentOnTop && mIsPanelVisible);
            }
        });
    }

    private void updateTaskStates(ActivityManager.RunningTaskInfo taskInfo) {
        synchronized (mLock) {
            mIsAppGridOnTop = mTaskCategoryManager.isAppGridActivity(taskInfo);
            mIsRecentOnTop = mTaskCategoryManager.isRecentsActivity(taskInfo);
            mIsNotificationOnTop = mTaskCategoryManager.isNotificationActivity(taskInfo);
        }
    }
}
