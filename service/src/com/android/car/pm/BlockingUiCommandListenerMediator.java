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

package com.android.car.pm;

import android.app.TaskInfo;
import android.car.builtin.app.TaskInfoHelper;
import android.car.builtin.util.Slogf;
import android.car.content.pm.ICarBlockingUiCommandListener;
import android.content.Intent;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;

import com.android.car.CarLog;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Class for controlling the {@link ICarBlockingUiCommandListener}.
 *
 * @hide
 */
public final class BlockingUiCommandListenerMediator {
    private static final String TAG = CarLog.tagFor(BlockingUiCommandListenerMediator.class);
    private static final boolean DBG = Slogf.isLoggable(TAG, Log.DEBUG);
    private static final int NO_LISTENERS_FOR_DISPLAY = 0;
    private static final int MAX_FINISHING_BLOCKING_UI_LOG_SIZE = 5;

    private final Object mLock = new Object();
    /**
     * Mapping between the display IDs and the BlockingUICommandListeners.
     */
    @GuardedBy("mLock")
    private final SparseArray<RemoteCallbackList<ICarBlockingUiCommandListener>>
            mDisplayToCarBlockingUiCommandListener = new SparseArray<>();
    // For dumpsys logging
    @GuardedBy("mLock")
    private final List<FinishLogs> mFinishingBlockingUiFinishLogs = new ArrayList<>(
            MAX_FINISHING_BLOCKING_UI_LOG_SIZE);

    /**
     * Registers the {@link ICarBlockingUiCommandListener} listening for the commands to control the
     * BlockingUI.
     *
     * @param listener  listener to register.
     * @param displayId display Id with which the listener is associated.
     */
    public void registerBlockingUiCommandListener(ICarBlockingUiCommandListener listener,
            int displayId) {
        synchronized (mLock) {
            RemoteCallbackList<ICarBlockingUiCommandListener> carBlockingUIRemoteCallbacks =
                    mDisplayToCarBlockingUiCommandListener.get(displayId);
            if (carBlockingUIRemoteCallbacks == null) {
                carBlockingUIRemoteCallbacks = new RemoteCallbackList<>();
                mDisplayToCarBlockingUiCommandListener.put(displayId, carBlockingUIRemoteCallbacks);
            }
            carBlockingUIRemoteCallbacks.register(listener);
        }
    }

    /**
     * Unregisters the {@link ICarBlockingUiCommandListener}.
     *
     * @param listener listener to unregister.
     */
    public void unregisterBlockingUiCommandListener(ICarBlockingUiCommandListener listener) {
        synchronized (mLock) {
            for (int i = 0; i < mDisplayToCarBlockingUiCommandListener.size(); i++) {
                int displayId = mDisplayToCarBlockingUiCommandListener.keyAt(i);
                RemoteCallbackList<ICarBlockingUiCommandListener> carBlockingUIRemoteCallbacks =
                        mDisplayToCarBlockingUiCommandListener.get(displayId);
                boolean unregistered = carBlockingUIRemoteCallbacks.unregister(listener);
                if (unregistered) {
                    if (carBlockingUIRemoteCallbacks.getRegisteredCallbackCount() == 0) {
                        mDisplayToCarBlockingUiCommandListener.remove(displayId);
                    }
                    return;
                }
            }
            Slogf.e(TAG, "BlockingUIListener already unregistered");
        }
    }

    /**
     * Broadcast the finish command to listeners.
     *
     * @param taskInfo           the {@link TaskInfo} due to which finish is broadcast to the
     *                           listeners.
     * @param lastKnownDisplayId the last known display id where the task changed or vanished.
     */
    public void finishBlockingUi(TaskInfo taskInfo, int lastKnownDisplayId) {
        synchronized (mLock) {
            int displayId = getDisplayId(taskInfo, lastKnownDisplayId);
            if (!mDisplayToCarBlockingUiCommandListener.contains(displayId)) {
                Slogf.e(TAG, "No BlockingUI listeners for display Id %d", displayId);
                return;
            }
            addFinishBlockingUiTransitionLogLocked(taskInfo, displayId);
            RemoteCallbackList<ICarBlockingUiCommandListener> carBlockingUIRemoteCallbacks =
                    mDisplayToCarBlockingUiCommandListener.get(displayId);
            int numCallbacks = carBlockingUIRemoteCallbacks.beginBroadcast();
            if (DBG) {
                Slogf.d(TAG, "Broadcasting finishBlockingUi to %d callbacks for %d display ID",
                        numCallbacks, displayId);
            }
            for (int i = 0; i < numCallbacks; i++) {
                try {
                    carBlockingUIRemoteCallbacks.getBroadcastItem(i).finishBlockingUi();
                } catch (RemoteException remoteException) {
                    Slogf.e(TAG, "Error dispatching finishBlockingUi", remoteException);
                }
            }
            carBlockingUIRemoteCallbacks.finishBroadcast();
        }
    }

    private static int getDisplayId(TaskInfo taskInfo, int lastKnownDisplayId) {
        int displayId = TaskInfoHelper.getDisplayId(taskInfo);
        // Display ID can often be invalid when the task has vanished.
        if (displayId == Display.INVALID_DISPLAY) {
            displayId = lastKnownDisplayId;
        }
        return displayId;
    }

    /**
     * Returns the number of registered callbacks for the display ID.
     *
     * @param displayId display Id with which the listener is associated.
     * @return number of registered callbacks for the given {@code displayId}.
     */
    @VisibleForTesting
    public int getCarBlockingUiCommandListenerRegisteredCallbacksForDisplay(int displayId) {
        synchronized (mLock) {
            if (mDisplayToCarBlockingUiCommandListener.get(displayId) != null) {
                return mDisplayToCarBlockingUiCommandListener
                        .get(displayId).getRegisteredCallbackCount();
            }
            return NO_LISTENERS_FOR_DISPLAY;
        }
    }

    /** Log information why the blocking ui was finished. */
    @GuardedBy("mLock")
    private void addFinishBlockingUiTransitionLogLocked(TaskInfo taskInfo, int displayId) {
        if (mFinishingBlockingUiFinishLogs.size() >= MAX_FINISHING_BLOCKING_UI_LOG_SIZE) {
            mFinishingBlockingUiFinishLogs.removeFirst();
        }
        FinishLogs log = new FinishLogs(taskInfo.taskId, taskInfo.baseIntent, displayId,
                System.currentTimeMillis());
        mFinishingBlockingUiFinishLogs.add(log);
    }

    /** Dump the {@code mFinishingBlockingUiFinishLogs}. */
    public void dump(IndentingPrintWriter writer) {
        synchronized (mLock) {
            writer.println("*BlockingUiCommandListenerMediator*");
            writer.println("Finishing BlockingUi logs:");
            for (int i = 0; i < mFinishingBlockingUiFinishLogs.size(); i++) {
                writer.println(mFinishingBlockingUiFinishLogs.get(i).toString());
            }
        }
    }

    /**
     * An utility class to dump blocking ui finishing logs.
     */
    private static final class FinishLogs {
        private final int mTaskId;
        private final Intent mBaseIntent;
        private final int mDisplayId;
        private final long mTimestampMs;

        FinishLogs(int taskId, Intent baseIntent, int displayId, long timestampMs) {
            mTaskId = taskId;
            mBaseIntent = baseIntent;
            mDisplayId = displayId;
            mTimestampMs = timestampMs;
        }

        private CharSequence timeToLog(long timestamp) {
            return android.text.format.DateFormat.format("MM-dd HH:mm:ss", timestamp);
        }

        @Override
        public String toString() {
            return "Finish BlockingUI, reason: taskId=" + mTaskId + ", baseIntent=" + mBaseIntent
                    + ", displayId=" + mDisplayId + " at timestamp=" + timeToLog(mTimestampMs);
        }
    }
}
