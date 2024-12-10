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

package com.android.systemui.car.wm;

import static android.car.feature.Flags.distantDisplayTransitions;

import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.car.Car;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;
import android.window.DisplayAreaInfo;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.Dumpable;
import com.android.systemui.car.CarDistantDisplayMediator;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.distantdisplay.common.DistantDisplayForegroundTaskMap.TaskData;
import com.android.systemui.car.distantdisplay.common.DistantDisplayTaskManager;
import com.android.systemui.dump.DumpManager;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.shared.TransitionUtil;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * This class is the shell transition handler for distant displays which enforces the distant
 * display policy.
 */
@WMSingleton
public class DistantDisplayTransitions implements Transitions.TransitionHandler,
        CarServiceProvider.CarServiceOnConnectedListener, Dumpable,
        CarDistantDisplayMediator.DistantDisplayTaskManagerListener {
    // TODO(b/358104325): Add unit tests for this class.
    private static final String TAG = "DistantDisplayTransit";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final SparseArray<WindowContainerToken> mDisplayIdToRootTDAToken = new SparseArray<>();
    private final DisplayManager mDisplayManager;

    private DistantDisplayTaskManager mDistantDisplayTaskManager;
    private int mDistantDisplayId = Display.INVALID_DISPLAY;

    @Inject
    public DistantDisplayTransitions(Transitions transitions,
            Context context,
            CarServiceProvider carServiceProvider,
            CarDistantDisplayMediator carDistantDisplayMediator,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            DumpManager dumpManager) {
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
        if (!distantDisplayTransitions()) {
            if (DBG) {
                Slog.d(TAG, "Not initializing DistantDisplayTransitions, as flag is disabled");
            }
            return;
        }

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.addHandler(this);
            carServiceProvider.addListener(this);
            dumpManager.registerDumpable(this);
            carDistantDisplayMediator.addTaskManagerListener(this);
            mDisplayManager.registerDisplayListener(mDisplayListener,
                    new Handler(Looper.getMainLooper()));
        } else {
            Slog.e(TAG,
                    "Not initializing DistantDisplayTransitions, as shell transitions are "
                            + "disabled");
        }
    }

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    mRootTaskDisplayAreaOrganizer.registerListener(displayId, mRootTDAListener);
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    // No-op
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    // No-op
                }
            };

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        if (request.getTriggerTask() == null) {
            return null;
        }

        WindowContainerTransaction wct = null;
        if (mDistantDisplayTaskManager != null) {
            mDistantDisplayId = mDistantDisplayTaskManager.getDistantDisplayId();
        }

        if (TransitionUtil.isOpeningType(request.getType()) && !isHome(request.getTriggerTask())
                && isAlreadyOnDistantDisplay(request.getTriggerTask())) {
            TaskData taskData = getTopTaskOnDistantDisplay();
            if (DBG) {
                Slog.d(TAG, "Task " + taskData.mBaseIntent
                        + " already running on the distant display. Reparent it to the same "
                        + "display");
            }
            wct = new WindowContainerTransaction();
            wct.reparent(taskData.mToken, mDisplayIdToRootTDAToken.get(mDistantDisplayId), true);
        }
        return wct;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        // TODO(b/350004828): Temporarily return false always
        return false;
    }

    private final RootTaskDisplayAreaOrganizer.RootTaskDisplayAreaListener mRootTDAListener =
            new RootTaskDisplayAreaOrganizer.RootTaskDisplayAreaListener() {
                @Override
                public void onDisplayAreaAppeared(DisplayAreaInfo displayAreaInfo) {
                    if (DBG) {
                        Slog.d(TAG, "onDisplayAreaAppeared: " + displayAreaInfo);
                    }
                    int displayId = displayAreaInfo.displayId;
                    // TODO(b/360724449): Revisit this logic when architecture moved to 
                    //  DisplayAreas since there can be multiple DisplayAreas now.
                    mDisplayIdToRootTDAToken.put(displayId, displayAreaInfo.token);
                }

                @Override
                public void onDisplayAreaVanished(DisplayAreaInfo displayAreaInfo) {
                    if (DBG) {
                        Slog.d(TAG, "onDisplayAreaVanished: " + displayAreaInfo);
                    }
                    int displayId = displayAreaInfo.displayId;
                    mDisplayIdToRootTDAToken.remove(displayId);
                }

                @Override
                public void onDisplayAreaInfoChanged(DisplayAreaInfo displayAreaInfo) {
                    if (DBG) {
                        Slog.d(TAG, "onDisplayAreaInfoChanged: " + displayAreaInfo);
                    }
                    int displayId = displayAreaInfo.displayId;
                    mDisplayIdToRootTDAToken.put(displayId, displayAreaInfo.token);
                }
            };

    private static boolean isHome(ActivityManager.RunningTaskInfo taskInfo) {
        return taskInfo.getActivityType() == WindowConfiguration.ACTIVITY_TYPE_HOME;
    }

    private TaskData getTopTaskOnDistantDisplay() {
        if (mDistantDisplayTaskManager != null) {
            return mDistantDisplayTaskManager.getTopTaskOnDisplay(mDistantDisplayId);
        }
        return null;
    }

    private boolean isAlreadyOnDistantDisplay(ActivityManager.RunningTaskInfo taskInfo) {
        TaskData taskData = getTopTaskOnDistantDisplay();
        if (taskData != null) {
            return taskData.mTaskId == taskInfo.taskId && taskData.mDisplayId == mDistantDisplayId;
        }
        return false;
    }

    private void initializeRootTDAOrganizer() {
        Display[] displays = mDisplayManager.getDisplays();
        for (int i = 0; i < displays.length; i++) {
            mRootTaskDisplayAreaOrganizer.registerListener(displays[i].getDisplayId(),
                    mRootTDAListener);
        }
    }

    @Override
    public void dump(@NonNull PrintWriter writer, @Nullable String[] args) {
        writer.println("  mDistantDisplayId:" + mDistantDisplayId);
        // TODO(b/355002192): Add more dumping parameters
    }

    @Override
    public void onConnected(Car car) {
        initializeRootTDAOrganizer();
    }

    @Override
    public void onTaskManagerReady(DistantDisplayTaskManager distantDisplayTaskManager) {
        mDistantDisplayTaskManager = distantDisplayTaskManager;
        mDistantDisplayId = mDistantDisplayTaskManager.getDistantDisplayId();
    }
}
