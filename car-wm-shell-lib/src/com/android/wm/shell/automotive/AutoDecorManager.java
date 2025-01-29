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

import android.content.Context;
import android.graphics.Rect;
import android.util.ArraySet;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;

import com.android.server.utils.Slogf;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.shared.annotations.ShellMainThread;

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
    private final ArraySet<AutoDecorImpl> mDecors = new ArraySet<>();
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final ShellExecutor mShellExecutor;

    @Inject
    AutoDecorManager(Context context, DisplayController displayController,
            RootTaskDisplayAreaOrganizer rootTdaOrganizer,
            @ShellMainThread ShellExecutor shellExecutor) {
        mContext = context;
        mDisplayController = displayController;
        mRootTaskDisplayAreaOrganizer = rootTdaOrganizer;
        mShellExecutor = shellExecutor;
    }

    /**
     * Creates a new AutoDecor object.
     *
     * @param view          The view associated with the AutoDecor.
     * @param initialZOrder The Z-order of the AutoDecor.
     * @param initialBounds The bounds of the AutoDecor.
     * @return The newly created AutoDecor object, or null if creation failed.
     */
    @ShellMainThread
    public AutoDecor createAutoDecor(View view, int initialZOrder, Rect initialBounds) {
        if (!enableAutoTaskStackController()) {
            Slogf.e(TAG,
                    "Failed to create root task stack as the auto_task_stack_windowing TS flag is"
                            + " disabled.");
            return null;
        }

        AutoDecorImpl autoDecorImpl = new AutoDecorImpl(mContext, mDisplayController, view,
                initialZOrder, initialBounds, mShellExecutor);
        if (DBG) {
            Slogf.d(TAG, "Creating auto decor %s", autoDecorImpl);
        }

        mDecors.add(autoDecorImpl);
        return autoDecorImpl;
    }

    /**
     * Adds a AutoDecor to the Default Task display Area of the given display.
     *
     * @param t         The transaction to apply the addition to.
     * @param autoDecor The AutoDecor to add.
     * @param displayId display where decor needs to be added.
     */
    @ShellMainThread
    public void attachAutoDecorToDisplay(SurfaceControl.Transaction t, AutoDecor autoDecor,
            int displayId) {
        if (!enableAutoTaskStackController()) {
            Slogf.e(TAG,
                    "Failed to create root task stack as the auto_task_stack_windowing TS flag is"
                            + " disabled.");
            return;
        }

        AutoDecorImpl autoDecorImpl = (AutoDecorImpl) autoDecor;

        if (DBG) {
            Slogf.d(TAG, "Adding global decor %s to the display %d", autoDecorImpl, displayId);
        }

        if (autoDecorImpl == null) {
            throw new IllegalArgumentException("Invalid AutoDecor argument");
        }

        if (!mDecors.contains(autoDecorImpl)) {
            throw new IllegalArgumentException(
                    "Invalid AutoDecor argument. The Decor has been deleted previously. Create "
                            + "new Decor using createAutoDecor call.");
        }

        if (autoDecorImpl.isAttached()) {
            throw new IllegalArgumentException(
                    "AutoDecor has already been added to Task Display area. To update the "
                            + "AutoDecor, use AutoDecor APIs.");
        }

        SurfaceControl parentSurface = mRootTaskDisplayAreaOrganizer.getDisplayAreaLeash(
                displayId);
        autoDecorImpl.attachDecorToParentSurface(t, displayId, parentSurface);
    }

    /**
     * Deletes an AutoDecor from the manager.
     *
     * @param t         The transaction to apply the removal to.
     * @param autoDecor The AutoDecor to remove.
     */
    @ShellMainThread
    public void removeAutoDecor(SurfaceControl.Transaction t, AutoDecor autoDecor) {
        if (!enableAutoTaskStackController()) {
            Slogf.e(TAG,
                    "Failed to create root task stack as the auto_task_stack_windowing TS flag is"
                            + " disabled.");
            return;
        }

        AutoDecorImpl autoDecorImpl = (AutoDecorImpl) autoDecor;
        if (DBG) {
            Slogf.d(TAG, "Deleting decor %s", autoDecorImpl);
        }
        autoDecorImpl.detachDecorFromParentSurface(t);
        mDecors.remove(autoDecorImpl);
    }
}
