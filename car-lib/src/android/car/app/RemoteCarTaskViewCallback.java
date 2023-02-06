/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.car.app;

import android.app.ActivityManager;

/**
 * A callback interface for the host activity that uses {@link RemoteCarTaskView} and its
 * derivatives.
 * @hide
 */
interface RemoteCarTaskViewCallback<T extends RemoteCarTaskView> {
    /**
     * Called when the underlying {@link RemoteCarTaskView} instance is created.
     *
     * @param taskView the new newly created {@link RemoteCarTaskView} instance.
     */
    default void onTaskViewCreated(T taskView) {}

    /**
     * Called when the underlying {@link RemoteCarTaskView} is ready. A {@link RemoteCarTaskView}
     * can be considered ready when it has completed all the set up that is required.
     * This callback is only triggered once.
     */
    default void onTaskViewInitialized() {}

    /**
     * Called when the underlying {@link RemoteCarTaskView} is released.
     * This callback is only triggered once.
     */
    default void onTaskViewReleased() {}

    /**
     * Called when the task has appeared in the taskview.
     *
     * @param taskInfo the taskInfo of the task that has appeared.
     */
    default void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo) {}

    /**
     * Called when the task's info has changed.
     *
     * @param taskInfo the taskInfo of the task that has a change in info.
     */
    default void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {}

    /**
     * Called when the task has vanished.
     *
     * @param taskInfo the taskInfo of the task that has vanished.
     */
    default void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {}
}
