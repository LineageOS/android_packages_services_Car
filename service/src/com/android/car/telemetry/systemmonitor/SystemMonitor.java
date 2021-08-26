/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.telemetry.systemmonitor;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.content.Context;
import android.os.Handler;
import android.util.Slog;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * SystemMonitor monitors system states and report to listeners when there are
 * important changes.
 */
public class SystemMonitor {

    private static final int NUM_LOADAVG_VALS = 3;
    private static final float HI_CPU_LOAD_PER_CORE_BASE_LEVEL = 1.0f;
    private static final float MED_CPU_LOAD_PER_CORE_BASE_LEVEL = 0.5f;
    private static final float HI_MEM_LOAD_BASE_LEVEL = 0.95f;
    private static final float MED_MEM_LOAD_BASE_LEVEL = 0.80f;
    private static final String LOADAVG_PATH = "/proc/loadavg";

    private static final int POLL_INTERVAL_MILLIS = 60000;

    private final Handler mWorkerHandler;

    private final Object mLock = new Object();

    private final Context mContext;
    private final ActivityManager mActivityManager;
    private final String mLoadavgPath;
    private final Runnable mSystemLoadRunnable = this::getSystemLoadRepeated;

    @GuardedBy("mLock")
    @Nullable private SystemMonitorCallback mCallback;
    @GuardedBy("mLock")
    private boolean mSystemMonitorRunning = false;


    /**
     * Interface for receiving notifications about system monitor changes.
     */
    public interface SystemMonitorCallback {
        /**
         * Listens to system monitor event.
         *
         * @param event the system monitor event.
         */
        void onSystemMonitorEvent(SystemMonitorEvent event);
    }

    /**
     * Creates a SystemMonitor instance set with default loadavg path.
     *
     * @param context the context this is running in.
     * @param workerHandler a handler for running monitoring jobs.
     * @return SystemMonitor instance.
     */
    public static SystemMonitor create(Context context, Handler workerHandler) {
        return new SystemMonitor(context, workerHandler, LOADAVG_PATH);
    }

    @VisibleForTesting
    SystemMonitor(Context context, Handler workerHandler, String loadavgPath) {
        mContext = context;
        mWorkerHandler = workerHandler;
        mActivityManager = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mLoadavgPath = loadavgPath;
    }

    /**
     * Sets the {@link SystemMonitorCallback} to notify of system state changes.
     *
     * @param callback the callback to nofify state changes on.
     */
    public void setSystemMonitorCallback(SystemMonitorCallback callback) {
        synchronized (mLock) {
            mCallback = callback;
            if (!mSystemMonitorRunning) {
                startSystemLoadMonitoring();
            }
        }
    }

    /**
     * Unsets the {@link SystemMonitorCallback}.
     */
    public void unsetSystemMonitorCallback() {
        synchronized (mLock) {
            stopSystemLoadMonitoringLocked();
            mCallback = null;
        }
    }

    /**
     * Gets the loadavg data from /proc/loadavg, getting the first 3 averages,
     * which are 1-min, 5-min and 15-min moving averages respectively.
     *
     * Requires Selinux permissions 'open', 'read, 'getattr' to proc_loadavg,
     * which is set in Car/car_product/sepolicy/private/carservice_app.te.
     *
     * @return the {@link CpuLoadavg}.
     */
    @VisibleForTesting
    @Nullable
    CpuLoadavg getCpuLoad() {
        try (BufferedReader reader = new BufferedReader(new FileReader(mLoadavgPath))) {
            String line = reader.readLine();
            String[] vals = line.split("\\s+", NUM_LOADAVG_VALS + 1);
            if (vals.length < NUM_LOADAVG_VALS) {
                Slog.w(CarLog.TAG_TELEMETRY, "Loadavg wrong format");
                return null;
            }
            CpuLoadavg cpuLoadavg = new CpuLoadavg();
            cpuLoadavg.mOneMinuteVal = Float.parseFloat(vals[0]);
            cpuLoadavg.mFiveMinutesVal = Float.parseFloat(vals[1]);
            cpuLoadavg.mFifteenMinutesVal = Float.parseFloat(vals[2]);
            return cpuLoadavg;
        } catch (IOException | NumberFormatException ex) {
            Slog.w(CarLog.TAG_TELEMETRY, "Failed to read loadavg file.", ex);
            return null;
        }
    }

    /**
     * Gets the {@link ActivityManager.MemoryInfo} for system memory pressure.
     *
     * Of the MemoryInfo fields, we will only be using availMem and totalMem,
     * since lowMemory and threshold are likely deprecated.
     *
     * @return {@link MemoryInfo} for the system.
     */
    private MemoryInfo getMemoryLoad() {
        MemoryInfo mi = new ActivityManager.MemoryInfo();
        mActivityManager.getMemoryInfo(mi);
        return mi;
    }

    /**
     * Sets the CPU usage level for a {@link SystemMonitorEvent}.
     *
     * @param event the {@link SystemMonitorEvent}.
     * @param cpuLoadPerCore the CPU load average per CPU core.
     */
    @VisibleForTesting
    void setEventCpuUsageLevel(SystemMonitorEvent event, double cpuLoadPerCore) {
        if (cpuLoadPerCore > HI_CPU_LOAD_PER_CORE_BASE_LEVEL) {
            event.setCpuUsageLevel(SystemMonitorEvent.USAGE_LEVEL_HI);
        } else if (cpuLoadPerCore > MED_CPU_LOAD_PER_CORE_BASE_LEVEL
                   && cpuLoadPerCore <= HI_CPU_LOAD_PER_CORE_BASE_LEVEL) {
            event.setCpuUsageLevel(SystemMonitorEvent.USAGE_LEVEL_MED);
        } else {
            event.setCpuUsageLevel(SystemMonitorEvent.USAGE_LEVEL_LOW);
        }
    }

    /**
     * Sets the memory usage level for a {@link SystemMonitorEvent}.
     *
     * @param event the {@link SystemMonitorEvent}.
     * @param memLoadRatio ratio of used memory to total memory.
     */
    @VisibleForTesting
    void setEventMemUsageLevel(SystemMonitorEvent event, double memLoadRatio) {
        if (memLoadRatio > HI_MEM_LOAD_BASE_LEVEL) {
            event.setMemoryUsageLevel(SystemMonitorEvent.USAGE_LEVEL_HI);
        } else if (memLoadRatio > MED_MEM_LOAD_BASE_LEVEL
                   && memLoadRatio <= HI_MEM_LOAD_BASE_LEVEL) {
            event.setMemoryUsageLevel(SystemMonitorEvent.USAGE_LEVEL_MED);
        } else {
            event.setMemoryUsageLevel(SystemMonitorEvent.USAGE_LEVEL_LOW);
        }
    }

    /**
     * The Runnable to repeatedly getting system load data with some interval.
     */
    private void getSystemLoadRepeated() {
        synchronized (mLock) {
            try {
                CpuLoadavg cpuLoadAvg = getCpuLoad();
                if (cpuLoadAvg == null) {
                    return;
                }
                int numProcessors = Runtime.getRuntime().availableProcessors();

                MemoryInfo memInfo = getMemoryLoad();

                SystemMonitorEvent event = new SystemMonitorEvent();
                setEventCpuUsageLevel(event, cpuLoadAvg.mOneMinuteVal / numProcessors);
                setEventMemUsageLevel(event, 1 - memInfo.availMem / memInfo.totalMem);

                mCallback.onSystemMonitorEvent(event);
            } finally {
                if (mSystemMonitorRunning) {
                    mWorkerHandler.postDelayed(mSystemLoadRunnable, POLL_INTERVAL_MILLIS);
                }
            }
        }
    }

    /**
     * Starts system load monitoring.
     */
    private void startSystemLoadMonitoring() {
        synchronized (mLock) {
            mWorkerHandler.post(mSystemLoadRunnable);
            mSystemMonitorRunning = true;
        }
    }

    /**
     * Stops system load monitoring.
     */
    @GuardedBy("mLock")
    private void stopSystemLoadMonitoringLocked() {
        synchronized (mLock) {
            mWorkerHandler.removeCallbacks(mSystemLoadRunnable);
            mSystemMonitorRunning = false;
        }
    }

    static final class CpuLoadavg {
        float mOneMinuteVal;
        float mFiveMinutesVal;
        float mFifteenMinutesVal;
    }
}
