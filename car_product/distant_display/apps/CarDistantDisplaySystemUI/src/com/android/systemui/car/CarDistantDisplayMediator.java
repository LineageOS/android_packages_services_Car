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

package com.android.systemui.car;

import androidx.annotation.AnyThread;

import com.android.internal.annotations.GuardedBy;
import com.android.systemui.car.distantdisplay.common.DistantDisplayTaskManager;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provides a common connection between the DistantDisplayTaskManager and WM modules that can be
 * shared.
 *
 * It needs to be @Singleton scoped because it is used by a number of components in @WMSingleton &
 * @SysUISingleton scopes.
 */
@Singleton
public class CarDistantDisplayMediator {
    // TODO(b/358104325): Add unit tests for this class.
    private DistantDisplayTaskManager mDistantDisplayTaskManager;
    private boolean mIsDistantDisplayReady = false;
    /**
     * mListeners is guarded by itself.
     */
    @GuardedBy("mListeners")
    private final List<DistantDisplayTaskManagerListener> mListeners = new ArrayList<>();

    @Inject
    public CarDistantDisplayMediator() {}

    /**
     * Lets other components set the distant display task manager.
     */
    public void setDistantDisplayTaskManager(DistantDisplayTaskManager distantDisplayTaskManager) {
        mIsDistantDisplayReady = true;
        mDistantDisplayTaskManager = distantDisplayTaskManager;
        synchronized (mListeners) {
            for (DistantDisplayTaskManagerListener listener : mListeners) {
                listener.onTaskManagerReady(mDistantDisplayTaskManager);
            }
        }
    }

    /**
     * Let other components hook into the connection to the distant display. If we're already
     * connected to the distant display, the callback is immediately triggered.
     */
    @AnyThread
    public void addTaskManagerListener(DistantDisplayTaskManagerListener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
        }
        if (mIsDistantDisplayReady) {
            listener.onTaskManagerReady(mDistantDisplayTaskManager);
        }
    }

    /**
     * Remove a distant display task manager listener.
     */
    @AnyThread
    public void removeListener(DistantDisplayTaskManagerListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    /**
     * Listener which is triggered when distant display task manager is created.
     */
    public interface DistantDisplayTaskManagerListener {
        /** Called when the distant display task manager is ready. */
        void onTaskManagerReady(DistantDisplayTaskManager distantDisplayTaskManager);
    }
}
