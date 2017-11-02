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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.Car;
import android.car.storagemonitoring.CarStorageMonitoringManager;
import android.car.storagemonitoring.UidIoStats;
import android.car.storagemonitoring.UidIoStatsRecord;
import android.car.storagemonitoring.WearEstimate;
import android.car.storagemonitoring.WearEstimateChange;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.JsonWriter;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import com.android.car.storagemonitoring.UidIoStatsProvider;
import com.android.car.storagemonitoring.WearEstimateRecord;
import com.android.car.storagemonitoring.WearHistory;
import com.android.car.storagemonitoring.WearInformation;
import com.android.car.storagemonitoring.WearInformationProvider;
import com.android.car.systeminterface.StorageMonitoringInterface;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.systeminterface.SystemStateInterface;
import com.android.car.systeminterface.TimeInterface;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test the public entry points for the CarStorageMonitoringManager */
@MediumTest
public class CarStorageMonitoringTest extends MockedCarTestBase {
    private static final String TAG = CarStorageMonitoringTest.class.getSimpleName();

    private static final WearInformation DEFAULT_WEAR_INFORMATION =
        new WearInformation(30, 0, WearInformation.PRE_EOL_INFO_NORMAL);

    private static final class TestData {
        static final TestData DEFAULT = new TestData(0, DEFAULT_WEAR_INFORMATION, null, null);

        final long uptime;
        @NonNull
        final WearInformation wearInformation;
        @Nullable
        final WearHistory wearHistory;
        @NonNull
        final UidIoStatsRecord[] ioStats;

        TestData(long uptime,
                @Nullable WearInformation wearInformation,
                @Nullable WearHistory wearHistory,
                @Nullable UidIoStatsRecord[] ioStats) {
            if (wearInformation == null) wearInformation = DEFAULT_WEAR_INFORMATION;
            if (ioStats == null) ioStats = new UidIoStatsRecord[0];
            this.uptime = uptime;
            this.wearInformation = wearInformation;
            this.wearHistory = wearHistory;
            this.ioStats = ioStats;
        }
    }

    private static final Map<String, TestData> PER_TEST_DATA =
            new HashMap<String, TestData>() {{
                put("testReadWearHistory",
                    new TestData(6500, DEFAULT_WEAR_INFORMATION,
                        WearHistory.fromRecords(
                            WearEstimateRecord.Builder.newBuilder()
                                .fromWearEstimate(WearEstimate.UNKNOWN_ESTIMATE)
                                .toWearEstimate(new WearEstimate(10, 0))
                                .atUptime(1000)
                                .atTimestamp(Instant.ofEpochMilli(5000)).build(),
                            WearEstimateRecord.Builder.newBuilder()
                                .fromWearEstimate(new WearEstimate(10, 0))
                                .toWearEstimate(new WearEstimate(20, 0))
                                .atUptime(4000)
                                .atTimestamp(Instant.ofEpochMilli(12000)).build(),
                            WearEstimateRecord.Builder.newBuilder()
                                .fromWearEstimate(new WearEstimate(20, 0))
                                .toWearEstimate(new WearEstimate(30, 0))
                                .atUptime(6500)
                                .atTimestamp(Instant.ofEpochMilli(17000)).build()), null));

                put("testNotAcceptableWearEvent",
                    new TestData(2520006499L,
                        new WearInformation(40, 0, WearInformation.PRE_EOL_INFO_NORMAL),
                        WearHistory.fromRecords(
                            WearEstimateRecord.Builder.newBuilder()
                                .fromWearEstimate(WearEstimate.UNKNOWN_ESTIMATE)
                                .toWearEstimate(new WearEstimate(10, 0))
                                .atUptime(1000)
                                .atTimestamp(Instant.ofEpochMilli(5000)).build(),
                            WearEstimateRecord.Builder.newBuilder()
                                .fromWearEstimate(new WearEstimate(10, 0))
                                .toWearEstimate(new WearEstimate(20, 0))
                                .atUptime(4000)
                                .atTimestamp(Instant.ofEpochMilli(12000)).build(),
                            WearEstimateRecord.Builder.newBuilder()
                                .fromWearEstimate(new WearEstimate(20, 0))
                                .toWearEstimate(new WearEstimate(30, 0))
                                .atUptime(6500)
                                .atTimestamp(Instant.ofEpochMilli(17000)).build()), null));

                put("testAcceptableWearEvent",
                    new TestData(2520006501L,
                        new WearInformation(40, 0, WearInformation.PRE_EOL_INFO_NORMAL),
                        WearHistory.fromRecords(
                            WearEstimateRecord.Builder.newBuilder()
                                .fromWearEstimate(WearEstimate.UNKNOWN_ESTIMATE)
                                .toWearEstimate(new WearEstimate(10, 0))
                                .atUptime(1000)
                                .atTimestamp(Instant.ofEpochMilli(5000)).build(),
                            WearEstimateRecord.Builder.newBuilder()
                                .fromWearEstimate(new WearEstimate(10, 0))
                                .toWearEstimate(new WearEstimate(20, 0))
                                .atUptime(4000)
                                .atTimestamp(Instant.ofEpochMilli(12000)).build(),
                            WearEstimateRecord.Builder.newBuilder()
                                .fromWearEstimate(new WearEstimate(20, 0))
                                .toWearEstimate(new WearEstimate(30, 0))
                                .atUptime(6500)
                                .atTimestamp(Instant.ofEpochMilli(17000)).build()), null));

                put("testBootIoStats",
                    new TestData(1000L,
                        new WearInformation(0, 0, WearInformation.PRE_EOL_INFO_NORMAL),
                        null,
                        new UidIoStatsRecord[]{
                            new UidIoStatsRecord(0, 5000, 6000, 3000, 1000, 1,
                                0, 0, 0, 0, 0),
                            new UidIoStatsRecord(1000, 200, 5000, 0, 4000, 0,
                                1000, 0, 500, 0, 0)}));
            }};

    private final MockSystemStateInterface mMockSystemStateInterface =
            new MockSystemStateInterface();
    private final MockStorageMonitoringInterface mMockStorageMonitoringInterface =
            new MockStorageMonitoringInterface();

    private CarStorageMonitoringManager mCarStorageMonitoringManager;

    @Override
    protected synchronized SystemInterface.Builder getSystemInterfaceBuilder() {
        SystemInterface.Builder builder = super.getSystemInterfaceBuilder();
        return builder.withSystemStateInterface(mMockSystemStateInterface)
            .withStorageMonitoringInterface(mMockStorageMonitoringInterface)
            .withTimeInterface(new MockTimeInterface());
    }

    @Override
    protected synchronized void configureFakeSystemInterface() {
        try {
            final String testName = getName();
            final TestData wearData = PER_TEST_DATA.getOrDefault(testName, TestData.DEFAULT);
            final WearHistory wearHistory = wearData.wearHistory;

            mMockStorageMonitoringInterface.setWearInformation(wearData.wearInformation);

            if (wearHistory != null) {
                File wearHistoryFile = new File(getFakeSystemInterface().getFilesDir(),
                    CarStorageMonitoringService.WEAR_INFO_FILENAME);
                try (JsonWriter jsonWriter = new JsonWriter(new FileWriter(wearHistoryFile))) {
                    wearHistory.writeToJson(jsonWriter);
                }
            }

            if (wearData.uptime > 0) {
                File uptimeFile = new File(getFakeSystemInterface().getFilesDir(),
                    CarStorageMonitoringService.UPTIME_TRACKER_FILENAME);
                try (JsonWriter jsonWriter = new JsonWriter(new FileWriter(uptimeFile))) {
                    jsonWriter.beginObject();
                    jsonWriter.name("uptime").value(wearData.uptime);
                    jsonWriter.endObject();
                }
            }

            Arrays.stream(wearData.ioStats).forEach(
                    mMockStorageMonitoringInterface::addIoStatsRecord);

        } catch (IOException e) {
            Log.e(TAG, "failed to configure fake system interface", e);
            fail("failed to configure fake system interface instance");
        }

    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mMockSystemStateInterface.executeBootCompletedActions();

        mCarStorageMonitoringManager =
            (CarStorageMonitoringManager) getCar().getCarManager(Car.STORAGE_MONITORING_SERVICE);
    }

    public void testReadPreEolInformation() throws Exception {
        assertEquals(DEFAULT_WEAR_INFORMATION.preEolInfo,
                mCarStorageMonitoringManager.getPreEolIndicatorStatus());
    }

    public void testReadWearEstimate() throws Exception {
        final WearEstimate wearEstimate = mCarStorageMonitoringManager.getWearEstimate();

        assertNotNull(wearEstimate);
        assertEquals(DEFAULT_WEAR_INFORMATION.lifetimeEstimateA, wearEstimate.typeA);
        assertEquals(DEFAULT_WEAR_INFORMATION.lifetimeEstimateB, wearEstimate.typeB);
    }

    public void testReadWearHistory() throws Exception {
        final List<WearEstimateChange> wearEstimateChanges =
                mCarStorageMonitoringManager.getWearEstimateHistory();

        assertNotNull(wearEstimateChanges);
        assertFalse(wearEstimateChanges.isEmpty());

        final WearHistory expectedWearHistory = PER_TEST_DATA.get(getName()).wearHistory;

        assertEquals(expectedWearHistory.size(), wearEstimateChanges.size());
        for (int i = 0; i < wearEstimateChanges.size(); ++i) {
            final WearEstimateRecord expected = expectedWearHistory.get(i);
            final WearEstimateChange actual = wearEstimateChanges.get(i);

            assertTrue(expected.isSameAs(actual));
        }
    }

    private void checkLastWearEvent(boolean isAcceptable) throws Exception {
        final List<WearEstimateChange> wearEstimateChanges =
            mCarStorageMonitoringManager.getWearEstimateHistory();

        assertNotNull(wearEstimateChanges);
        assertFalse(wearEstimateChanges.isEmpty());

        final TestData wearData = PER_TEST_DATA.get(getName());

        final WearInformation expectedCurrentWear = wearData.wearInformation;
        final WearEstimate expectedPreviousWear = wearData.wearHistory.getLast().getNewWearEstimate();

        final WearEstimateChange actualCurrentWear =
                wearEstimateChanges.get(wearEstimateChanges.size() - 1);

        assertEquals(isAcceptable, actualCurrentWear.isAcceptableDegradation);
        assertEquals(expectedCurrentWear.toWearEstimate(), actualCurrentWear.newEstimate);
        assertEquals(expectedPreviousWear, actualCurrentWear.oldEstimate);
    }

    public void testNotAcceptableWearEvent() throws Exception {
        checkLastWearEvent(false);
    }

    public void testAcceptableWearEvent() throws Exception {
        checkLastWearEvent(true);
    }

    public void testBootIoStats() throws Exception {
        final List<UidIoStats> bootIoStats =
            mCarStorageMonitoringManager.getBootIoStats();

        assertNotNull(bootIoStats);
        assertFalse(bootIoStats.isEmpty());

        final UidIoStatsRecord[] bootIoRecords = PER_TEST_DATA.get(getName()).ioStats;

        bootIoStats.forEach(uidIoStats -> assertTrue(Arrays.stream(bootIoRecords).anyMatch(
                ioRecord -> uidIoStats.representsSameMetrics(ioRecord))));
    }

    static final class MockStorageMonitoringInterface implements StorageMonitoringInterface,
        WearInformationProvider {
        private WearInformation mWearInformation = null;
        private SparseArray<UidIoStatsRecord> mIoStats = new SparseArray<>();
        private UidIoStatsProvider mIoStatsProvider = () -> mIoStats;

        void setWearInformation(WearInformation wearInformation) {
            mWearInformation = wearInformation;
        }

        void addIoStatsRecord(UidIoStatsRecord record) {
            mIoStats.append(record.uid, record);
        }

        void deleteIoStatsRecord(int uid) {
            mIoStats.delete(uid);
        }

        @Override
        public WearInformation load() {
            return mWearInformation;
        }

        @Override
        public WearInformationProvider[] getFlashWearInformationProviders() {
            return new WearInformationProvider[] {this};
        }

        @Override
        public UidIoStatsProvider getUidIoStatsProvider() {
            return mIoStatsProvider;
        }
    }

    static final class MockTimeInterface implements TimeInterface {

        @Override
        public long getUptime(boolean includeDeepSleepTime) {
            return 0;
        }

        @Override
        public void scheduleAction(Runnable r, long delayMs) {}

        @Override
        public void cancelAllActions() {}
    }

    static final class MockSystemStateInterface implements SystemStateInterface {
        private final List<Pair<Runnable, Duration>> mActionsList = new ArrayList<>();

        @Override
        public void shutdown() {}

        @Override
        public void enterDeepSleep(int wakeupTimeSec) {}

        @Override
        public void scheduleActionForBootCompleted(Runnable action, Duration delay) {
            mActionsList.add(Pair.create(action, delay));
            mActionsList.sort(Comparator.comparing(d -> d.second));
        }

        void executeBootCompletedActions() {
            for (Pair<Runnable, Duration> action : mActionsList) {
                action.first.run();
            }
        }
    }
}
