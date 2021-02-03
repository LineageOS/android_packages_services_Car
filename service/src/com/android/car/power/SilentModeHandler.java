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
package com.android.car.power;

import android.annotation.NonNull;
import android.os.FileObserver;
import android.os.SystemProperties;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import libcore.io.IoUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Objects;

/**
 * Class to handle Silent Mode and Non-Silent Mode.
 *
 * <p>This monitors {@code /sys/power/pm_silentmode_hw_state} to figure out when to switch to Silent
 * Mode and updates {@code /sys/power/pm_silentmode_kernel} to tell early-init services about Silent
 * Mode change. Also, it handles forced Silent Mode for testing purpose, which is given through
 * reboot reason.
 */
final class SilentModeHandler {
    private static final String TAG = CarLog.TAG_POWER + "_"
            + SilentModeHandler.class.getSimpleName();

    private static final String SYSFS_FILENAME_GPIO_MONITORING =
            "/sys/power/pm_silentmode_hw_state";
    private static final String SYSFS_FILENAME_KERNEL_SILENTMODE =
            "/sys/power/pm_silentmode_kernel";
    private static final String VALUE_SILENT_MODE = "1";
    private static final String VALUE_NON_SILENT_MODE = "0";
    private static final String SYSTEM_BOOT_REASON = "sys.boot.reason";
    private static final String FORCED_NON_SILENT = "reboot,forcednonsilent";
    private static final String FORCED_SILENT = "reboot,forcedsilent";

    private final Object mLock = new Object();
    private final CarPowerManagementService mService;
    private final String mGpioMonitoringFileName;
    private final String mKernelSilentModeFileName;

    private FileObserver mFileObserver;
    @GuardedBy("mLock")
    private boolean mSilentModeByGpio;
    private boolean mForcedMode;

    SilentModeHandler(@NonNull CarPowerManagementService service) {
        this(service, SYSFS_FILENAME_GPIO_MONITORING, SYSFS_FILENAME_KERNEL_SILENTMODE,
                SystemProperties.get(SYSTEM_BOOT_REASON));
    }

    @VisibleForTesting
    SilentModeHandler(@NonNull CarPowerManagementService service,
            @NonNull String gpioMonitoringFileName, @NonNull String kernelSilentModeFileName,
            @NonNull String bootReason) {
        Objects.requireNonNull(service, "CarPowerManagementService must not be null");
        Objects.requireNonNull(gpioMonitoringFileName,
                "Filename for GPIO monitoring must not be null");
        Objects.requireNonNull(kernelSilentModeFileName,
                "Filename for Kernel silent mode must not be null");
        Objects.requireNonNull(bootReason, "Boot reason must not be null");
        mService = service;
        mGpioMonitoringFileName = gpioMonitoringFileName;
        mKernelSilentModeFileName = kernelSilentModeFileName;
        switch (bootReason) {
            case FORCED_SILENT:
                Slog.i(TAG, "Starting in forced silent mode");
                mForcedMode = true;
                mSilentModeByGpio = true;
                break;
            case FORCED_NON_SILENT:
                Slog.i(TAG, "Starting in forced non-silent mode");
                mForcedMode = true;
                mSilentModeByGpio = false;
                break;
            default:
                mForcedMode = false;
        }
    }

    void init() {
        if (mForcedMode) {
            // In forced mode, mSilentModeByGpio is set by shell command and not changed by real
            // GPIO signal. It's okay to access without lock.
            updateKernelSilentMode(mSilentModeByGpio);
            Slog.i(TAG, "Now in forced mode: monitoring " + mGpioMonitoringFileName
                    + " is disabled");
        } else {
            startMonitoringSilentModeGpio();
        }
    }

    void release() {
        if (mFileObserver != null) {
            mFileObserver.stopWatching();
            mFileObserver = null;
        }
    }

    void dump(IndentingPrintWriter writer) {
        synchronized (mLock) {
            writer.printf("Monitoring GPIO signal: %b\n", mFileObserver != null);
            writer.printf("Silent mode by GPIO signal: %b\n", mSilentModeByGpio);
            writer.printf("Forced silent mode: %b\n", mForcedMode);
        }
    }

    boolean isSilentMode() {
        synchronized (mLock) {
            return mSilentModeByGpio;
        }
    }

    void querySilentModeGpio() {
        if (mFileObserver != null) {
            mFileObserver.onEvent(FileObserver.MODIFY, mGpioMonitoringFileName);
        }
    }

    void updateKernelSilentMode(boolean silent) {
        try (BufferedWriter writer =
                new BufferedWriter(new FileWriter(mKernelSilentModeFileName))) {
            writer.write(silent ? VALUE_SILENT_MODE : VALUE_NON_SILENT_MODE);
            writer.flush();
        } catch (IOException e) {
            Slog.w(TAG, "Failed to update " + mKernelSilentModeFileName + " to "
                    + (silent ? VALUE_SILENT_MODE : VALUE_NON_SILENT_MODE));
        }
    }

    private void startMonitoringSilentModeGpio() {
        File monitorFile = new File(mGpioMonitoringFileName);
        if (!monitorFile.exists()) {
            Slog.w(TAG, "Failed to start monitoring Silent Mode GPIO: " + mGpioMonitoringFileName
                    + " doesn't exist");
            return;
        }
        mFileObserver = new FileObserver(monitorFile, FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String filename) {
                boolean newSilentMode;
                boolean oldSilentMode;
                synchronized (mLock) {
                    oldSilentMode = mSilentModeByGpio;
                    try {
                        String contents = IoUtils.readFileAsString(mGpioMonitoringFileName).trim();
                        mSilentModeByGpio = VALUE_SILENT_MODE.equals(contents);
                        Slog.i(TAG, mGpioMonitoringFileName + " indicates "
                                + (mSilentModeByGpio ? "silent" : "non-silent") + " mode");
                    } catch (Exception e) {
                        Slog.w(TAG, "Failed to read " + mGpioMonitoringFileName + ": " + e);
                        return;
                    }
                    newSilentMode = mSilentModeByGpio;
                }
                if (newSilentMode != oldSilentMode) {
                    mService.notifySilentModeChange(newSilentMode);
                }
            }
        };
        mFileObserver.startWatching();
        // Trigger the observer to get the initial contents
        querySilentModeGpio();
    }
}
