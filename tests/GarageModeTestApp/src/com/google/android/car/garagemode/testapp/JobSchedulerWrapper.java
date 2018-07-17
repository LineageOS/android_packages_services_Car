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
package com.google.android.car.garagemode.testapp;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PersistableBundle;
import android.widget.ListView;
import android.widget.Toast;

import java.util.LinkedList;
import java.util.List;

class JobSchedulerWrapper {
    private static final Logger LOG = new Logger("JobSchedulerWrapper");

    private static final String PREFS_FILE_NAME = "garage_mode_job_scheduler";
    private static final String PREFS_NEXT_JOB_ID = "next_job_id";

    private JobScheduler mJobScheduler;
    private Context mContext;
    private ListView mListView;
    private Handler mHandler;
    private Watchdog mWatchdog;
    private List<JobInfoRow> mLastList;
    private Runnable mRefreshWorker;

    JobSchedulerWrapper(Context context, ListView listView) {
        mContext = context;
        mJobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        mListView = listView;
    }

    public void setWatchdog(Watchdog watchdog) {
        mWatchdog = watchdog;
    }

    public synchronized void refresh() {
        List<JobInfoRow> list = convertToJobInfoRows();

        reportNewJobs(mLastList, list);
        reportCompletedJobs(mLastList, list);

        mLastList = list;
        mListView.setAdapter(
                new JobInfoRowArrayAdapter(mContext, mListView.getId(), list));
    }

    public void start() {
        LOG.d("Starting JobSchedulerWrapper");
        mHandler = new Handler();
        mRefreshWorker = () -> {
            refresh();
            mHandler.postDelayed(mRefreshWorker, 1000);
        };
        mHandler.postDelayed(mRefreshWorker, 1000);
    }

    public void stop() {
        LOG.d("Stopping JobSchedulerWrapper");
        mHandler.removeCallbacks(mRefreshWorker);
        mRefreshWorker = null;
        mHandler = null;
        mWatchdog = null;
    }

    public void scheduleAJob(
            int amountOfSeconds,
            int networkType,
            boolean isChargingRequired,
            boolean isIdleRequired) {
        ComponentName jobComponentName = new ComponentName(mContext, DishService.class);
        SharedPreferences prefs = mContext
                .getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE);
        int jobId = prefs.getInt(PREFS_NEXT_JOB_ID, 0);
        PersistableBundle bundle = new PersistableBundle();

        bundle.putInt(DishService.EXTRA_DISH_COUNT, amountOfSeconds);

        JobInfo jobInfo = new JobInfo.Builder(jobId, jobComponentName)
                .setRequiresCharging(isChargingRequired)
                .setRequiresDeviceIdle(isIdleRequired)
                .setExtras(bundle)
                .setRequiredNetworkType(networkType)
                .build();

        mJobScheduler.schedule(jobInfo);
        Toast.makeText(
                mContext,
                "Scheduled new job with id: " + jobInfo.getId(), Toast.LENGTH_LONG).show();

        LOG.d("Scheduled a job: " + jobInfo);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(PREFS_NEXT_JOB_ID, jobId + 1);
        editor.commit();

        refresh();
    }

    private void reportNewJobs(List<JobInfoRow> oldList, List<JobInfoRow> newList) {
        // TODO(serikb): Improve complexity of the search
        if (oldList != null && newList != null) {
            boolean found;
            for (JobInfoRow newInfo : newList) {
                found = false;
                for (JobInfoRow oldInfo : oldList) {
                    if (newInfo.getId().equals(oldInfo.getId())) {
                        found = true;
                        break;
                    }
                }
                if (!found && mWatchdog != null) {
                    mWatchdog.logEvent(
                            "New job with id(" + newInfo.getId() + ") has been scheduled");
                }
            }
        }
    }

    private void reportCompletedJobs(List<JobInfoRow> oldList, List<JobInfoRow> newList) {
        // TODO(serikb): Improve complexity of the search
        if (oldList != null && newList != null) {
            boolean found;
            for (JobInfoRow oldInfo : oldList) {
                found = false;
                for (JobInfoRow newInfo : newList) {
                    if (oldInfo.getId().equals(newInfo.getId())) {
                        found = true;
                        break;
                    }
                }
                if (!found && mWatchdog != null) {
                    mWatchdog.logEvent("Job with id(" + oldInfo.getId() + ") has been completed.");
                }
            }
        }
    }

    private List<JobInfoRow> convertToJobInfoRows() {
        List<JobInfoRow> listJobs = new LinkedList<>();

        List<JobInfo> jobs = mJobScheduler.getAllPendingJobs();
        for (JobInfo job : jobs) {
            listJobs.add(new JobInfoRow(job.getId()));
        }
        return listJobs;
    }
}
