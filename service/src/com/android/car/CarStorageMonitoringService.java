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
import android.car.storagemonitoring.UidIoStats;
import android.car.storagemonitoring.WearEstimate;
import android.car.storagemonitoring.WearEstimateChange;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.JsonWriter;
import android.util.Log;
import com.android.car.internal.CarPermission;
import com.android.car.storagemonitoring.WearEstimateRecord;
import com.android.car.storagemonitoring.WearHistory;
import com.android.car.storagemonitoring.WearInformation;
import com.android.car.storagemonitoring.WearInformationProvider;
import com.android.car.systeminterface.SystemInterface;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.json.JSONException;

public class CarStorageMonitoringService extends ICarStorageMonitoring.Stub
        implements CarServiceBase {
    private static final String TAG = CarLog.TAG_STORAGE;
    private static final int MIN_WEAR_ESTIMATE_OF_CONCERN = 80;

    static final String UPTIME_TRACKER_FILENAME = "service_uptime";
    static final String WEAR_INFO_FILENAME = "wear_info";

    private final WearInformationProvider[] mWearInformationProviders;
    private final Context mContext;
    private final File mUptimeTrackerFile;
    private final File mWearInfoFile;
    private final OnShutdownReboot mOnShutdownReboot;
    private final SystemInterface mSystemInterface;

    private final CarPermission mStorageMonitoringPermission;

    private UptimeTracker mUptimeTracker = null;
    private Optional<WearInformation> mWearInformation = Optional.empty();
    private List<WearEstimateChange> mWearEstimateChanges = Collections.emptyList();
    private List<UidIoStats> mBootIoStats = Collections.emptyList();
    private boolean mInitialized = false;

    public CarStorageMonitoringService(Context context, SystemInterface systemInterface) {
        mContext = context;
        mUptimeTrackerFile = new File(systemInterface.getFilesDir(), UPTIME_TRACKER_FILENAME);
        mWearInfoFile = new File(systemInterface.getFilesDir(), WEAR_INFO_FILENAME);
        mOnShutdownReboot = new OnShutdownReboot(mContext);
        mSystemInterface = systemInterface;
        mWearInformationProviders = systemInterface.getFlashWearInformationProviders();
        mStorageMonitoringPermission =
                new CarPermission(mContext, Car.PERMISSION_STORAGE_MONITORING);
        mWearEstimateChanges = Collections.emptyList();
        systemInterface.scheduleActionForBootCompleted(this::doInitServiceIfNeeded,
            Duration.ofSeconds(10));
    }

    private static long getUptimeSnapshotIntervalMs() {
        return Duration.ofHours(R.integer.uptimeHoursIntervalBetweenUptimeDataWrite).toMillis();
    }

    private Optional<WearInformation> loadWearInformation() {
        for (WearInformationProvider provider : mWearInformationProviders) {
            WearInformation wearInfo = provider.load();
            if (wearInfo != null) {
                Log.d(TAG, "retrieved wear info " + wearInfo + " via provider " + provider);
                return Optional.of(wearInfo);
            }
        }

        Log.d(TAG, "no wear info available");
        return Optional.empty();
    }

    private WearHistory loadWearHistory() {
        if (mWearInfoFile.exists()) {
            try {
                WearHistory wearHistory = WearHistory.fromJson(mWearInfoFile);
                Log.d(TAG, "retrieved wear history " + wearHistory);
                return wearHistory;
            } catch (IOException | JSONException e) {
                Log.e(TAG, "unable to read wear info file " + mWearInfoFile, e);
            }
        }

        Log.d(TAG, "no wear history available");
        return new WearHistory();
    }

    // returns true iff a new event was added (and hence the history needs to be saved)
    private boolean addEventIfNeeded(WearHistory wearHistory) {
        if (!mWearInformation.isPresent()) return false;

        WearInformation wearInformation = mWearInformation.get();
        WearEstimate lastWearEstimate;
        WearEstimate currentWearEstimate = wearInformation.toWearEstimate();

        if (wearHistory.size() == 0) {
            lastWearEstimate = WearEstimate.UNKNOWN_ESTIMATE;
        } else {
            lastWearEstimate = wearHistory.getLast().getNewWearEstimate();
        }

        if (currentWearEstimate.equals(lastWearEstimate)) return false;

        WearEstimateRecord newRecord = new WearEstimateRecord(lastWearEstimate,
            currentWearEstimate,
            mUptimeTracker.getTotalUptime(),
            Instant.now());
        Log.d(TAG, "new wear record generated " + newRecord);
        wearHistory.add(newRecord);
        return true;
    }

    private void storeWearHistory(WearHistory wearHistory) {
        try (JsonWriter jsonWriter = new JsonWriter(new FileWriter(mWearInfoFile))) {
            wearHistory.writeToJson(jsonWriter);
        } catch (IOException e) {
            Log.e(TAG, "unable to write wear info file" + mWearInfoFile, e);
        }
    }

    @Override
    public void init() {
        Log.d(TAG, "CarStorageMonitoringService init()");

        mUptimeTracker = new UptimeTracker(mUptimeTrackerFile,
            getUptimeSnapshotIntervalMs(),
            mSystemInterface);
    }

    private void launchWearChangeActivity() {
        final String activityPath = mContext.getResources().getString(
            R.string.activityHandlerForFlashWearChanges);
        if (activityPath.isEmpty()) return;
        try {
            final ComponentName activityComponent =
                Objects.requireNonNull(ComponentName.unflattenFromString(activityPath));
            Intent intent = new Intent();
            intent.setComponent(activityComponent);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        } catch (ActivityNotFoundException | NullPointerException e) {
            Log.e(TAG,
                "value of activityHandlerForFlashWearChanges invalid non-empty string " +
                    activityPath, e);
        }
    }

    private static void logOnAdverseWearLevel(WearInformation wearInformation) {
        if (wearInformation.preEolInfo > WearInformation.PRE_EOL_INFO_NORMAL ||
            Math.max(wearInformation.lifetimeEstimateA,
                wearInformation.lifetimeEstimateB) >= MIN_WEAR_ESTIMATE_OF_CONCERN) {
            Log.w(TAG, "flash storage reached wear a level that requires attention: "
                    + wearInformation);
        }
    }

    private synchronized void doInitServiceIfNeeded() {
        if (mInitialized) return;

        Log.d(TAG, "initializing CarStorageMonitoringService");

        mWearInformation = loadWearInformation();

        // TODO(egranata): can this be done lazily?
        final WearHistory wearHistory = loadWearHistory();
        final boolean didWearChangeHappen = addEventIfNeeded(wearHistory);
        if (didWearChangeHappen) {
            storeWearHistory(wearHistory);
        }
        Log.d(TAG, "wear history being tracked is " + wearHistory);
        mWearEstimateChanges = wearHistory.toWearEstimateChanges(
                mContext.getResources().getInteger(
                        R.integer.acceptableHoursPerOnePercentFlashWear));

        mOnShutdownReboot.addAction((Context ctx, Intent intent) -> release());

        mWearInformation.ifPresent(CarStorageMonitoringService::logOnAdverseWearLevel);

        if (didWearChangeHappen) {
            launchWearChangeActivity();
        }

        long bootUptime = mSystemInterface.getUptime();
        mBootIoStats = SparseArrayStream.valueStream(mSystemInterface.getUidIoStatsProvider().load())
            .map(record -> {
                // at boot, assume all UIDs have been running for as long as the system has
                // been up, since we don't really know any better
                UidIoStats stats = new UidIoStats(record, bootUptime);
                Log.d(TAG, "loaded boot I/O stat data: " + stats);
                return stats;
            }).collect(Collectors.toList());

        Log.i(TAG, "CarStorageMonitoringService is up");

        mInitialized = true;
    }

    @Override
    public void release() {
        Log.i(TAG, "tearing down CarStorageMonitoringService");
        if (mUptimeTracker != null) {
            mUptimeTracker.onDestroy();
        }
        mOnShutdownReboot.clearActions();
    }

    @Override
    public void dump(PrintWriter writer) {
        mStorageMonitoringPermission.assertGranted();
        doInitServiceIfNeeded();

        writer.println("*CarStorageMonitoringService*");
        writer.println("last wear information retrieved: " +
            mWearInformation.map(WearInformation::toString).orElse("missing"));
        writer.println("wear change history: " +
            mWearEstimateChanges.stream().map(WearEstimateChange::toString).reduce("",
                (String s, String t) -> s + "\n" + t));
    }

    // ICarStorageMonitoring implementation

    @Override
    public int getPreEolIndicatorStatus() {
        mStorageMonitoringPermission.assertGranted();
        doInitServiceIfNeeded();

        return mWearInformation.map(wi -> wi.preEolInfo)
                .orElse(WearInformation.UNKNOWN_PRE_EOL_INFO);
    }

    @Override
    public WearEstimate getWearEstimate() {
        mStorageMonitoringPermission.assertGranted();
        doInitServiceIfNeeded();

        return mWearInformation.map(wi ->
                new WearEstimate(wi.lifetimeEstimateA,wi.lifetimeEstimateB)).orElse(
                    WearEstimate.UNKNOWN_ESTIMATE);
    }

    @Override
    public List<WearEstimateChange> getWearEstimateHistory() {
        mStorageMonitoringPermission.assertGranted();
        doInitServiceIfNeeded();

        return mWearEstimateChanges;
    }

    @Override
    public List<UidIoStats> getBootIoStats() {
        mStorageMonitoringPermission.assertGranted();
        doInitServiceIfNeeded();

        return mBootIoStats;
    }
}
