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

    @Test
    public void containsUuidsInOverflow_withBitFlipped_shouldReturnTrue() {
        ScanRecord mockScanRecord = mock(ScanRecord.class);
        ScanResult scanResult = new ScanResult(null, mockScanRecord, 0, 0);
        byte[] data = new BigInteger(
                "02011A14FF4C00010000000000000000000000000020000000000000000000000000000000"
                        + "00000000000000000000000000000000000000000000000000", 16)
                .toByteArray();
        BigInteger mask = new BigInteger("00000000000000000000000000200000", 16);
        when(mockScanRecord.getBytes()).thenReturn(data);
        assertThat(ScanResults.containsUuidsInOverflow(scanResult, mask)).isTrue();
    }
}
