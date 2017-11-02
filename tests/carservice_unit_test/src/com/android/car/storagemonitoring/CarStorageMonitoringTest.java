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

package com.android.car.storagemonitoring;

import android.car.storagemonitoring.UidIoStats;
import android.car.storagemonitoring.UidIoStats.PerStateMetrics;
import android.car.storagemonitoring.UidIoStatsRecord;
import android.car.storagemonitoring.WearEstimate;
import android.car.storagemonitoring.WearEstimateChange;
import android.os.Parcel;
import android.test.suitebuilder.annotation.MediumTest;

import android.util.SparseArray;
import com.android.car.test.utils.TemporaryFile;
import android.util.JsonReader;
import android.util.JsonWriter;

import java.io.FileWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import junit.framework.TestCase;
import org.json.JSONObject;

/**
 * Tests the storage monitoring API in CarService.
 */
@MediumTest
public class CarStorageMonitoringTest extends TestCase {
    static final String TAG = CarStorageMonitoringTest.class.getSimpleName();

    public void testEMmcWearInformationProvider() throws Exception {
        try (TemporaryFile lifetimeFile = new TemporaryFile(TAG)) {
            try (TemporaryFile eolFile = new TemporaryFile(TAG)) {
                lifetimeFile.write("0x05 0x00");
                eolFile.write("01");

                EMmcWearInformationProvider wearInfoProvider = new EMmcWearInformationProvider(
                        lifetimeFile.getFile(), eolFile.getFile());

                WearInformation wearInformation = wearInfoProvider.load();

                assertNotNull(wearInformation);
                assertEquals(40, wearInformation.lifetimeEstimateA);
                assertEquals(WearInformation.UNKNOWN_LIFETIME_ESTIMATE,
                        wearInformation.lifetimeEstimateB);

                assertEquals(WearInformation.PRE_EOL_INFO_NORMAL, wearInformation.preEolInfo);
            }
        }
    }

    public void testUfsWearInformationProvider() throws Exception {
        try (TemporaryFile lifetimeFile = new TemporaryFile(TAG)) {
            lifetimeFile.write("ufs version: 1.0\n" +
                    "Health Descriptor[Byte offset 0x2]: bPreEOLInfo = 0x2\n" +
                    "Health Descriptor[Byte offset 0x1]: bDescriptionIDN = 0x1\n" +
                    "Health Descriptor[Byte offset 0x3]: bDeviceLifeTimeEstA = 0x0\n" +
                    "Health Descriptor[Byte offset 0x5]: VendorPropInfo = somedatahere\n" +
                    "Health Descriptor[Byte offset 0x4]: bDeviceLifeTimeEstB = 0xA\n");

            UfsWearInformationProvider wearInfoProvider = new UfsWearInformationProvider(
                lifetimeFile.getFile());

            WearInformation wearInformation = wearInfoProvider.load();

            assertNotNull(wearInformation);
            assertEquals(90, wearInformation.lifetimeEstimateB);
            assertEquals(WearInformation.PRE_EOL_INFO_WARNING, wearInformation.preEolInfo);
            assertEquals(WearInformation.UNKNOWN_LIFETIME_ESTIMATE,
                    wearInformation.lifetimeEstimateA);
        }
    }

    public void testWearEstimateEquality() {
        WearEstimate wearEstimate1 = new WearEstimate(10, 20);
        WearEstimate wearEstimate2 = new WearEstimate(10, 20);
        WearEstimate wearEstimate3 = new WearEstimate(20, 30);
        assertEquals(wearEstimate1, wearEstimate1);
        assertEquals(wearEstimate1, wearEstimate2);
        assertNotSame(wearEstimate1, wearEstimate3);
    }

    public void testWearEstimateParcel() throws Exception {
        WearEstimate originalWearEstimate = new WearEstimate(10, 20);
        Parcel p = Parcel.obtain();
        originalWearEstimate.writeToParcel(p, 0);
        p.setDataPosition(0);
        WearEstimate newWearEstimate = new WearEstimate(p);
        assertEquals(originalWearEstimate, newWearEstimate);
        p.recycle();
    }

    public void testWearEstimateChangeEquality() {
        WearEstimateChange wearEstimateChange1 = new WearEstimateChange(
                new WearEstimate(10, 20),
                new WearEstimate(20, 30),
                5000L,
                Instant.now(),
                false);
        WearEstimateChange wearEstimateChange2 = new WearEstimateChange(
            new WearEstimate(10, 20),
            new WearEstimate(20, 30),
            5000L,
            wearEstimateChange1.dateAtChange,
            false);
        assertEquals(wearEstimateChange1, wearEstimateChange1);
        assertEquals(wearEstimateChange1, wearEstimateChange2);
        WearEstimateChange wearEstimateChange3 = new WearEstimateChange(
            new WearEstimate(10, 30),
            new WearEstimate(20, 30),
            3000L,
            Instant.now(),
            true);
        assertNotSame(wearEstimateChange1, wearEstimateChange3);
    }

    public void testWearEstimateChangeParcel() throws Exception {
        WearEstimateChange originalWearEstimateChange = new WearEstimateChange(
                new WearEstimate(10, 0),
                new WearEstimate(20, 10),
                123456789L,
                Instant.now(),
                false);
        Parcel p = Parcel.obtain();
        originalWearEstimateChange.writeToParcel(p, 0);
        p.setDataPosition(0);
        WearEstimateChange newWearEstimateChange = new WearEstimateChange(p);
        assertEquals(originalWearEstimateChange, newWearEstimateChange);
        p.recycle();
    }

    public void testWearEstimateJson() throws Exception {
        WearEstimate originalWearEstimate = new WearEstimate(20, 0);
        StringWriter stringWriter = new StringWriter(1024);
        JsonWriter jsonWriter = new JsonWriter(stringWriter);
        originalWearEstimate.writeToJson(jsonWriter);
        StringReader stringReader = new StringReader(stringWriter.toString());
        JsonReader jsonReader = new JsonReader(stringReader);
        WearEstimate newWearEstimate = new WearEstimate(jsonReader);
        assertEquals(originalWearEstimate, newWearEstimate);
    }

    public void testWearEstimateRecordJson() throws Exception {
        try (TemporaryFile temporaryFile = new TemporaryFile(TAG)) {
            WearEstimateRecord originalWearEstimateRecord = new WearEstimateRecord(new WearEstimate(10, 20),
                new WearEstimate(10, 30), 5000, Instant.ofEpochMilli(1000));
            try (JsonWriter jsonWriter = new JsonWriter(new FileWriter(temporaryFile.getFile()))) {
                originalWearEstimateRecord.writeToJson(jsonWriter);
            }
            JSONObject jsonObject = new JSONObject(
                    new String(Files.readAllBytes(temporaryFile.getPath())));
            WearEstimateRecord newWearEstimateRecord = new WearEstimateRecord(jsonObject);
            assertEquals(originalWearEstimateRecord, newWearEstimateRecord);
        }
    }

    public void testWearEstimateRecordEquality() throws Exception {
        WearEstimateRecord wearEstimateRecord1 = new WearEstimateRecord(WearEstimate.UNKNOWN_ESTIMATE,
                new WearEstimate(10, 20), 5000, Instant.ofEpochMilli(2000));
        WearEstimateRecord wearEstimateRecord2 = new WearEstimateRecord(WearEstimate.UNKNOWN_ESTIMATE,
            new WearEstimate(10, 20), 5000, Instant.ofEpochMilli(2000));
        WearEstimateRecord wearEstimateRecord3 = new WearEstimateRecord(WearEstimate.UNKNOWN_ESTIMATE,
            new WearEstimate(10, 40), 5000, Instant.ofEpochMilli(1000));

        assertEquals(wearEstimateRecord1, wearEstimateRecord1);
        assertEquals(wearEstimateRecord1, wearEstimateRecord2);
        assertNotSame(wearEstimateRecord1, wearEstimateRecord3);
    }

    public void testWearHistoryJson() throws Exception {
        try (TemporaryFile temporaryFile = new TemporaryFile(TAG)) {
            WearEstimateRecord wearEstimateRecord1 = new WearEstimateRecord(
                WearEstimate.UNKNOWN_ESTIMATE,
                new WearEstimate(10, 20), 5000, Instant.ofEpochMilli(2000));
            WearEstimateRecord wearEstimateRecord2 = new WearEstimateRecord(
                wearEstimateRecord1.getOldWearEstimate(),
                new WearEstimate(10, 40), 9000, Instant.ofEpochMilli(16000));
            WearEstimateRecord wearEstimateRecord3 = new WearEstimateRecord(
                wearEstimateRecord2.getOldWearEstimate(),
                new WearEstimate(20, 40), 12000, Instant.ofEpochMilli(21000));
            WearHistory originalWearHistory = WearHistory.fromRecords(wearEstimateRecord1,
                wearEstimateRecord2, wearEstimateRecord3);
            try (JsonWriter jsonWriter = new JsonWriter(new FileWriter(temporaryFile.getFile()))) {
                originalWearHistory.writeToJson(jsonWriter);
            }
            JSONObject jsonObject = new JSONObject(
                new String(Files.readAllBytes(temporaryFile.getPath())));
            WearHistory newWearHistory = new WearHistory(jsonObject);
            assertEquals(originalWearHistory, newWearHistory);
        }
    }

    public void testWearHistoryEquality() throws Exception {
        WearEstimateRecord wearEstimateRecord1 = new WearEstimateRecord(
            WearEstimate.UNKNOWN_ESTIMATE,
            new WearEstimate(10, 20), 5000, Instant.ofEpochMilli(2000));
        WearEstimateRecord wearEstimateRecord2 = new WearEstimateRecord(
            wearEstimateRecord1.getOldWearEstimate(),
            new WearEstimate(10, 40), 9000, Instant.ofEpochMilli(16000));
        WearEstimateRecord wearEstimateRecord3 = new WearEstimateRecord(
            wearEstimateRecord2.getOldWearEstimate(),
            new WearEstimate(20, 40), 12000, Instant.ofEpochMilli(21000));
        WearEstimateRecord wearEstimateRecord4 = new WearEstimateRecord(
            wearEstimateRecord3.getOldWearEstimate(),
            new WearEstimate(30, 50), 17000, Instant.ofEpochMilli(34000));
        WearEstimateRecord wearEstimateRecord5 = new WearEstimateRecord(
            wearEstimateRecord3.getOldWearEstimate(),
            new WearEstimate(20, 50), 15000, Instant.ofEpochMilli(34000));

        WearHistory wearHistory1 = WearHistory.fromRecords(wearEstimateRecord1,
            wearEstimateRecord2, wearEstimateRecord3, wearEstimateRecord4);
        WearHistory wearHistory2 = WearHistory.fromRecords(wearEstimateRecord4,
            wearEstimateRecord1, wearEstimateRecord2, wearEstimateRecord3);
        WearHistory wearHistory3 = WearHistory.fromRecords(wearEstimateRecord1,
            wearEstimateRecord2, wearEstimateRecord3, wearEstimateRecord5);

        assertEquals(wearHistory1, wearHistory1);
        assertEquals(wearHistory1, wearHistory2);
        assertNotSame(wearHistory1, wearHistory3);
    }

    public void testWearHistoryToChanges() {
        WearEstimateRecord wearEstimateRecord1 = new WearEstimateRecord(
            WearEstimate.UNKNOWN_ESTIMATE,
            new WearEstimate(10, 20), 3600000, Instant.ofEpochMilli(2000));
        WearEstimateRecord wearEstimateRecord2 = new WearEstimateRecord(
            wearEstimateRecord1.getOldWearEstimate(),
            new WearEstimate(10, 40), 172000000, Instant.ofEpochMilli(16000));
        WearEstimateRecord wearEstimateRecord3 = new WearEstimateRecord(
            wearEstimateRecord2.getOldWearEstimate(),
            new WearEstimate(20, 40), 172000001, Instant.ofEpochMilli(21000));

        WearHistory wearHistory = WearHistory.fromRecords(wearEstimateRecord1,
            wearEstimateRecord2, wearEstimateRecord3);

        List<WearEstimateChange> wearEstimateChanges = wearHistory.toWearEstimateChanges(1);

        assertEquals(3, wearEstimateChanges.size());
        WearEstimateChange unknownToOne = wearEstimateChanges.get(0);
        WearEstimateChange oneToTwo = wearEstimateChanges.get(1);
        WearEstimateChange twoToThree = wearEstimateChanges.get(2);

        assertEquals(unknownToOne.oldEstimate, wearEstimateRecord1.getOldWearEstimate());
        assertEquals(unknownToOne.newEstimate, wearEstimateRecord1.getNewWearEstimate());
        assertEquals(unknownToOne.uptimeAtChange, wearEstimateRecord1.getTotalCarServiceUptime());
        assertEquals(unknownToOne.dateAtChange, wearEstimateRecord1.getUnixTimestamp());
        assertTrue(unknownToOne.isAcceptableDegradation);

        assertEquals(oneToTwo.oldEstimate, wearEstimateRecord2.getOldWearEstimate());
        assertEquals(oneToTwo.newEstimate, wearEstimateRecord2.getNewWearEstimate());
        assertEquals(oneToTwo.uptimeAtChange, wearEstimateRecord2.getTotalCarServiceUptime());
        assertEquals(oneToTwo.dateAtChange, wearEstimateRecord2.getUnixTimestamp());
        assertTrue(oneToTwo.isAcceptableDegradation);

        assertEquals(twoToThree.oldEstimate, wearEstimateRecord3.getOldWearEstimate());
        assertEquals(twoToThree.newEstimate, wearEstimateRecord3.getNewWearEstimate());
        assertEquals(twoToThree.uptimeAtChange, wearEstimateRecord3.getTotalCarServiceUptime());
        assertEquals(twoToThree.dateAtChange, wearEstimateRecord3.getUnixTimestamp());
        assertFalse(twoToThree.isAcceptableDegradation);
    }

    public void testUidIoStatEntry() throws Exception {
        try (TemporaryFile statsFile = new TemporaryFile(TAG)) {
            statsFile.write("0 256797495 181736102 362132480 947167232 0 0 0 0 250 0\n"
                + "1006 489007 196802 0 20480 51474 2048 1024 2048 1 1\n");

            ProcfsUidIoStatsProvider statsProvider = new ProcfsUidIoStatsProvider(
                    statsFile.getPath());

            SparseArray<UidIoStatsRecord> entries = statsProvider.load();

            assertNotNull(entries);
            assertEquals(2, entries.size());

            UidIoStats entry = new UidIoStats(entries.get(0), 1234);
            assertNotNull(entry);
            assertEquals(0, entry.uid);
            assertEquals(1234, entry.runtimeMillis);
            assertEquals(256797495, entry.foreground.bytesRead);
            assertEquals(181736102, entry.foreground.bytesWritten);
            assertEquals(362132480, entry.foreground.bytesReadFromStorage);
            assertEquals(947167232, entry.foreground.bytesWrittenToStorage);
            assertEquals(250, entry.foreground.fsyncCalls);
            assertEquals(0, entry.background.bytesRead);
            assertEquals(0, entry.background.bytesWritten);
            assertEquals(0, entry.background.bytesReadFromStorage);
            assertEquals(0, entry.background.bytesWrittenToStorage);
            assertEquals(0, entry.background.fsyncCalls);

            entry = new UidIoStats(entries.get(1006), 4321);
            assertNotNull(entry);
            assertEquals(1006, entry.uid);
            assertEquals(4321, entry.runtimeMillis);
            assertEquals(489007, entry.foreground.bytesRead);
            assertEquals(196802, entry.foreground.bytesWritten);
            assertEquals(0, entry.foreground.bytesReadFromStorage);
            assertEquals(20480, entry.foreground.bytesWrittenToStorage);
            assertEquals(1, entry.foreground.fsyncCalls);
            assertEquals(51474, entry.background.bytesRead);
            assertEquals(2048, entry.background.bytesWritten);
            assertEquals(1024, entry.background.bytesReadFromStorage);
            assertEquals(2048, entry.background.bytesWrittenToStorage);
            assertEquals(1, entry.background.fsyncCalls);
        }
    }

    public void testUidIoStatEntryMissingFields() throws Exception {
        try (TemporaryFile statsFile = new TemporaryFile(TAG)) {
            statsFile.write("0 256797495 181736102 362132480 947167232 0 0 0 0 250 0\n"
                + "1 2 3 4 5 6 7 8 9\n");

            ProcfsUidIoStatsProvider statsProvider = new ProcfsUidIoStatsProvider(
                statsFile.getPath());

            SparseArray<UidIoStatsRecord> entries = statsProvider.load();

            assertNull(entries);
        }
    }

    public void testUidIoStatEntryNonNumericFields() throws Exception {
        try (TemporaryFile statsFile = new TemporaryFile(TAG)) {
            statsFile.write("0 256797495 181736102 362132480 947167232 0 0 0 0 250 0\n"
                + "notanumber 489007 196802 0 20480 51474 2048 1024 2048 1 1\n");

            ProcfsUidIoStatsProvider statsProvider = new ProcfsUidIoStatsProvider(
                statsFile.getPath());

            SparseArray<UidIoStatsRecord> entries = statsProvider.load();

            assertNull(entries);
        }
    }

    public void testUidIoStatEntryEquality() throws Exception {
        UidIoStats statEntry1 = new UidIoStats(10, 1234,
            new PerStateMetrics(10, 20, 30, 40, 50),
            new PerStateMetrics(100, 200, 300, 400, 500));
        UidIoStats statEntry2 = new UidIoStats(10, 1234,
            new PerStateMetrics(10, 20, 30, 40, 50),
            new PerStateMetrics(100, 200, 300, 400, 500));
        UidIoStats statEntry3 = new UidIoStats(30, 4567,
            new PerStateMetrics(1, 20, 30, 42, 50),
            new PerStateMetrics(10, 200, 300, 420, 500));
        UidIoStats statEntry4 = new UidIoStats(11, 6541,
            new PerStateMetrics(10, 20, 30, 40, 50),
            new PerStateMetrics(100, 200, 300, 400, 500));
        UidIoStats statEntry5 = new UidIoStats(10, 1234,
            new PerStateMetrics(10, 20, 30, 40, 0),
            new PerStateMetrics(100, 200, 300, 400, 500));

        assertEquals(statEntry1, statEntry1);
        assertEquals(statEntry1, statEntry2);
        assertNotSame(statEntry1, statEntry3);
        assertNotSame(statEntry1, statEntry4);
        assertNotSame(statEntry1, statEntry5);
    }

    public void testUidIoStatEntryParcel() throws Exception {
        UidIoStats statEntry = new UidIoStats(10, 5000,
            new PerStateMetrics(10, 20, 30, 40, 50),
            new PerStateMetrics(100, 200, 300, 400, 500));
        Parcel p = Parcel.obtain();
        statEntry.writeToParcel(p, 0);
        p.setDataPosition(0);
        UidIoStats other = new UidIoStats(p);
        assertEquals(other, statEntry);
    }

    public void testUidIoStatEntryJson() throws Exception {
        try (TemporaryFile temporaryFile = new TemporaryFile(TAG)) {
            UidIoStats statEntry = new UidIoStats(10, 1200,
                new PerStateMetrics(10, 20, 30, 40, 50),
                new PerStateMetrics(100, 200, 300, 400, 500));
            try (JsonWriter jsonWriter = new JsonWriter(new FileWriter(temporaryFile.getFile()))) {
                statEntry.writeToJson(jsonWriter);
            }
            JSONObject jsonObject = new JSONObject(
                new String(Files.readAllBytes(temporaryFile.getPath())));
            UidIoStats other = new UidIoStats(jsonObject);
            assertEquals(statEntry, other);
        }
    }


    public void testUidIoStatEntryDelta() throws Exception {
        UidIoStats statEntry1 = new UidIoStats(10, 1000,
            new PerStateMetrics(10, 20, 30, 40, 50),
            new PerStateMetrics(60, 70, 80, 90, 100));

        UidIoStats statEntry2 = new UidIoStats(10,2000,
            new PerStateMetrics(110, 120, 130, 140, 150),
            new PerStateMetrics(260, 370, 480, 500, 110));

        UidIoStats statEntry3 = new UidIoStats(30, 3000,
            new PerStateMetrics(10, 20, 30, 40, 50),
            new PerStateMetrics(100, 200, 300, 400, 500));


        UidIoStats delta21 = statEntry2.delta(statEntry1);
        assertNotNull(delta21);
        assertEquals(statEntry1.uid, delta21.uid);

        assertEquals(1000, delta21.runtimeMillis);
        assertEquals(100, delta21.foreground.bytesRead);
        assertEquals(100, delta21.foreground.bytesWritten);
        assertEquals(100, delta21.foreground.bytesReadFromStorage);
        assertEquals(100, delta21.foreground.bytesWrittenToStorage);
        assertEquals(100, delta21.foreground.fsyncCalls);

        assertEquals(200, delta21.background.bytesRead);
        assertEquals(300, delta21.background.bytesWritten);
        assertEquals(400, delta21.background.bytesReadFromStorage);
        assertEquals(410, delta21.background.bytesWrittenToStorage);
        assertEquals(10, delta21.background.fsyncCalls);

        try {
            UidIoStats delta31 = statEntry3.delta(statEntry1);
            fail("delta only allowed on stats for matching user ID");
        } catch (IllegalArgumentException e) {
            // test passed
        }
    }
}
