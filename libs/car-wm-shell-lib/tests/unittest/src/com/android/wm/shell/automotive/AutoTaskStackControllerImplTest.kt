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

import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityTaskManager.INVALID_TASK_ID
import android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT
import android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.graphics.Rect
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.testing.AndroidTestingRunner
import android.view.Display.DEFAULT_DISPLAY
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_BACK
import android.window.TaskAppearedInfo
import android.window.TaskCreationParams
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REPARENT
import android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT
import androidx.test.filters.SmallTest
import com.android.testing.wm.util.TransitionInfoBuilder
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTaskOrganizer.TaskListener
import com.android.wm.shell.automotive.utility.TestRunningTaskInfoBuilder
import com.android.wm.shell.automotive.utility.TestShellExecutor
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TransitionFinishCallback
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AutoTaskStackControllerImplTest : CarWmShellTestCase() {

    @Mock
    lateinit var taskOrganizer: ShellTaskOrganizer

    var shellMainThread: ShellExecutor = TestShellExecutor()

    @Mock
    lateinit var transitions: Transitions

    @Mock
    lateinit var rootTdaOrganizer: RootTaskDisplayAreaOrganizer

    @Mock
    lateinit var rootTaskStackListener: RootTaskStackListener

    @Mock
    lateinit var mAutoTaskRepository: AutoTaskRepository
    @Mock
    lateinit var mAutoWmShellCommandHandler: AutoWmShellCommandHandler

    var mMainThreadHandler: Handler? = null

    private lateinit var controller: AutoTaskStackControllerImpl
    private val displayId = 0
    private val delegate = TestAutoTaskStackTransitionHandlerDelegate()

    class TestAutoTaskStackTransitionHandlerDelegate :
        AutoTaskStackTransitionHandlerDelegate {
        var lastStartTransaction: SurfaceControl.Transaction? = null
        var lastFinishTransaction: SurfaceControl.Transaction? = null
        var lastTaskStackStates: List<TaskStackStateChange>? = null
        var handleRequestReturn: AutoTaskStackTransaction? = null
        var play = true
        var startAnimationCalled = false
        var onTransitionConsumedCalled = false
        var mergeAnimationCalled = false

        override fun handleRequest(
            transition: IBinder,
            request: TransitionRequestInfo
        ): AutoTaskStackTransaction? {
            return handleRequestReturn
        }

        override fun startAnimation(
            transition: IBinder,
            changedTaskStacks: List<TaskStackStateChange>,
            info: TransitionInfo,
            startTransaction: SurfaceControl.Transaction,
            finishTransaction: SurfaceControl.Transaction,
            finishCallback: TransitionFinishCallback
        ): Boolean {
            startAnimationCalled = true
            lastStartTransaction = startTransaction
            lastFinishTransaction = finishTransaction
            lastTaskStackStates = changedTaskStacks
            return play
        }

        override fun onTransitionConsumed(
            transition: IBinder,
            changedTaskStacks: List<TaskStackStateChange>,
            aborted: Boolean,
            finishTransaction: SurfaceControl.Transaction?
        ) {
            onTransitionConsumedCalled = true
        }

        override fun mergeAnimation(
            transition: IBinder,
            changedTaskStacks: List<TaskStackStateChange>,
            info: TransitionInfo,
            surfaceTransaction: SurfaceControl.Transaction,
            mergeTarget: IBinder,
            finishCallback: TransitionFinishCallback
        ) {
            mergeAnimationCalled = true
        }
    }

    private fun setupRootTask(
        taskId: Int,
        leash: SurfaceControl = mock(SurfaceControl::class.java),
        task: RunningTaskInfo? = null,
        name: String = ""
    ): Pair<RunningTaskInfo, TaskListener> {
        val taskInfo = task ?: let {
            TestRunningTaskInfoBuilder()
                .setTaskId(taskId).setDisplayId(displayId).build()
        }
        var listener: TaskListener? = null
        whenever(
            taskOrganizer.createTask(
                any(TaskCreationParams::class.java),
                any(TaskListener::class.java)
            )
        ).thenAnswer {
            listener = it.arguments[1] as ShellTaskOrganizer.TaskListener
            listener!!.onTaskAppeared(taskInfo, leash)
            return@thenAnswer TaskAppearedInfo(taskInfo, leash)
        }
        controller.createRootTaskStack(displayId, name, rootTaskStackListener)
        return Pair(taskInfo, listener!!)
    }

    private fun setupChildTask(
        taskId: Int,
        parentTaskId: Int = -1,
        parentTaskListener: TaskListener,
        leash: SurfaceControl = mock(SurfaceControl::class.java),
        task: RunningTaskInfo? = null,
    ): RunningTaskInfo {
        val taskInfo = task ?: let {
            TestRunningTaskInfoBuilder()
                .setTaskId(taskId)
                .setParentTaskId(parentTaskId)
                .setDisplayId(displayId).build()
        }
        parentTaskListener.onTaskAppeared(taskInfo, leash)
        return taskInfo
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        controller = AutoTaskStackControllerImpl(
            taskOrganizer,
            shellMainThread,
            transitions,
            rootTdaOrganizer,
            context,
            mAutoTaskRepository,
            mAutoWmShellCommandHandler
        )
        controller.initialize()
        mMainThreadHandler = Handler(Looper.getMainLooper())

        controller.autoTransitionHandlerDelegate = delegate
    }

    @Test
    fun createRootTask_rootTaskAppearedBefore_callsOnTaskStackAppeared() {
        // Arrange
        val taskInfo =
            TestRunningTaskInfoBuilder()
                .setTaskId(32).setDisplayId(displayId).build()
        var listener: TaskListener? = null
        whenever(
            taskOrganizer.createTask(
                any(TaskCreationParams::class.java),
                any(TaskListener::class.java)
            )
        ).thenAnswer {
            listener = it.arguments[1] as ShellTaskOrganizer.TaskListener
            val mockLeash = mock(SurfaceControl::class.java)
            listener!!.onTaskAppeared(taskInfo, mockLeash)
            return@thenAnswer TaskAppearedInfo(taskInfo, mockLeash)
        }
        val name = ""

        // Act
        controller.createRootTaskStack(displayId, name, rootTaskStackListener)

        // Assert
        val captor = argumentCaptor<RootTaskStack>()
        verify(rootTaskStackListener).onRootTaskStackAppeared(captor.capture())
        verify(mAutoTaskRepository).onRootTaskStackAppeared(captor.capture())
        verify(mAutoTaskRepository).onRootTaskStackCreated(captor.capture())
        val taskStack = captor.firstValue
        assertThat(taskStack.id).isEqualTo(32)
    }

    @Test
    fun createRootTask_rootTaskAppearedAfter_callsOnTaskStackAppeared() {
        // Arrange
        val taskInfo =
            TestRunningTaskInfoBuilder()
                .setTaskId(32).setDisplayId(displayId).build()
        var listener: TaskListener?
        whenever(
            taskOrganizer.createTask(
                any(TaskCreationParams::class.java),
                any(TaskListener::class.java)
            )
        ).thenAnswer {
            listener = it.arguments[1] as ShellTaskOrganizer.TaskListener
            val mockLeash = mock(SurfaceControl::class.java)
            mMainThreadHandler!!.post {
                listener.onTaskAppeared(taskInfo, mockLeash)
            }
            return@thenAnswer TaskAppearedInfo(taskInfo, mockLeash)
        }
        val name = ""

        // Act
        controller.createRootTaskStack(displayId, name, rootTaskStackListener)
        waitForMainThread()

        // Assert
        val captor = argumentCaptor<RootTaskStack>()
        verify(rootTaskStackListener).onRootTaskStackAppeared(captor.capture())
        verify(mAutoTaskRepository).onRootTaskStackAppeared(captor.capture())
        verify(mAutoTaskRepository).onRootTaskStackCreated(captor.capture())
        val taskStack = captor.firstValue
        assertThat(taskStack.id).isEqualTo(32)
    }

    @Test
    fun rootTaskInfoChanged_callsOnTaskStackInfoChanged() {
        // Arrange
        val (taskInfo, taskListener) = setupRootTask(taskId = 12)

        // Act
        val newTaskInfo = TestRunningTaskInfoBuilder()
            .setDisplayId(displayId)
            .setTaskId(12)
            .setVisible(true)
            .build()
        taskListener.onTaskInfoChanged(newTaskInfo)

        // Assert
        val captor = argumentCaptor<RootTaskStack>()
        verify(rootTaskStackListener).onRootTaskStackInfoChanged(captor.capture())
        assertThat(captor.firstValue.rootTaskInfo.topActivity).isEqualTo(newTaskInfo.topActivity)
    }

    @Test
    fun rootTaskVanished_callsOnTaskStackDestroyed() {
        // Arrange
        val (taskInfo, taskListener) = setupRootTask(taskId = 12)

        // Act
        taskListener.onTaskVanished(taskInfo)

        // Assert
        verify(rootTaskStackListener).onRootTaskStackDestroyed(anyOrNull())
        assertThat(controller.taskStackStateMap[12]).isNull()
    }

    @Test
    fun destroyTaskStack_clearsStateAndCallsOnTaskStackDestroyed() {
        // Arrange
        val (taskInfo, taskListener) = setupRootTask(taskId = 12)
        whenever(
            taskOrganizer.deleteTask(any(WindowContainerToken::class.java))
        ).thenAnswer {
            taskListener.onTaskVanished(taskInfo)
            true
        }

        // Act
        controller.destroyTaskStack(taskStackId = 12)

        // Assert
        verify(rootTaskStackListener).onRootTaskStackDestroyed(anyOrNull())
        assertThat(controller.taskStackStateMap[taskInfo.taskId]).isNull()
    }

    @Test
    fun createRootTask_childTaskAppeared_callsOnTaskAppeared() {
        // Arrange
        val leash = mock(SurfaceControl::class.java)
        val (taskInfo, taskListener) = setupRootTask(taskId = 14)

        // Act
        val childTaskInfo = TestRunningTaskInfoBuilder()
            .setParentTaskId(taskInfo.taskId)
            .setTaskId(101).build()
        taskListener.onTaskAppeared(childTaskInfo, leash)

        // Assert
        verify(rootTaskStackListener).onTaskAppeared(eq(childTaskInfo), eq(leash))
    }

    @Test
    fun createRootTask_childTaskInfoChanged_callsOnTaskInfoChanged() {
        // Arrange
        val (taskInfo, taskListener) = setupRootTask(taskId = 15)
        setupChildTask(taskId = 101, parentTaskListener = taskListener)

        // Act
        val newChildTaskInfo = TestRunningTaskInfoBuilder()
            .setParentTaskId(taskInfo.taskId)
            .setTaskId(101)
            .setVisible(false)
            .build()
        taskListener.onTaskInfoChanged(newChildTaskInfo)

        // Assert
        verify(rootTaskStackListener).onTaskInfoChanged(eq(newChildTaskInfo))
    }

    @Test
    fun createRootTask_childTaskVanished_callsOnTaskVanished() {
        // Arrange
        val (taskInfo, taskListener) = setupRootTask(taskId = 23)
        val childTaskInfo = setupChildTask(taskId = 102, parentTaskListener = taskListener)

        // Act
        taskListener.onTaskVanished(childTaskInfo)

        // Assert
        verify(rootTaskStackListener).onTaskVanished(eq(childTaskInfo))
    }

    @Test
    fun setDefaultTaskStack_setsLaunchRoot() {
        // Arrange
        setupRootTask(taskId = 16)

        // Act
        controller.setDefaultRootTaskStackOnDisplay(displayId, rootTaskStackId = 16)

        // Assert
        val wctCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(taskOrganizer).applyTransaction(wctCaptor.capture())
        assertThat(wctCaptor.firstValue.isEmpty).isFalse()
        assertThat(wctCaptor.firstValue.hierarchyOps).hasSize(2)
        assertThat(wctCaptor.firstValue.hierarchyOps[0].type).isEqualTo(
            HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT
        )
    }

    @Test
    fun setDefaultTaskStack_null_clearsLaunchRoot() {
        // Arrange
        setupRootTask(taskId = 16)
        controller.setDefaultRootTaskStackOnDisplay(displayId, 16)

        // Act
        controller.setDefaultRootTaskStackOnDisplay(displayId, null)

        // Assert
        val wctCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(taskOrganizer, times(2)).applyTransaction(wctCaptor.capture())
        assertThat(wctCaptor.firstValue.isEmpty).isFalse()
        assertThat(wctCaptor.firstValue.hierarchyOps).hasSize(2)

        assertThat(wctCaptor.firstValue.hierarchyOps[0].type).isEqualTo(
            HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT
        )
        assertThat(wctCaptor.firstValue.hierarchyOps[0].windowingModes).isEqualTo(
            intArrayOf(
                WINDOWING_MODE_UNDEFINED,
            )
        )
        assertThat(wctCaptor.firstValue.hierarchyOps[0].activityTypes).isEqualTo(
            intArrayOf(
                ACTIVITY_TYPE_STANDARD,
                ACTIVITY_TYPE_UNDEFINED,
                ACTIVITY_TYPE_RECENTS,
                ACTIVITY_TYPE_ASSISTANT
            )
        )

        assertThat(wctCaptor.secondValue.hierarchyOps).hasSize(1)
        assertThat(wctCaptor.secondValue.hierarchyOps[0].type).isEqualTo(
            HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT
        )
        assertThat(wctCaptor.secondValue.hierarchyOps[0].windowingModes).isNull()
        assertThat(wctCaptor.secondValue.hierarchyOps[0].activityTypes).isNull()
    }

    @Test
    fun setDefaultTaskStack_rootTaskInexistent_NoOp() {
        controller.setDefaultRootTaskStackOnDisplay(displayId, 1)

        verify(taskOrganizer, never()).applyTransaction(anyOrNull())
    }

    private fun setupTransitionReply(transitionId: IBinder) {
        whenever(
            transitions.startTransition(
                anyInt(),
                any(WindowContainerTransaction::class.java),
                any(Transitions.TransitionHandler::class.java)
            )
        ).thenAnswer {
            mMainThreadHandler!!.post({
                controller.startAnimation(
                    transitionId,
                    TransitionInfo(1, 0),
                    mock(SurfaceControl.Transaction::class.java),
                    mock(SurfaceControl.Transaction::class.java),
                    {}
                )
            })
            transitionId
        }
    }

    @Test
    fun startTransition_withTaskStackStates_leadsToCorrectTranslationToWct() {
        // Arrange
        val (taskInfo, taskListener) = setupRootTask(taskId = 17)
        setupChildTask(taskId = 111, parentTaskListener = taskListener)

        val transitionId = Binder()
        setupTransitionReply(transitionId)

        // Act
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            taskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 10, 10), true, 0)
        )
        controller.startTransition(transaction)

        // Assert
        val wctCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(anyInt(), wctCaptor.capture(), anyOrNull())
        val wct = wctCaptor.firstValue
        val expected = WindowContainerTransaction()
            .setBounds(
                taskInfo.token,
                Rect(10, 10, 10, 10)
            )
            .reorder(taskInfo.token, true)
        assertThat(wct.toString()).isEqualTo(expected.toString())
    }

    @Test
    fun startTransition_withEmptyTransaction_returnsNull() {
        // Verifies that calling startTransition with an empty transaction returns null
        // and does not start a transition.

        // Arrange
        val transaction = AutoTaskStackTransaction()

        // Act
        val result = controller.startTransition(transaction)

        // Assert
        // Expect null because no operations were provided.
        assertThat(result).isNull()
        // Expect that no transition is started.
        verify(transitions, never()).startTransition(
            anyInt(),
            any(WindowContainerTransaction::class.java),
            any(Transitions.TransitionHandler::class.java)
        )
    }

    @Test
    fun startTransition_withTaskStackStates_leadsToCorrectStartAnimation() {
        // Arrange
        val leash = mock(SurfaceControl::class.java)
        val (rootTask, taskListener) = setupRootTask(taskId = 13, leash = leash)
        val startTransaction = mock(SurfaceControl.Transaction::class.java)
        val finishTransaction = mock(SurfaceControl.Transaction::class.java)
        val claim = Binder()
        val info = TransitionInfoBuilder(TRANSIT_OPEN)
            .addChange(TransitionInfo.Change(rootTask.token, leash).apply {
                taskInfo = rootTask
            })
            .build()

        whenever(
            transitions.startTransition(
                anyInt(),
                any(WindowContainerTransaction::class.java),
                any(Transitions.TransitionHandler::class.java)
            )
        ).thenAnswer {
            mMainThreadHandler!!.post({
                controller.startAnimation(
                    claim,
                    info,
                    startTransaction,
                    finishTransaction,
                    mock(TransitionFinishCallback::class.java)
                )
            })
            claim
        }

        // Act
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            rootTask.taskId,
            AutoTaskStackState(Rect(10, 10, 10, 10), true, 3)
        )
        controller.startTransition(transaction)!!
        waitForMainThread()

        // Assert
        assertThat(delegate.lastStartTransaction).isEqualTo(startTransaction)
        assertThat(delegate.lastFinishTransaction).isEqualTo(finishTransaction)
        assertThat(delegate.lastTaskStackStates).isEqualTo(
            transaction.getTaskStackStates()
                .map { (taskId, state) -> TaskStackStateChange(taskId = taskId, state = state) }
        )
    }

    private fun waitForMainThread() {
        runOnMainThreadAndBlock({})
    }

    /**
     * Posts the given Runnable on the main thread, and blocks the calling thread until it's run.
     */
    private fun runOnMainThreadAndBlock(action: Runnable) {
        val latch = CountDownLatch(1)
        mMainThreadHandler!!.post {
            action.run()
            latch.countDown()
        }
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    @Test
    fun startTransition_withTaskStackStates_reordersRootTaskLeashes() {
        // Arrange
        val leash13 = mock(SurfaceControl::class.java)
        val leash15 = mock(SurfaceControl::class.java)
        val (rootTask13, taskListener13) = setupRootTask(taskId = 13, leash = leash13)
        val (rootTask15, taskListener15) = setupRootTask(taskId = 15, leash = leash15)

        val tdaLeash = mock(SurfaceControl::class.java)
        val startTransaction = mock(SurfaceControl.Transaction::class.java)
        val finishTransaction = mock(SurfaceControl.Transaction::class.java)
        val claim = Binder()
        val info = TransitionInfoBuilder(TRANSIT_OPEN)
            .addChange(TransitionInfo.Change(rootTask13.token, leash13).apply {
                taskInfo = rootTask13
            })
            .addChange(TransitionInfo.Change(rootTask15.token, leash15).apply {
                taskInfo = rootTask15
            })
            .build()

        whenever(
            transitions.startTransition(
                anyInt(),
                any(WindowContainerTransaction::class.java),
                any(Transitions.TransitionHandler::class.java)
            )
        ).thenAnswer {
            mMainThreadHandler!!.post({
                controller.startAnimation(
                    claim,
                    info,
                    startTransaction,
                    finishTransaction,
                    mock(TransitionFinishCallback::class.java)
                )
            })
            claim
        }
        whenever(rootTdaOrganizer.getDisplayAreaLeash(anyInt())).thenReturn(tdaLeash)

        // Act
        val transaction = AutoTaskStackTransaction()
            .setTaskStackState(
                rootTask13.taskId,
                AutoTaskStackState(Rect(10, 10, 100, 100), true, 1)
            )
            .setTaskStackState(
                rootTask15.taskId,
                AutoTaskStackState(Rect(10, 20, 400, 400), true, 3)
            )
        controller.startTransition(transaction)!!
        waitForMainThread()

        // Assert
        verify(startTransaction).setLayer(leash13, 1)
        verify(startTransaction).setLayer(leash15, 3)

        verify(finishTransaction).setLayer(leash13, 1)
        verify(finishTransaction).setLayer(leash15, 3)
    }

    @Test
    fun transitionFromCore_delegateWithEmptyOperations_handledInternally() {
        // Arrange
        val transition = mock(IBinder::class.java)
        val request = mock(TransitionRequestInfo::class.java)
        delegate.handleRequestReturn = AutoTaskStackTransaction()

        // Act
        val result = controller.handleRequest(transition, request)

        // Assert
        assertThat(result).isNotNull()
    }

    @Test
    fun startTransition_withSetSafeRegionBounds_leadsToCorrectTranslationToWct() {
        // Arrange
        val (taskInfo, _) = setupRootTask(taskId = 101)
        val safeRegionBounds = Rect(10, 20, 30, 40)
        val transitionId = Binder()
        setupTransitionReply(transitionId)

        // Act
        val autoTransaction = AutoTaskStackTransaction()
            .setSafeRegionBounds(taskInfo.taskId, safeRegionBounds)
        controller.startTransition(autoTransaction)

        // Assert
        val wctCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(anyInt(), wctCaptor.capture(), anyOrNull())
        val wct = wctCaptor.firstValue
        val expectedWct = WindowContainerTransaction()
            .setSafeRegionBounds(taskInfo.token, safeRegionBounds)
        assertThat(wct.toString()).isEqualTo(expectedWct.toString())
    }

    @Test
    fun startTransition_withSetSafeRegionBounds_replacesExistingOpInTransaction() {
        // Arrange
        val (taskInfo, _) = setupRootTask(taskId = 102)
        val originalSafeRegionBounds = Rect(1, 2, 3, 4)
        val newSafeRegionBounds = Rect(10, 20, 30, 40)
        val transitionId = Binder()
        setupTransitionReply(transitionId)

        // Act
        val autoTransaction = AutoTaskStackTransaction()
            .setSafeRegionBounds(taskInfo.taskId, originalSafeRegionBounds)
            .setSafeRegionBounds(taskInfo.taskId, newSafeRegionBounds) // Replace for the same task
        controller.startTransition(autoTransaction)

        // Assert
        val wctCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(anyInt(), wctCaptor.capture(), anyOrNull())
        val wct = wctCaptor.firstValue
        val expectedWct = WindowContainerTransaction()
            .setSafeRegionBounds(taskInfo.token, newSafeRegionBounds)
        assertThat(wct.toString()).isEqualTo(expectedWct.toString())
    }

    @Test
    fun startTransition_withMultipleSetSafeRegionBounds_forDifferentTasks_preservesAll() {
        // Arrange
        val (taskInfo1, _) = setupRootTask(taskId = 103, name = "task1")
        val (taskInfo2, _) = setupRootTask(taskId = 104, name = "task2")
        val safeRegionBounds1 = Rect(1, 2, 3, 4)
        val safeRegionBounds2 = Rect(10, 20, 30, 40)
        val transitionId = Binder()
        setupTransitionReply(transitionId)

        // Act
        val autoTransaction = AutoTaskStackTransaction()
            .setSafeRegionBounds(taskInfo1.taskId, safeRegionBounds1)
            .setSafeRegionBounds(taskInfo2.taskId, safeRegionBounds2)
        controller.startTransition(autoTransaction)

        // Assert
        val wctCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(transitions).startTransition(anyInt(), wctCaptor.capture(), anyOrNull())
        val wct = wctCaptor.firstValue
        val expectedWct = WindowContainerTransaction()
            .setSafeRegionBounds(taskInfo1.token, safeRegionBounds1)
            .setSafeRegionBounds(taskInfo2.token, safeRegionBounds2)
        assertThat(wct.toString()).isEqualTo(expectedWct.toString())
        assertThat(wct.hierarchyOps).hasSize(2)
    }

    @Test
    fun transitionFromCore_delegateWithSafeRegionBounds_handleRequestReturnsCorrect() {
        // Arrange
        val (taskInfo, _) = setupRootTask(taskId = 106)
        val safeRegionBounds = Rect(5, 15, 25, 35)
        val autoTransaction = AutoTaskStackTransaction()
            .setSafeRegionBounds(taskInfo.taskId, safeRegionBounds)
        delegate.handleRequestReturn = autoTransaction
        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)

        // Act
        val resultWct = controller.handleRequest(transition, requestInfo)

        // Assert
        assertThat(resultWct).isNotNull()
        val expectedWct = WindowContainerTransaction()
            .setSafeRegionBounds(taskInfo.token, safeRegionBounds)
        assertThat(resultWct.toString()).isEqualTo(expectedWct.toString())
    }

    @Test
    fun transitionFromCore_notPlayedByDelegate_containsSafeRegionBoundsChange_shouldNotBePlayed() {
        // Arrange
        val taskLeash = mock(SurfaceControl::class.java)
        val (rootTaskInfo, _) = setupRootTask(taskId = 106)
        val safeRegionBounds = Rect(5, 15, 25, 35)
        val autoTransaction = AutoTaskStackTransaction()
            .setSafeRegionBounds(rootTaskInfo.taskId, safeRegionBounds)
        delegate.handleRequestReturn = autoTransaction
        delegate.play = false
        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)
        val info = TransitionInfoBuilder(TRANSIT_OPEN)
            .addChange(TransitionInfo.Change(rootTaskInfo.token, taskLeash).apply {
                taskInfo = rootTaskInfo
            })
            .build()
        val resultWct = controller.handleRequest(transition, requestInfo)

        // Act
        val result = controller.startAnimation(
            transition,
            info,
            mock(SurfaceControl.Transaction::class.java),
            mock(SurfaceControl.Transaction::class.java),
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun transitionFromCore_delegateWithTaskStackStates_handleRequestReturnsCorrect() {
        val leash = mock(SurfaceControl::class.java)
        val (taskInfo, listener) = setupRootTask(taskId = 18)

        val transaction = AutoTaskStackTransaction().setTaskStackState(
            taskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 30, 30), true, 0)
        )
        delegate.handleRequestReturn = transaction

        // Act
        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)
        val result = controller.handleRequest(transition, requestInfo)

        // Assert
        assertThat(result).isNotNull()
        val expected = WindowContainerTransaction()
            .setBounds(
                taskInfo.token,
                Rect(10, 10, 30, 30)
            )
            .reorder(taskInfo.token, true)
        assertThat(result.toString()).isEqualTo(expected.toString())
    }

    @Test
    fun transitionFromCore_notPlayedByDelegate_withoutTaskStackChange_NotPlayed() {
        // Arrange
        val (taskInfo, listener) = setupRootTask(taskId = 18)
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            taskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 30, 30), true, 0)
        )
        delegate.handleRequestReturn = transaction
        delegate.play = false

        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)
        controller.handleRequest(transition, requestInfo)

        // Act
        val result = controller.startAnimation(
            transition,
            TransitionInfo(1, 0),
            mock(SurfaceControl.Transaction::class.java),
            mock(SurfaceControl.Transaction::class.java),
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun transitionFromCore_notPlayedByDelegate_containsTaskStackChange_shouldNotBePlayed() {
        // Arrange
        val taskLeash = mock(SurfaceControl::class.java)
        val (rootTaskInfo, listener) = setupRootTask(taskId = 18, leash = taskLeash)
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            rootTaskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 30, 30), true, 0)
        )
        delegate.handleRequestReturn = transaction
        delegate.play = false

        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)
        controller.handleRequest(transition, requestInfo)
        val info = TransitionInfoBuilder(TRANSIT_OPEN)
            .addChange(TransitionInfo.Change(rootTaskInfo.token, taskLeash).apply {
                taskInfo = rootTaskInfo
            })
            .build()

        // Act
        val result = controller.startAnimation(
            transition,
            info,
            mock(SurfaceControl.Transaction::class.java),
            mock(SurfaceControl.Transaction::class.java),
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun transitionFromCore_withAdditionalChangeInStartAnimation_taskStacksReconciled() {
        // Arrange
        val taskLeash = mock(SurfaceControl::class.java)
        val (rootTaskInfo, taskListener1) = setupRootTask(taskId = 101, leash = taskLeash)
        val rootTask1Child =
            setupChildTask(taskId = 111, parentTaskId = 101, parentTaskListener = taskListener1)
        val (rootTaskInfo2, taskListener2) = setupRootTask(taskId = 102, leash = taskLeash)
        val rootTask2Child =
            setupChildTask(taskId = 112, parentTaskId = 102, parentTaskListener = taskListener2)
        val (rootTaskInfo3, taskListener3) = setupRootTask(taskId = 103, leash = taskLeash)
        val rootTask3Child =
            setupChildTask(taskId = 113, parentTaskId = 103, parentTaskListener = taskListener3)
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            rootTaskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 30, 30), true, 0)
        ).setTaskStackState(
            rootTaskInfo2.taskId,
            AutoTaskStackState(Rect(10, 10, 40, 300), true, 0)
        )
        delegate.handleRequestReturn = transaction
        delegate.play = true

        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)
        controller.handleRequest(transition, requestInfo)
        val info = TransitionInfoBuilder(TRANSIT_OPEN)
            .addChange(TransitionInfo.Change(rootTaskInfo.token, taskLeash).apply {
                taskInfo = rootTaskInfo
            })
            .addChange(TransitionInfo.Change(rootTaskInfo2.token, taskLeash).apply {
                taskInfo = rootTaskInfo2
            })
            // Send an additional change for the rootTask3 child
            .addChange(TransitionInfo.Change(rootTask3Child.token, taskLeash).apply {
                taskInfo = rootTask3Child
                mode = TRANSIT_OPEN
            })
            .build()

        val startTransaction = mock(SurfaceControl.Transaction::class.java)
        whenever(startTransaction.reparent(any(), any())).thenReturn(startTransaction)
        whenever(startTransaction.setPosition(any(), anyFloat(), anyFloat()))
            .thenReturn(startTransaction)
        whenever(startTransaction.setAlpha(any(), anyFloat())).thenReturn(startTransaction)

        // Act
        val result = controller.startAnimation(
            transition,
            info,
            startTransaction,
            mock(SurfaceControl.Transaction::class.java),
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        assertThat(result).isTrue()
        assertThat(delegate.lastTaskStackStates!!.find { it.taskId == rootTaskInfo3.taskId })
            .isNotNull()
        assertThat(delegate.lastTaskStackStates).contains(
            TaskStackStateChange(
                taskId = rootTaskInfo3.taskId,
                state = AutoTaskStackState(Rect(), true, AutoTaskStackController.UNKNOWN_Z_LAYER)
            )
        )
    }

    @Test
    fun transitionFromCore_missingInStartAnimation_visibilityMatchesRequest_taskStacksReconciled() {
        // Arrange
        val taskLeash = mock(SurfaceControl::class.java)
        val (rootTaskInfo, taskListener1) = setupRootTask(taskId = 101, leash = taskLeash)
        val rootTask1Child =
            setupChildTask(taskId = 111, parentTaskId = 101, parentTaskListener = taskListener1)
        val (rootTaskInfo2, taskListener2) = setupRootTask(taskId = 102, leash = taskLeash)
        val rootTask2Child =
            setupChildTask(taskId = 112, parentTaskId = 102, parentTaskListener = taskListener2)
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            rootTaskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 30, 30), true, 0)
        ).setTaskStackState(
            rootTaskInfo2.taskId,
            AutoTaskStackState(Rect(10, 10, 40, 300), true, 0)
        )

        // Set current known state visibility to false
        controller.updateTaskStackStates(
            mapOf(
                rootTaskInfo2.taskId to AutoTaskStackState(Rect(10, 10, 40, 300), false, 0)
            )
        )

        delegate.handleRequestReturn = transaction
        delegate.play = true

        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)
        controller.handleRequest(transition, requestInfo)
        val info = TransitionInfoBuilder(TRANSIT_OPEN)
            .addChange(TransitionInfo.Change(rootTaskInfo.token, taskLeash).apply {
                taskInfo = rootTaskInfo
            })
            .build()
        val visibleTaskInfo2 = TestRunningTaskInfoBuilder()
            .setTaskId(102)
            .setVisible(true)
            .build()
        taskListener2.onTaskInfoChanged(visibleTaskInfo2)

        // Act
        val result = controller.startAnimation(
            transition,
            info,
            mock(SurfaceControl.Transaction::class.java),
            mock(SurfaceControl.Transaction::class.java),
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        assertThat(result).isTrue()
        assertThat(delegate.lastTaskStackStates!!.find { it.taskId == rootTaskInfo2.taskId })
            .isNotNull()
        assertThat(delegate.lastTaskStackStates).contains(
            TaskStackStateChange(
                taskId = rootTaskInfo2.taskId,
                state = AutoTaskStackState(Rect(10, 10, 40, 300), true, 0)
            )
        )
    }

    @Test
    fun transitionFromCore_missingInStartAnimation_visibilityMatchesCurrent_taskStacksReconciled() {
        // Arrange
        val taskLeash = mock(SurfaceControl::class.java)
        val (rootTaskInfo, taskListener1) = setupRootTask(taskId = 101, leash = taskLeash)
        val rootTask1Child =
            setupChildTask(taskId = 111, parentTaskId = 101, parentTaskListener = taskListener1)
        val (rootTaskInfo2, taskListener2) = setupRootTask(taskId = 102, leash = taskLeash)
        val rootTask2Child =
            setupChildTask(taskId = 112, parentTaskId = 102, parentTaskListener = taskListener2)
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            rootTaskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 30, 30), true, 0)
        ).setTaskStackState(
            rootTaskInfo2.taskId,
            AutoTaskStackState(Rect(10, 10, 40, 300), true, 0)
        )

        val visibleTaskInfo2 = TestRunningTaskInfoBuilder()
            .setTaskId(102)
            .setVisible(false)
            .build()
        taskListener2.onTaskInfoChanged(visibleTaskInfo2)

        // Set current known state visibility to false
        controller.updateTaskStackStates(
            mapOf(
                rootTaskInfo2.taskId to AutoTaskStackState(Rect(10, 10, 40, 300), false, 0)
            )
        )

        delegate.handleRequestReturn = transaction
        delegate.play = true

        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)
        controller.handleRequest(transition, requestInfo)
        val info = TransitionInfoBuilder(TRANSIT_OPEN)
            .addChange(TransitionInfo.Change(rootTaskInfo.token, taskLeash).apply {
                taskInfo = rootTaskInfo
            })
            .build()

        // Act
        val result = controller.startAnimation(
            transition,
            info,
            mock(SurfaceControl.Transaction::class.java),
            mock(SurfaceControl.Transaction::class.java),
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        assertThat(result).isTrue()
        assertThat(delegate.lastTaskStackStates!!.find { it.taskId == rootTaskInfo2.taskId })
            .isNull()
    }

    @Test
    fun transitionFromCore_missingInStartAnimation_visibilityMatchesNeither_taskStacksReconciled() {
        // Arrange
        val taskLeash = mock(SurfaceControl::class.java)
        val (rootTaskInfo, taskListener1) = setupRootTask(taskId = 101, leash = taskLeash)
        val rootTask1Child =
            setupChildTask(taskId = 111, parentTaskId = 101, parentTaskListener = taskListener1)
        val (rootTaskInfo2, taskListener2) = setupRootTask(taskId = 102, leash = taskLeash)
        val rootTask2Child =
            setupChildTask(taskId = 112, parentTaskId = 102, parentTaskListener = taskListener2)
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            rootTaskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 30, 30), true, 0)
        ).setTaskStackState(
            rootTaskInfo2.taskId,
            AutoTaskStackState(Rect(10, 10, 40, 300), true, 0)
        )

        val visibleTaskInfo2 = TestRunningTaskInfoBuilder()
            .setTaskId(102)
            .setVisible(false)
            .build()
        taskListener2.onTaskInfoChanged(visibleTaskInfo2)

        // Set current known state visibility to true
        controller.updateTaskStackStates(
            mapOf(
                rootTaskInfo2.taskId to AutoTaskStackState(Rect(10, 10, 40, 300), true, 0)
            )
        )

        delegate.handleRequestReturn = transaction
        delegate.play = true

        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)
        controller.handleRequest(transition, requestInfo)
        val info = TransitionInfoBuilder(TRANSIT_OPEN)
            .addChange(TransitionInfo.Change(rootTaskInfo.token, taskLeash).apply {
                taskInfo = rootTaskInfo
            })
            .build()

        // Act
        val result = controller.startAnimation(
            transition,
            info,
            mock(SurfaceControl.Transaction::class.java),
            mock(SurfaceControl.Transaction::class.java),
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        assertThat(result).isTrue()
        assertThat(delegate.lastTaskStackStates!!.find { it.taskId == rootTaskInfo2.taskId })
            .isNotNull()
        assertThat(delegate.lastTaskStackStates).contains(
            TaskStackStateChange(
                taskId = rootTaskInfo2.taskId,
                state = AutoTaskStackState(Rect(10, 10, 40, 300), false, 0)
            )
        )
    }

    @Test
    fun transition_fromCore_notDelegatedToClient_notPlayed_leashesOrdered() {
        val leash1 = mock(SurfaceControl::class.java)
        val leash2 = mock(SurfaceControl::class.java)
        val (rootTask1, _) = setupRootTask(taskId = 101, leash = leash1)
        setupRootTask(taskId = 102, leash = leash2)

        controller.updateTaskStackStates(mapOf(
            101 to AutoTaskStackState(Rect(), true, 1),
            102 to AutoTaskStackState(Rect(), true, 2)
        ))

        // When the delegate returns null, ATSC should take control and not delegate to client
        delegate.handleRequestReturn = null
        delegate.play = false // This shouldn't matter as startAnimation won't be called

        val transition = Binder()
        val requestInfo = mock(TransitionRequestInfo::class.java)
        val info = TransitionInfoBuilder(TRANSIT_OPEN)
            .addChange(
                TransitionInfo.Change(rootTask1.token, leash1).apply { taskInfo = rootTask1 }
            )
            .build()

        val startTransaction = mock(SurfaceControl.Transaction::class.java)
        val finishTransaction = mock(SurfaceControl.Transaction::class.java)
        val finishCallback = mock(TransitionFinishCallback::class.java)

        // Act
        controller.handleRequest(transition, requestInfo)
        controller.startAnimation(
            transition,
            info,
            startTransaction,
            finishTransaction,
            finishCallback
        )

        // Assert
        assertThat(delegate.startAnimationCalled).isFalse()
        // Leashes should still be reordered even if the animation is not delegated
        verify(startTransaction).setLayer(leash1, 1)
        verify(startTransaction).setLayer(leash2, 2)
        verify(finishTransaction).setLayer(leash1, 1)
        verify(finishTransaction).setLayer(leash2, 2)
    }

    @Test
    fun transition_fromCore_notDelegatedToClient_aborted_leashesOrdered() {
        val leash1 = mock(SurfaceControl::class.java)
        val leash2 = mock(SurfaceControl::class.java)
        setupRootTask(taskId = 101, leash = leash1)
        setupRootTask(taskId = 102, leash = leash2)

        controller.updateTaskStackStates(mapOf(
            101 to AutoTaskStackState(Rect(), true, 1),
            102 to AutoTaskStackState(Rect(), true, 2)
        ))

        delegate.handleRequestReturn = null

        val transition = Binder()
        val requestInfo = mock(TransitionRequestInfo::class.java)
        val finishTransaction = mock(SurfaceControl.Transaction::class.java)

        // Act
        controller.handleRequest(transition, requestInfo)
        controller.onTransitionConsumed(transition, aborted = true, finishTransaction)

        // Assert
        assertThat(delegate.onTransitionConsumedCalled).isFalse()
        verify(finishTransaction).setLayer(leash1, 1)
        verify(finishTransaction).setLayer(leash2, 2)
    }

    @Test
    fun mergeTargetNotDelegatedToClient_mergeSkipped() {
        val transitionToBeMerged = Binder()
        val mergeTargetTransition = Binder()
        val requestInfo = mock(TransitionRequestInfo::class.java)

        // Setup merge target to NOT be delegated to client
        delegate.handleRequestReturn = null
        controller.handleRequest(mergeTargetTransition, requestInfo)

        // Setup transition-to-be-merged to be delegated to client
        delegate.handleRequestReturn = AutoTaskStackTransaction()
        controller.handleRequest(transitionToBeMerged, requestInfo)

        // Act
        controller.mergeAnimation(
            transitionToBeMerged,
            mock(TransitionInfo::class.java),
            mock(SurfaceControl.Transaction::class.java),
            mergeTargetTransition,
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        assertThat(delegate.mergeAnimationCalled).isFalse()
    }

    @Test
    fun transitionToBeMerged_notDelegatedToClient_mergeSkipped() {
        val transitionToBeMerged = Binder()
        val mergeTargetTransition = Binder()
        val requestInfo = mock(TransitionRequestInfo::class.java)

        // Setup transition-to-be-merged to NOT be delegated
        delegate.handleRequestReturn = null
        controller.handleRequest(transitionToBeMerged, requestInfo)

        // Setup merge target to BE delegated
        delegate.handleRequestReturn = AutoTaskStackTransaction()
        controller.handleRequest(mergeTargetTransition, requestInfo)

        // Act
        controller.mergeAnimation(
            transitionToBeMerged,
            mock(TransitionInfo::class.java),
            mock(SurfaceControl.Transaction::class.java),
            mergeTargetTransition,
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        assertThat(delegate.mergeAnimationCalled).isFalse()
    }

    @Test
    fun transitionFromCore_withAdditionalChangeForExistingTaskStack_taskStacksReconciled() {
        // Arrange
        val taskLeash = mock(SurfaceControl::class.java)
        val (rootTaskInfo, taskListener1) = setupRootTask(taskId = 101, leash = taskLeash)
        val rootTask1Child =
            setupChildTask(taskId = 111, parentTaskId = 101, parentTaskListener = taskListener1)
        val (rootTaskInfo2, taskListener2) = setupRootTask(taskId = 102, leash = taskLeash)
        val rootTask2Child =
            setupChildTask(taskId = 112, parentTaskId = 102, parentTaskListener = taskListener2)
        val (rootTaskInfo3, taskListener3) = setupRootTask(taskId = 103, leash = taskLeash)
        val rootTask3Child =
            setupChildTask(taskId = 113, parentTaskId = 103, parentTaskListener = taskListener3)
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            rootTaskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 30, 30), true, 0)
        ).setTaskStackState(
            rootTaskInfo2.taskId,
            AutoTaskStackState(Rect(10, 10, 40, 300), true, 0)
        ).setTaskStackState(
            rootTaskInfo3.taskId,
            AutoTaskStackState(Rect(10, 10, 40, 300), false, 900)
        )
        delegate.handleRequestReturn = transaction
        delegate.play = true

        val transition = mock(IBinder::class.java)
        val requestInfo = mock(TransitionRequestInfo::class.java)
        controller.handleRequest(transition, requestInfo)
        val info = TransitionInfoBuilder(TRANSIT_OPEN)
            .addChange(TransitionInfo.Change(rootTaskInfo.token, taskLeash).apply {
                taskInfo = rootTaskInfo
            })
            .addChange(TransitionInfo.Change(rootTaskInfo2.token, taskLeash).apply {
                taskInfo = rootTaskInfo2
                mode = TRANSIT_TO_BACK
            })
            // Send an additional change for the rootTask3 child
            .addChange(TransitionInfo.Change(rootTask3Child.token, taskLeash).apply {
                taskInfo = rootTask3Child
                mode = TRANSIT_OPEN
            })
            .build()

        val startTransaction = mock(SurfaceControl.Transaction::class.java)
        whenever(startTransaction.reparent(any(), any())).thenReturn(startTransaction)
        whenever(startTransaction.setPosition(any(), anyFloat(), anyFloat()))
            .thenReturn(startTransaction)
        whenever(startTransaction.setAlpha(any(), anyFloat())).thenReturn(startTransaction)

        // Act
        val result = controller.startAnimation(
            transition,
            info,
            startTransaction,
            mock(SurfaceControl.Transaction::class.java),
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        assertThat(result).isTrue()
        assertThat(delegate.lastTaskStackStates!!.find { it.taskId == rootTaskInfo3.taskId })
            .isNotNull()
        assertThat(delegate.lastTaskStackStates).contains(
            TaskStackStateChange(
                taskId = rootTaskInfo2.taskId,
                state = AutoTaskStackState(Rect(10, 10, 40, 300), false, 0)
            )
        )
        assertThat(delegate.lastTaskStackStates).contains(
            TaskStackStateChange(
                taskId = rootTaskInfo3.taskId,
                state = AutoTaskStackState(Rect(10, 10, 40, 300), true, 900)
            )
        )
    }

    @Test
    fun minLayerCheck_AutoTaskStackTransaction() {
        // Arrange
        val taskLeash = mock(SurfaceControl::class.java)
        val (rootTaskInfo, listener) = setupRootTask(taskId = 18, leash = taskLeash)
        assertThrows(IllegalArgumentException::class.java) {
            AutoTaskStackTransaction().setTaskStackState(
                rootTaskInfo.taskId,
                AutoTaskStackState(Rect(10, 10, 30, 30), true, -1)
            )
        }
    }

    @Test
    fun startTransition_whenTransitionsReturnsNull_returnsNull() {
        // Arrange
        // Mock transitions.startTransition to return null, simulating a failed transition start.
        whenever(
            transitions.startTransition(
                anyInt(),
                any(WindowContainerTransaction::class.java),
                any(Transitions.TransitionHandler::class.java)
            )
        ).thenReturn(null)

        val (taskInfo, _) = setupRootTask(taskId = 1)
        val transaction = AutoTaskStackTransaction().setTaskStackState(
            taskInfo.taskId,
            AutoTaskStackState(Rect(10, 10, 10, 10), true, 0)
        )

        // Act
        // Call the method under test.
        val result = controller.startTransition(transaction)

        // Assert
        // Verify that the result is null, as expected when the transition fails to start.
        assertThat(result).isNull()
    }

    @Test
    fun onBackPressedOnTaskRoot_nullTaskInfo_throwsException() {
        // Arrange
        val (_, taskListener) = setupRootTask(taskId = 1)

        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            taskListener.onBackOnTaskRoot(null, true, false, false)
        }
    }

    @Test
    fun onBackPressedOnTaskRoot_moveTaskToBackFalse_closesTask() {
        // Arrange
        val (rootTask, taskListener) = setupRootTask(taskId = 1)
        val childTask = TestRunningTaskInfoBuilder()
            .setTaskId(10)
            .setParentTaskId(rootTask.taskId)
            .setToken(mock(WindowContainerToken::class.java))
            .build()
        taskListener.onTaskAppeared(childTask, mock(SurfaceControl::class.java))

        // Act
        taskListener.onBackOnTaskRoot(childTask, true, false, false)

        // Assert
        verify(rootTaskStackListener).onBackOnTaskRoot(childTask, true, false, false)
        // The implementation calls ActivityManager.getService().removeTask(), which is a static
        // call and hard to mock. We verify that the move-to-back logic is not triggered.
        verify(taskOrganizer, never()).applyTransaction(any())
        verify(rootTaskStackListener, never()).moveRootTaskToBack(anyOrNull())
    }

    @Test
    fun onBackPressedOnTaskRoot_moveTaskToBack_parentNotFound_delegatesToListener() {
        // Arrange
        val (rootTask, taskListener) = setupRootTask(taskId = 1)
        val childTask = TestRunningTaskInfoBuilder().setTaskId(10).setParentTaskId(999).build()

        // Act
        taskListener.onBackOnTaskRoot(childTask, false, false, false)

        // Assert
        verify(rootTaskStackListener).onBackOnTaskRoot(childTask, false, false, false)
        verify(taskOrganizer, never()).applyTransaction(any())
        verify(rootTaskStackListener).moveRootTaskToBack(childTask)
    }

    @Test
    fun onBackPressedOnTaskRoot_moveTaskToBack_withOpaqueSibling_reordersTask() {
        // Arrange
        val rootTask = TestRunningTaskInfoBuilder()
            .setTaskId(1)
            .setToken(mock(WindowContainerToken::class.java))
            .build()
        val (_, taskListener) = setupRootTask(taskId = 1, task = rootTask)

        val childTaskToMove = TestRunningTaskInfoBuilder()
            .setTaskId(10)
            .setParentTaskId(1)
            .setToken(mock(WindowContainerToken::class.java))
            .build()

        // Act
        taskListener.onBackOnTaskRoot(childTaskToMove, false, false, true)

        // Assert
        verify(rootTaskStackListener).onBackOnTaskRoot(childTaskToMove, false, false, true)
        val wctCaptor = argumentCaptor<WindowContainerTransaction>()
        verify(taskOrganizer).applyTransaction(wctCaptor.capture())
        val wct = wctCaptor.firstValue

        val expectedWct = WindowContainerTransaction()
        expectedWct.reorder(childTaskToMove.token, false)
        assertThat(wct.toString()).isEqualTo(expectedWct.toString())

        verify(rootTaskStackListener, never()).moveRootTaskToBack(anyOrNull())
    }

    @Test
    fun onBackPressedOnTaskRoot_moveTaskToBack_noOpaqueSibling_delegatesToRootTaskStackListener() {
        // Arrange
        val (_, taskListener) = setupRootTask(taskId = 1)
        val childTaskToMove = TestRunningTaskInfoBuilder()
            .setTaskId(10)
            .setParentTaskId(1)
            .build()

        // Act
        taskListener.onBackOnTaskRoot(childTaskToMove, false, false, false)

        // Assert
        verify(rootTaskStackListener).onBackOnTaskRoot(childTaskToMove, false, false, false)
        verify(rootTaskStackListener).moveRootTaskToBack(childTaskToMove)
        verify(taskOrganizer, never()).applyTransaction(any())
    }

    @Test
    fun onBackPressedOnTaskRoot_moveTaskToBack_withTransparentSibling_movesRootTaskToBack() {
        // Arrange
        val (_, taskListener) = setupRootTask(taskId = 1)
        val childTaskToMove = TestRunningTaskInfoBuilder()
            .setTaskId(10)
            .setParentTaskId(1)
            .build()

        // Act
        taskListener.onBackOnTaskRoot(childTaskToMove, false, false, false)

        // Assert
        verify(rootTaskStackListener).onBackOnTaskRoot(childTaskToMove, false, false, false)
        verify(rootTaskStackListener).moveRootTaskToBack(childTaskToMove)
        verify(taskOrganizer, never()).applyTransaction(any())
    }

    @Test
    fun handleRequest_fullscreenTaskWithoutParent_reparentsToLaunchRoot() {
        // Setup an orphaned, fullscreen, non-home task
        val triggerTask = TestRunningTaskInfoBuilder()
            .setTaskId(100)
            .setParentTaskId(INVALID_TASK_ID) // -1
            .setWindowingMode(WINDOWING_MODE_FULLSCREEN)
            .setActivityType(ACTIVITY_TYPE_STANDARD)
            .setDisplayId(DEFAULT_DISPLAY)
            .build()

        // Configure a launch root task for this display in the controller
        val (launchRootTask, _) = setupRootTask(taskId = 200)
        controller.setDefaultRootTaskStackOnDisplay(DEFAULT_DISPLAY, 200)

        // Populate autoTaskStateMap with the root task
        val rootTaskState = AutoTaskStackState(
            bounds = Rect(0, 0, 1000, 1000),
            isAboveBarrier = true,
            layer = 1
        )
        controller.updateTaskStackStates(mapOf(200 to rootTaskState))

        // Create an opening transition request
        val request = TransitionRequestInfo(TRANSIT_OPEN, triggerTask, null)

        // Act
        val wct = controller.handleRequest(Binder(), request)

        // Assert
        assertThat(wct).isNotNull()
        // Ensure that the transaction contains a reparent operation
        assertWctHasReparent(wct!!, triggerTask.token, launchRootTask.token)
    }

    @Test
    fun startAnimation_appTaskOpening_reparentsToRootTask() {
        // Arrange
        val rootTaskLeash = mock(SurfaceControl::class.java)
        val appTaskLeash = mock(SurfaceControl::class.java)
        val tdaLeash = mock(SurfaceControl::class.java)
        val (rootTask, taskListener) = setupRootTask(taskId = 13, leash = rootTaskLeash)
        val appTask =
            setupChildTask(
                taskId = 1001,
                parentTaskId = 13,
                parentTaskListener = taskListener
            )

        val startTransaction = mock(SurfaceControl.Transaction::class.java)
        whenever(startTransaction.reparent(any(), any())).thenReturn(startTransaction)
        whenever(startTransaction.setPosition(any(), anyFloat(), anyFloat()))
            .thenReturn(startTransaction)
        whenever(startTransaction.setAlpha(any(), anyFloat())).thenReturn(startTransaction)

        val info = TransitionInfoBuilder(TRANSIT_OPEN)
            .addChange(TransitionInfo.Change(appTask.token, appTaskLeash).apply {
                taskInfo = appTask
                mode = TRANSIT_OPEN
            })
            .build()

        whenever(rootTdaOrganizer.getDisplayAreaLeash(anyInt())).thenReturn(tdaLeash)
        rootTask.displayAreaFeatureId = 1
        whenever(rootTdaOrganizer.getDisplayAreaInfo(anyInt())).thenReturn(
            android.window.DisplayAreaInfo(
                WindowContainerToken.createProxy("test"),
                displayId,
                rootTask.displayAreaFeatureId
            )
        )

        // Act
        controller.startAnimation(
            Binder(),
            info,
            startTransaction,
            mock(SurfaceControl.Transaction::class.java),
            mock(TransitionFinishCallback::class.java)
        )

        // Assert
        // Verify App Task reparenting
        verify(startTransaction).reparent(appTaskLeash, rootTaskLeash)
        verify(startTransaction).setPosition(appTaskLeash, 0f, 0f)
        verify(startTransaction).setAlpha(appTaskLeash, 1f)
    }

    @Test
    fun handleRequest_barrierTask_doesNotReparent() {
        // Setup a fullscreen task that looks like it needs reparenting
        val barrierTask = TestRunningTaskInfoBuilder()
            .setTaskId(100)
            .setParentTaskId(INVALID_TASK_ID)
            .setWindowingMode(WINDOWING_MODE_FULLSCREEN)
            .setActivityType(ACTIVITY_TYPE_STANDARD)
            .setDisplayId(DEFAULT_DISPLAY)
            .build()

        // Configure this task as a barrier in the repository
        whenever(mAutoTaskRepository.getBarrierToken(DEFAULT_DISPLAY)).thenReturn(barrierTask.token)

        // Configure a launch root task
        setupRootTask(taskId = 200)
        controller.setDefaultRootTaskStackOnDisplay(DEFAULT_DISPLAY, 200)

        // Create an opening transition request
        val request = TransitionRequestInfo(TRANSIT_OPEN, barrierTask, null)

        // Act
        val wct = controller.handleRequest(Binder(), request)

        // Assert
        assertThat(wct).isNotNull()
        assertWctHasNoReparent(wct!!, barrierTask.token)
    }

    /**
     * Asserts that the given [WindowContainerTransaction] does NOT contain a reparent operation
     * for the [task].
     */
    private fun assertWctHasNoReparent(
        wct: WindowContainerTransaction,
        task: WindowContainerToken
    ) {
        val hierarchyOps = wct.hierarchyOps
        val hasReparent = hierarchyOps.any { op ->
            op.type == HIERARCHY_OP_TYPE_REPARENT &&
                    op.container == task.asBinder()
        }
        assertWithMessage(
            "WCT should NOT contain a reparent operation for task $task"
        ).that(hasReparent).isFalse()
    }

    /**
     * Asserts that the given [WindowContainerTransaction] contains a reparent operation
     * for the [task] to the [parent].
     */
    private fun assertWctHasReparent(
        wct: WindowContainerTransaction,
        task: WindowContainerToken,
        parent: WindowContainerToken?
    ) {
        val hierarchyOps = wct.hierarchyOps
        val hasReparent = hierarchyOps.any { op ->
            op.type == HIERARCHY_OP_TYPE_REPARENT &&
                    op.container == task.asBinder() &&
                    op.newParent == parent?.asBinder()
        }
        assertWithMessage(
            "WCT should contain a reparent operation for task $task to parent $parent"
        ).that(hasReparent).isTrue()
    }
}
