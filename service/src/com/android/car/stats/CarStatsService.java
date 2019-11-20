/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.stats;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.car.stats.VmsClientLog.ConnectionState;
import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Implements collection and dumpsys reporting of statistics in CSV format.
 */
public class CarStatsService {
    private static final boolean DEBUG = false;
    private static final String TAG = "CarStatsService";
    private static final String VMS_CONNECTION_STATS_DUMPSYS_HEADER =
            "uid,packageName,attempts,connected,disconnected,terminated,errors";

    private static final Function<VmsClientLog, String> VMS_CONNECTION_STATS_DUMPSYS_FORMAT =
            entry -> String.format(Locale.US,
                    "%d,%s,%d,%d,%d,%d,%d",
                    entry.getUid(), entry.getPackageName(),
                    entry.getConnectionStateCount(ConnectionState.CONNECTING),
                    entry.getConnectionStateCount(ConnectionState.CONNECTED),
                    entry.getConnectionStateCount(ConnectionState.DISCONNECTED),
                    entry.getConnectionStateCount(ConnectionState.TERMINATED),
                    entry.getConnectionStateCount(ConnectionState.CONNECTION_ERROR));

    private static final String VMS_CLIENT_STATS_DUMPSYS_HEADER =
            "uid,layerType,layerChannel,layerVersion,"
                    + "txBytes,txPackets,rxBytes,rxPackets,droppedBytes,droppedPackets";

    private static final Function<VmsClientStats, String> VMS_CLIENT_STATS_DUMPSYS_FORMAT =
            entry -> String.format(
                    "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                    entry.getUid(),
                    entry.getLayerType(), entry.getLayerChannel(), entry.getLayerVersion(),
                    entry.getTxBytes(), entry.getTxPackets(),
                    entry.getRxBytes(), entry.getRxPackets(),
                    entry.getDroppedBytes(), entry.getDroppedPackets());

    private static final Comparator<VmsClientStats> VMS_CLIENT_STATS_ORDER =
            Comparator.comparingInt(VmsClientStats::getUid)
                    .thenComparingInt(VmsClientStats::getLayerType)
                    .thenComparingInt(VmsClientStats::getLayerChannel)
                    .thenComparingInt(VmsClientStats::getLayerVersion);

    private final PackageManager mPackageManager;

    @GuardedBy("mVmsClientStats")
    private final Map<Integer, VmsClientLog> mVmsClientStats = new ArrayMap<>();

    public CarStatsService(Context context) {
        mPackageManager = context.getPackageManager();
    }

    /**
     * Gets a logger for the VMS client with a given UID.
     */
    public VmsClientLog getVmsClientLog(int clientUid) {
        synchronized (mVmsClientStats) {
            return mVmsClientStats.computeIfAbsent(
                    clientUid,
                    uid -> {
                        String packageName = mPackageManager.getNameForUid(uid);
                        if (DEBUG) {
                            Log.d(TAG, "Created VmsClientLog: " + packageName);
                        }
                        return new VmsClientLog(uid, packageName);
                    });
        }
    }

    /**
     * Dumps metrics in CSV format.
     */
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        List<String> flags = Arrays.asList(args);
        if (args.length == 0 || flags.contains("--vms-client")) {
            dumpVmsStats(writer);
        }
    }

    private void dumpVmsStats(PrintWriter writer) {
        synchronized (mVmsClientStats) {
            writer.println(VMS_CONNECTION_STATS_DUMPSYS_HEADER);
            mVmsClientStats.values().stream()
                    // Unknown UID will not have connection stats
                    .filter(entry -> entry.getUid() > 0)
                    // Sort stats by UID
                    .sorted(Comparator.comparingInt(VmsClientLog::getUid))
                    .forEachOrdered(entry -> writer.println(
                            VMS_CONNECTION_STATS_DUMPSYS_FORMAT.apply(entry)));
            writer.println();

            writer.println(VMS_CLIENT_STATS_DUMPSYS_HEADER);
            mVmsClientStats.values().stream()
                    .flatMap(log -> log.getLayerEntries().stream())
                    .sorted(VMS_CLIENT_STATS_ORDER)
                    .forEachOrdered(entry -> writer.println(
                            VMS_CLIENT_STATS_DUMPSYS_FORMAT.apply(entry)));
        }
    }
}
