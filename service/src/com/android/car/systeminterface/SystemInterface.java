/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.car.systeminterface;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

import com.android.car.power.CarPowerManagementService;
import com.android.car.procfsinspector.ProcessInfo;
import com.android.car.storagemonitoring.LifetimeWriteInfoProvider;
import com.android.car.storagemonitoring.UidIoStatsProvider;
import com.android.car.storagemonitoring.WearInformationProvider;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * This class contains references to all the different wrapper interfaces between
 * CarService and the Android OS APIs.
 */
public class SystemInterface implements ActivityManagerInterface,
        DisplayInterface, IOInterface, StorageMonitoringInterface,
        SystemStateInterface, TimeInterface,
        WakeLockInterface {
    private final ActivityManagerInterface mActivityManagerInterface;
    private final DisplayInterface mDisplayInterface;
    private final IOInterface mIOInterface;
    private final StorageMonitoringInterface mStorageMonitoringInterface;
    private final SystemStateInterface mSystemStateInterface;
    private final TimeInterface mTimeInterface;
    private final WakeLockInterface mWakeLockInterface;

    SystemInterface(ActivityManagerInterface activityManagerInterface,
            DisplayInterface displayInterface,
            IOInterface ioInterface,
            StorageMonitoringInterface storageMonitoringInterface,
            SystemStateInterface systemStateInterface,
            TimeInterface timeInterface,
            WakeLockInterface wakeLockInterface) {
        mActivityManagerInterface = activityManagerInterface;
        mDisplayInterface = displayInterface;
        mIOInterface = ioInterface;
        mStorageMonitoringInterface = storageMonitoringInterface;
        mSystemStateInterface = systemStateInterface;
        mTimeInterface = timeInterface;
        mWakeLockInterface = wakeLockInterface;
    }

    public ActivityManagerInterface getActivityManagerInterface() {
        return mActivityManagerInterface;
    }
    public DisplayInterface getDisplayInterface() { return mDisplayInterface; }
    public IOInterface getIOInterface() { return mIOInterface; }
    public SystemStateInterface getSystemStateInterface() { return mSystemStateInterface; }
    public TimeInterface getTimeInterface() { return mTimeInterface; }
    public WakeLockInterface getWakeLockInterface() { return mWakeLockInterface; }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user) {
        mActivityManagerInterface.sendBroadcastAsUser(intent, user);
    }

    @Override
    public File getSystemCarDir() {
        return mIOInterface.getSystemCarDir();
    }

    @Override
    public void releaseAllWakeLocks() {
        mWakeLockInterface.releaseAllWakeLocks();
    }

    @Override
    public void switchToPartialWakeLock() {
        mWakeLockInterface.switchToPartialWakeLock();
    }

    @Override
    public void switchToFullWakeLock() {
        mWakeLockInterface.switchToFullWakeLock();
    }

    @Override
    public long getUptime() {
        return mTimeInterface.getUptime();
    }

    @Override
    public long getUptime(boolean includeDeepSleepTime) {
        return mTimeInterface.getUptime(includeDeepSleepTime);
    }

    @Override
    public void scheduleAction(Runnable r, long delayMs) {
        mTimeInterface.scheduleAction(r, delayMs);
    }

    @Override
    public List<ProcessInfo> getRunningProcesses() {
        return mSystemStateInterface.getRunningProcesses();
    }

    @Override
    public void cancelAllActions() {
        mTimeInterface.cancelAllActions();
    }

    @Override
    public void setDisplayBrightness(int brightness) {
        mDisplayInterface.setDisplayBrightness(brightness);
    }

    @Override
    public void setDisplayState(boolean on) {
        mDisplayInterface.setDisplayState(on);
    }

    @Override
    public void startDisplayStateMonitoring(CarPowerManagementService service) {
        mDisplayInterface.startDisplayStateMonitoring(service);
    }

    @Override
    public void stopDisplayStateMonitoring() {
        mDisplayInterface.stopDisplayStateMonitoring();
    }

    @Override
    public boolean isDisplayEnabled() {
        return mDisplayInterface.isDisplayEnabled();
    }

    @Override
    public WearInformationProvider[] getFlashWearInformationProviders(
            String lifetimePath, String eolPath) {
        return mStorageMonitoringInterface.getFlashWearInformationProviders(
                lifetimePath, eolPath);
    }

    @Override
    public UidIoStatsProvider getUidIoStatsProvider() {
        return mStorageMonitoringInterface.getUidIoStatsProvider();
    }

    @Override
    public LifetimeWriteInfoProvider getLifetimeWriteInfoProvider() {
        return mStorageMonitoringInterface.getLifetimeWriteInfoProvider();
    }

    @Override
    public void shutdown() {
        mSystemStateInterface.shutdown();
    }

    @Override
    public boolean enterDeepSleep() {
        return mSystemStateInterface.enterDeepSleep();
    }

    @Override
    public boolean enterHibernation() {
        return mSystemStateInterface.enterHibernation();
    }

    @Override
    public void scheduleActionForBootCompleted(Runnable action, Duration delay) {
        mSystemStateInterface.scheduleActionForBootCompleted(action, delay);
    }

    @Override
    public boolean isWakeupCausedByTimer() {
        return mSystemStateInterface.isWakeupCausedByTimer();
    }

    @Override
    public boolean isSystemSupportingDeepSleep() {
        return mSystemStateInterface.isSystemSupportingDeepSleep();
    }

    @Override
    public boolean isSystemSupportingHibernation() {
        return mSystemStateInterface.isSystemSupportingHibernation();
    }

    @Override
    public void refreshDisplayBrightness() {
        mDisplayInterface.refreshDisplayBrightness();
    }

    public final static class Builder {
        private ActivityManagerInterface mActivityManagerInterface;
        private DisplayInterface mDisplayInterface;
        private IOInterface mIOInterface;
        private StorageMonitoringInterface mStorageMonitoringInterface;
        private SystemStateInterface mSystemStateInterface;
        private TimeInterface mTimeInterface;
        private WakeLockInterface mWakeLockInterface;

        private Builder() {}

        public static Builder newSystemInterface() {
            return new Builder();
        }

        public static Builder defaultSystemInterface(Context context) {
            Objects.requireNonNull(context);
            Builder builder = newSystemInterface();
            builder.withActivityManagerInterface(new ActivityManagerInterface.DefaultImpl(context));
            builder.withWakeLockInterface(new WakeLockInterface.DefaultImpl(context));
            builder.withDisplayInterface(new DisplayInterface.DefaultImpl(context,
                    builder.mWakeLockInterface));
            builder.withIOInterface(new IOInterface.DefaultImpl(context));
            builder.withStorageMonitoringInterface(new StorageMonitoringInterface.DefaultImpl());
            builder.withSystemStateInterface(new SystemStateInterface.DefaultImpl(context));
            return builder.withTimeInterface(new TimeInterface.DefaultImpl());
        }

        public static Builder fromBuilder(Builder otherBuilder) {
            return newSystemInterface()
                    .withActivityManagerInterface(otherBuilder.mActivityManagerInterface)
                    .withDisplayInterface(otherBuilder.mDisplayInterface)
                    .withIOInterface(otherBuilder.mIOInterface)
                    .withStorageMonitoringInterface(otherBuilder.mStorageMonitoringInterface)
                    .withSystemStateInterface(otherBuilder.mSystemStateInterface)
                    .withTimeInterface(otherBuilder.mTimeInterface)
                    .withWakeLockInterface(otherBuilder.mWakeLockInterface);
        }

        public Builder withActivityManagerInterface(ActivityManagerInterface
                activityManagerInterface) {
            mActivityManagerInterface = activityManagerInterface;
            return this;
        }

        public Builder withDisplayInterface(DisplayInterface displayInterface) {
            mDisplayInterface = displayInterface;
            return this;
        }

        public Builder withIOInterface(IOInterface ioInterface) {
            mIOInterface = ioInterface;
            return this;
        }

        public Builder withStorageMonitoringInterface(StorageMonitoringInterface
                storageMonitoringInterface) {
            mStorageMonitoringInterface = storageMonitoringInterface;
            return this;
        }

        public Builder withSystemStateInterface(SystemStateInterface systemStateInterface) {
            mSystemStateInterface = systemStateInterface;
            return this;
        }

        public Builder withTimeInterface(TimeInterface timeInterface) {
            mTimeInterface = timeInterface;
            return this;
        }

        public Builder withWakeLockInterface(WakeLockInterface wakeLockInterface) {
            mWakeLockInterface = wakeLockInterface;
            return this;
        }

        public SystemInterface build() {
            return new SystemInterface(Objects.requireNonNull(mActivityManagerInterface),
                Objects.requireNonNull(mDisplayInterface),
                Objects.requireNonNull(mIOInterface),
                Objects.requireNonNull(mStorageMonitoringInterface),
                Objects.requireNonNull(mSystemStateInterface),
                Objects.requireNonNull(mTimeInterface),
                Objects.requireNonNull(mWakeLockInterface));
        }
    }
}
