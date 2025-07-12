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

import static com.android.wm.shell.Flags.enableAutoTaskStackController;
import static com.android.window.flags.Flags.safeRegionLetterboxingV1;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.graphics.Rect;
import android.os.Binder;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.InsetsFrameProvider;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.dagger.WMSingleton;

import java.io.PrintWriter;
import java.util.Objects;

import javax.inject.Inject;

/**
 * Manages the layout of tasks, insets, and safe area within the automotive display.
 *
 * <p>This class is responsible for defining safe regions, adding, updating, and removing insets,
 * and managing the overall layout of tasks in the automotive environment.
 */
@WMSingleton
public class AutoLayoutManager {

    private static final String TAG = "AutoLayoutManager";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final AutoTaskRepository mAutoTaskRepository;
    @VisibleForTesting
    final SparseArray<ArraySet<InsetsFrameProvider>> mTaskIdToInsetFrameProviderMap =
            new SparseArray<>();
    private final Binder mInsetToken = new Binder();
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;

    @Inject
    AutoLayoutManager(
            ShellTaskOrganizer shellTaskOrganizer,
            AutoTaskRepository autoTaskRepository,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer) {
        mShellTaskOrganizer = shellTaskOrganizer;
        mAutoTaskRepository = autoTaskRepository;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
    }

    /**
     * Sets safe region for a given display ID.
     *
     * <p>Calling this API for same display ID again would update the safe region. If activities
     * using the safe region are present, they will receive a config change. Pass safeRegion null
     * for resetting the safe region.
     */
    public void setOrUpdateSafeRegion(int displayId, @Nullable Rect safeRegion) {
        if (!safeRegionLetterboxingV1()) {
            Slogf.e(TAG, "safe_region_letterboxing_v1 TS flag is disabled.");
            return;
        }

        Slogf.i(TAG, "Defining safe region [%s] for display [%d]", safeRegion, displayId);

        if (mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId) == null) {
            Slogf.e(TAG, "DisplayAreaInfo for Display [%d] is not available.", displayId);
            return;
        }

        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setSafeRegionBounds(mRootTaskDisplayAreaOrganizer.getDisplayAreaInfo(displayId).token,
                safeRegion);
        mShellTaskOrganizer.applyTransaction(wct);
    }

    /**
     * Adds or updates insets to a task.
     *
     * <p>If the same rootTaskStack, same index and same type is passed, inset would be updated
     * with newer frame bounds, otherwise new inset is added.
     *
     * @param rootTaskStack The rootTaskStack to add inset
     * @param index         The index of the inset.
     * @param type          The type of the inset.
     * @param frame         The frame of the inset.
     */
    public void addOrUpdateInsets(@NonNull RootTaskStack rootTaskStack, int index, int type,
            @NonNull  Rect frame) {
        Objects.requireNonNull(rootTaskStack);
        Objects.requireNonNull(frame);

        if (!enableAutoTaskStackController()) {
            Slogf.e(TAG, "auto_task_stack_windowing TS flag is disabled.");
            return;
        }

        int taskId = rootTaskStack.getRootTaskInfo().taskId;

        if (DBG) {
            Slogf.d(TAG, "Adding Or Updating inset to taskId: %d, index: %d, type: %d, frame: %s",
                    taskId, index, type, frame);
        }

        ActivityManager.RunningTaskInfo taskInfo = mAutoTaskRepository.getTaskInfo(taskId);

        if (taskInfo == null) {
            Slogf.e(TAG, "Task doesn't exist. taskId: %d.", taskId);
            return;
        }

        ArraySet<InsetsFrameProvider> insetsFrameProviders = mTaskIdToInsetFrameProviderMap.get(
                taskId);
        if (insetsFrameProviders == null) {
            insetsFrameProviders = new ArraySet<>();
            mTaskIdToInsetFrameProviderMap.put(taskId, insetsFrameProviders);
        }

        InsetsFrameProvider requestedInset = new InsetsFrameProvider(mInsetToken, index, type);
        insetsFrameProviders.add(requestedInset);

        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.addInsetsSource(taskInfo.token, mInsetToken, index, type, frame, new Rect[0], 0);
        mShellTaskOrganizer.applyTransaction(wct);
    }

    /**
     * Removes insets from a task.
     *
     * @param rootTaskStack The rootTaskStack to add inset
     * @param index         The index of the inset.
     * @param type          The type of the inset.
     */
    public void removeInsets(@NonNull RootTaskStack rootTaskStack, int index, int type) {
        Objects.requireNonNull(rootTaskStack);

        if (!enableAutoTaskStackController()) {
            Slogf.e(TAG, "auto_task_stack_windowing TS flag is disabled.");
            return;
        }

        int taskId = rootTaskStack.getRootTaskInfo().taskId;

        if (DBG) {
            Slogf.d(TAG, "removing inset to taskId: %d, index: %d, type: %d",
                    taskId, index, type);
        }

        ActivityManager.RunningTaskInfo taskInfo = mAutoTaskRepository.getTaskInfo(taskId);

        if (taskInfo == null) {
            Slogf.e(TAG, "Task doesn't exist. taskId: %d.", taskId);
            return;
        }

        ArraySet<InsetsFrameProvider> insetsFrameProviders = mTaskIdToInsetFrameProviderMap.get(
                taskId);

        if (insetsFrameProviders == null) {
            Slogf.e(TAG, "Task %d has no inset.", taskId);
            return;
        }

        InsetsFrameProvider requestedInset = new InsetsFrameProvider(mInsetToken, index, type);
        if (insetsFrameProviders.contains(requestedInset)) {
            WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.removeInsetsSource(taskInfo.token, mInsetToken, index, type);
            mShellTaskOrganizer.applyTransaction(wct);
            insetsFrameProviders.remove(requestedInset);
        } else {
            Slogf.e(TAG, "No inset data corresponding to taskId: %d, index: %d, type: %d",
                    taskId, index, type);
        }

        if (insetsFrameProviders.isEmpty()) {
            mTaskIdToInsetFrameProviderMap.remove(taskId);
        }
    }

    /**
     * Removes all insets for a task.
     *
     * @param taskId The task id.
     */
    // TODO(b/405379514): When task disappear, call this to remove all the inset.
    void removeAllInsetForTask(int taskId) {
        ArraySet<InsetsFrameProvider> insetsFrameProviders = mTaskIdToInsetFrameProviderMap.get(
                taskId);

        if (insetsFrameProviders == null || insetsFrameProviders.isEmpty()) {
            return;
        }

        WindowContainerTransaction wct = new WindowContainerTransaction();
        for (InsetsFrameProvider inset : insetsFrameProviders) {
            wct.removeInsetsSource(mAutoTaskRepository.getTaskInfo(taskId).token, mInsetToken,
                    inset.getIndex(), inset.getType());
        }
        mShellTaskOrganizer.applyTransaction(wct);

        mTaskIdToInsetFrameProviderMap.remove(taskId);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "AutoLayoutManager:");
        for (int i = 0; i < mTaskIdToInsetFrameProviderMap.size(); i++) {
            int taskId = mTaskIdToInsetFrameProviderMap.keyAt(i);
            ArraySet<InsetsFrameProvider> insetsFrameProviders =
                    mTaskIdToInsetFrameProviderMap.valueAt(i);
            pw.println(
                    prefix + "TaskId:" + taskId + " inset Count: " + insetsFrameProviders.size());
            String insetPrefix = "    ";
            if (!insetsFrameProviders.isEmpty()) {
                pw.println(prefix + "Insets:");
            }
            for (int j = 0; j < insetsFrameProviders.size(); j++) {
                pw.println(prefix + insetPrefix + insetsFrameProviders.valueAt(j));
            }

        }
    }

    ArraySet<InsetsFrameProvider> getInsets(int rootTaskStackId) {
        return mTaskIdToInsetFrameProviderMap.get(rootTaskStackId);
    }
}
