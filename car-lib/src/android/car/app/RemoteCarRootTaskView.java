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

package android.car.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.car.builtin.app.TaskInfoHelper;
import android.car.builtin.view.ViewHelper;
import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.view.SurfaceControl;

import java.util.concurrent.Executor;

/**
 * A {@link RemoteCarRootTaskView} should be used when a given list of activities are required to
 * appear inside the bounds of a the given {@link android.view.View} in the form of a task stack.
 * This {@link RemoteCarTaskView} creates a root task and all the given activities will launch
 * inside that root task.
 *
 * <p>It serves these use-cases:
 * <ul>
 *     <li>Should be used when the apps that are meant to be in it can be started from anywhere
 *     in the system. i.e. when the host app has no control over their launching.</li>
 *     <li>Suitable for apps like Assistant or Setup-Wizard.</li>
 * </ul>
 *
 * @hide
 */
public final class RemoteCarRootTaskView extends RemoteCarTaskView {
    private static final String TAG = RemoteCarRootTaskView.class.getSimpleName();

    private final Executor mCallbackExecutor;
    private final RemoteCarRootTaskViewCallback mCallback;
    private final ICarActivityService mCarActivityService;
    private final CarTaskViewController mCarTaskViewController;
    private final RemoteCarRootTaskViewConfig mConfig;
    private final Rect mTmpRect = new Rect();
    private final RootTaskStackManager mRootTaskStackManager = new RootTaskStackManager();

    private ActivityManager.RunningTaskInfo mRootTask;

    final ICarTaskViewClient mICarTaskViewClient = new ICarTaskViewClient.Stub() {
        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
            if (mRootTask == null) {
                mRootTask = taskInfo;

                try {
                    mCarActivityService.setPersistentActivitiesOnRootTask(
                            mConfig.getAllowListedActivities(), TaskInfoHelper.getToken(taskInfo));
                } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                    throw e;
                } catch (ServiceSpecificException e) {
                    throw new IllegalStateException("Car service looks crashed");
                } catch (RemoteException | RuntimeException e) {
                    throw new IllegalStateException("Car service looks crashed");
                }

                // If onTaskAppeared() is called, it implicitly means that super.isInitialized()
                // is true, as the root task is created only after initialization.
                mCallbackExecutor.execute(() -> mCallback.onTaskViewInitialized());

                if (taskInfo.taskDescription != null) {
                    ViewHelper.seResizeBackgroundColor(
                            RemoteCarRootTaskView.this,
                            taskInfo.taskDescription.getBackgroundColor());
                }
                updateWindowBounds();
            }

            mRootTaskStackManager.taskAppeared(taskInfo, leash);
        }

        @Override
        public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
            if (mRootTask.taskId == taskInfo.taskId && taskInfo.taskDescription != null) {
                ViewHelper.seResizeBackgroundColor(
                        RemoteCarRootTaskView.this,
                        taskInfo.taskDescription.getBackgroundColor());
            }
            mRootTaskStackManager.taskInfoChanged(taskInfo);
        }

        @Override
        public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
            if (mRootTask.taskId == taskInfo.taskId) {
                mRootTask = null;
            }
            mRootTaskStackManager.taskVanished(taskInfo);
        }

        @Override
        public void setResizeBackgroundColor(SurfaceControl.Transaction t, int color) {
            ViewHelper.seResizeBackgroundColor(RemoteCarRootTaskView.this, color);
        }

        @Override
        public Rect getCurrentBoundsOnScreen() {
            ViewHelper.getBoundsOnScreen(RemoteCarRootTaskView.this, mTmpRect);
            return mTmpRect;
        }
    };

    RemoteCarRootTaskView(
            @NonNull Context context,
            RemoteCarRootTaskViewConfig config,
            @NonNull Executor callbackExecutor,
            @NonNull RemoteCarRootTaskViewCallback callback,
            CarTaskViewController carTaskViewController,
            @NonNull ICarActivityService carActivityService) {
        super(context);
        mConfig = config;
        mCallbackExecutor = callbackExecutor;
        mCallback = callback;
        mCarTaskViewController = carTaskViewController;
        mCarActivityService = carActivityService;

        mCallbackExecutor.execute(() -> mCallback.onTaskViewCreated(this));
    }

    /**
     * Returns the task info of the top task running in the root task embedded in this task view.
     *
     * @return task info object of the top task.
     */
    @Nullable
    public ActivityManager.RunningTaskInfo getTopTaskInfo() {
        return mRootTaskStackManager.getTopTask();
    }

    @Override
    void onInitialized() {
        // A signal when the surface and host-side are ready. This task view initialization
        // completes after root task has been created.
        createRootTask(mConfig.getDisplayId());
    }

    @Override
    public boolean isInitialized() {
        return super.isInitialized() && mRootTask != null;
    }

    @Override
    void onReleased() {
        mCallbackExecutor.execute(() -> mCallback.onTaskViewReleased());
        mCarTaskViewController.onRemoteCarTaskViewReleased(this);
    }

    @Nullable
    @Override
    public ActivityManager.RunningTaskInfo getTaskInfo() {
        return mRootTask;
    }

    RemoteCarRootTaskViewConfig getConfig() {
        return mConfig;
    }

    @Override
    public String toString() {
        return toString(/* withBounds= */ false);
    }

    String toString(boolean withBounds) {
        if (withBounds) {
            ViewHelper.getBoundsOnScreen(this, mTmpRect);
        }
        return TAG + " {\n"
                + "  config=" + mConfig + "\n"
                + "  taskId=" + (getTaskInfo() == null ? "null" : getTaskInfo().taskId) + "\n"
                + (withBounds ? ("  boundsOnScreen=" + mTmpRect) : "")
                + "}\n";

    }
}
