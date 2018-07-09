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

    private static final String PREFS_NEXT_JOB_ID = "next_job_id";

    private JobScheduler mJobScheduler;
    private Context mContext;
    private ListView mListView;
    private Handler mHandler;

    JobSchedulerWrapper(Context context, ListView listView) {
        mContext = context;
        mJobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        mListView = listView;
        mHandler = new Handler();

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
                mHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    public synchronized void refresh() {
        LOG.d("Refreshing JobSchedulerWrapper list");
        List<JobInfoRow> list = convertToJobInfoRows();
        mListView.setAdapter(
                new JobInfoRowArrayAdapter(mContext, mListView.getId(), list));
    }

    public void scheduleAJob(
            int amountOfSeconds,
            int networkType,
            boolean isChargingRequired,
            boolean isIdleRequired) {
        ComponentName jobComponentName = new ComponentName(mContext, DishService.class);
        SharedPreferences prefs = mContext
                .getSharedPreferences(PREFS_NEXT_JOB_ID, Context.MODE_PRIVATE);
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
        Toast.makeText(mContext, "Scheduled: " + jobInfo, Toast.LENGTH_LONG).show();

        LOG.d("Scheduled a job: " + jobInfo);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(PREFS_NEXT_JOB_ID, jobId + 1);
        editor.commit();

        refresh();
    }

    private List<JobInfoRow> convertToJobInfoRows() {
        List<JobInfoRow> listJobs = new LinkedList<>();

        List<JobInfo> jobs = mJobScheduler.getAllPendingJobs();
        for (JobInfo job : jobs) {
            listJobs.add(new JobInfoRow(job.getId()));
        }
        LOG.d("Returning list of jobs: " + listJobs.size());
        return listJobs;
    }
}
