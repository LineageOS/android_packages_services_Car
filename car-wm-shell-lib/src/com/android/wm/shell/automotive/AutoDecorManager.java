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

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.util.ArraySet;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;

import com.android.server.utils.Slogf;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.shared.annotations.ShellMainThread;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Manages the creation, addition, and deletion of AutoDecor objects.
 *
 * <p>AutoDecor objects are used to decorate surfaces, providing control over
 * their bounds, Z-order, and associated view.
 */
@WMSingleton
public class AutoDecorManager {
    private static final String TAG = "AutoDecorManager";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
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
    public AutoDecor createAutoDecor(View view, int initialZOrder, Rect initialBounds,
            String decorName) {
        if (!enableAutoTaskStackController()) {
            Slogf.e(TAG,
                    "Failed to create root task stack as the auto_task_stack_windowing TS flag is"
                            + " disabled.");
            return null;
        }

        AutoDecor autoDecor = new AutoDecor(mContext, mDisplayController, mAutoTaskRepository,
                 view, initialZOrder, initialBounds, decorName);
        if (DBG) {
            Slogf.d(TAG, "Creating auto decor %s", autoDecor);
        }

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
    public void attachAutoDecorToDisplay(AutoDecor autoDecor,
            int displayId) {
        if (!enableAutoTaskStackController()) {
            Slogf.e(TAG,
                    "Failed to create root task stack as the auto_task_stack_windowing TS flag is"
                            + " disabled.");
            return;
        }

        if (DBG) {
            Slogf.d(TAG, "Adding global decor %s to the display %d", autoDecor, displayId);
        }

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
     * @param taskId task where decor needs to be added. The task could be root task.
     */
    @ShellMainThread
    public void attachAutoDecorToTask(AutoDecor autoDecor, int taskId) {
        if (!enableAutoTaskStackController()) {
            Slogf.e(TAG,
                    "Failed to create root task stack as the auto_task_stack_windowing TS flag is"
                            + " disabled.");
            return;
        }

        if (DBG) {
            Slogf.d(TAG, "Adding local decor %s to the task %d", autoDecor, taskId);
        }

        ActivityManager.RunningTaskInfo taskInfo = mAutoTaskRepository.getTaskInfo(taskId);
        validateAutoDecor(autoDecor);

        autoDecor.attachDecorToTask(taskInfo);
    }

    private void validateAutoDecor(AutoDecor autoDecor) {
        if (autoDecor == null) {
            throw new IllegalArgumentException("Invalid AutoDecor argument");
        }

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
    public void removeAutoDecor(AutoDecor autoDecor) {
        if (!enableAutoTaskStackController()) {
            Slogf.e(TAG,
                    "Failed to create root task stack as the auto_task_stack_windowing TS flag is"
                            + " disabled.");
            return;
        }
        if (DBG) {
            Slogf.d(TAG, "Deleting decor %s", autoDecor);
        }
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
