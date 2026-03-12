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
import static com.android.wm.shell.automotive.CarWmShellProtoLogGroups.CAR_WM_SHELL_DECOR;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.util.ArraySet;
import android.view.SurfaceControl;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.protolog.ProtoLog;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.shared.annotations.ShellMainThread;

import java.io.PrintWriter;
import java.util.Objects;

import javax.inject.Inject;

/**
 * Manages the creation, addition, and deletion of AutoDecor objects.
 *
 * <p>AutoDecor objects are used to decorate surfaces, providing control over
 * their bounds, Z-order, and associated view.
 */
@WMSingleton
public class AutoDecorManager {
    private final Context mContext;
    private final DisplayController mDisplayController;
    private final ArraySet<AutoDecor> mDecors = new ArraySet<>();
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final AutoTaskRepository mAutoTaskRepository;

    @Inject
    AutoDecorManager(Context context, DisplayController displayController,
            RootTaskDisplayAreaOrganizer rootTdaOrganizer,
            AutoTaskRepository autoTaskRepository) {
        mContext = context;
        mDisplayController = displayController;
        mRootTaskDisplayAreaOrganizer = rootTdaOrganizer;
        mAutoTaskRepository = autoTaskRepository;
    }

    /**
     * Creates a new AutoDecor object.
     *
     * @param view          The view associated with the AutoDecor.
     * @param initialZOrder The Z-order of the AutoDecor. If the decor is attached to the
     *                      display, it would be attached to default task display area and Z
     *                      layer will be resolved at the default Task display area level. If the
     *                      decor is attached to the task, then Z layer resolution will happen at
     *                      the task level.
     * @param initialBounds The bounds of the AutoDecor. The bounds are relative to the display
     *                      if the decor is used as global decor and decor is attached to the
     *                      default task display area. If the decor is attached to the task, then
     *                      the bounds are related to the task. So bounds are relative to the
     *                      surface it is being attached.
     * @param decorName     AutoDecor name for debugging and identification
     * @return The newly created AutoDecor object, or null if creation failed.
     */
    @ShellMainThread
    @Nullable
    public AutoDecor createAutoDecor(@NonNull View view, int initialZOrder,
            @NonNull Rect initialBounds,
            String decorName) {
        Objects.requireNonNull(view);
        Objects.requireNonNull(initialBounds);

        if (!enableAutoTaskStackController()) {
            ProtoLog.e(CAR_WM_SHELL_DECOR,
                    "Failed to create root task stack as the auto_task_stack_windowing TS flag is"
                            + " disabled.");
            return null;
        }

        if (initialBounds.width() <= 0 || initialBounds.height() <= 0) {
            ProtoLog.e(CAR_WM_SHELL_DECOR,
                    "initialBounds [%s] are not correct. Can't create AutoDecor",
                    String.valueOf(initialBounds));
            return null;
        }

        AutoDecor autoDecor = new AutoDecor(mContext, mDisplayController, mAutoTaskRepository,
                view, initialZOrder, initialBounds, decorName);

        ProtoLog.d(CAR_WM_SHELL_DECOR, "Creating auto decor %s", autoDecor);

        mDecors.add(autoDecor);
        return autoDecor;
    }

    /**
     * Adds an AutoDecor to the Default Task display Area of the given display.
     *
     * @param autoDecor The AutoDecor to add.
     * @param displayId display where decor needs to be added.
     */
    @ShellMainThread
    public void attachAutoDecorToDisplay(@NonNull AutoDecor autoDecor,
            int displayId) {
        Objects.requireNonNull(autoDecor);

        if (!enableAutoTaskStackController()) {
            ProtoLog.e(CAR_WM_SHELL_DECOR,
                    "Failed to create root task stack as the auto_task_stack_windowing TS flag is"
                            + " disabled.");
            return;
        }

        ProtoLog.d(CAR_WM_SHELL_DECOR, "Adding global decor %s to the display %d", autoDecor,
                displayId);

        validateAutoDecor(autoDecor);

        SurfaceControl parentSurface = mRootTaskDisplayAreaOrganizer.getDisplayAreaLeash(
                displayId);
        autoDecor.attachDecorToParentSurface(displayId, parentSurface);
    }

    /**
     * Adds an AutoDecor to the specific task.
     *
     * <p>The Decor surface is re-parented to the task. No inset is passed to the task.
     *
     * @param autoDecor The AutoDecor to add.
     * @param taskId    task where decor needs to be added. The task could be root task.
     */
    @ShellMainThread
    public void attachAutoDecorToTask(@NonNull AutoDecor autoDecor, int taskId) {
        Objects.requireNonNull(autoDecor);

        if (!enableAutoTaskStackController()) {
            ProtoLog.e(CAR_WM_SHELL_DECOR,
                    "Failed to create root task stack as the auto_task_stack_windowing TS flag is"
                            + " disabled.");
            return;
        }

        ProtoLog.d(CAR_WM_SHELL_DECOR, "Adding local decor %s to the task %d", autoDecor, taskId);

        ActivityManager.RunningTaskInfo taskInfo = mAutoTaskRepository.getTaskInfo(taskId);
        validateAutoDecor(autoDecor);

        autoDecor.attachDecorToTask(taskInfo);
    }

    private void validateAutoDecor(AutoDecor autoDecor) {
        if (!mDecors.contains(autoDecor)) {
            throw new IllegalArgumentException(
                    "Invalid AutoDecor argument. The Decor has been deleted previously. Create "
                            + "new Decor using createAutoDecor call.");
        }

        if (autoDecor.isEverAttached()) {
            throw new IllegalArgumentException(
                    "AutoDecor has already been added to Task Display area. To update the "
                            + "AutoDecor, use AutoDecor APIs.");
        }
    }

    /**
     * Deletes an AutoDecor from the manager.
     *
     * @param autoDecor The AutoDecor to remove.
     */
    @ShellMainThread
    public void removeAutoDecor(@NonNull AutoDecor autoDecor) {
        Objects.requireNonNull(autoDecor);

        if (!enableAutoTaskStackController()) {
            ProtoLog.e(CAR_WM_SHELL_DECOR,
                    "Failed to create root task stack as the auto_task_stack_windowing TS flag is"
                            + " disabled.");
            return;
        }

        ProtoLog.d(CAR_WM_SHELL_DECOR, "Deleting decor %s", autoDecor);
        autoDecor.detachDecorFromParentSurface();
        mDecors.remove(autoDecor);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "AutoDecorManager:");
        pw.println(prefix + "Total Decors: " + mDecors.size());
        for (AutoDecor autoDecor : mDecors) {
            autoDecor.dump(pw, prefix);
        }
    }
}
