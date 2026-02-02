/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.car.watchdog;

import android.car.watchdog.IoOveruseStats;
import android.car.watchdog.PerStateBytes;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.util.function.BiFunction;

/** Defines I/O usage stats for a package. */
public final class PackageIoUsage {
    private static final android.automotive.watchdog.PerStateBytes DEFAULT_PER_STATE_BYTES =
            new android.automotive.watchdog.PerStateBytes();
    private static final int MISSING_VALUE = -1;

    private android.automotive.watchdog.IoOveruseStats mIoOveruseStats;
    private android.automotive.watchdog.PerStateBytes mForgivenWriteBytes;
    private int mForgivenOveruses;
    private int mHistoricalNotForgivenOveruses;
    private int mTotalTimesKilled;

    public PackageIoUsage() {
        mForgivenWriteBytes = DEFAULT_PER_STATE_BYTES;
        mForgivenOveruses = 0;
        mHistoricalNotForgivenOveruses = MISSING_VALUE;
        mTotalTimesKilled = 0;
    }

    public PackageIoUsage(android.automotive.watchdog.IoOveruseStats ioOveruseStats,
            android.automotive.watchdog.PerStateBytes forgivenWriteBytes, int forgivenOveruses,
            int totalTimesKilled) {
        mIoOveruseStats = ioOveruseStats;
        mForgivenWriteBytes = forgivenWriteBytes;
        mForgivenOveruses = forgivenOveruses;
        mTotalTimesKilled = totalTimesKilled;
        mHistoricalNotForgivenOveruses = MISSING_VALUE;
    }

    public PackageIoUsage(PackageIoUsage other) {
        if (other.mIoOveruseStats != null) {
            mIoOveruseStats = new android.automotive.watchdog.IoOveruseStats();
            mIoOveruseStats.killableOnOveruse = other.mIoOveruseStats.killableOnOveruse;
            mIoOveruseStats.startTime = other.mIoOveruseStats.startTime;
            mIoOveruseStats.durationInSeconds = other.mIoOveruseStats.durationInSeconds;
            mIoOveruseStats.totalOveruses = other.mIoOveruseStats.totalOveruses;
            mIoOveruseStats.writtenBytes = copyPerStateBytes(other.mIoOveruseStats.writtenBytes);
            mIoOveruseStats.remainingWriteBytes =
                    copyPerStateBytes(other.mIoOveruseStats.remainingWriteBytes);
        } else {
            mIoOveruseStats = null;
        }
        mForgivenWriteBytes = copyPerStateBytes(other.mForgivenWriteBytes);
        mForgivenOveruses = other.mForgivenOveruses;
        mTotalTimesKilled = other.mTotalTimesKilled;
        mHistoricalNotForgivenOveruses = other.mHistoricalNotForgivenOveruses;
    }

    /** Returns the I/O overuse stats related to the package. */
    public android.automotive.watchdog.IoOveruseStats getInternalIoOveruseStats() {
        return mIoOveruseStats;
    }

    /** Returns the forgiven write bytes. */
    public android.automotive.watchdog.PerStateBytes getForgivenWriteBytes() {
        return mForgivenWriteBytes;
    }

    /** Returns the number of forgiven overuses for the current day. */
    public int getForgivenOveruses() {
        return mForgivenOveruses;
    }

    /**
     * Returns the number of not forgiven overuses. These are overuses that have not been
     * attributed previously to a package's recurring overuse.
     */
    public int getNotForgivenOveruses() {
        if (!hasUsage()) {
            return 0;
        }
        int historicalNotForgivenOveruses =
                mHistoricalNotForgivenOveruses != MISSING_VALUE
                        ? mHistoricalNotForgivenOveruses : 0;
        return (mIoOveruseStats.totalOveruses - mForgivenOveruses)
                + historicalNotForgivenOveruses;
    }

    /** Sets historical not forgiven overuses. */
    public void setHistoricalNotForgivenOveruses(int historicalNotForgivenOveruses) {
        mHistoricalNotForgivenOveruses = historicalNotForgivenOveruses;
    }

    /** Forgives all the I/O overuse stats' overuses. */
    public void forgiveOveruses() {
        if (!hasUsage()) {
            return;
        }
        mForgivenOveruses = mIoOveruseStats.totalOveruses;
        mHistoricalNotForgivenOveruses = 0;
    }

    /** Returns the total number of times the package was killed. */
    public int getTotalTimesKilled() {
        return mTotalTimesKilled;
    }

    /** Returns whether or not the historical overuses should be forgiven. **/
    public boolean shouldForgiveHistoricalOveruses() {
        return mHistoricalNotForgivenOveruses != MISSING_VALUE;
    }

    /** Returns true iff the I/O overuse stats are available. */
    public boolean hasUsage() {
        return mIoOveruseStats != null;
    }

    /** Overwrites the I/O usage stats from the given instance. */
    public void overwrite(PackageIoUsage ioUsage) {
        mIoOveruseStats = ioUsage.mIoOveruseStats;
        mForgivenWriteBytes = ioUsage.mForgivenWriteBytes;
        mTotalTimesKilled = ioUsage.mTotalTimesKilled;
        mHistoricalNotForgivenOveruses = ioUsage.mHistoricalNotForgivenOveruses;
    }

    /** Updates the I/O usage stats with the latest info. */
    public void update(android.automotive.watchdog.IoOveruseStats internalStats,
            android.automotive.watchdog.PerStateBytes forgivenWriteBytes) {
        mIoOveruseStats = internalStats;
        mForgivenWriteBytes = forgivenWriteBytes;
    }

    /** Returns the I/O overuse stats with the given killable state. */
    public IoOveruseStats getIoOveruseStats(boolean isKillable) {
        return toIoOveruseStatsBuilder(mIoOveruseStats, mTotalTimesKilled, isKillable).build();
    }

    /** Returns true iff the I/O usage exceeds the daily threshold. */
    public boolean isExceedingThreshold() {
        if (!hasUsage()) {
            return false;
        }
        android.automotive.watchdog.PerStateBytes remaining =
                mIoOveruseStats.remainingWriteBytes;
        return remaining.foregroundBytes == 0 || remaining.backgroundBytes == 0
                || remaining.garageModeBytes == 0;
    }

    /** Increments the kills stats when the package is killed. */
    public void onKilled() {
        ++mTotalTimesKilled;
    }

    /** Resets the I/O usage stat. */
    public void resetStats() {
        mIoOveruseStats = null;
        mForgivenWriteBytes = DEFAULT_PER_STATE_BYTES;
        mForgivenOveruses = 0;
        mHistoricalNotForgivenOveruses = MISSING_VALUE;
        mTotalTimesKilled = 0;
    }

    /** Dumps the I/O usage stats to the given proto output stream. */
    public void dumpProto(ProtoOutputStream proto) {
        long packageIoUsageToken = proto.start(PerformanceDump.UsageByUserPackage.PACKAGE_IO_USAGE);
        long ioOveruseStatsToken = proto.start(PerformanceDump.PackageIoUsage.IO_OVERUSE_STATS);

        proto.write(PerformanceDump.IoOveruseStats.KILLABLE_ON_OVERUSE,
                mIoOveruseStats.killableOnOveruse);
        dumpPerStateBytes(mIoOveruseStats.remainingWriteBytes,
                PerformanceDump.IoOveruseStats.REMAINING_WRITE_BYTES, proto);
        proto.write(PerformanceDump.IoOveruseStats.START_TIME, mIoOveruseStats.startTime);
        proto.write(PerformanceDump.IoOveruseStats.DURATION, mIoOveruseStats.durationInSeconds);

        dumpPerStateBytes(mIoOveruseStats.writtenBytes,
                PerformanceDump.IoOveruseStats.WRITTEN_BYTES, proto);

        proto.write(PerformanceDump.IoOveruseStats.TOTAL_OVERUSES, mIoOveruseStats.totalOveruses);
        proto.end(ioOveruseStatsToken);

        dumpPerStateBytes(mForgivenWriteBytes, PerformanceDump.PackageIoUsage.FORGIVEN_WRITE_BYTES,
                proto);

        proto.write(PerformanceDump.PackageIoUsage.FORGIVEN_OVERUSES, mForgivenOveruses);
        proto.write(PerformanceDump.PackageIoUsage.HISTORICAL_NOT_FORGIVEN_OVERUSES,
                mHistoricalNotForgivenOveruses);
        proto.write(PerformanceDump.PackageIoUsage.TOTAL_TIMES_KILLED, mTotalTimesKilled);

        proto.end(packageIoUsageToken);
    }

    @VisibleForTesting
    static IoOveruseStats.Builder toIoOveruseStatsBuilder(
            android.automotive.watchdog.IoOveruseStats internalStats,
            int totalTimesKilled, boolean isKillableOnOveruses) {
        return new IoOveruseStats.Builder(internalStats.startTime, internalStats.durationInSeconds)
                .setTotalOveruses(internalStats.totalOveruses)
                .setTotalTimesKilled(totalTimesKilled)
                .setTotalBytesWritten(totalPerStateBytes(internalStats.writtenBytes))
                .setKillableOnOveruse(isKillableOnOveruses)
                .setRemainingWriteBytes(toPerStateBytes(internalStats.remainingWriteBytes));
    }

    private static PerStateBytes toPerStateBytes(
            android.automotive.watchdog.PerStateBytes internalPerStateBytes) {
        return new PerStateBytes(internalPerStateBytes.foregroundBytes,
                internalPerStateBytes.backgroundBytes, internalPerStateBytes.garageModeBytes);
    }

    private static android.automotive.watchdog.PerStateBytes copyPerStateBytes(
            android.automotive.watchdog.PerStateBytes other) {
        if (other == null) {
            return null;
        }
        android.automotive.watchdog.PerStateBytes copy =
                new android.automotive.watchdog.PerStateBytes();
        copy.foregroundBytes = other.foregroundBytes;
        copy.backgroundBytes = other.backgroundBytes;
        copy.garageModeBytes = other.garageModeBytes;
        return copy;
    }

    private static long totalPerStateBytes(
            android.automotive.watchdog.PerStateBytes internalPerStateBytes) {
        BiFunction<Long, Long, Long> sum = (l, r) -> {
            return (Long.MAX_VALUE - l > r) ? l + r : Long.MAX_VALUE;
        };
        return sum.apply(sum.apply(internalPerStateBytes.foregroundBytes,
                internalPerStateBytes.backgroundBytes), internalPerStateBytes.garageModeBytes);
    }

    private static void dumpPerStateBytes(android.automotive.watchdog.PerStateBytes perStateBytes,
                                          long fieldId, ProtoOutputStream proto) {
        long perStateBytesToken = proto.start(fieldId);
        proto.write(PerformanceDump.PerStateBytes.FOREGROUND_BYTES, perStateBytes.foregroundBytes);
        proto.write(PerformanceDump.PerStateBytes.BACKGROUND_BYTES, perStateBytes.backgroundBytes);
        proto.write(PerformanceDump.PerStateBytes.GARAGEMODE_BYTES, perStateBytes.garageModeBytes);
        proto.end(perStateBytesToken);
    }
}
