/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.car;

import android.car.Car;
import android.car.storagemonitoring.ICarStorageMonitoring;
import android.car.storagemonitoring.WearEstimate;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.android.car.internal.CarPermission;
import com.android.car.storagemonitoring.WearInformation;
import com.android.car.storagemonitoring.WearInformationProvider;
import java.io.File;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Optional;

public class CarStorageMonitoringService extends ICarStorageMonitoring.Stub
        implements CarServiceBase {

    private static final String UPTIME_TRACKER_FILENAME = "service_uptime";
    private static final String WEAR_INFO_FILENAME = "wear_info";

    private final WearInformationProvider[] mWearInformationProviders;
    private final Context mContext;
    private final File mUptimeTrackerFile;
    private final File mWearInfoFile;
    private final OnShutdownReboot mOnShutdownReboot;

    private final CarPermission mStorageMonitoringPermission;

    private UptimeTracker mUptimeTracker;
    private Optional<WearInformation> mWearInformation = Optional.empty();

    public CarStorageMonitoringService(Context context, SystemInterface systemInterface) {
        mContext = context;
        mUptimeTrackerFile = new File(systemInterface.getFilesDir(), UPTIME_TRACKER_FILENAME);
        mWearInfoFile = new File(systemInterface.getFilesDir(), WEAR_INFO_FILENAME);
        mOnShutdownReboot = new OnShutdownReboot(mContext);
        mWearInformationProviders = systemInterface.getFlashWearInformationProviders();
        mStorageMonitoringPermission =
                new CarPermission(mContext, Car.PERMISSION_STORAGE_MONITORING);
    }

    /**
     * The overlay vends this data in hours/1% increase, but the current implementation
     * works in terms of 10% increases. This method computes the number of milliseconds
     * that make up an acceptable rate of 10% increases.
     */
    static long getAcceptableFlashWearRate() {
        return 10 * Duration.ofHours(R.integer.acceptableHoursPerOnePercentFlashWear).toMillis();
    }

    /**
     * We define the proper interval between uptime snapshots to be 1% of
     * the value of uptimeHoursForAcceptableWearIncrease in the overlay.
     */
    private static long getUptimeSnapshotIntervalMs() {
        return Duration.ofHours(R.integer.uptimeHoursIntervalBetweenUptimeDataWrite).toMillis();
    }

    private Optional<WearInformation> loadWearInformation() {
        for (WearInformationProvider provider : mWearInformationProviders) {
            WearInformation wearInfo = provider.load();
            if (wearInfo != null) {
                return Optional.of(wearInfo);
            }
        }
        return Optional.empty();
    }

    @Override
    public void init() {
        Log.i(CarLog.TAG_STORAGE, "starting up CarStorageMonitoringService");
        mUptimeTracker = new UptimeTracker(mUptimeTrackerFile, getUptimeSnapshotIntervalMs());
        mWearInformation = loadWearInformation();
        mOnShutdownReboot.addAction((Context ctx, Intent intent) -> release());
    }

    @Override
    public void release() {
        Log.i(CarLog.TAG_STORAGE, "tearing down CarStorageMonitoringService");
        mUptimeTracker.onDestroy();
        mOnShutdownReboot.clearActions();
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*CarStorageMonitoringService*");
        writer.println("last wear information retrieved: " +
            mWearInformation.map(WearInformation::toString).orElse("missing"));
    }

    // ICarStorageMonitoring implementation

    @Override
    public int getPreEolIndicatorStatus() {
        mStorageMonitoringPermission.assertGranted();

        return mWearInformation.map(wi -> wi.preEolInfo)
                .orElse(WearInformation.UNKNOWN_PRE_EOL_INFO);
    }

    @Override
    public WearEstimate getWearEstimate() {
        mStorageMonitoringPermission.assertGranted();

        return mWearInformation.map(wi ->
                new WearEstimate(wi.lifetimeEstimateA,wi.lifetimeEstimateB)).orElse(
                    new WearEstimate(WearEstimate.UNKNOWN, WearEstimate.UNKNOWN));
    }
}
