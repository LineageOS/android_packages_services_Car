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
import android.car.storagemonitoring.WearEstimate;
import android.car.storagemonitoring.WearEstimateChange;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.JsonWriter;
import android.util.Log;
import com.android.car.storagemonitoring.WearEstimateRecord;
import com.android.car.storagemonitoring.WearHistory;
import com.android.car.storagemonitoring.WearInformation;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test the public entry points for the CarStorageMonitoringManager */
@MediumTest
public class CarStorageMonitoringTest extends MockedCarTestBase {
    private static final String TAG = CarStorageMonitoringTest.class.getSimpleName();

    private static final WearInformation DEFAULT_WEAR_INFORMATION =
        new WearInformation(30, 0, WearInformation.PRE_EOL_INFO_NORMAL);

    private static final class WearData {
        static final WearData DEFAULT = new WearData(0, DEFAULT_WEAR_INFORMATION, null);

        final long uptime;
        @NonNull
        final WearInformation wearInformation;
        @Nullable
        final WearHistory wearHistory;

        WearData(long uptime, @Nullable WearInformation wearInformation, @Nullable WearHistory wearHistory) {
            if (wearInformation == null) wearInformation = DEFAULT_WEAR_INFORMATION;
            this.uptime = uptime;
            this.wearInformation = wearInformation;
            this.wearHistory = wearHistory;
        }
    }

    private static final Map<String, WearData> PER_TEST_WEAR_DATA =
            new HashMap<String, WearData>() {{
                put("testReadWearHistory",
                    new WearData(6500, DEFAULT_WEAR_INFORMATION,
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
                                .atTimestamp(Instant.ofEpochMilli(17000)).build())));

                put("testNotAcceptableWearEvent",
                    new WearData(2520006499L,
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
                                .atTimestamp(Instant.ofEpochMilli(17000)).build())));

                put("testAcceptableWearEvent",
                    new WearData(2520006501L,
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
                                .atTimestamp(Instant.ofEpochMilli(17000)).build())));
            }};

    private CarStorageMonitoringManager mCarStorageMonitoringManager;

    @Override
    protected synchronized void configureFakeSystemInterface() {
        try {
            final String testName = getName();
            final WearData wearData = PER_TEST_WEAR_DATA.getOrDefault(testName, WearData.DEFAULT);
            final WearHistory wearHistory = wearData.wearHistory;

            setFlashWearInformation(wearData.wearInformation);
            setUptimeProvider( (boolean b) -> 0 );

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
        } catch (IOException e) {
            Log.e(TAG, "failed to configure fake system interface", e);
            fail("failed to configure fake system interface instance");
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        fakeBootCompletedEvent();

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

        final WearHistory expectedWearHistory = PER_TEST_WEAR_DATA.get(getName()).wearHistory;

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

        final WearData wearData = PER_TEST_WEAR_DATA.get(getName());

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
}
