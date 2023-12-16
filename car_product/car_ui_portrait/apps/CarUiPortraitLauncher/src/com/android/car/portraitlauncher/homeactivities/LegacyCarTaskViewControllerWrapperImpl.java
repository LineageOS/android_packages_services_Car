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
package com.android.car.portraitlauncher.homeactivities;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowInsets;

import com.android.car.carlauncher.CarTaskView;
import com.android.car.carlauncher.LaunchRootCarTaskViewCallbacks;
import com.android.car.carlauncher.SemiControlledCarTaskViewCallbacks;
import com.android.car.carlauncher.TaskViewManager;

import java.util.List;
import java.util.concurrent.Executor;

/** A {@link TaskViewControllerWrapper} that organizing the {@link CarTaskView}. */
final class LegacyCarTaskViewControllerWrapperImpl extends TaskViewControllerWrapper {
    private static final String TAG = LegacyCarTaskViewControllerWrapperImpl.class.getSimpleName();
    private final TaskViewManager mTaskViewManager;

    LegacyCarTaskViewControllerWrapperImpl(Activity activity) {
        super();
        mTaskViewManager = new TaskViewManager(activity, activity.getMainThreadHandler());
    }

    private static void logIfDebuggable(String message) {
        if (DBG) {
            Log.d(TAG, message);
        }
    }

    @Override
    void setSystemOverlayInsets(Rect bottomInsets, Rect topInsets, int panel) {
        CarTaskView targetTaskView = (CarTaskView) getTaskView(panel);
        if (targetTaskView == null) {
            return;
        }
        targetTaskView.addInsets(
                /* index= */ 0, WindowInsets.Type.systemOverlays(), bottomInsets);
        targetTaskView.addInsets(
                /* index= */ 1, WindowInsets.Type.systemOverlays(), topInsets);
    }

    @Override
    void setObscuredTouchRegion(Region obscuredTouchRegion, int panel) {
        CarTaskView targetTaskView = (CarTaskView) getTaskView(panel);

        if (targetTaskView == null) {
            return;
        }
        targetTaskView.setObscuredTouchRegion(obscuredTouchRegion);
    }

    @Override
    void showEmbeddedTasks() {
        mTaskViewManager.showEmbeddedTasks();
    }

    @Override
    void onDestroy() {
        for (int keyIndex = 0; keyIndex < mTaskViewsMap.size(); keyIndex++) {
            int key = mTaskViewsMap.keyAt(keyIndex);
            SurfaceView surfaceView = mTaskViewsMap.get(key);
            if (surfaceView instanceof CarTaskView carTaskView) {
                carTaskView.release();
            }
        }
    }

    @Override
    void updateWindowBounds() {
        for (int keyIndex = 0; keyIndex < mTaskViewsMap.size(); keyIndex++) {
            int key = mTaskViewsMap.keyAt(keyIndex);
            SurfaceView surfaceView = mTaskViewsMap.get(key);
            if (surfaceView instanceof CarTaskView carTaskView) {
                carTaskView.post(carTaskView::onLocationChanged);
            }
        }
    }

    @Override
    void createCarRootTaskView(TaskViewCallback callback, int displayId,
            Executor executor, List<ComponentName> list) {
        mTaskViewManager.createSemiControlledTaskView(executor, list,
                new SemiControlledCarTaskViewCallbacks() {
                    @Override
                    public void onTaskViewCreated(CarTaskView taskView) {
                        callback.onTaskViewCreated(taskView);
                    }

                    @Override
                    public void onTaskViewReady() {
                        callback.onTaskViewInitialized();
                    }
                });
    }

    @Override
    void createCarDefaultRootTaskView(TaskViewCallback callback, int displayId, Executor executor) {
        mTaskViewManager.createLaunchRootTaskView(executor, new LaunchRootCarTaskViewCallbacks() {
            @Override
            public void onTaskViewCreated(CarTaskView taskView) {
                callback.onTaskViewCreated(taskView);
            }

            @Override
            public void onTaskViewReady() {
                callback.onTaskViewInitialized();
            }
        });
    }

    @Override
    void updateAllowListedActivities(int taskViewId, List<ComponentName> list) {
        SurfaceView target = getTaskView(taskViewId);
        if (target instanceof CarTaskView carTaskView) {
            mTaskViewManager.setAllowListedActivities(carTaskView, list);
        }
    }

    @Override
    ActivityManager.RunningTaskInfo getRootTaskInfo() {
        return mTaskViewManager.getTopTaskInLaunchRootTask();
    }

    @Override
    void moveToFront(int taskViewId) {
        logIfDebuggable("updateLaunchRootCarTaskVisibility");
        mTaskViewManager.updateLaunchRootCarTaskVisibility(/* visibility= */ false);
    }

    @Override
    public void setWindowBounds(Rect taskViewBounds, int panel) {
        CarTaskView targetTaskView = (CarTaskView) getTaskView(panel);
        if (targetTaskView == null) {
            return;
        }
        targetTaskView.setWindowBounds(taskViewBounds);
    }

    @Override
    void updateCarDefaultTaskViewVisibility(boolean visibility) {
        mTaskViewManager.updateLaunchRootCarTaskVisibility(visibility);
    }

    @Override
    SurfaceView getTaskView(int taskViewId) {
        return mTaskViewsMap.get(taskViewId);
    }
}
