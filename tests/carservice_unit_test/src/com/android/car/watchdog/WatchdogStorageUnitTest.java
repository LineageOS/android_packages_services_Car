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

package com.android.car.watchdog;

import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_NEVER;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_NO;
import static android.car.watchdog.PackageKillableState.KILLABLE_STATE_YES;

import static com.android.car.watchdog.WatchdogStorage.RETENTION_PERIOD;
import static com.android.car.watchdog.WatchdogStorage.STATS_TEMPORAL_UNIT;
import static com.android.car.watchdog.WatchdogStorage.WatchdogDbHelper.DATABASE_NAME;
import static com.android.car.watchdog.WatchdogStorage.ZONE_OFFSET;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.automotive.watchdog.PerStateBytes;
import android.car.builtin.util.Slogf;
import android.car.watchdog.IoOveruseStats;
import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * <p>This class contains unit tests for the {@link WatchdogStorage}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class WatchdogStorageUnitTest {
    private static final String TAG = WatchdogStorageUnitTest.class.getSimpleName();

    private WatchdogStorage mService;
    private File mDatabaseFile;
    private TimeSourceInterface mTimeSource;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        mDatabaseFile = context.createDeviceProtectedStorageContext()
                .getDatabasePath(DATABASE_NAME);
        mService = new WatchdogStorage(context, /* useDataSystemCarDir= */ false);
        setDate(/* numDaysAgo= */ 0);
    }

    @After
    public void tearDown() {
        mService.release();
        if (!mDatabaseFile.delete()) {
            Slogf.e(TAG, "Failed to delete the database file: %s", mDatabaseFile.getAbsolutePath());
        }
    }

    @Test
    public void testSaveUserPackageSettings() throws Exception {
        List<WatchdogStorage.UserPackageSettingsEntry> expected = sampleSettings();

        assertThat(mService.saveUserPackageSettings(expected)).isTrue();

        UserPackageSettingsEntrySubject.assertThat(mService.getUserPackageSettings())
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void testOverwriteUserPackageSettings() throws Exception {
        List<WatchdogStorage.UserPackageSettingsEntry> expected = Arrays.asList(
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "system_package.non_critical.A", KILLABLE_STATE_YES),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "system_package.non_critical.B", KILLABLE_STATE_NO));

        assertThat(mService.saveUserPackageSettings(expected)).isTrue();

        expected = Arrays.asList(
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "system_package.non_critical.A", KILLABLE_STATE_NEVER),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "system_package.non_critical.B", KILLABLE_STATE_NO));

        assertThat(mService.saveUserPackageSettings(expected)).isTrue();

        UserPackageSettingsEntrySubject.assertThat(mService.getUserPackageSettings())
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void testSaveAndGetIoOveruseStats() throws Exception {
        injectSampleUserPackageSettings();
        /* Start time aligned to the beginning of the day. */
        long startTime = mTimeSource.now().atZone(ZONE_OFFSET).truncatedTo(STATS_TEMPORAL_UNIT)
                .toEpochSecond();

        assertWithMessage("Saved I/O usage stats successfully")
                .that(mService.saveIoUsageStats(sampleStatsForDate(startTime, /* duration= */ 60)))
                .isTrue();

        long expectedDuration =
                mTimeSource.now().atZone(ZONE_OFFSET).toEpochSecond() - startTime;
        List<WatchdogStorage.IoUsageStatsEntry> expected = sampleStatsForDate(
                startTime, expectedDuration);

        IoUsageStatsEntrySubject.assertThat(mService.getTodayIoUsageStats())
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void testSaveAndGetIoOveruseStatsWithOffsettedStartTime() throws Exception {
        injectSampleUserPackageSettings();
        /* Start time in the middle of the day. */
        long startTime = mTimeSource.now().atZone(ZONE_OFFSET).truncatedTo(STATS_TEMPORAL_UNIT)
                .plusHours(12).toEpochSecond();
        List<WatchdogStorage.IoUsageStatsEntry> entries = sampleStatsForDate(
                startTime, /* duration= */ 60);

        assertWithMessage("Saved I/O usage stats successfully")
                .that(mService.saveIoUsageStats(entries)).isTrue();

        long expectedStartTime = mTimeSource.now().atZone(ZONE_OFFSET)
                .truncatedTo(STATS_TEMPORAL_UNIT).toEpochSecond();
        long expectedDuration =
                mTimeSource.now().atZone(ZONE_OFFSET).toEpochSecond() - expectedStartTime;
        List<WatchdogStorage.IoUsageStatsEntry> expected = sampleStatsForDate(
                expectedStartTime, expectedDuration);

        IoUsageStatsEntrySubject.assertThat(mService.getTodayIoUsageStats())
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void testOverwriteIoOveruseStats() throws Exception {
        injectSampleUserPackageSettings();
        long startTime = mTimeSource.now().atZone(ZONE_OFFSET).truncatedTo(STATS_TEMPORAL_UNIT)
                .toEpochSecond();
        long duration = mTimeSource.now().atZone(ZONE_OFFSET).toEpochSecond() - startTime;

        List<WatchdogStorage.IoUsageStatsEntry> expected = Collections.singletonList(
                constructIoUsageStatsEntry(
                        /* userId= */ 100, "system_package.non_critical.A", startTime, duration,
                        /* remainingWriteBytes= */
                        CarWatchdogServiceUnitTest.constructPerStateBytes(200, 300, 400),
                        /* writtenBytes= */
                        CarWatchdogServiceUnitTest.constructPerStateBytes(1000, 2000, 3000),
                        /* forgivenWriteBytes= */
                        CarWatchdogServiceUnitTest.constructPerStateBytes(100, 100, 100),
                        /* totalOveruses= */ 2, /* totalTimesKilled= */ 1));

        assertWithMessage("Saved I/O usage stats successfully")
                .that(mService.saveIoUsageStats(expected)).isTrue();

        IoUsageStatsEntrySubject.assertThat(mService.getTodayIoUsageStats())
                .containsExactlyElementsIn(expected);

        expected = Collections.singletonList(
                constructIoUsageStatsEntry(
                        /* userId= */ 100, "system_package.non_critical.A", startTime, duration,
                        /* remainingWriteBytes= */
                        CarWatchdogServiceUnitTest.constructPerStateBytes(400, 600, 800),
                        /* writtenBytes= */
                        CarWatchdogServiceUnitTest.constructPerStateBytes(2000, 3000, 4000),
                        /* forgivenWriteBytes= */
                        CarWatchdogServiceUnitTest.constructPerStateBytes(1200, 2300, 3400),
                        /* totalOveruses= */ 4, /* totalTimesKilled= */ 2));

        assertWithMessage("Saved I/O usage stats successfully")
                .that(mService.saveIoUsageStats(expected)).isTrue();

        IoUsageStatsEntrySubject.assertThat(mService.getTodayIoUsageStats())
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void testSaveIoOveruseStatsOutsideRetentionPeriod() throws Exception {
        injectSampleUserPackageSettings();
        int retentionDaysAgo = RETENTION_PERIOD.getDays();

        assertWithMessage("Saved I/O usage stats successfully")
                .that(mService.saveIoUsageStats(sampleStatsBetweenDates(
                        /* includingStartDaysAgo= */ retentionDaysAgo,
                        /* excludingEndDaysAgo= */ retentionDaysAgo + 1))).isTrue();

        assertWithMessage("Didn't fetch I/O overuse stats outside retention period")
                .that(mService.getHistoricalIoOveruseStats(
                        /* userId= */ 100, "system_package.non_critical.A", retentionDaysAgo))
                .isNull();
    }

    @Test
    public void testGetHistoricalIoOveruseStats() throws Exception {
        injectSampleUserPackageSettings();

        assertThat(mService.saveIoUsageStats(sampleStatsBetweenDates(
                /* includingStartDaysAgo= */ 0, /* excludingEndDaysAgo= */ 5))).isTrue();

        IoOveruseStats actual  = mService.getHistoricalIoOveruseStats(
                /* userId= */ 100, "system_package.non_critical.A", /* numDaysAgo= */ 7);

        assertWithMessage("Fetched I/O overuse stats").that(actual).isNotNull();

        /*
         * Returned stats shouldn't include stats for the current date as WatchdogPerfHandler fills
         * the current day's stats.
         */
        ZonedDateTime currentDate = mTimeSource.now().atZone(ZONE_OFFSET)
                .truncatedTo(STATS_TEMPORAL_UNIT);
        long startTime = currentDate.minus(4, STATS_TEMPORAL_UNIT).toEpochSecond();
        long duration = currentDate.toEpochSecond() - startTime;
        IoOveruseStats expected = new IoOveruseStats.Builder(startTime, duration)
                .setTotalOveruses(8).setTotalTimesKilled(4).setTotalBytesWritten(24_000).build();

        IoOveruseStatsSubject.assertWithMessage(
                "Fetched stats only for 4 days. Expected stats (%s) equals actual stats (%s)",
                expected.toString(), actual.toString()).that(actual)
                .isEqualTo(expected);
    }

    @Test
    public void testGetHistoricalIoOveruseStatsWithNoRecentStats() throws Exception {
        injectSampleUserPackageSettings();

        assertThat(mService.saveIoUsageStats(sampleStatsBetweenDates(
                /* includingStartDaysAgo= */ 3, /* excludingEndDaysAgo= */ 5))).isTrue();

        IoOveruseStats actual  = mService.getHistoricalIoOveruseStats(
                /* userId= */ 100, "system_package.non_critical.A", /* numDaysAgo= */ 7);

        assertWithMessage("Fetched I/O overuse stats").that(actual).isNotNull();

        /*
         * Returned stats shouldn't include stats for the current date as WatchdogPerfHandler fills
         * the current day's stats.
         */
        ZonedDateTime currentDate = mTimeSource.now().atZone(ZONE_OFFSET)
                .truncatedTo(STATS_TEMPORAL_UNIT);
        long startTime = currentDate.minus(4, STATS_TEMPORAL_UNIT).toEpochSecond();
        long duration = currentDate.toEpochSecond() - startTime;
        IoOveruseStats expected = new IoOveruseStats.Builder(startTime, duration)
                .setTotalOveruses(4).setTotalTimesKilled(2).setTotalBytesWritten(12_000).build();

        IoOveruseStatsSubject.assertWithMessage(
                "Fetched stats only for 2 days. Expected stats (%s) equals actual stats (%s)",
                expected.toString(), actual.toString()).that(actual)
                .isEqualTo(expected);
    }

    @Test
    public void testDeleteUserPackage() throws Exception {
        ArrayList<WatchdogStorage.UserPackageSettingsEntry> settingsEntries = sampleSettings();
        List<WatchdogStorage.IoUsageStatsEntry> ioUsageStatsEntries = sampleStatsForToday();

        assertThat(mService.saveUserPackageSettings(settingsEntries)).isTrue();
        assertThat(mService.saveIoUsageStats(ioUsageStatsEntries)).isTrue();

        int deleteUserId = 100;
        String deletePackageName = "system_package.non_critical.A";

        mService.deleteUserPackage(deleteUserId, deletePackageName);

        settingsEntries.removeIf(
                (s) -> s.userId == deleteUserId && s.packageName.equals(deletePackageName));

        UserPackageSettingsEntrySubject.assertThat(mService.getUserPackageSettings())
                .containsExactlyElementsIn(settingsEntries);

        ioUsageStatsEntries.removeIf(
                (e) -> e.userId == deleteUserId && e.packageName.equals(deletePackageName));

        IoUsageStatsEntrySubject.assertThat(mService.getTodayIoUsageStats())
                .containsExactlyElementsIn(ioUsageStatsEntries);
    }

    @Test
    public void testDeleteUserPackageWithNonexistentPackage() throws Exception {
        injectSampleUserPackageSettings();
        List<WatchdogStorage.IoUsageStatsEntry> ioUsageStatsEntries = sampleStatsForToday();

        assertThat(mService.saveIoUsageStats(ioUsageStatsEntries)).isTrue();

        int deleteUserId = 100;
        String deletePackageName = "system_package.non_existent.A";

        mService.deleteUserPackage(deleteUserId, deletePackageName);

        UserPackageSettingsEntrySubject.assertThat(mService.getUserPackageSettings())
                .containsExactlyElementsIn(sampleSettings());

        ioUsageStatsEntries.removeIf(
                (e) -> e.userId == deleteUserId && e.packageName.equals(deletePackageName));

        IoUsageStatsEntrySubject.assertThat(mService.getTodayIoUsageStats())
                .containsExactlyElementsIn(ioUsageStatsEntries);
    }

    @Test
    public void testDeleteUserPackageWithHistoricalIoOveruseStats()
            throws Exception {
        ArrayList<WatchdogStorage.UserPackageSettingsEntry> settingsEntries = sampleSettings();

        assertThat(mService.saveUserPackageSettings(settingsEntries)).isTrue();
        assertThat(mService.saveIoUsageStats(sampleStatsBetweenDates(
                /* includingStartDaysAgo= */ 1, /* excludingEndDaysAgo= */ 6))).isTrue();

        int deleteUserId = 100;
        String deletePackageName = "system_package.non_critical.A";

        mService.deleteUserPackage(deleteUserId, deletePackageName);

        settingsEntries.removeIf(
                (s) -> s.userId == deleteUserId && s.packageName.equals(deletePackageName));

        UserPackageSettingsEntrySubject.assertThat(mService.getUserPackageSettings())
                .containsExactlyElementsIn(settingsEntries);

        IoOveruseStats actual = mService.getHistoricalIoOveruseStats(
                /* userId= */ 100, "system_package.non_critical.A", /* numDaysAgo= */ 7);

        assertWithMessage("Fetched historical I/O overuse stats").that(actual).isNull();
    }

    @Test
    public void testSyncUsers() throws Exception {
        List<WatchdogStorage.UserPackageSettingsEntry> settingsEntries = sampleSettings();
        List<WatchdogStorage.IoUsageStatsEntry> ioUsageStatsEntries = sampleStatsForToday();

        assertThat(mService.saveUserPackageSettings(settingsEntries)).isTrue();
        assertThat(mService.saveIoUsageStats(ioUsageStatsEntries)).isTrue();

        mService.syncUsers(/* aliveUserIds= */ new int[] {101});

        settingsEntries.removeIf((s) -> s.userId == 100);
        ioUsageStatsEntries.removeIf((e) -> e.userId == 100);

        UserPackageSettingsEntrySubject.assertThat(mService.getUserPackageSettings())
                .containsExactlyElementsIn(settingsEntries);

        IoUsageStatsEntrySubject.assertThat(mService.getTodayIoUsageStats())
                .containsExactlyElementsIn(ioUsageStatsEntries);
    }

    @Test
    public void testSyncUsersWithHistoricalIoOveruseStats() throws Exception {
        List<WatchdogStorage.UserPackageSettingsEntry> settingsEntries = sampleSettings();

        assertThat(mService.saveUserPackageSettings(settingsEntries)).isTrue();
        assertThat(mService.saveIoUsageStats(sampleStatsBetweenDates(
                /* includingStartDaysAgo= */ 1, /* excludingEndDaysAgo= */ 6))).isTrue();

        mService.syncUsers(/* aliveUserIds= */ new int[] {101});

        settingsEntries.removeIf((s) -> s.userId == 100);

        UserPackageSettingsEntrySubject.assertThat(mService.getUserPackageSettings())
                .containsExactlyElementsIn(settingsEntries);

        IoOveruseStats actualSystemPackage = mService.getHistoricalIoOveruseStats(
                /* userId= */ 100, "system_package.non_critical.A", /* numDaysAgo= */ 7);
        IoOveruseStats actualVendorPackage = mService.getHistoricalIoOveruseStats(
                /* userId= */ 100, "vendor_package.critical.C", /* numDaysAgo= */ 7);

        assertWithMessage("Fetched system I/O overuse stats for deleted user")
                .that(actualSystemPackage).isNull();
        assertWithMessage("Fetched vendor I/O overuse stats for deleted user")
                .that(actualVendorPackage).isNull();
    }

    @Test
    public void testSyncUsersWithNoDataForDeletedUser() throws Exception {
        List<WatchdogStorage.UserPackageSettingsEntry> settingsEntries = sampleSettings();
        List<WatchdogStorage.IoUsageStatsEntry> ioUsageStatsEntries = sampleStatsForToday();

        assertThat(mService.saveUserPackageSettings(settingsEntries)).isTrue();
        assertThat(mService.saveIoUsageStats(ioUsageStatsEntries)).isTrue();

        mService.syncUsers(/* aliveUserIds= */ new int[] {100, 101});

        UserPackageSettingsEntrySubject.assertThat(mService.getUserPackageSettings())
                .containsExactlyElementsIn(settingsEntries);
        IoUsageStatsEntrySubject.assertThat(mService.getTodayIoUsageStats())
                .containsExactlyElementsIn(ioUsageStatsEntries);
    }

    @Test
    public void testTruncateStatsOutsideRetentionPeriodOnDateChange() throws Exception {
        injectSampleUserPackageSettings();
        setDate(/* numDaysAgo= */ 1);

        assertThat(mService.saveIoUsageStats(sampleStatsBetweenDates(
                /* includingStartDaysAgo= */ 0, /* excludingEndDaysAgo= */ 40),
                /* shouldCheckRetention= */ false)).isTrue();

        IoOveruseStats actual  = mService.getHistoricalIoOveruseStats(
                /* userId= */ 100, "system_package.non_critical.A", /* numDaysAgo= */ 40);

        assertWithMessage("Fetched I/O overuse stats").that(actual).isNotNull();

        ZonedDateTime currentDate = mTimeSource.now().atZone(ZONE_OFFSET)
                .truncatedTo(STATS_TEMPORAL_UNIT);
        long startTime = currentDate.minus(39, STATS_TEMPORAL_UNIT).toEpochSecond();
        long duration = currentDate.toEpochSecond() - startTime;
        IoOveruseStats expected = new IoOveruseStats.Builder(startTime, duration)
                .setTotalOveruses(78).setTotalTimesKilled(39).setTotalBytesWritten(234_000).build();

        IoOveruseStatsSubject.assertWithMessage(
                "Fetched stats only for 39 days. Expected stats (%s) equals actual stats (%s)",
                expected.toString(), actual.toString()).that(actual)
                .isEqualTo(expected);

        setDate(/* numDaysAgo= */ 0);
        mService.shrinkDatabase();

        actual = mService.getHistoricalIoOveruseStats(
                /* userId= */ 100, "system_package.non_critical.A", /* numDaysAgo= */ 40);

        assertWithMessage("Fetched I/O overuse stats").that(actual).isNotNull();

        currentDate = mTimeSource.now().atZone(ZONE_OFFSET).truncatedTo(STATS_TEMPORAL_UNIT);
        startTime = currentDate.minus(RETENTION_PERIOD.minusDays(1)).toEpochSecond();
        duration = currentDate.toEpochSecond() - startTime;
        expected = new IoOveruseStats.Builder(startTime, duration)
                .setTotalOveruses(58).setTotalTimesKilled(29).setTotalBytesWritten(174_000).build();

        IoOveruseStatsSubject.assertWithMessage("Fetched stats only within retention period. "
                        + "Expected stats (%s) equals actual stats (%s)",
                expected.toString(), actual.toString()).that(actual).isEqualTo(expected);
    }

    private void setDate(int numDaysAgo) {
        TimeSourceInterface timeSource = new TimeSourceInterface() {
            @Override
            public Instant now() {
                /* Return the same time, so the tests are deterministic. */
                return mNow;
            }

            @Override
            public String toString() {
                return "Mocked date to " + now();
            }

            private final Instant mNow = Instant.now().minus(numDaysAgo, ChronoUnit.DAYS);
        };
        mService.setTimeSource(timeSource);
        mTimeSource = timeSource;
    }

    private void injectSampleUserPackageSettings() throws Exception {
        List<WatchdogStorage.UserPackageSettingsEntry> expected = sampleSettings();

        assertThat(mService.saveUserPackageSettings(expected)).isTrue();
    }

    private static ArrayList<WatchdogStorage.UserPackageSettingsEntry> sampleSettings() {
        return new ArrayList<>(Arrays.asList(
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "system_package.non_critical.A", KILLABLE_STATE_YES),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "system_package.non_critical.B", KILLABLE_STATE_NO),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 100, "vendor_package.critical.C", KILLABLE_STATE_NEVER),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 101, "system_package.non_critical.A", KILLABLE_STATE_NO),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 101, "system_package.non_critical.B", KILLABLE_STATE_YES),
                new WatchdogStorage.UserPackageSettingsEntry(
                        /* userId= */ 101, "vendor_package.critical.C", KILLABLE_STATE_NEVER)));
    }

    private ArrayList<WatchdogStorage.IoUsageStatsEntry> sampleStatsBetweenDates(
            int includingStartDaysAgo, int excludingEndDaysAgo) {
        ZonedDateTime currentDate = mTimeSource.now().atZone(ZONE_OFFSET)
                .truncatedTo(STATS_TEMPORAL_UNIT);
        ArrayList<WatchdogStorage.IoUsageStatsEntry> entries = new ArrayList<>();
        for (int i = includingStartDaysAgo; i < excludingEndDaysAgo; ++i) {
            entries.addAll(sampleStatsForDate(
                    currentDate.minus(i, STATS_TEMPORAL_UNIT).toEpochSecond(),
                    STATS_TEMPORAL_UNIT.getDuration().toSeconds()));
        }
        return entries;
    }

    private ArrayList<WatchdogStorage.IoUsageStatsEntry> sampleStatsForToday() {
        long currentTime = mTimeSource.now().atZone(ZONE_OFFSET)
                .truncatedTo(STATS_TEMPORAL_UNIT).toEpochSecond();
        long duration = mTimeSource.now().atZone(ZONE_OFFSET).toEpochSecond() - currentTime;
        return sampleStatsForDate(currentTime, duration);
    }

    private static ArrayList<WatchdogStorage.IoUsageStatsEntry> sampleStatsForDate(
            long statsDateEpoch, long duration) {
        ArrayList<WatchdogStorage.IoUsageStatsEntry> entries = new ArrayList<>();
        for (int i = 100; i <= 101; ++i) {
            entries.add(constructIoUsageStatsEntry(
                    /* userId= */ i, "system_package.non_critical.A", statsDateEpoch, duration,
                    /* remainingWriteBytes= */
                    CarWatchdogServiceUnitTest.constructPerStateBytes(200, 300, 400),
                    /* writtenBytes= */
                    CarWatchdogServiceUnitTest.constructPerStateBytes(1000, 2000, 3000),
                    /* forgivenWriteBytes= */
                    CarWatchdogServiceUnitTest.constructPerStateBytes(100, 100, 100),
                    /* totalOveruses= */ 2, /* totalTimesKilled= */ 1));
            entries.add(constructIoUsageStatsEntry(
                    /* userId= */ i, "vendor_package.critical.C", statsDateEpoch, duration,
                    /* remainingWriteBytes= */
                    CarWatchdogServiceUnitTest.constructPerStateBytes(500, 600, 700),
                    /* writtenBytes= */
                    CarWatchdogServiceUnitTest.constructPerStateBytes(4000, 5000, 6000),
                    /* forgivenWriteBytes= */
                    CarWatchdogServiceUnitTest.constructPerStateBytes(200, 200, 200),
                    /* totalOveruses= */ 1, /* totalTimesKilled= */ 0));
        }
        return entries;
    }

    private static WatchdogStorage.IoUsageStatsEntry constructIoUsageStatsEntry(
            int userId, String packageName, long startTime, long duration,
            PerStateBytes remainingWriteBytes, PerStateBytes writtenBytes,
            PerStateBytes forgivenWriteBytes, int totalOveruses, int totalTimesKilled) {
        WatchdogPerfHandler.PackageIoUsage ioUsage = new WatchdogPerfHandler.PackageIoUsage(
                constructInternalIoOveruseStats(startTime, duration, remainingWriteBytes,
                        writtenBytes, totalOveruses), forgivenWriteBytes, totalTimesKilled);
        return new WatchdogStorage.IoUsageStatsEntry(userId, packageName, ioUsage);
    }

    private static android.automotive.watchdog.IoOveruseStats constructInternalIoOveruseStats(
            long startTime, long duration, PerStateBytes remainingWriteBytes,
            PerStateBytes writtenBytes, int totalOveruses) {
        android.automotive.watchdog.IoOveruseStats stats =
                new android.automotive.watchdog.IoOveruseStats();
        stats.startTime = startTime;
        stats.durationInSeconds = duration;
        stats.remainingWriteBytes = remainingWriteBytes;
        stats.writtenBytes = writtenBytes;
        stats.totalOveruses = totalOveruses;
        return stats;
    }
}
