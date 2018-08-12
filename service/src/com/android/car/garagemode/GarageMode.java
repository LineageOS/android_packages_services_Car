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
import android.content.Intent;
import android.os.Handler;

import java.util.List;

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
    private boolean mGarageModeJobsRunning;
    private JobScheduler mJobScheduler;
    private Handler mHandler;
    private Runnable mRunnable;
    private List<JobInfo> mStartedJobs;

    GarageMode(Controller controller) {
        mGarageModeActive = false;
        mGarageModeJobsRunning = false;
        mController = controller;
        mJobScheduler = controller.getJobSchedulerService();
        mHandler = controller.getHandler();

        mRunnable = () -> {
            updateJobSnapshots();
            if (mStartedJobs.size() > 0) {
                mGarageModeJobsRunning = true;
                LOG.d("Some jobs are still running. Need to wait more ...");
                mHandler.postDelayed(mRunnable, JOB_SNAPSHOT_UPDATE_FREQUENCY_MS);
            } else {
                mGarageModeJobsRunning = false;
                exitGarageMode();
            }
        };
    }

    boolean isGarageModeActive() {
        return mGarageModeActive;
    }

    synchronized void enterGarageMode() {
        LOG.d("Entering GarageMode");
        mGarageModeActive = true;
        broadcastSignalToJobSchedulerTo(true);
        startMonitoringThread();
    }

    synchronized void exitGarageMode() {
        LOG.d("Exiting GarageMode");
        mGarageModeActive = false;
        broadcastSignalToJobSchedulerTo(false);
        mController.notifyGarageModeEndToPowerManager();
        stopMonitoringThread();
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

    private void startMonitoringThread() {
        mHandler.postDelayed(mRunnable, JOB_SNAPSHOT_INITIAL_UPDATE_MS);
    }

    private void stopMonitoringThread() {
        mHandler.removeCallbacks(mRunnable);
    }

    private synchronized void updateJobSnapshots() {
        mStartedJobs = mJobScheduler.getStartedJobs();
    }
}
