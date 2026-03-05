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
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import android.view.Display.DEFAULT_DISPLAY
import android.view.SurfaceControl
import android.window.TaskAppearedInfo
import android.window.TaskCreationParams
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.automotive.CarWmShellTestCase
import com.android.wm.shell.automotive.Flags
import com.android.wm.shell.automotive.utility.TestRunningTaskInfoBuilder
import com.android.wm.shell.automotive.utility.TestShellExecutor
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.ShellExecutor
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.argumentCaptor
import org.mockito.Mockito.`when` as whenever

/**
 * Unit tests for [AutoVisibilityBarrierController].
 *
 * Build/Install/Run:
 *  atest CarWMShellUnitTests:AutoVisibilityBarrierControllerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class AutoVisibilityBarrierControllerTest : CarWmShellTestCase() {

    @Rule
    @JvmField
    val setFlagsRule = SetFlagsRule()

    @Mock
    private lateinit var taskOrganizer: ShellTaskOrganizer

    @Mock
    private lateinit var displayController: DisplayController

    private val shellMainThread: ShellExecutor = TestShellExecutor()

    private lateinit var controller: AutoVisibilityBarrierController

    @Before
    fun setUp() {
        controller = AutoVisibilityBarrierController(
            taskOrganizer,
            displayController,
            shellMainThread
        )
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_AUTO_VISIBILITY_BARRIER)
    fun initialize_registersDisplayWindowListener() {
        // Act
        controller.initialize()

        // Verify
        verify(displayController).addDisplayWindowListener(controller)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_AUTO_VISIBILITY_BARRIER)
    fun onDisplayAdded_createsBarrierTask() {
        // Arrange
        controller.initialize()

        // Act
        controller.onDisplayAdded(DEFAULT_DISPLAY)

        // Verify
        val params = argumentCaptor<TaskCreationParams>().let { paramsCaptor ->
            verify(taskOrganizer).createTask(paramsCaptor.capture(), any())
            paramsCaptor.firstValue
        }
        assertThat(params.displayId).isEqualTo(DEFAULT_DISPLAY)
        assertThat(params.name).isEqualTo("visibility_barrier-display$DEFAULT_DISPLAY")
        assertThat(params.isVisibilityBarrier).isTrue()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_AUTO_VISIBILITY_BARRIER)
    fun onDisplayAdded_configuresBarrierTask() {
        // Arrange
        val taskId = 100
        val leash = mock(SurfaceControl::class.java)
        val taskInfo = createMockTaskInfo(DEFAULT_DISPLAY, taskId)
        setupTaskOrganizerMock(taskInfo, leash)
        controller.initialize()

        // Act
        controller.onDisplayAdded(DEFAULT_DISPLAY)

        // Verify
        verifyVisibilityBarrierConfiguration(taskInfo.token)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_AUTO_VISIBILITY_BARRIER)
    fun getBarrierToken_returnsCorrectToken() {
        // Arrange
        val taskId = 101
        val leash = mock(SurfaceControl::class.java)
        val taskInfo = createMockTaskInfo(DEFAULT_DISPLAY, taskId)
        setupTaskOrganizerMock(taskInfo, leash)
        controller.initialize()
        controller.onDisplayAdded(DEFAULT_DISPLAY)

        // Act
        val resultToken = controller.getBarrierToken(DEFAULT_DISPLAY)

        // Verify
        assertThat(resultToken).isEqualTo(taskInfo.token)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_AUTO_VISIBILITY_BARRIER)
    fun onDisplayRemoved_deletesBarrierTask() {
        // Arrange
        val taskId = 102
        val leash = mock(SurfaceControl::class.java)
        val taskInfo = createMockTaskInfo(DEFAULT_DISPLAY, taskId)
        setupTaskOrganizerMock(taskInfo, leash)
        controller.initialize()
        controller.onDisplayAdded(DEFAULT_DISPLAY)

        // Act
        controller.onDisplayRemoved(DEFAULT_DISPLAY)

        // Verify
        verify(taskOrganizer).deleteTask(taskInfo.token)
        assertThat(controller.getBarrierToken(DEFAULT_DISPLAY)).isNull()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_AUTO_VISIBILITY_BARRIER)
    fun onTaskVanished_removesBarrierFromMap() {
        // Arrange
        val taskId = 103
        val leash = mock(SurfaceControl::class.java)
        val taskInfo = createMockTaskInfo(DEFAULT_DISPLAY, taskId)
        val listener = setupTaskOrganizerMock(taskInfo, leash)
        controller.initialize()
        controller.onDisplayAdded(DEFAULT_DISPLAY)

        // Act
        listener.onTaskVanished(taskInfo)

        // Verify
        assertThat(controller.getBarrierToken(DEFAULT_DISPLAY)).isNull()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_AUTO_VISIBILITY_BARRIER)
    fun onDisplayAdded_barrierAlreadyInMap_skipsCreation() {
        // Arrange
        val taskId = 105
        val leash = mock(SurfaceControl::class.java)
        val taskInfo = createMockTaskInfo(DEFAULT_DISPLAY, taskId)
        setupTaskOrganizerMock(taskInfo, leash)
        controller.initialize()
        // First addition: creates the barrier and populates the map
        controller.onDisplayAdded(DEFAULT_DISPLAY)

        // Act: Try adding the same display again
        controller.onDisplayAdded(DEFAULT_DISPLAY)

        // Verify: createTask is NOT called a second time
        verify(taskOrganizer, org.mockito.Mockito.times(1)).createTask(any(), any())
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_AUTO_VISIBILITY_BARRIER)
    fun createBarrier_duplicate_skipped() {
        // Arrange
        val taskId = 104
        val leash = mock(SurfaceControl::class.java)
        val taskInfo = createMockTaskInfo(DEFAULT_DISPLAY, taskId)
        setupTaskOrganizerMock(taskInfo, leash)
        controller.initialize()
        controller.onDisplayAdded(DEFAULT_DISPLAY)

        // Act
        controller.onDisplayAdded(DEFAULT_DISPLAY)

        // Verify createTask is only called once during the initial onDisplayAdded
        verify(taskOrganizer).createTask(any(), any())
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_AUTO_VISIBILITY_BARRIER)
    fun initialize_flagDisabled_doesNotRegisterListener() {
        // Act
        controller.initialize()

        // Verify
        verify(displayController, org.mockito.Mockito.never()).addDisplayWindowListener(any())
    }

    private fun createMockTaskInfo(displayId: Int, taskId: Int): ActivityManager.RunningTaskInfo {
        return TestRunningTaskInfoBuilder()
            .setTaskId(taskId)
            .setDisplayId(displayId)
            .build()
    }

    private fun setupTaskOrganizerMock(
        taskInfo: ActivityManager.RunningTaskInfo,
        leash: SurfaceControl
    ): ShellTaskOrganizer.TaskListener {
        var capturedListener: ShellTaskOrganizer.TaskListener? = null
        whenever(taskOrganizer.createTask(any(), any())).thenAnswer {
            capturedListener = it.arguments[1] as ShellTaskOrganizer.TaskListener
            TaskAppearedInfo(taskInfo, leash)
        }
        return object : ShellTaskOrganizer.TaskListener {
            override fun onTaskAppeared(
                info: ActivityManager.RunningTaskInfo,
                leash: SurfaceControl
            ) {
                capturedListener?.onTaskAppeared(info, leash)
            }

            override fun onTaskVanished(info: ActivityManager.RunningTaskInfo) {
                capturedListener?.onTaskVanished(info)
            }
        }
    }

    private fun verifyVisibilityBarrierConfiguration(token: WindowContainerToken) {
        val binder = token.asBinder()
        val wct = argumentCaptor<WindowContainerTransaction>().let { wctCaptor ->
            verify(taskOrganizer).applyTransaction(wctCaptor.capture())
            wctCaptor.firstValue
        }

        // Verify hierarchy ops
        assertThat(wct.hierarchyOps.map { it.type }).contains(HIERARCHY_OP_TYPE_REORDER)
        val reorderOp = wct.hierarchyOps.find { it.type == HIERARCHY_OP_TYPE_REORDER }!!
        assertThat(reorderOp.container).isEqualTo(binder)
        assertThat(reorderOp.toTop).isFalse()

        // Verify changes
        assertThat(wct.changes[binder]).isNotNull()
        val change = wct.changes[binder]!!
        assertThat(change.forceExcludedFromRecents).isTrue()
        assertThat(change.forceTranslucent).isTrue()
        assertThat(change.focusable).isFalse()
    }
}
