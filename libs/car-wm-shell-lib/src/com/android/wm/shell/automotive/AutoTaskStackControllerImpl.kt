/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.app.ActivityTaskManager.INVALID_TASK_ID
import android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT
import android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED
import android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.IBinder
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.WindowManager
import android.view.WindowManager.TRANSIT_CHANGE
import android.window.TaskCreationParams
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerTransaction
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.Flags.enableAutoTaskStackController
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.automotive.CarWmShellProtoLogGroups.CAR_WM_SHELL_TASK_STACK_CONTROLLER
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.dagger.WMSingleton
import com.android.wm.shell.shared.TransitionUtil
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TransitionFinishCallback
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@WMSingleton
class AutoTaskStackControllerImpl @Inject constructor(
    val taskOrganizer: ShellTaskOrganizer,
    @ShellMainThread private val shellMainThread: ShellExecutor,
    val transitions: Transitions,
    val rootTdaOrganizer: RootTaskDisplayAreaOrganizer,
    val context: Context,
    val autoTaskRepository: AutoTaskRepository,
    val unused: AutoWmShellCommandHandler
) : AutoTaskStackController, Transitions.TransitionHandler, AutoShellInitializable {
    override var autoTransitionHandlerDelegate: AutoTaskStackTransitionHandlerDelegate? = null

    private val _taskStackStateMap: ConcurrentHashMap<Int, AutoTaskStackState> = ConcurrentHashMap()
    override val taskStackStateMap: Map<Int, AutoTaskStackState>
        get() {
            // The getter itself doesn't enforce a thread.
            // However, the underlying _taskStackStateMap is thread-safe for reads.
            return _taskStackStateMap
        }

    // Map of task stack id to the corresponding AutoTaskStack object.
    private val taskStackMap = mutableMapOf<Int, AutoTaskStack>()
    private val pendingTransitions = ArrayList<PendingTransition>()
    private val mTaskStackStateTranslator = TaskStackStateTranslator()
    private val appTasksMap = mutableMapOf<Int, ActivityManager.RunningTaskInfo>()
    private val defaultRootTaskPerDisplay = mutableMapOf<Int, Int>()

    override fun initialize() {
        transitions.addHandler(this)
    }

    /** Translates the [AutoTaskStackState] to relevant WM and surface transactions. */
    // TODO(b/421471212): Move it to a separate class.
    inner class TaskStackStateTranslator {
        // TODO(b/384946072): Move to an interface with 2 implementations, one for root task and
        //  other for TDA
        fun applyVisibilityAndBounds(
            wct: WindowContainerTransaction,
            taskStack: AutoTaskStack,
            state: AutoTaskStackState
        ) {
            if (taskStack !is RootTaskStack) {
                ProtoLog.e(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Unsupported task stack, unable to convertToWct"
                )
                return
            }
            wct.setBounds(taskStack.rootTaskInfo.token, state.bounds)
            wct.reorder(taskStack.rootTaskInfo.token, state.childrenTasksVisible)
        }

        fun applyVisibility(
            wct: WindowContainerTransaction,
            taskStack: AutoTaskStack,
        ) {
            if (taskStack !is RootTaskStack) {
                ProtoLog.e(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Unsupported task stack, unable to convertToWct"
                )
                return
            }
            wct.reorder(
                taskStack.rootTaskInfo.token,
                /* onTop = */
                true
            )
        }

        fun reorderLeash(
            taskStack: AutoTaskStack,
            state: AutoTaskStackState,
            transaction: Transaction
        ) {
            if (taskStack !is RootTaskStack) {
                ProtoLog.e(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Unsupported task stack, unable to reorder leash"
                )
                return
            }
            transaction.setLayer(taskStack.leash, state.layer)
        }

        fun restoreLeash(taskStack: AutoTaskStack, transaction: Transaction) {
            if (taskStack !is RootTaskStack) {
                ProtoLog.e(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Unsupported task stack, unable to restore leash"
                )
                return
            }

            val rootTdaInfo = rootTdaOrganizer.getDisplayAreaInfo(taskStack.displayId)
            if (rootTdaInfo == null ||
                rootTdaInfo.featureId != taskStack.rootTaskInfo.displayAreaFeatureId
            ) {
                ProtoLog.e(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Cannot find the rootTDA for the root task stack %d",
                    taskStack.id
                )
                return
            }
            transaction.reparent(
                taskStack.leash,
                rootTdaOrganizer.getDisplayAreaLeash(taskStack.displayId)
            )
        }

        fun setSafeRegionBounds(
            wct: WindowContainerTransaction,
            taskStack: AutoTaskStack,
            safeRegionBounds: Rect
        ) {
            if (taskStack !is RootTaskStack) {
                ProtoLog.e(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Unsupported task stack, unable to convertToWct"
                )
                return
            }
            wct.setSafeRegionBounds(taskStack.rootTaskInfo.token, safeRegionBounds)
        }
    }

    inner class RootTaskStackListenerAdapter(
        val rootTaskStackListener: RootTaskStackListener,
        val name: String
    ) : ShellTaskOrganizer.TaskListener {
        private var rootTaskStack: RootTaskStack? = null

        // TODO(b/384948029): Notify car service for all the children tasks' events
        override fun onTaskAppeared(
            taskInfo: ActivityManager.RunningTaskInfo?,
            leash: SurfaceControl?
        ) {
            if (taskInfo == null) {
                throw IllegalArgumentException("taskInfo can't be null in onTaskAppeared")
            }
            if (leash == null) {
                throw IllegalArgumentException("leash can't be null in onTaskAppeared")
            }
            ProtoLog.d(
                CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                "onTaskAppeared = %d",
                taskInfo.taskId
            )

            if (rootTaskStack == null) {
                val rootTask =
                    RootTaskStack(taskInfo.taskId, taskInfo.displayId, leash, name, taskInfo)
                taskStackMap[rootTask.id] = rootTask

                rootTaskStack = rootTask
                autoTaskRepository.onRootTaskStackAppeared(rootTask)
                rootTaskStackListener.onRootTaskStackAppeared(rootTask)
                taskOrganizer.setInterceptBackPressedOnTaskRoot(
                    rootTaskStack!!.rootTaskInfo.token,
                    true
                )
                return
            }
            appTasksMap[taskInfo.taskId] = taskInfo
            autoTaskRepository.onTaskAppeared(rootTaskStack, taskInfo, leash)
            rootTaskStackListener.onTaskAppeared(taskInfo, leash)
        }

        override fun onTaskInfoChanged(taskInfo: ActivityManager.RunningTaskInfo?) {
            if (taskInfo == null) {
                throw IllegalArgumentException("taskInfo can't be null in onTaskInfoChanged")
            }
            ProtoLog.d(
                CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                "onTaskInfoChanged = %d",
                taskInfo.taskId
            )
            var previousRootTaskStackInfo = rootTaskStack ?: run {
                ProtoLog.e(CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Received onTaskInfoChanged, when root task stack is null")
                return@onTaskInfoChanged
            }
            rootTaskStack?.let {
                if (taskInfo.taskId == previousRootTaskStackInfo.id) {
                    previousRootTaskStackInfo =
                        previousRootTaskStackInfo.copy(rootTaskInfo = taskInfo)
                    taskStackMap[previousRootTaskStackInfo.id] = previousRootTaskStackInfo
                    rootTaskStack = previousRootTaskStackInfo
                    rootTaskStackListener.onRootTaskStackInfoChanged(rootTaskStack!!)
                    return
                }
            }

            appTasksMap[taskInfo.taskId] = taskInfo
            autoTaskRepository.onTaskChanged(rootTaskStack, taskInfo)
            rootTaskStackListener.onTaskInfoChanged(taskInfo)
        }

        override fun onTaskVanished(taskInfo: ActivityManager.RunningTaskInfo?) {
            if (taskInfo == null) {
                throw IllegalArgumentException("taskInfo can't be null in onTaskVanished")
            }
            ProtoLog.d(
                CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                "onTaskVanished  = %d",
                taskInfo.taskId
            )
            var rootTask = rootTaskStack ?: run {
                ProtoLog.e(CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Received onTaskVanished, when root task stack is null")
                return@onTaskVanished
            }
            if (taskInfo.taskId == rootTask.id) {
                rootTask = rootTask.copy(rootTaskInfo = taskInfo)
                rootTaskStack = rootTask
                rootTaskStackListener.onRootTaskStackDestroyed(rootTask)
                taskStackMap.remove(rootTask.id)
                _taskStackStateMap.remove(rootTask.id)
                autoTaskRepository.onRootTaskStackDestroyed(rootTask)
                rootTaskStack = null
                return
            }
            appTasksMap.remove(taskInfo.taskId)
            rootTaskStackListener.onTaskVanished(taskInfo)
            autoTaskRepository.onTaskVanished(rootTaskStack, taskInfo)
        }

        /**
         * Called when a back press is triggered on the root task, or moveTaskToBack() is called on
         * an activity.
         *
         * Note: rootTaskStackListener.onBackOnTaskRoot() is always called before
         * rootTaskStackListener.moveRootTaskToBack() is potentially called.
         */
        override fun onBackOnTaskRoot(
            taskInfo: ActivityManager.RunningTaskInfo?,
            isFromBackPress: Boolean,
            isOptInOnBackInvoked: Boolean,
            hasOpaqueSibling: Boolean
        ) {
            if (taskInfo == null) {
                throw IllegalArgumentException("taskInfo can't be null in onBackOnTaskRoot")
            }
            ProtoLog.d(
                CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                "onBackOnTaskRoot: task#%d, isFromBackPress:%b" +
                        ", hasOpaqueSibling:%b",
                taskInfo.taskId,
                isFromBackPress,
                hasOpaqueSibling
            )
            super.onBackOnTaskRoot(
                taskInfo,
                isFromBackPress,
                isOptInOnBackInvoked,
                hasOpaqueSibling
            )
            rootTaskStackListener.onBackOnTaskRoot(
                taskInfo,
                isFromBackPress,
                isOptInOnBackInvoked,
                hasOpaqueSibling
            )
            if (isFromBackPress) {
                handleBackButtonPress(taskInfo)
            } else {
                handleMoveTaskToBack(taskInfo, hasOpaqueSibling)
            }
        }

        /**
         * Handles the request to move a task to the back. If the task has a parent task and has a
         * opaque siblings, move the task to the back. Otherwise, defer to ScalableUI to handle it.
         * TODO(b/409394537): try alternative solutions, such as having a per root task
         *  visibility barrier, or creating a new always hidden root task.
         */
        private fun handleMoveTaskToBack(
            taskInfo: ActivityManager.RunningTaskInfo,
            hasOpaqueSibling: Boolean
        ) {
            if (hasOpaqueSibling) {
                val taskToken = taskInfo.token
                ProtoLog.d(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "handleMoveTaskToBack: targetTask#${taskInfo.taskId}, token $taskToken"
                )
                val wct = WindowContainerTransaction()
                // false for onTop means move to bottom of its current parent
                wct.reorder(taskToken, false)
                taskOrganizer.applyTransaction(wct)
                return
            }

            // Defer to ScalableUI to handle this case.
            rootTaskStackListener.moveRootTaskToBack(taskInfo)
        }

        /** Handle back event and close the task. */
        private fun handleBackButtonPress(taskInfo: ActivityManager.RunningTaskInfo) {
            ProtoLog.i(
                CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                "Received onBackPressedOnTaskRoot, closing the task: %s",
                taskInfo.toString()
            )
            val taskId = taskInfo.taskId
            try {
                ActivityManager.getService().removeTask(taskId)
            } catch (e: Exception) {
                ProtoLog.e(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Failed to remove task%d. Exception: %s",
                    taskId,
                    e.toString()
                )
            }
        }

        override fun attachChildSurfaceToTask(taskId: Int, b: SurfaceControl.Builder) {
            val parentLeash = findParentSurfaceControl(taskId)
            if (parentLeash != null) {
                b.setParent(parentLeash)
            } else {
                ProtoLog.e(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Failed to attach child surface to task#%d: Parent surface not found.",
                    taskId
                )
            }
        }

        override fun reparentChildSurfaceToTask(
            taskId: Int,
            sc: SurfaceControl,
            t: SurfaceControl.Transaction
        ) {
            val parentLeash = findParentSurfaceControl(taskId)
            if (parentLeash != null) {
                t.reparent(sc, parentLeash)
            } else {
                ProtoLog.e(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Failed to attach child surface to task#%d: Parent surface not found.",
                    taskId
                )
            }
        }
    }

    private fun findParentSurfaceControl(taskId: Int): SurfaceControl? {
        // Attempt to retrieve from autoTaskRepository
        appTasksMap[taskId]?.let { appTask ->
            autoTaskRepository.getSurfaceControl(appTask)?.let {
                return it // Found in autoTaskRepository
            } ?: run {
                ProtoLog.w(CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "SurfaceControl not found in autoTaskRepository for task#%d", taskId)
            }
        } ?: run {
            ProtoLog.w(CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                "Task not found in appTasksMap for task#%d", taskId)
        }

        // If not found, attempt to retrieve from taskStackMap
        (taskStackMap[taskId] as? RootTaskStack)?.leash?.let {
            return it // Found in taskStackMap
        } ?: run {
            ProtoLog.w(CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                "RootTaskStack or Leash not found for task#%d", taskId)
        }

        return null // Parent surface not found in either source
    }

    override fun createRootTaskStack(
        displayId: Int,
        name: String,
        listener: RootTaskStackListener
    ) {
        shellMainThread.execute {
            if (!enableAutoTaskStackController()) {
                ProtoLog.e(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Failed to create root task stack as the " +
                            "auto_task_stack_windowing TS flag is disabled."
                )
            } else {
                val params =
                    TaskCreationParams.Builder()
                        .setName(name)
                        .setDisplayId(displayId)
                        .setWindowingMode(WINDOWING_MODE_MULTI_WINDOW)
                        .build()
                taskOrganizer.createTask(params, RootTaskStackListenerAdapter(listener, name))
            }
        }
    }

    override fun destroyTaskStack(taskStackId: Int) {
        shellMainThread.execute {
            // TODO(b/384946072): Add support for DisplayAreaTaskStack
            val taskStack = taskStackMap[taskStackId] as? RootTaskStack
            if (taskStack == null) {
                ProtoLog.e(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "Task stack with id %d doesn't exist",
                    taskStackId
                )
            } else {
                val deleted: Boolean = taskOrganizer.deleteTask(taskStack.rootTaskInfo.token)
            }
        }
    }

    override fun setDefaultRootTaskStackOnDisplay(displayId: Int, rootTaskStackId: Int?) {
        shellMainThread.execute {
            run(outer@{
                if (!enableAutoTaskStackController()) {
                    ProtoLog.e(
                        CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                        "Failed to set default root task stack as the " +
                                "auto_task_stack_windowing TS flag is disabled."
                    )
                    return@outer
                }
                var wct = WindowContainerTransaction()

                // Clear the default root task stack if already set
                defaultRootTaskPerDisplay[displayId]?.let { existingDefaultRootTaskStackId ->
                    (taskStackMap[existingDefaultRootTaskStackId] as? RootTaskStack)
                        ?.let { rootTaskStack ->
                            wct.setLaunchRoot(rootTaskStack.rootTaskInfo.token, null, null)
                        }
                }

                if (rootTaskStackId != null) {
                    var taskStack =
                        taskStackMap[rootTaskStackId] ?: run { return@outer }
                    ProtoLog.d(
                        CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                        "setting launch root for  = %d",
                        taskStack.id
                    )
                    if (taskStack !is RootTaskStack) {
                        throw IllegalArgumentException(
                            "Cannot set a non root task stack as default root task " +
                                    "stack"
                        )
                    }
                    wct.setLaunchRoot(
                        taskStack.rootTaskInfo.token,
                        intArrayOf(WINDOWING_MODE_UNDEFINED),
                        intArrayOf(
                            ACTIVITY_TYPE_STANDARD,
                            ACTIVITY_TYPE_UNDEFINED,
                            ACTIVITY_TYPE_RECENTS,

                            // TODO(b/386242708): Figure out if this flag will ever be used for automotive
                            //  assistant. Based on output, remove it from here and fix the
                            //  AssistantStackTests accordingly.
                            ACTIVITY_TYPE_ASSISTANT
                        )
                    )
                    defaultRootTaskPerDisplay[displayId] = taskStack.id
                }

                taskOrganizer.applyTransaction(wct)
            })
        }
    }

    override fun startTransition(transaction: AutoTaskStackTransaction): IBinder? {
        // TODO(b/416504816): Remove this and use coroutine suspend functions to execute this
        // on main thread and still be able to able to return.
        ProtoLog.d(
            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
            "startTransition\n\t%s",
            transaction.operations.joinToString("\n\t")
        )
        shellMainThread.assertCurrentThread()

        if (!enableAutoTaskStackController()) {
            ProtoLog.e(
                CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                "Failed to start transaction as the " +
                        "auto_task_stack_windowing TS flag is disabled."
            )
            return null
        }
        if (transaction.operations.isEmpty()) {
            ProtoLog.e(
                CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                "Operations empty, no transaction started"
            )
            return null
        }

        var wct = WindowContainerTransaction()
        convertToWct(transaction, wct)
        var pending = PendingTransition(
            TRANSIT_CHANGE,
            wct,
            transaction,
        )
        return startTransitionNow(pending)
    }

    override fun handleRequest(
        transition: IBinder,
        request: TransitionRequestInfo
    ): WindowContainerTransaction? {
        ProtoLog.d(
            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
            "handleRequest, id=%s, binder=%s, type=%d, triggertask = %s",
            request.debugId,
            transition,
            request.type,
            request.triggerTask?.toShortString()
        )
        var ast = autoTransitionHandlerDelegate?.handleRequest(transition, request)
        val action = request.triggerTask?.baseIntent?.action
        val category = request.triggerTask?.baseIntent?.categories

        if (action?.equals(Intent.ACTION_MAIN) == true &&
            category?.contains(Intent.CATEGORY_HOME) == true &&
            TransitionUtil.isOpeningType(request.type)
        ) {
            // This is done for the home event only because home task is a fullscreen task that can
            // cause a potential change in existing root-task visibilities.
            // Any other task would open in a multi-window root task which won't affect visibility
            // of any other root task and hence the states of other root tasks don't need to be
            // restored for such cases.
            ProtoLog.v(
                CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                "HOME transaction. Updating state for root tasks which are not " +
                    "updated by client."
            )
            if (ast == null) {
                ast = AutoTaskStackTransaction()
            }
            for ((key, value) in taskStackStateMap.entries) {
                ast.setTaskStackStateIfNotSet(
                    key,
                    AutoTaskStackState(value.bounds, value.childrenTasksVisible, value.layer)
                )
            }
        }

        val wct = WindowContainerTransaction()
        if (ast == null) {
            ProtoLog.v(
                CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                "A transition %s not being handled by Delegate. CarWmShell will take control",
                request.debugId
            )
            ast = AutoTaskStackTransaction()
            pendingTransitions.add(
                PendingTransition(request.type, wct, ast, delegateToClient = false)
                    .apply { isClaimed = transition }
            )
            return wct
        }
        ProtoLog.d(
            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
            "Sending ast = \n\t%s",
            ast.operations.joinToString("\n\t")
        )
        // When ast.operations is empty, it will trigger the regular flow and transition will be
        // delegated to the client
        convertToWct(ast, wct)
        pendingTransitions.add(
            PendingTransition(request.type, wct, ast).apply { isClaimed = transition }
        )
        return wct
    }

    fun ActivityManager.RunningTaskInfo.toShortString(): String {
        return "TaskInfo{" +
                "taskId=" + this.taskId +
                " userId=" + this.userId +
                " displayId=" + this.displayId +
                " topActivity=" + this.topActivity +
                " baseIntent=" + this.baseIntent +
                " baseActivity=" + this.baseActivity +
                "}"
    }

    fun updateTaskStackStates(taskStatStates: Map<Int, AutoTaskStackState>) {
        _taskStackStateMap.putAll(taskStatStates)
    }

    /**
     * Creates a reconciled TaskStackStateChange, prioritizing the provided requested state,
     * and falling back to the current known state.
     *
     * @param taskStackId The ID of the task stack.
     * @param newVisibility The reconciled visibility of the task stack's children.
     * @param requestedTaskStackState The state explicitly requested by the client for this task stack,
     *                                or `null` if not explicitly requested.
     * @return A new [TaskStackStateChange] reflecting the reconciled state.
     */
    private fun createReconciledTaskStackChange(
        taskStackId: Int,
        newVisibility: Boolean,
        requestedTaskStackState: AutoTaskStackState?
    ): TaskStackStateChange {
        // Prioritize the explicitly requested state if available
        val baseState = requestedTaskStackState
            // If not explicitly requested, try to get the current actual state
            ?: _taskStackStateMap[taskStackId]

        val bounds = baseState?.bounds ?: Rect()
        val layer = baseState?.layer ?: AutoTaskStackController.UNKNOWN_Z_LAYER

        ProtoLog.v(
            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
            "Creating reconciled task stack change for %d (%s), newVisibility: %b, " +
                    "layer %d, bounds %s" +
                    "fromRequestedState: %b, fromCurrentState: %b",
            taskStackId,
            taskStackMap[taskStackId]?.name ?: "",
            newVisibility,
            layer,
            bounds,
            requestedTaskStackState != null,
            baseState == _taskStackStateMap[taskStackId] && requestedTaskStackState == null
        )

        return TaskStackStateChange(taskStackId, AutoTaskStackState(bounds, newVisibility, layer))
    }

    /**
     * Calculates task stack changes based on what actually changed in window manager.
     * This is necessary because the WindowManager might make changes to task visibility (e.g.,
     * hiding a task stack) that were not explicitly requested by the client in the
     * [AutoTaskStackTransaction].
     * This function ensures that the internal [AutoTaskStackState] accurately reflects the current
     * state of the task stacks after a transition, handling cases where (not an exhaustive list):
     * 1. A task stack becomes invisible due to a closing transition, even if it was not explicitly
     *    requested to be invisible by the client.
     * 2. A task stack becoming visible due to an app task launching inside it (this happens
     *    implicitly where core brings the task stack (or root task) to the front) even if it was
     *    not explicitly requested by the client.
     *
     * @param requestedTaskStackChanges The task stack states requested by the client in the
     *                                  [AutoTaskStackTransaction].
     * @param changes The list of [TransitionInfo.Change] objects representing the actual changes
     *                that occurred during the window transition.
     * @return A list of task stack IDs to their reconciled [AutoTaskStackState]s.
     */
    fun calculateTaskStackStateChangesFromTransition(
        requestedTaskStackChanges: Map<Int, AutoTaskStackState>,
        changes: List<TransitionInfo.Change>
    ): List<TaskStackStateChange> {
        val taskStackChanges = mutableListOf<TaskStackStateChange>()
        val processedTaskStacks = mutableSetOf<Int>()

        // TODO: The reconciliation below won't be required once b/388067743 is fixed.
        for (chg in changes) {
            val taskInfo = chg.taskInfo ?: continue

            // Determine the relevant taskStackId and whether this is a direct change to the
            // task stack.
            val (taskStackId, isTaskStackChange) = when {
                taskStackMap.containsKey(taskInfo.taskId) -> taskInfo.taskId to true
                taskInfo.parentTaskId != INVALID_TASK_ID &&
                        taskStackMap.containsKey(taskInfo.parentTaskId)
                    -> taskInfo.parentTaskId to false
                else -> {
                    // This change is not for a known root task stack or its immediate child.
                    continue
                }
            }

            if (processedTaskStacks.contains(taskStackId)) {
                continue
            }

            val requestedTaskStackState = requestedTaskStackChanges[taskStackId]

            if (TransitionUtil.isOpeningMode(chg.mode)) {
                // When a task is opening, regardless of a task stack itself or an app (child) task
                // inside the task stack, its given that the underlying task stack is
                // becoming visible. Since the visibilities are inherited in WM, a child task
                // cannot become visible without its parent task being visible.
                if (requestedTaskStackState == null ||
                    !requestedTaskStackState.childrenTasksVisible) {
                    ProtoLog.v(
                        CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                        "Task stack %d is becoming visible due to child task %d, but was not " +
                                "requested to be visible. Reconciling.",
                        taskStackId,
                        taskInfo.taskId
                    )
                }
                taskStackChanges.add(createReconciledTaskStackChange(
                    taskStackId,
                    newVisibility = true,
                    requestedTaskStackState = requestedTaskStackState
                ))
                processedTaskStacks.add(taskStackId)
            } else if (isTaskStackChange && TransitionUtil.isClosingMode(chg.mode)) {
                // A 'close' transition on a task stack change means that this task stack is
                // changing visibility on the core side to false.
                if (requestedTaskStackState == null ||
                    requestedTaskStackState.childrenTasksVisible) {
                    ProtoLog.v(
                        CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                        "Task stack %d is becoming invisible but was not explicitly requested " +
                                "to be invisible. Reconciling.",
                        taskStackId
                    )
                }
                taskStackChanges.add(createReconciledTaskStackChange(
                    taskStackId,
                    newVisibility = false,
                    requestedTaskStackState = requestedTaskStackState
                ))
                processedTaskStacks.add(taskStackId)
            } else if (isTaskStackChange && requestedTaskStackState != null) {
                // This handles other cases, like bounds changes, where there was an explicit
                // request from the client, but it wasn't a simple open/close.
                taskStackChanges.add(TaskStackStateChange(taskStackId, requestedTaskStackState))
                processedTaskStacks.add(taskStackId)
            }
        }
        return taskStackChanges
    }

    override fun startAnimation(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: Transaction,
        finishTransaction: Transaction,
        finishCallback: TransitionFinishCallback
    ): Boolean {
        ProtoLog.d(
            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
            "startAnimation, id=%s = changes=%s",
            info.debugId,
            info.changes.toString()
        )
        val pending: PendingTransition? = findPending(transition)
        var changedTaskStacks: List<TaskStackStateChange> = emptyList()
        if (pending != null) {
            pendingTransitions.remove(pending)
            changedTaskStacks = calculateTaskStackStateChangesFromTransition(
                pending.transaction.getTaskStackStates(),
                info.changes
            )
            updateTaskStackStates(changedTaskStacks.associate { it.taskId to it.state })
        }

        reorderLeashes(startTransaction)
        reorderLeashes(finishTransaction)

        for (chg in info.changes) {
            // TODO(b/384946072): handle the da stack similarly. The below implementation only
            // handles the root task stack

            val taskInfo = chg.taskInfo ?: continue
            val taskStack = taskStackMap[taskInfo.taskId] ?: continue

            // Restore the leashes for the task stacks to ensure correct z-order competition
            if (taskStackMap.containsKey(taskInfo.taskId)) {
                mTaskStackStateTranslator.restoreLeash(
                    taskStack,
                    startTransaction
                )
                if (TransitionUtil.isOpeningMode(chg.mode)) {
                    // Clients can still manipulate the alpha, but this ensures that the default
                    // behavior is natural
                    startTransaction.setAlpha(chg.leash, 1f)
                }
                continue
            }
        }

        ProtoLog.d(
            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
            " in startAnimation, id=%d, changedTaskStacks=\n\t%s",
            info.debugId,
            changedTaskStacks.joinToString("\n\t")
        )
        if ((pending?.delegateToClient ?: true)) {
            val isPlayedByDelegate = autoTransitionHandlerDelegate?.startAnimation(
                transition,
                changedTaskStacks,
                info,
                startTransaction,
                finishTransaction,
                {
                    shellMainThread.execute {
                        finishCallback.onTransitionFinished(it)
                        startNextTransition()
                    }
                }
            ) ?: false

            if (isPlayedByDelegate) {
                ProtoLog.d(
                    CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                    "%s played",
                    info.debugId
                )
                return true
            }
        }

        return false
    }

    fun convertToWct(ast: AutoTaskStackTransaction, wct: WindowContainerTransaction) {
        ast.operations.forEach { operation ->
            when (operation) {
                is TaskStackOperation.ReparentTask -> {
                    val appTask = appTasksMap[operation.taskId]

                    if (appTask == null) {
                        ProtoLog.e(
                            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                            "task with id=%d not found, failed to reparent.",
                            operation.taskId
                        )
                        return@forEach
                    }
                    if (!taskStackMap.containsKey(operation.parentTaskStackId)) {
                        ProtoLog.e(
                            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                            "task stack with id=%d not found, failed to reparent",
                            operation.parentTaskStackId
                        )
                        return@forEach
                    }
                    // TODO(b/384946072): Handle a display area stack as well
                    wct.reparent(
                        appTask.token,
                        (taskStackMap[operation.parentTaskStackId] as RootTaskStack)
                            .rootTaskInfo.token,
                        operation.onTop
                    )
                }

                is TaskStackOperation.SendPendingIntent -> wct.sendPendingIntent(
                    operation.sender,
                    operation.intent,
                    operation.options
                )

                is TaskStackOperation.SetTaskStackState -> {
                    taskStackMap[operation.taskStackId]?.let { taskStack ->
                        mTaskStackStateTranslator.applyVisibilityAndBounds(
                            wct,
                            taskStack,
                            operation.state
                        )
                    }
                        ?: ProtoLog.w(
                            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                            "AutoTaskStack with id %d not found.",
                            operation.taskStackId
                        )
                }

                is TaskStackOperation.SetFocusedTaskStack -> {
                    // Do nothing here. Focus needs to be set in the last.
                }

                is TaskStackOperation.SetSafeRegionBounds -> {
                    taskStackMap[operation.taskStackId]?.let { taskStack ->
                        mTaskStackStateTranslator.setSafeRegionBounds(
                            wct,
                            taskStack,
                            operation.safeRegionBounds
                        )
                    }
                        ?: ProtoLog.w(
                            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                            "AutoTaskStack with id %d not found.",
                            operation.taskStackId
                        )
                }
            }
        }

        // process focus task in the end so that it would get the focus.
        ast.operations.forEach { operation ->
            if (operation is TaskStackOperation.SetFocusedTaskStack) {
                taskStackMap[operation.taskStackId]?.let { taskStack ->
                    mTaskStackStateTranslator.applyVisibility(
                        wct,
                        taskStack,
                    )
                }
                    ?: ProtoLog.w(
                        CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                        "AutoTaskStack with id %d not found.",
                        operation.taskStackId
                    )
            }
        }
    }

    override fun mergeAnimation(
        transition: IBinder,
        info: TransitionInfo,
        surfaceTransaction: Transaction,
        mergeTarget: IBinder,
        finishCallback: TransitionFinishCallback
    ) {
        ProtoLog.d(
            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
            "mergeAnimation, id=%d, into target=%s",
            info.debugId,
            mergeTarget
        )
        // If either of the current playing transition or the new one is not to be delegated to
        // client, skip sending the merge signal.
        val pending: PendingTransition? = findPending(transition)

        // Update the task stack states as the client may handle the merge and startAnimation
        // will never be called. Moreover, the changes on WM side have anyway been applied so
        // updating the task stack states here is safe.
        var changedTaskStacks: List<TaskStackStateChange> = emptyList()
        if (pending != null) {
            changedTaskStacks = calculateTaskStackStateChangesFromTransition(
                pending.transaction.getTaskStackStates(),
                info.changes
            )
            updateTaskStackStates(changedTaskStacks.associate { it.taskId to it.state })
        }
        ProtoLog.d(
            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
            " in mergeAnimation, id=%d, changedTaskStacks=\n\t%s",
            info.debugId,
            changedTaskStacks.joinToString("\n\t")
        )

        if (!(pending?.delegateToClient ?: true)) {
            return
        }

        val pendingMergeTarget: PendingTransition? = findPending(mergeTarget)
        if (!(pendingMergeTarget?.delegateToClient ?: true)) {
            return
        }

        autoTransitionHandlerDelegate?.mergeAnimation(
            transition,
            changedTaskStacks,
            info,
            surfaceTransaction,
            mergeTarget,
            /* finishCallback = */
            {
                shellMainThread.execute {
                    finishCallback.onTransitionFinished(it)
                }
            }
        )
    }

    override fun onTransitionConsumed(
        transition: IBinder,
        aborted: Boolean,
        finishTransaction: Transaction?
    ) {
        ProtoLog.d(
            CAR_WM_SHELL_TASK_STACK_CONTROLLER,
            "onTransitionConsumed, aborted=%b",
            aborted
        )
        val pending: PendingTransition? = findPending(transition)
        if (pending != null) {
            pendingTransitions.remove(pending)
            updateTaskStackStates(pending.transaction.getTaskStackStates())
            // Still update the surface order because this means wm didn't lead to any change
            if (finishTransaction != null) {
                reorderLeashes(finishTransaction)
            }

            if (!pending.delegateToClient) {
                ProtoLog.d(CAR_WM_SHELL_TASK_STACK_CONTROLLER, "prevent client delegation")
                return
            }
        }
        autoTransitionHandlerDelegate?.onTransitionConsumed(
            transition,
            pending?.transaction?.getTaskStackStates() ?: emptyMap(),
            aborted,
            finishTransaction
        )
    }

    private fun reorderLeashes(transaction: SurfaceControl.Transaction) {
        _taskStackStateMap.forEach { (taskId, taskStackState) ->
            taskStackMap[taskId]?.let { taskStack ->
                mTaskStackStateTranslator.reorderLeash(taskStack, taskStackState, transaction)
            } ?: ProtoLog.w(CAR_WM_SHELL_TASK_STACK_CONTROLLER,
                "Warning: AutoTaskStack with id %d not found.", taskId)
        }
    }

    private fun findPending(claimed: IBinder) = pendingTransitions.find { it.isClaimed == claimed }

    private fun startTransitionNow(pending: PendingTransition): IBinder? {
        val claimedTransition = transitions.startTransition(pending.mType, pending.wct, this)
        pending.isClaimed = claimedTransition
        pendingTransitions.add(pending)
        return claimedTransition
    }

    fun startNextTransition() {
        if (pendingTransitions.isEmpty()) return
        val pending: PendingTransition = pendingTransitions[0]
        if (pending.isClaimed != null) {
            // Wait for this to start animating.
            return
        }
        pending.isClaimed = transitions.startTransition(pending.mType, pending.wct, this)
    }

    fun dump(pw: PrintWriter, prefix: String) {
        // TODO(b/395032583): Add more dump data.
        pw.println(prefix + "AutoTaskStackController:")
        pw.println(prefix + "RootTaskStacksMap: ")
        for ((key, value) in _taskStackStateMap) {
            pw.println(prefix + "RootTaskStackId: $key $value")
        }
    }

    internal class PendingTransition(
        @field:WindowManager.TransitionType @param:WindowManager.TransitionType val mType: Int,
        val wct: WindowContainerTransaction,
        val transaction: AutoTaskStackTransaction,
        val delegateToClient: Boolean = true
    ) {
        var isClaimed: IBinder? = null
    }

    fun getRootTasks(): List<AutoTaskStack> {
        return taskStackMap.values.toList()
    }
}
