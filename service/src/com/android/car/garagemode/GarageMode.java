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
import android.os.UserHandle;
import android.util.ArraySet;

import com.android.car.CarLocalServices;
import com.android.car.CarStatsLog;
import com.android.car.user.CarUserService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
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

    @VisibleForTesting
    static final long JOB_SNAPSHOT_INITIAL_UPDATE_MS = 10_000; // 10 seconds

    private static final long JOB_SNAPSHOT_UPDATE_FREQUENCY_MS = 1_000; // 1 second
    private static final long USER_STOP_CHECK_INTERVAL = 10_000; // 10 secs
    private static final int ADDITIONAL_CHECKS_TO_DO = 1;

    private final Controller mController;

    private boolean mGarageModeActive;
    private int mAdditionalChecksToDo = ADDITIONAL_CHECKS_TO_DO;
    private JobScheduler mJobScheduler;
    private Handler mHandler;
    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            int numberRunning = numberOfIdleJobsRunning();
            if (numberRunning > 0) {
                LOG.d("" + numberRunning + " jobs are still running. Need to wait more ...");
                mAdditionalChecksToDo = ADDITIONAL_CHECKS_TO_DO;
            } else {
                // No idle-mode jobs are running.
                // Are there any scheduled idle jobs that could run now?
                int numberReadyToRun = numberOfJobsPending();
                if (numberReadyToRun == 0) {
                    LOG.d("No jobs are running. No jobs are pending. Exiting Garage Mode.");
                    finish();
                    return;
                }
                if (mAdditionalChecksToDo == 0) {
                    LOG.d("No jobs are running. Waited too long for "
                            + numberReadyToRun + " pending jobs. Exiting Garage Mode.");
                    finish();
                    return;
                }
                LOG.d("No jobs are running. Waiting " + mAdditionalChecksToDo
                        + " more cycles for " + numberReadyToRun + " pending jobs.");
                mAdditionalChecksToDo--;
            }
            mHandler.postDelayed(mRunnable, JOB_SNAPSHOT_UPDATE_FREQUENCY_MS);
        }
    };

    private final Runnable mStopUserCheckRunnable = new Runnable() {
        @Override
        public void run() {
            int userToStop = UserHandle.USER_SYSTEM; // BG user never becomes system user.
            int remainingUsersToStop = 0;
            synchronized (this) {
                remainingUsersToStop = mStartedBackgroundUsers.size();
                if (remainingUsersToStop > 0) {
                    userToStop = mStartedBackgroundUsers.valueAt(0);
                } else {
                    return;
                }
            }
            if (numberOfIdleJobsRunning() == 0) { // all jobs done or stopped.
                // Keep user until job scheduling is stopped. Otherwise, it can crash jobs.
                if (userToStop != UserHandle.USER_SYSTEM) {
                    CarLocalServices.getService(CarUserService.class).stopBackgroundUser(
                            userToStop);
                    LOG.i("Stopping background user:" + userToStop + " remaining users:"
                            + (remainingUsersToStop - 1));
                }
                synchronized (this) {
                    mStartedBackgroundUsers.remove(userToStop);
                    if (mStartedBackgroundUsers.size() == 0) {
                        LOG.i("all background users stopped");
                        return;
                    }
                }
            } else {
                LOG.i("Waiting for jobs to finish, remaining users:" + remainingUsersToStop);
            }
            // Poll again
            mHandler.postDelayed(mStopUserCheckRunnable, USER_STOP_CHECK_INTERVAL);
        }
    };


    private CompletableFuture<Void> mFuture;
    private ArraySet<Integer> mStartedBackgroundUsers = new ArraySet<>();

    GarageMode(Controller controller) {
        mGarageModeActive = false;
        mController = controller;
        mJobScheduler = controller.getJobSchedulerService();
        mHandler = controller.getHandler();
    }

    boolean isGarageModeActive() {
        return mGarageModeActive;
    }

    List<String> dump() {
        List<String> outString = new ArrayList<>();
        if (!mGarageModeActive) {
            return outString;
        }
        List<String> jobList = new ArrayList<>();
        int numJobs = getListOfIdleJobsRunning(jobList);
        if (numJobs > 0) {
            outString.add("GarageMode is waiting for " + numJobs + " jobs:");
            // Dump the names of the jobs that we are waiting for
            for (int idx = 0; idx < jobList.size(); idx++) {
                outString.add("   " + (idx + 1) + ": " + jobList.get(idx));
            }
        } else {
            // Dump the names of the pending jobs that we are waiting for
            numJobs = getListOfPendingJobs(jobList);
            outString.add("GarageMode is waiting for " + jobList.size() + " pending idle jobs:");
            for (int idx = 0; idx < jobList.size(); idx++) {
                outString.add("   " + (idx + 1) + ": " + jobList.get(idx));
            }
        }
        return outString;
    }

    void enterGarageMode(CompletableFuture<Void> future) {
        LOG.d("Entering GarageMode");
        synchronized (this) {
            mGarageModeActive = true;
        }
        updateFuture(future);
        broadcastSignalToJobSchedulerTo(true);
        CarStatsLog.logGarageModeStart();
        startMonitoringThread();
        ArrayList<Integer> startedUsers =
                CarLocalServices.getService(CarUserService.class).startAllBackgroundUsers();
        synchronized (this) {
            mStartedBackgroundUsers.addAll(startedUsers);
        }
    }

    synchronized void cancel() {
        broadcastSignalToJobSchedulerTo(false);
        if (mFuture != null && !mFuture.isDone()) {
            mFuture.cancel(true);
        }
        mFuture = null;
        startBackgroundUserStopping();
    }

    synchronized void finish() {
        broadcastSignalToJobSchedulerTo(false);
        CarStatsLog.logGarageModeStop();
        mController.scheduleNextWakeup();
        synchronized (this) {
            if (mFuture != null && !mFuture.isDone()) {
                mFuture.complete(null);
            }
            mFuture = null;
        }
        startBackgroundUserStopping();
    }

    private void cleanupGarageMode() {
        LOG.d("Cleaning up GarageMode");
        synchronized (this) {
            mGarageModeActive = false;
        }
        stopMonitoringThread();
        mHandler.removeCallbacks(mRunnable);
        startBackgroundUserStopping();
    }

    private void startBackgroundUserStopping() {
        synchronized (this) {
            if (mStartedBackgroundUsers.size() > 0) {
                mHandler.postDelayed(mStopUserCheckRunnable, USER_STOP_CHECK_INTERVAL);
            }
        }
    }

    private void updateFuture(CompletableFuture<Void> future) {
        synchronized (this) {
            mFuture = future;
        }
        if (mFuture != null) {
            mFuture.whenComplete((result, exception) -> {
                if (exception == null) {
                    LOG.d("GarageMode completed normally");
                } else if (exception instanceof CancellationException) {
                    LOG.d("GarageMode was canceled");
                } else {
                    LOG.e("GarageMode ended due to exception: ", exception);
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
        mAdditionalChecksToDo = ADDITIONAL_CHECKS_TO_DO;
        mHandler.postDelayed(mRunnable, JOB_SNAPSHOT_INITIAL_UPDATE_MS);
    }

    private synchronized void stopMonitoringThread() {
        mHandler.removeCallbacks(mRunnable);
    }

    private int numberOfIdleJobsRunning() {
        return getListOfIdleJobsRunning(null);
    }

    private int getListOfIdleJobsRunning(List<String> jobList) {
        if (jobList != null) {
            jobList.clear();
        }
        List<JobInfo> startedJobs = mJobScheduler.getStartedJobs();
        if (startedJobs == null) {
            return 0;
        }
        int count = 0;
        for (int idx = 0; idx < startedJobs.size(); idx++) {
            JobInfo jobInfo = startedJobs.get(idx);
            if (jobInfo.isRequireDeviceIdle()) {
                count++;
                if (jobList != null) {
                    jobList.add(jobInfo.toString());
                }
            }
        }
        return count;
    }

    private int numberOfJobsPending() {
        return getListOfPendingJobs(null);
    }

    private int getListOfPendingJobs(List<String> jobList) {
        if (jobList != null) {
            jobList.clear();
        }
        List<JobSnapshot> allScheduledJobs = mJobScheduler.getAllJobSnapshots();
        if (allScheduledJobs == null) {
            return 0;
        }
        int numberPending = 0;
        for (int idx = 0; idx < allScheduledJobs.size(); idx++) {
            JobSnapshot scheduledJob = allScheduledJobs.get(idx);
            JobInfo jobInfo = scheduledJob.getJobInfo();
            if (scheduledJob.isRunnable() && jobInfo.isRequireDeviceIdle()) {
                numberPending++;
                if (jobList != null) {
                    jobList.add(jobInfo.toString());
                }
            }
        }
        return numberPending;
    }
}
