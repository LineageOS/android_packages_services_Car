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

package com.android.wm.shell.automotive.visibilitybarrier

import android.app.ActivityManager
import android.window.TaskCreationParams
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.automotive.AutoShellInitializable
import com.android.wm.shell.automotive.AutoTaskRepository
import com.android.wm.shell.automotive.CarWmShellProtoLogGroups.CAR_WM_SHELL_VISIBILITY_BARRIER
import com.android.wm.shell.automotive.Flags
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.dagger.WMSingleton
import com.android.wm.shell.shared.annotations.ShellMainThread
import javax.inject.Inject

/**
 * Controller to manage the visibility barrier for each display.
 *
 * The visibility barrier is an empty Shell-organized task. Siblings below the visibility barrier
 * are made invisible by the WindowManager. This provides a reliable mechanism to hide tasks without
 * needing to occlude them with a fullscreen visible activity.
 *
 * Note: A home activity can co-exist with the visibility barrier and either will not affect the
 * other.
 */
@WMSingleton
class AutoVisibilityBarrierController @Inject constructor(
    private val taskOrganizer: ShellTaskOrganizer,
    private val displayController: DisplayController,
    private val autoTaskRepository: AutoTaskRepository,
    @ShellMainThread private val shellMainThread: ShellExecutor
) : DisplayController.OnDisplaysChangedListener, AutoShellInitializable {

    // Listener for the Barrier Tasks created by this controller
    private val barrierTaskListener = object : ShellTaskOrganizer.TaskListener {
        override fun onTaskVanished(taskInfo: ActivityManager.RunningTaskInfo) {
            shellMainThread.execute {
                ProtoLog.i(
                    CAR_WM_SHELL_VISIBILITY_BARRIER,
                    "Visibility barrier task vanished for display %d",
                    taskInfo.displayId
                )
                autoTaskRepository.removeBarrierToken(taskInfo.displayId)
            }
        }
    }

    override fun initialize() {
        if (!Flags.enableAutoVisibilityBarrier()) {
            return
        }
        ProtoLog.i(CAR_WM_SHELL_VISIBILITY_BARRIER, "Initializing AutoVisibilityBarrierController")
        displayController.addDisplayWindowListener(this)
    }

    override fun onDisplayAdded(displayId: Int) {
        shellMainThread.execute {
            createBarrierForDisplay(displayId)
        }
    }

    override fun onDisplayRemoved(displayId: Int) {
        shellMainThread.execute {
            // Explicitly delete the task to ensure it's never migrated to another display for the
            // same user.
            autoTaskRepository.getBarrierToken(displayId)?.let { token ->
                ProtoLog.i(
                    CAR_WM_SHELL_VISIBILITY_BARRIER,
                    "Deleting visibility barrier for removed display %d",
                    displayId
                )
                taskOrganizer.deleteTask(token)
                autoTaskRepository.removeBarrierToken(displayId)
            }
        }
    }

    private fun createBarrierForDisplay(displayId: Int) {
        if (autoTaskRepository.getBarrierToken(displayId) != null) {
            ProtoLog.e(
                CAR_WM_SHELL_VISIBILITY_BARRIER,
                "Barrier already exists for display %d. Skipping creation.",
                displayId
            )
            return
        }

        ProtoLog.i(
            CAR_WM_SHELL_VISIBILITY_BARRIER,
            "Requesting visibility barrier for display %d",
            displayId
        )
        val params = TaskCreationParams.Builder()
            .setName("visibility_barrier-display$displayId")
            .setDisplayId(displayId)
            .setVisibilityBarrier(true)
            .build()

        val taskAppearedInfo = taskOrganizer.createTask(params, barrierTaskListener)
        if (taskAppearedInfo != null) {
            val taskInfo = taskAppearedInfo.taskInfo
            autoTaskRepository.setBarrierToken(displayId, taskInfo.token)
            ProtoLog.i(
                CAR_WM_SHELL_VISIBILITY_BARRIER,
                "Visibility barrier task created for display %d, taskId %d",
                displayId,
                taskInfo.taskId
            )
            setupVisibilityBarrierTask(taskInfo.token)
        } else {
            ProtoLog.e(
                CAR_WM_SHELL_VISIBILITY_BARRIER,
                "Failed to create visibility barrier task for display %d",
                displayId
            )
        }
    }

    private fun setupVisibilityBarrierTask(token: WindowContainerToken) {
        val wct = WindowContainerTransaction()
        wct.setTaskForceExcludedFromRecents(token, true /* forceExcluded */)
        wct.setForceTranslucent(token, true /* forceTranslucent */)
        wct.setFocusable(token, false /* focusable */)
        wct.reorder(token, false /* onTop */)
        wct.setTaskTrimmableFromRecents(token, false /* isTrimmableFromRecents */)
        taskOrganizer.applyTransaction(wct)
    }
}
