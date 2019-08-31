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

package com.android.car.trust;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;

@RunWith(AndroidJUnit4.class)
public class ScanResultsTest {

    private static final BigInteger CORRECT_DATA =
            new BigInteger(
                    "02011A14FF4C000100000000000000000000000000200000000000000000000000000000"
                            + "0000000000000000000000000000000000000000000000000000", 16);

    private static final BigInteger CORRECT_MASK =
            new BigInteger("00000000000000000000000000200000", 16);

    private static final BigInteger MULTIPLE_BIT_MASK =
            new BigInteger("00000000000000000100000000200000", 16);

    @Test
    public void containsUuidsInOverflow_correctBitFlipped_shouldReturnTrue() {
        ScanRecord mockScanRecord = mock(ScanRecord.class);
        ScanResult scanResult = new ScanResult(null, mockScanRecord, 0, 0);
        when(mockScanRecord.getBytes()).thenReturn(CORRECT_DATA.toByteArray());
        assertThat(ScanResults.containsUuidsInOverflow(scanResult, CORRECT_MASK)).isTrue();
    }

    @Test
    public void containsUuidsInOverflow_bitNotFlipped_shouldReturnFalse() {
        ScanRecord mockScanRecord = mock(ScanRecord.class);
        ScanResult scanResult = new ScanResult(null, mockScanRecord, 0, 0);
        when(mockScanRecord.getBytes()).thenReturn(CORRECT_DATA.negate().toByteArray());
        assertThat(ScanResults.containsUuidsInOverflow(scanResult, CORRECT_MASK)).isFalse();
    }

    @Test
    public void containsUuidsInOverflow_maskWithMultipleBitsIncompleteMatch_shouldReturnTrue() {
        ScanRecord mockScanRecord = mock(ScanRecord.class);
        ScanResult scanResult = new ScanResult(null, mockScanRecord, 0, 0);
        when(mockScanRecord.getBytes()).thenReturn(CORRECT_DATA.toByteArray());
        assertThat(ScanResults.containsUuidsInOverflow(scanResult, MULTIPLE_BIT_MASK)).isTrue();
    }

    @Test
    public void containsUuidsInOverflow_incorrectLengthByte_shouldReturnFalse() {
        ScanRecord mockScanRecord = mock(ScanRecord.class);
        ScanResult scanResult = new ScanResult(null, mockScanRecord, 0, 0);
        // Incorrect length of 0x20
        byte[] data = new BigInteger(
                "02011A20FF4C00010000000000000000000000000020000000000000000000000000000000"
                        + "00000000000000000000000000000000000000000000000000", 16)
                .toByteArray();
        BigInteger mask = new BigInteger("00000000000000000000000000200000", 16);
        when(mockScanRecord.getBytes()).thenReturn(data);
        assertThat(ScanResults.containsUuidsInOverflow(scanResult, mask)).isFalse();
    }

    @Test
    public void containsUuidsInOverflow_incorrectAdTypeByte_shouldReturnFalse() {
        ScanRecord mockScanRecord = mock(ScanRecord.class);
        ScanResult scanResult = new ScanResult(null, mockScanRecord, 0, 0);
        // Incorrect advertising type of 0xEF
        byte[] data = new BigInteger(
                "02011A14EF4C00010000000000000000000000000020000000000000000000000000000000"
                        + "00000000000000000000000000000000000000000000000000", 16)
                .toByteArray();
        when(mockScanRecord.getBytes()).thenReturn(data);
        assertThat(ScanResults.containsUuidsInOverflow(scanResult, CORRECT_MASK)).isFalse();
    }

    @Test
    public void containsUuidsInOverflow_incorrectCustomId_shouldReturnFalse() {
        ScanRecord mockScanRecord = mock(ScanRecord.class);
        ScanResult scanResult = new ScanResult(null, mockScanRecord, 0, 0);
        // Incorrect custom id of 0x4C1001
        byte[] data = new BigInteger(
                "02011A14FF4C10010000000000000000000000000020000000000000000000000000000000"
                        + "00000000000000000000000000000000000000000000000000", 16)
                .toByteArray();
        when(mockScanRecord.getBytes()).thenReturn(data);
        assertThat(ScanResults.containsUuidsInOverflow(scanResult, CORRECT_MASK)).isFalse();
    }

    @Test
    public void containsUuidsInOverflow_incorrectContentLength_shouldReturnFalse() {
        ScanRecord mockScanRecord = mock(ScanRecord.class);
        ScanResult scanResult = new ScanResult(null, mockScanRecord, 0, 0);
        byte[] data = new BigInteger(
                "02011A14FF4C1001000000000000000000000000002", 16)
                .toByteArray();
        when(mockScanRecord.getBytes()).thenReturn(data);
        assertThat(ScanResults.containsUuidsInOverflow(scanResult, CORRECT_MASK)).isFalse();
    }
}
