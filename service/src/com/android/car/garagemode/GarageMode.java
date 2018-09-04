/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.garagemode;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobSnapshot;
import android.content.Intent;
import android.os.Handler;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Class that interacts with JobScheduler, controls system idleness and monitor jobs which are
 * in GarageMode interest
 */

class GarageMode {
    private static final Logger LOG = new Logger("GarageMode");

    /**
     * When changing this field value, please update
     * {@link com.android.server.job.controllers.idle.CarIdlenessTracker} as well.
     */
    public static final String ACTION_GARAGE_MODE_ON =
            "com.android.server.jobscheduler.GARAGE_MODE_ON";

    /**
     * When changing this field value, please update
     * {@link com.android.server.job.controllers.idle.CarIdlenessTracker} as well.
     */
    public static final String ACTION_GARAGE_MODE_OFF =
            "com.android.server.jobscheduler.GARAGE_MODE_OFF";

    static final long JOB_SNAPSHOT_INITIAL_UPDATE_MS = 10000; // 10 seconds
    static final long JOB_SNAPSHOT_UPDATE_FREQUENCY_MS = 1000; // 1 second

    private final Controller mController;

    private boolean mGarageModeActive;
    private JobScheduler mJobScheduler;
    private Handler mHandler;
    private Runnable mRunnable;
    private CompletableFuture<Void> mFuture;

    GarageMode(Controller controller) {
        mGarageModeActive = false;
        mController = controller;
        mJobScheduler = controller.getJobSchedulerService();
        mHandler = controller.getHandler();

        mRunnable = () -> {
            if (areAnyIdleJobsRunning()) {
                LOG.d("Some jobs are still running. Need to wait more ...");
                mHandler.postDelayed(mRunnable, JOB_SNAPSHOT_UPDATE_FREQUENCY_MS);
            } else {
                LOG.d("No jobs are currently running.");
                finish();
            }
        };
    }

    boolean isGarageModeActive() {
        return mGarageModeActive;
    }

    void enterGarageMode(CompletableFuture<Void> future) {
        LOG.d("Entering GarageMode");
        synchronized (this) {
            mGarageModeActive = true;
        }
        updateFuture(future);
        broadcastSignalToJobSchedulerTo(true);
        startMonitoringThread();
    }

    synchronized void cancel() {
        if (mFuture != null && !mFuture.isDone()) {
            mFuture.cancel(true);
        }
        mFuture = null;
    }

    synchronized void finish() {
        mController.scheduleNextWakeup();
        synchronized (this) {
            if (mFuture != null && !mFuture.isDone()) {
                mFuture.complete(null);
            }
            mFuture = null;
        }
    }

    private void cleanupGarageMode() {
        LOG.d("Cleaning up GarageMode");
        synchronized (this) {
            mGarageModeActive = false;
        }
        broadcastSignalToJobSchedulerTo(false);
        stopMonitoringThread();
    }

    private void updateFuture(CompletableFuture<Void> future) {
        synchronized (this) {
            mFuture = future;
        }
        if (mFuture != null) {
            mFuture.whenComplete((result, exception) -> {
                if (exception != null) {
                    LOG.e("Seems like GarageMode got canceled, cleaning up", exception);
                } else {
                    LOG.d("Seems like GarageMode is completed, cleaning up");
                }
                cleanupGarageMode();
            });
        }
    }

    private void broadcastSignalToJobSchedulerTo(boolean enableGarageMode) {
        Intent i = new Intent();
        if (enableGarageMode) {
            i.setAction(ACTION_GARAGE_MODE_ON);
        } else {
            i.setAction(ACTION_GARAGE_MODE_OFF);
        }
        i.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_NO_ABORT);
        mController.sendBroadcast(i);
    }

    private synchronized void startMonitoringThread() {
        mHandler.postDelayed(mRunnable, JOB_SNAPSHOT_INITIAL_UPDATE_MS);
    }

    private synchronized void stopMonitoringThread() {
        mHandler.removeCallbacks(mRunnable);
    }

    private boolean areAnyIdleJobsRunning() {
        List<JobInfo> startedJobs = mJobScheduler.getStartedJobs();
        for (JobSnapshot snap : mJobScheduler.getAllJobSnapshots()) {
            if (startedJobs.contains(snap.getJobInfo())) {
                if (snap.getJobInfo().isRequireDeviceIdle()) {
                    return true;
                }
            }
        }
        return false;
    }
}
