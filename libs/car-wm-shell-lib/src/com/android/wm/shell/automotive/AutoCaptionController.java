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

import static com.android.window.flags.Flags.safeRegionLetterboxingV1;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.car.Car;
import android.car.content.pm.CarPackageManager;
import android.car.feature.Flags;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.dagger.WMSingleton;

import java.io.PrintWriter;
import java.util.Objects;

import javax.inject.Inject;

/**
 * Manages the caption bar for Automotive.
 *
 * <p>This class provides APIs to manage caption bar with safe region. If only safe area needs to be
 * specified, then {@code AutoLayoutManager#setOrUpdateSafeArea} call can be used. This class
 * listens to task changes and updates the caption bar based on
 * {@code CarPackageManager#requiresDisplayCompat}.
 */
@WMSingleton
public class AutoCaptionController {
    private static final int DEFAULT_Z_INDEX_CAPTION_BAR = 100001;
    private static final String CAPTION_BAR_NAME_FORMAT = "AutoCaptionControllerBar:%d";
    private static final String TRANSACTION_NAME_FORMAT = "AutoCaptionControllerTransaction:%d";
    private static final String TAG = AutoCaptionController.class.getSimpleName();
    private static final boolean DEBUG = Build.IS_DEBUGGABLE || Log.isLoggable(TAG, Log.DEBUG);
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final AutoSurfaceTransactionFactory mAutoSurfaceTransactionFactory;
    private final AutoDecorManager mAutoDecorManager;
    // To save the caption region info for each root task. Each root task can have its own
    // caption region.
    private final SparseArray<CaptionRegionInfo> mCaptionRegionInfoPerRootTask =
            new SparseArray<>();
    // To save the caption region for each display. This caption region is for default task
    // display area.
    private final SparseArray<CaptionRegionInfo> mCaptionRegionInfoPerDisplay = new SparseArray<>();
    // To keep the AutoDecor added to the task as caption bar.
    private final SparseArray<AutoDecor> mTaskIdToCaptionBar = new SparseArray<>();
    private final AutoTaskRepository mAutoTaskRepository;
    private final PackageManager mPackageManager;

    private CarPackageManager mCarPackageManager;

    private boolean mIsCarReady = false;

    private AutoTaskRepository.AutoAppTaskListener mAutoAppTaskListener =
            new AutoTaskRepository.AutoAppTaskListener() {
                public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo) {
                    if (taskInfo.parentTaskId != -1) {
                        // task is within a root task
                        handleCaptionBarOnTaskAppeared(
                                mAutoTaskRepository.getRootTaskStack(taskInfo.parentTaskId),
                                taskInfo);
                        return;
                    }

                    handleCaptionBarOnTaskAppeared(taskInfo);
                }

                public void onTaskChanged(ActivityManager.RunningTaskInfo taskInfo) {
                    handleCaptionBarOnTaskChanged(taskInfo);
                }

                public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
                    handleCaptionBarOnTaskVanished(taskInfo);
                }
            };

    @Inject
    AutoCaptionController(Context context,
            ShellTaskOrganizer shellTaskOrganizer, AutoTaskRepository autoTaskRepository,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            AutoDecorManager autoDecorManager,
            AutoSurfaceTransactionFactory autoSurfaceTransactionFactory) {
        mShellTaskOrganizer = shellTaskOrganizer;
        mAutoTaskRepository = autoTaskRepository;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
        mAutoDecorManager = autoDecorManager;
        mAutoSurfaceTransactionFactory = autoSurfaceTransactionFactory;
        autoTaskRepository.addAppTaskListener(mAutoAppTaskListener);
        mPackageManager = context.getPackageManager();
        // TODO((b/401349206): Add a factory or provider for CarService connection.
        Car.createCar(context, /* handler= */ null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                (car, ready) -> {
                    if (mIsCarReady) {
                        return;
                    }
                    mIsCarReady = ready;
                    if (ready) {
                        mCarPackageManager = (CarPackageManager) car.getCarManager(
                                Car.PACKAGE_SERVICE);
                    }
                });
    }

    /**
     * Sets a caption region for the root task stack.
     *
     * <p>Calling this API for same rootTaskStack would update the caption region. If root task
     * stack bounds are changed, this API should be called again.
     *
     * @param rootTaskStack             The root task stack.
     * @param relativeCaptionRegion     The region for caption bar. The region is relative to
     *                                  the root task bounds.
     * @param autoCaptionBarViewController The factory for providing view of the caption bar.
     */
    // TODO(b/398655273): Use builder pattern to avoid confusion in the parameter names.
    public void setCaptionRegion(@NonNull RootTaskStack rootTaskStack,
            @NonNull Rect relativeCaptionRegion,
            @NonNull AutoCaptionBarViewController autoCaptionBarViewController) {
        Objects.requireNonNull(rootTaskStack);
        Objects.requireNonNull(relativeCaptionRegion);
        Objects.requireNonNull(autoCaptionBarViewController);

        if (!safeRegionLetterboxingV1()) {
            Slogf.e(TAG, "safe_region_letterboxing_v1 TS flag is disabled.");
            return;
        }

        // TODO (b/430955826): Ensure caption bar updates happen when changes occur.
        if (mCaptionRegionInfoPerRootTask.contains(rootTaskStack.getId())) {
            Slogf.i(TAG,
                    "Root task already has a caption region. Updating it to new values. caption "
                            + "region [%s], root task stack [%d]",
                    relativeCaptionRegion, rootTaskStack.getId());
        } else {
            Slogf.i(TAG, "Defining caption region [%s] for root task stack %d",
                    relativeCaptionRegion, rootTaskStack.getId());
        }

        mCaptionRegionInfoPerRootTask.append(rootTaskStack.getId(),
                new CaptionRegionInfo(relativeCaptionRegion,
                        autoCaptionBarViewController));
    }

    /**
     * Removes a caption region for the root task stack.
     *
     * @param rootTaskStack The root task stack.
     */
    public void removeCaptionRegion(@NonNull RootTaskStack rootTaskStack) {
        Objects.requireNonNull(rootTaskStack);

        if (!safeRegionLetterboxingV1()) {
            Slogf.e(TAG, "safe_region_letterboxing_v1 TS flag is disabled.");
            return;
        }

        Slogf.i(TAG, "Removing caption region for root task stack %d",
                rootTaskStack.getId());

        mCaptionRegionInfoPerRootTask.remove(rootTaskStack.getId());
    }

    /**
     * Sets a caption region for the default task display area.
     *
     * <p>Calling this API for same displayId would update the caption region. If root task
     * stack bounds are changed, this API should be called again. To set the safe region, use
     * {@link AutoLayoutManager#setOrUpdateSafeRegion}.
     *
     * @param displayId                 The display Id.
     * @param captionRegion             The region for caption bar.
     * @param autoCaptionBarViewController The factory for providing view of the caption bar.
     */
    // TODO(b/398655273): Use builder pattern to avoid confusion in the parameter names.
    public void setCaptionRegion(int displayId,
            @NonNull Rect captionRegion,
            @NonNull AutoCaptionBarViewController autoCaptionBarViewController) {
        Objects.requireNonNull(captionRegion);
        Objects.requireNonNull(autoCaptionBarViewController);

        if (!safeRegionLetterboxingV1()) {
            Slogf.e(TAG, "safe_region_letterboxing_v1 TS flag is disabled.");
            return;
        }

        if (mCaptionRegionInfoPerDisplay.contains(displayId)) {
            Slogf.i(TAG, "Display already has a caption region. Updating it to new values. "
                    + "caption region [%s] for display %d", captionRegion, displayId);
        } else {
            Slogf.i(TAG, "Defining caption region [%s] for display %d",
                    captionRegion, displayId);
        }

        if (mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId) == null) {
            Slogf.e(TAG, "DisplayAreaInfo for Display [%d] is not available.", displayId);
            return;
        }

        mCaptionRegionInfoPerDisplay.append(displayId,
                new CaptionRegionInfo(captionRegion, autoCaptionBarViewController));
    }

    /**
     * Removes a caption region for the default task display area. To remove the safe region, use
     * {@link AutoLayoutManager#setOrUpdateSafeRegion}.
     *
     * @param displayId The display Id.
     */
    public void removeCaptionRegion(int displayId) {
        if (!safeRegionLetterboxingV1()) {
            Slogf.e(TAG, "safe_region_letterboxing_v1 TS flag is disabled.");
            return;
        }

        Slogf.i(TAG, "Removing caption region for display %d", displayId);
        mCaptionRegionInfoPerDisplay.remove(displayId);
    }

    /**
     * Adds a caption bar to a task within a root task stack.
     *
     * @param rootTaskStack The root task stack containing the task.
     * @param taskInfo      The running task information.
     */
    void addCaptionBar(RootTaskStack rootTaskStack, ActivityManager.RunningTaskInfo taskInfo) {
        CaptionRegionInfo captionRegionInfo = mCaptionRegionInfoPerRootTask.get(
                rootTaskStack.getId());
        attachCaptionBar(taskInfo, captionRegionInfo);
    }

    /**
     * Adds a caption bar to a task within a display.
     *
     * @param displayId The display ID.
     * @param taskInfo  The running task information.
     */
    void addCaptionBar(int displayId, ActivityManager.RunningTaskInfo taskInfo) {
        CaptionRegionInfo captionRegionInfo = mCaptionRegionInfoPerDisplay.get(displayId);
        attachCaptionBar(taskInfo, captionRegionInfo);
    }

    /**
     * Attaches a caption bar to a task using the provided caption region information.
     *
     * @param taskInfo          The running task information.
     * @param captionRegionInfo The caption region information containing caption bar details.
     */
    private void attachCaptionBar(ActivityManager.RunningTaskInfo taskInfo,
            CaptionRegionInfo captionRegionInfo) {
        if (captionRegionInfo == null) {
            if (DEBUG) {
                Slogf.d(TAG,
                        "Caption region is not provided for task %d", taskInfo.taskId);
            }
            return;
        }

        if (DEBUG) {
            Slogf.d(TAG, "Adding caption to task. TaskId: %d", taskInfo.taskId);
        }

        AutoCaptionBarViewController autoCaptionBarViewController =
                captionRegionInfo.getAutoCaptionBarViewController();
        Rect captionBarBounds = captionRegionInfo.getCaptionRegionBounds();
        View captionView = autoCaptionBarViewController.createView(taskInfo);

        if (captionView == null) {
            Slogf.e(TAG, "Caption view is not provided for task %d", taskInfo.taskId);
            return;
        }

        String captionBarName = String.format(CAPTION_BAR_NAME_FORMAT, taskInfo.taskId);

        AutoDecor captionDecor = mAutoDecorManager.createAutoDecor(captionView,
                DEFAULT_Z_INDEX_CAPTION_BAR, captionBarBounds, captionBarName);
        // Attach the caption bar with spy window so that touch also travel to the task surface.
        // This is required if task is not in focus.
        captionDecor.attachDecorToTask(taskInfo, /* addSpyWindow= */true);
        mTaskIdToCaptionBar.append(taskInfo.taskId, captionDecor);
    }

    /**
     * Updates the visibility of the caption bar attached to a task
     *
     * @param taskInfo The running task information.
     * @param visible  to be updated.
     */
    void updateCaptionBarVisibility(ActivityManager.RunningTaskInfo taskInfo, boolean visible) {
        AutoDecor captionDecor = mTaskIdToCaptionBar.get(taskInfo.taskId);
        if (DEBUG) {
            Slogf.d(TAG,
                    "updateCaptionBarVisibility. TaskId: %d, visible %b, captionDecor %s",
                    taskInfo.taskId, visible, captionDecor);
        }

        if (captionDecor != null) {
            String transactionName = String.format(TRANSACTION_NAME_FORMAT, taskInfo.taskId);

            AutoSurfaceTransaction autoSurfaceTransaction =
                    mAutoSurfaceTransactionFactory.createTransaction(transactionName);
            autoSurfaceTransaction.setVisibility(captionDecor, visible);
            autoSurfaceTransaction.apply();
        } else if (visible) {
            // A new activity started within the same task which needs caption bar, attach a new
            // caption bar.
            addCaptionBarToTask(taskInfo);
        }
    }

    private void addCaptionBarToTask(ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo.parentTaskId == -1) {
            // Task is not within a root task. Use display Id
            addCaptionBar(taskInfo.displayId, taskInfo);
        } else {
            // Get task's root task stack
            RootTaskStack rootTaskStack = mAutoTaskRepository.getRootTaskStack(
                    taskInfo.parentTaskId);
            if (rootTaskStack != null) {
                addCaptionBar(rootTaskStack, taskInfo);
            } else {
                // Should not happen
                Slogf.e(TAG,
                        "updateCaptionBarVisibility. RootTaskStack is null. TaskId: %d",
                        taskInfo.taskId);
            }
        }
    }

    /**
     * Removes a caption bar from a task.
     *
     * @param taskInfo The running task information.
     */
    void removeCaptionBar(ActivityManager.RunningTaskInfo taskInfo) {
        AutoDecor captionDecor = mTaskIdToCaptionBar.get(taskInfo.taskId);
        mTaskIdToCaptionBar.remove(taskInfo.taskId);
        if (captionDecor != null) {
            mAutoDecorManager.removeAutoDecor(captionDecor);
        }
        if (DEBUG) {
            Slogf.d(TAG, "Caption removed. TaskId: %d", taskInfo.taskId);
        }
    }

    private void handleCaptionBarOnTaskAppeared(RootTaskStack rootTaskStack,
            ActivityManager.RunningTaskInfo task) {
        if (requiresCaptionBar(task)) {
            addCaptionBar(rootTaskStack, task);
        }
    }

    private void handleCaptionBarOnTaskAppeared(ActivityManager.RunningTaskInfo task) {
        if (requiresCaptionBar(task)) {
            addCaptionBar(task.displayId, task);
        }
    }

    private void handleCaptionBarOnTaskChanged(ActivityManager.RunningTaskInfo task) {
        if (requiresCaptionBar(task)) {
            updateCaptionBarVisibility(task, /* visibility= */ true);
            if (Flags.displayCompatibilityV2()) {
                notifyUpdateToViewController(task);
            }
        } else {
            updateCaptionBarVisibility(task, /* visibility= */ false);
        }
    }

    private void notifyUpdateToViewController(ActivityManager.RunningTaskInfo task) {
        CaptionRegionInfo captionRegionInfo;
        if (task.parentTaskId != -1) {
            captionRegionInfo = mCaptionRegionInfoPerRootTask.get(
                    mAutoTaskRepository.getRootTaskStack(task.parentTaskId).getId());
        } else {
            captionRegionInfo = mCaptionRegionInfoPerDisplay.get(task.displayId);
        }
        AutoDecor captionDecor = mTaskIdToCaptionBar.get(task.taskId);

        if (captionRegionInfo != null && captionDecor != null) {
            captionRegionInfo.mAutoCaptionBarViewController.updateView(captionDecor.getView(),
                    task);
        }
    }

    private void handleCaptionBarOnTaskVanished(ActivityManager.RunningTaskInfo task) {
        removeCaptionBar(task);
    }

    @SuppressLint("MissingPermission")
    private boolean requiresCaptionBar(ActivityManager.RunningTaskInfo task) {
        if (!safeRegionLetterboxingV1()) {
            Slogf.i(TAG,
                    "safe_region_letterboxing_v1 TS flag is disabled.");
            return false;
        }

        if (!mIsCarReady) {
            Slogf.i(TAG, "Car Service is not yet connected.");
            return false;
        }

        try {
            ComponentName componentName = task.topActivity;
            if (componentName == null) {
                if (DEBUG) {
                    Slogf.d(TAG, "componentName is null. TaskId: %d", task.taskId);
                }
                return false;
            }

            boolean requiresDisplayCompat = mCarPackageManager.requiresDisplayCompatForUser(
                    componentName.getPackageName(), task.userId);

            // If the activity is not safe region letterboxed, do not attach a caption bar.
            boolean isTopActivitySafeRegionLetterboxed =
                    task.appCompatTaskInfo.isTopActivitySafeRegionLetterboxed();

            if (DEBUG) {
                Slogf.d(TAG,
                        "Task id %d requires DisplayCompat %b, top activity safe region "
                                + "letterboxed %b, for user %d and top activity: %s",
                        task.taskId, requiresDisplayCompat, isTopActivitySafeRegionLetterboxed,
                        task.userId, componentName);
            }

            if (requiresDisplayCompat && isTopActivitySafeRegionLetterboxed) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slogf.e(TAG,
                    "Package name not found. TaskId %d. PackageName: %s",
                    task.getTaskId(), task.topActivity.getPackageName());
        }
        return false;
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "AutoCaptionController");

        if (mCaptionRegionInfoPerRootTask.size() > 0) {
            pw.println(prefix + "CaptionRegionInfoPerRootTask");
        }

        for (int i = 0; i < mCaptionRegionInfoPerRootTask.size(); i++) {
            int rootTaskId = mCaptionRegionInfoPerRootTask.keyAt(i);
            CaptionRegionInfo captionRegionInfo = mCaptionRegionInfoPerRootTask.valueAt(i);
            pw.println(prefix + "Root task id:" + rootTaskId);
            pw.println(prefix + "CaptionRegionInfo:" + captionRegionInfo);
        }

        if (mCaptionRegionInfoPerDisplay.size() > 0) {
            pw.println(prefix + "CaptionRegionInfoPerDisplay");
        }

        for (int i = 0; i < mCaptionRegionInfoPerDisplay.size(); i++) {
            int displayId = mCaptionRegionInfoPerDisplay.keyAt(i);
            CaptionRegionInfo captionRegionInfo = mCaptionRegionInfoPerDisplay.valueAt(i);
            pw.println(prefix + "Display id:" + displayId);
            pw.println(prefix + "CaptionRegionInfo:" + captionRegionInfo);
        }

        if (mTaskIdToCaptionBar.size() > 0) {
            pw.println(prefix + "TaskIdToCaptionBar");
        }

        for (int i = 0; i < mTaskIdToCaptionBar.size(); i++) {
            int taskId = mTaskIdToCaptionBar.keyAt(i);
            AutoDecor captionBarDecor = mTaskIdToCaptionBar.valueAt(i);
            pw.println(prefix + "taskId id:" + taskId);
            pw.println(prefix + "captionBarDecor:" + captionBarDecor);
        }
    }

    /**
     * Contains all relevant information for caption region.
     */
    static class CaptionRegionInfo {
        private final Rect mCaptionRegionBounds;
        private final AutoCaptionBarViewController mAutoCaptionBarViewController;

        /**
         * Constructor for CaptionRegionInfo.
         *
         * @param captionRegionBounds       The caption region.
         * @param autoCaptionBarViewController The factory for creating caption bar views.
         */
        CaptionRegionInfo(Rect captionRegionBounds,
                AutoCaptionBarViewController autoCaptionBarViewController) {
            mCaptionRegionBounds = captionRegionBounds;
            mAutoCaptionBarViewController = autoCaptionBarViewController;
        }

        /**
         * Gets the caption region.
         *
         * @return The caption region.
         */
        public Rect getCaptionRegionBounds() {
            return mCaptionRegionBounds;
        }

        /**
         * Gets the auto caption bar view factory.
         *
         * @return The auto caption bar view factory.
         */
        public AutoCaptionBarViewController getAutoCaptionBarViewController() {
            return mAutoCaptionBarViewController;
        }

        @Override
        public String toString() {
            return "CaptionRegionInfo{" + " mCaptionRegion=" + (mCaptionRegionBounds != null
                    ? mCaptionRegionBounds.toString() : "null") + ", mAutoCaptionBarViewController="
                    + mAutoCaptionBarViewController + '}';
        }
    }

    /** TODO(b/467720864): update tests to remove dependency on member variables. */
    @VisibleForTesting
    SparseArray<CaptionRegionInfo> getCaptionRegionInfoPerRootTask() {
        return mCaptionRegionInfoPerRootTask;
    }

    /** TODO(b/467720864): update tests to remove dependency on member variables. */
    @VisibleForTesting
    SparseArray<CaptionRegionInfo> getCaptionRegionInfoPerDisplay() {
        return mCaptionRegionInfoPerDisplay;
    }

    /** TODO(b/467720864): update tests to remove dependency on member variables. */
    @VisibleForTesting
    SparseArray<AutoDecor> getTaskIdToCaptionBar() {
        return mTaskIdToCaptionBar;
    }
}
