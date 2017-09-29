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

import android.car.storagemonitoring.WearEstimate;
import android.car.storagemonitoring.WearEstimateChange;
import android.os.Parcel;
import android.test.suitebuilder.annotation.MediumTest;

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
}
