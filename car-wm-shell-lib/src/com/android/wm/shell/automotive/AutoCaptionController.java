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

import static com.android.window.flags.Flags.safeRegionLetterboxing;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.car.Car;
import android.car.content.pm.CarPackageManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.window.WindowContainerTransaction;

import com.android.server.utils.Slogf;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.dagger.WMSingleton;

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
public class AutoCaptionController implements AutoTaskRepository.AutoAppTaskListener {

    private static final String TAG = "AutoCaptionController";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final int DEFAULT_Z_INDEX_CAPTION_BAR = 1;
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final AutoSurfaceTransactionFactory mAutoSurfaceTransactionFactory;
    private final AutoDecorManager mAutoDecorManager;
    // To save the safe area info for each root task. Each root task can have its own safe area.
    private final SparseArray<SafeRegionInfo> mSafeAreaInfoPerRootTask = new SparseArray<>();
    // To save the safe area for each display. This safe area is for default task display area.
    private final SparseArray<SafeRegionInfo> mSafeAreaInfoPerDisplay = new SparseArray<>();
    // To keep the AutoDecor added to the task as caption bar.
    private final SparseArray<AutoDecor> mTaskIdToCaptionBar = new SparseArray<>();
    private final AutoTaskRepository mAutoTaskRepository;

    private CarPackageManager mCarPackageManager;

    private boolean mIsCarReady = false;

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
        autoTaskRepository.addAppTaskListener(this);
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
     * Sets a safe region and caption region for the root task stack.
     *
     * <p>Calling this API for same rootTaskStack would update the safe region. If activities using
     * the safe region are present, they will receive a config change. In this case, caption region
     * would be updated and caption bar would be shown in the updated caption region.
     *
     * @param rootTaskStack The root task stack.
     * @param safeRegion    The safe region for activity.
     * @param captionRegion The region for caption bar.
     * @param autoCaptionViewFactory The factory for providing view of the caption bar.
     */
    // TODO(b/398655273): Use builder pattern to avoid confusion in the parameter names.
    public void setSafeRegionAndCaptionRegion(RootTaskStack rootTaskStack, Rect safeRegion,
            Rect captionRegion, AutoCaptionViewFactory autoCaptionViewFactory) {
        if (!safeRegionLetterboxing()) {
            Slogf.e(TAG, "safe_region_letterboxing TS flag is disabled.");
            return;
        }

        if (DBG) {
            Slogf.d(TAG, "Defining safe region [%s] and caption region [%s] for root task stack %d",
                    safeRegion, captionRegion, rootTaskStack.getId());
        }

        mSafeAreaInfoPerRootTask.append(rootTaskStack.getId(),
                new SafeRegionInfo(safeRegion, captionRegion, autoCaptionViewFactory));

        // Define safe region for the container
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setSafeRegionBounds(rootTaskStack.getRootTaskInfo().token, safeRegion);
        mShellTaskOrganizer.applyTransaction(wct);
    }

    /**
     * Removes a safe region and caption region for the root task stack.
     *
     * @param rootTaskStack The root task stack.
     */
    public void removeSafeRegionAndCaptionRegion(RootTaskStack rootTaskStack) {
        if (!safeRegionLetterboxing()) {
            Slogf.e(TAG, "safe_region_letterboxing TS flag is disabled.");
            return;
        }

        if (DBG) {
            Slogf.d(TAG, "Removing safe region and caption region for root task stack %d",
                    rootTaskStack.getId());
        }

        mSafeAreaInfoPerRootTask.remove(rootTaskStack.getId());

        // Remove safe region for the container
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setSafeRegionBounds(rootTaskStack.getRootTaskInfo().token, null);
        mShellTaskOrganizer.applyTransaction(wct);
    }


    /**
     * Sets a safe region and caption region for the default task display area.
     *
     * <p>Calling this API for same displayId would update the safe region. If activities using
     * the safe region are present, they will receive a config change. In this case, caption region
     * would be updated and caption bar would be shown in the updated caption region.
     *
     * @param displayId     The display Id.
     * @param safeRegion    The safe region for activity.
     * @param captionRegion The region for caption bar.
     * @param autoCaptionViewFactory The factory for providing view of the caption bar.
     */
    // TODO(b/398655273): Use builder pattern to avoid confusion in the parameter names.
    public void setSafeRegionAndCaptionRegion(int displayId, Rect safeRegion, Rect captionRegion,
            AutoCaptionViewFactory autoCaptionViewFactory) {
        if (!safeRegionLetterboxing()) {
            Slogf.e(TAG, "safe_region_letterboxing TS flag is disabled.");
            return;
        }

        if (DBG) {
            Slogf.d(TAG, "Defining safe region [%s] and caption region [%s] for display %d",
                    safeRegion, captionRegion, displayId);
        }

        mSafeAreaInfoPerDisplay.append(displayId,
                new SafeRegionInfo(safeRegion, captionRegion, autoCaptionViewFactory));

        // Define safe region for the container
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setSafeRegionBounds(
                mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId).token,
                safeRegion);
        mShellTaskOrganizer.applyTransaction(wct);
    }

    /**
     * Removes a safe region and caption region for the default task display area.
     *
     * @param displayId     The display Id.
     */
    public void removeSafeRegionAndCaptionRegion(int displayId) {
        if (!safeRegionLetterboxing()) {
            Slogf.e(TAG, "safe_region_letterboxing TS flag is disabled.");
            return;
        }

        if (DBG) {
            Slogf.d(TAG, "Removing safe region and caption region for display %d",
                    displayId);
        }

        mSafeAreaInfoPerDisplay.remove(displayId);

        // Remove safe region for the container
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setSafeRegionBounds(
                mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId).token,
                null);
        mShellTaskOrganizer.applyTransaction(wct);
    }

    /**
     * Adds a caption bar to a task within a root task stack.
     *
     * @param rootTaskStack The root task stack containing the task.
     * @param taskInfo      The running task information.
     */
    void addCaptionBar(RootTaskStack rootTaskStack, ActivityManager.RunningTaskInfo taskInfo) {
        SafeRegionInfo safeRegionInfo = mSafeAreaInfoPerRootTask.get(rootTaskStack.getId());
        attachCaptionBar(taskInfo, safeRegionInfo);
    }

    /**
     * Adds a caption bar to a task within a display.
     *
     * @param displayId The display ID.
     * @param taskInfo  The running task information.
     */
    void addCaptionBar(int displayId, ActivityManager.RunningTaskInfo taskInfo) {
        SafeRegionInfo safeRegionInfo = mSafeAreaInfoPerDisplay.get(displayId);
        attachCaptionBar(taskInfo, safeRegionInfo);
    }

    /**
     * Attaches a caption bar to a task using the provided safe area information.
     *
     * @param taskInfo     The running task information.
     * @param safeRegionInfo The safe area information containing caption bar details.
     */
    private void attachCaptionBar(ActivityManager.RunningTaskInfo taskInfo,
            SafeRegionInfo safeRegionInfo) {
        if (safeRegionInfo == null) {
            Slogf.e(TAG, "Safe area is not provided for task %d", taskInfo.taskId);
            return;
        }

        if (DBG) {
            Slogf.d(TAG, "Adding caption to task. TaskId: %d, SafeAreaInfo: %s",
                    taskInfo.taskId, safeRegionInfo);
        }

        AutoCaptionViewFactory autoCaptionViewFactory =
                safeRegionInfo.getAutoCaptionBarViewFactory();
        Rect captionBarBounds = safeRegionInfo.getCaptionRegionBounds();
        View captionView = autoCaptionViewFactory.createView(taskInfo);

        if (captionView == null) {
            Slogf.e(TAG, "Caption view is not provided for task %d", taskInfo.taskId);
            return;
        }

        String captionBarName = "CaptionBar:" + taskInfo.taskId;

        AutoDecor captionDecor = mAutoDecorManager.createAutoDecor(captionView,
                DEFAULT_Z_INDEX_CAPTION_BAR, captionBarBounds, captionBarName);
        captionDecor.attachDecorToTask(taskInfo);
        mTaskIdToCaptionBar.append(taskInfo.taskId, captionDecor);
    }

    /**
     * Updates the visibility of the caption bar attached to a task
     *
     * @param taskInfo The running task information.
     * @param visibility to be updated.
     */
    void updateCaptionBarVisibility(ActivityManager.RunningTaskInfo taskInfo, boolean visibility) {
        AutoDecor captionDecor = mTaskIdToCaptionBar.get(taskInfo.taskId);
        if (captionDecor != null) {
            AutoSurfaceTransaction autoSurfaceTransaction =
                    mAutoSurfaceTransactionFactory.createTransaction(
                            "CaptionVisibility-" + taskInfo.taskId);
            autoSurfaceTransaction.setVisibility(captionDecor, visibility);
            autoSurfaceTransaction.apply();
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
        if (DBG) {
            Slogf.d(TAG, "Caption removed. TaskId: %d, captionDecor: %s",
                    taskInfo.taskId, captionDecor);
        }
    }
    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo.parentTaskId != -1) {
            // task is within a root task
            handleCaptionBarOnTaskAppeared(
                    mAutoTaskRepository.getRootTaskStack(taskInfo.parentTaskId), taskInfo);
            return;
        }

        handleCaptionBarOnTaskAppeared(taskInfo);
    }

    @Override
    public void onTaskChanged(ActivityManager.RunningTaskInfo taskInfo) {
        handleCaptionBarOnTaskChanged(taskInfo);
    }

    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        handleCaptionBarOnTaskVanished(taskInfo);
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
        } else {
            updateCaptionBarVisibility(task, /* visibility= */ false);
        }
    }

    private void handleCaptionBarOnTaskVanished(ActivityManager.RunningTaskInfo task) {
        if (requiresCaptionBar(task)) {
            removeCaptionBar(task);
        }
    }

    @SuppressLint("MissingPermission")
    private boolean requiresCaptionBar(ActivityManager.RunningTaskInfo task) {
        if (!safeRegionLetterboxing()) {
            Slogf.i(TAG, "safe_region_letterboxing TS flag is disabled.");
            return false;
        }

        if (!mIsCarReady) {
            Slogf.i(TAG, "Car Service is not yet connected.");
            return false;
        }

        try {
            if (mCarPackageManager.requiresDisplayCompatForUser(task.topActivity.getPackageName(),
                    task.userId)) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slogf.e(TAG, "Package name found. TaskId %d. PackageName: %s", task.getTaskId(),
                    task.topActivity.getPackageName());
        }
        return false;
    }



    /**
     * Contains all relevant information for safe area.
     */
    static class SafeRegionInfo {
        private final Rect mSafeRegionBounds;
        private final Rect mCaptionRegionBounds;
        private final AutoCaptionViewFactory mAutoCaptionViewFactory;

        /**
         * Constructor for SafeAreaInfo.
         *
         * @param safeRegionBounds                The safe region.
         * @param captionRegionBounds             The caption region.
         * @param autoCaptionViewFactory The factory for creating caption bar views.
         */
        SafeRegionInfo(Rect safeRegionBounds, Rect captionRegionBounds,
                AutoCaptionViewFactory autoCaptionViewFactory) {
            mSafeRegionBounds = safeRegionBounds;
            mCaptionRegionBounds = captionRegionBounds;
            mAutoCaptionViewFactory = autoCaptionViewFactory;
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
         * Gets the safe region.
         *
         * @return The safe region.
         */
        public Rect getSafeRegionBounds() {
            return mSafeRegionBounds;
        }

        /**
         * Gets the auto caption bar view factory.
         *
         * @return The auto caption bar view factory.
         */
        public AutoCaptionViewFactory getAutoCaptionBarViewFactory() {
            return mAutoCaptionViewFactory;
        }

        @Override
        public String toString() {
            return "SafeAreaInfo{" + "mSafeRegion=" + (
                    mSafeRegionBounds != null ? mSafeRegionBounds.toString()
                    : "null") + ", mCaptionRegion=" + (mCaptionRegionBounds != null
                    ? mCaptionRegionBounds.toString()
                    : "null") + ", mAutoCaptionBarViewFactory=" + mAutoCaptionViewFactory + '}';
        }
    }
}
