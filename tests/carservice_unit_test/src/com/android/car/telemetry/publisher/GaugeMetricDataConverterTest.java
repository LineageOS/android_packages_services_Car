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

package com.android.car.telemetry.publisher;

import static com.google.common.truth.Truth.assertThat;

import android.os.PersistableBundle;

import com.android.car.telemetry.AtomsProto;
import com.android.car.telemetry.StatsLogProto;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.List;

@RunWith(JUnit4.class)
public class GaugeMetricDataConverterTest {
    @Test
    public void testConvertGaugeDataList_putsCorrectDataIntoPersistableBundle() {
        // TODO(b/200064146): handle process name and uid
        List<StatsLogProto.GaugeMetricData> gaugeDataList = Arrays.asList(
                StatsLogProto.GaugeMetricData.newBuilder()
                        .addBucketInfo(StatsLogProto.GaugeBucketInfo.newBuilder()
                                .addAtom(AtomsProto.Atom.newBuilder()
                                        .setProcessMemoryState(
                                                AtomsProto.ProcessMemoryState.newBuilder()
                                                    .setUid(1000)
                                                    .setProcessName("processName1")
                                                    .setRssInBytes(1234L)))
                                .addElapsedTimestampNanos(12345678L)
                                .addAtom(AtomsProto.Atom.newBuilder()
                                        .setProcessMemoryState(
                                                AtomsProto.ProcessMemoryState.newBuilder()
                                                    .setUid(1000)
                                                    .setProcessName("processName1")
                                                    .setRssInBytes(2345L)))
                                .addElapsedTimestampNanos(23456789L))
                        .addBucketInfo(StatsLogProto.GaugeBucketInfo.newBuilder()
                                .addAtom(AtomsProto.Atom.newBuilder()
                                        .setProcessMemoryState(
                                                AtomsProto.ProcessMemoryState.newBuilder()
                                                    .setUid(1200)
                                                    .setProcessName("processName2")
                                                    .setRssInBytes(3456L)))
                                .addElapsedTimestampNanos(34567890L))
                        .addDimensionLeafValuesInWhat(StatsLogProto.DimensionsValue.newBuilder()
                                .setValueInt(123))
                        .addDimensionLeafValuesInWhat(StatsLogProto.DimensionsValue.newBuilder()
                                .setValueStrHash(234567890L))
                        .build(),
                StatsLogProto.GaugeMetricData.newBuilder()
                        .addBucketInfo(StatsLogProto.GaugeBucketInfo.newBuilder()
                                .addAtom(AtomsProto.Atom.newBuilder()
                                        .setProcessMemoryState(
                                                AtomsProto.ProcessMemoryState.newBuilder()
                                                    .setUid(1300)
                                                    .setProcessName("processName3")
                                                    .setRssInBytes(4567L)))
                                .addElapsedTimestampNanos(445678901L))
                        .addDimensionLeafValuesInWhat(StatsLogProto.DimensionsValue.newBuilder()
                                .setValueInt(234))
                        .addDimensionLeafValuesInWhat(StatsLogProto.DimensionsValue.newBuilder()
                                .setValueStrHash(345678901L))
                        .build()
        );
        PersistableBundle bundle = new PersistableBundle();

        GaugeMetricDataConverter.convertGaugeDataList(gaugeDataList, bundle);

        assertThat(bundle.getLongArray(AtomDataConverter.RSS_IN_BYTES))
            .asList().containsExactly(1234L, 2345L, 3456L, 4567L);
        assertThat(bundle.getLongArray(EventMetricDataConverter.ELAPSED_TIME_NANOS))
            .asList().containsExactly(12345678L, 23456789L, 34567890L, 445678901L);
    }
}
