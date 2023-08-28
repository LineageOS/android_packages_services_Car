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
import android.annotation.RequiresApi;
import android.app.ActivityManager;
import android.car.annotation.ApiRequirements;
import android.car.builtin.view.ViewHelper;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.view.SurfaceControl;

import java.util.concurrent.Executor;

/**
 * A {@link RemoteCarDefaultRootTaskView} can act as a default app container. A default app
 * container is the container where all apps open by default.
 *
 * Please use this with caution. If this task view is used inside an activity, that activity might
 * always remain running as all the other activities will start appearing inside this launch root
 * instead of appearing in the full screen.
 *
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public final class RemoteCarDefaultRootTaskView extends RemoteCarTaskView {
    private static final String TAG = RemoteCarDefaultRootTaskView.class.getSimpleName();

    private final Executor mCallbackExecutor;
    private final RemoteCarDefaultRootTaskViewCallback mCallback;
    private final CarTaskViewController mCarTaskViewController;
    private final RemoteCarDefaultRootTaskViewConfig mConfig;
    private final Rect mTmpRect = new Rect();
    private final RootTaskStackManager mRootTaskStackManager = new RootTaskStackManager();

    private ActivityManager.RunningTaskInfo mRootTask;

    final ICarTaskViewClient mICarTaskViewClient = new ICarTaskViewClient.Stub() {
        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
            if (mRootTask == null) {
                mRootTask = taskInfo;
                // If onTaskAppeared() is called, it implicitly means that super.isInitialized()
                // is true, as the root task is created only after initialization.
                mCallbackExecutor.execute(() -> mCallback.onTaskViewInitialized());

                if (taskInfo.taskDescription != null) {
                    ViewHelper.seResizeBackgroundColor(
                            RemoteCarDefaultRootTaskView.this,
                            taskInfo.taskDescription.getBackgroundColor());
                }
                updateWindowBounds();
            }

            mRootTaskStackManager.taskAppeared(taskInfo, leash);
            mCallbackExecutor.execute(() -> mCallback.onTaskAppeared(taskInfo));
        }

        @Override
        public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
            if (mRootTask.taskId == taskInfo.taskId && taskInfo.taskDescription != null) {
                ViewHelper.seResizeBackgroundColor(
                        RemoteCarDefaultRootTaskView.this,
                        taskInfo.taskDescription.getBackgroundColor());
            }
            mRootTaskStackManager.taskInfoChanged(taskInfo);
            mCallbackExecutor.execute(() -> mCallback.onTaskInfoChanged(taskInfo));
        }

        @Override
        public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
            if (mRootTask.taskId == taskInfo.taskId) {
                mRootTask = null;
            }
            mRootTaskStackManager.taskVanished(taskInfo);
            mCallbackExecutor.execute(() -> mCallback.onTaskVanished(taskInfo));
        }

        @Override
        public void setResizeBackgroundColor(SurfaceControl.Transaction t, int color) {
            ViewHelper.seResizeBackgroundColor(RemoteCarDefaultRootTaskView.this, color);
        }

        @Override
        public Rect getCurrentBoundsOnScreen() {
            ViewHelper.getBoundsOnScreen(RemoteCarDefaultRootTaskView.this, mTmpRect);
            return mTmpRect;
        }
    };

    RemoteCarDefaultRootTaskView(
            @NonNull Context context,
            RemoteCarDefaultRootTaskViewConfig config,
            @NonNull Executor callbackExecutor,
            @NonNull RemoteCarDefaultRootTaskViewCallback callback,
            CarTaskViewController carTaskViewController) {
        super(context);
        mConfig = config;
        mCallbackExecutor = callbackExecutor;
        mCallback = callback;
        mCarTaskViewController = carTaskViewController;

        mCallbackExecutor.execute(() -> mCallback.onTaskViewCreated(this));
    }

    /**
     * Returns the task info of the top task running in the root task embedded in this task view.
     *
     * @return task info object of the top task.
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_1,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_1)
    @Nullable
    public ActivityManager.RunningTaskInfo getTopTaskInfo() {
        return mRootTaskStackManager.getTopTask();
    }

    @Override
    void onInitialized() {
        // A signal when the surface and host-side are ready. This task view initialization
        // completes after root task has been created and set as launch root.
        createLaunchRootTask(mConfig.getDisplayId(), mConfig.embedsHomeTask(),
                mConfig.embedsRecentsTask(), mConfig.embedsAssistantTask());
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

    RemoteCarDefaultRootTaskViewConfig getConfig() {
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
                + "  rootTaskId=" + (getTaskInfo() == null ? "null" : getTaskInfo().taskId) + "\n"
                + (withBounds ? ("  boundsOnScreen=" + mTmpRect) : "")
                + "}\n";

    }
}