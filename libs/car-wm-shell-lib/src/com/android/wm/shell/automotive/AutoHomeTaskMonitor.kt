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

package com.android.wm.shell.automotive

import android.app.ActivityManager
import android.app.WindowConfiguration
import android.util.Slog
import android.window.WindowContainerTransaction
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.automotive.AutoTaskRepository.AutoAppTaskListener
import com.android.wm.shell.dagger.WMSingleton
import javax.inject.Inject

/**
 * A class to monitor the home task and ensure that necessary invariants are held for automotive
 * multi-window use-cases.
 * This is required for ScalableUI based auto systems where the visibility barrier is the Home
 * for now. Under low memory pressure / idle scenarios, the recents would trim home and the
 * visibilities of the root task won't work. This will prevent that behavior.
 *
 * TODO(b/432217693): Remove this once car-wm-shell has moved to a dedicated visibility barrier
 * which is not home.
 */
@WMSingleton
class AutoHomeTaskMonitor @Inject constructor(
    private val mAutoTaskRepository: AutoTaskRepository,
    private val mShellTaskOrganizer: ShellTaskOrganizer
) : AutoAppTaskListener, AutoShellInitializable {

    override fun initialize() {
        mAutoTaskRepository.addAppTaskListener(this)
        mShellTaskOrganizer.runningTasks.forEach(::setNonTrimmableIfHome)
    }

    override fun onTaskAppeared(taskInfo: ActivityManager.RunningTaskInfo) {
        setNonTrimmableIfHome(taskInfo)
    }

    override fun onTaskChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        // No-op
    }

    override fun onTaskVanished(taskInfo: ActivityManager.RunningTaskInfo) {
        // No-op
    }

    private fun setNonTrimmableIfHome(taskInfo: ActivityManager.RunningTaskInfo) {
        if (taskInfo.activityType != WindowConfiguration.ACTIVITY_TYPE_HOME) {
            return
        }
        Slog.d(
            AutoShellModule.AUTO_WM_SHELL,
            "Setting home task ${taskInfo.taskId} (${taskInfo.baseActivity}) to be " +
                    "non-trimmable by recents"
        )
        val wct = WindowContainerTransaction()
        wct.setTaskTrimmableFromRecents(taskInfo.token, /* isTrimmableFromRecents */false)
        mShellTaskOrganizer.applyTransaction(wct)
    }
}
