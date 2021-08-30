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
import com.android.car.telemetry.StatsLogProto.ConfigMetricsReportList;
import com.android.car.telemetry.StatsLogProto.StatsLogReport;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

@RunWith(JUnit4.class)
public class ConfigMetricsReportListConverterTest {
    @Test
    public void testConvertMultipleReports_correctlyGroupsByMetricId() {
        StatsLogProto.EventMetricData eventData = StatsLogProto.EventMetricData.newBuilder()
                .setElapsedTimestampNanos(99999999L)
                .setAtom(AtomsProto.Atom.newBuilder()
                        .setAppStartMemoryStateCaptured(
                                AtomsProto.AppStartMemoryStateCaptured.newBuilder()
                                        .setUid(1000)
                                        .setActivityName("activityName")
                                        .setRssInBytes(1234L)))
                .build();

        StatsLogProto.GaugeMetricData gaugeData = StatsLogProto.GaugeMetricData.newBuilder()
                .addBucketInfo(StatsLogProto.GaugeBucketInfo.newBuilder()
                        .addAtom(AtomsProto.Atom.newBuilder()
                                .setProcessMemoryState(AtomsProto.ProcessMemoryState.newBuilder()
                                        .setUid(1300)
                                        .setProcessName("processName")
                                        .setRssInBytes(4567L)))
                        .addElapsedTimestampNanos(445678901L))
                .addDimensionLeafValuesInWhat(StatsLogProto.DimensionsValue.newBuilder()
                        .setValueInt(234))
                .addDimensionLeafValuesInWhat(StatsLogProto.DimensionsValue.newBuilder()
                        .setValueStrHash(345678901L))
                .build();

        ConfigMetricsReportList reportList = ConfigMetricsReportList.newBuilder()
                .addReports(StatsLogProto.ConfigMetricsReport.newBuilder()
                        .addMetrics(StatsLogReport.newBuilder()
                                .setMetricId(12345L)
                                .setEventMetrics(
                                        StatsLogReport.EventMetricDataWrapper.newBuilder()
                                                .addData(eventData))))
                .addReports(StatsLogProto.ConfigMetricsReport.newBuilder()
                        .addMetrics(StatsLogProto.StatsLogReport.newBuilder()
                                .setMetricId(23456L)
                                .setGaugeMetrics(
                                        StatsLogReport.GaugeMetricDataWrapper.newBuilder()
                                                .addData(gaugeData))))
                .build();

        Map<Long, PersistableBundle> map = ConfigMetricsReportListConverter.convert(reportList);

        PersistableBundle subBundle1 = map.get(12345L);
        PersistableBundle subBundle2 = map.get(23456L);
        PersistableBundle dimensionBundle = subBundle2.getPersistableBundle("234-345678901");
        assertThat(new ArrayList<Long>(map.keySet())).containsExactly(12345L, 23456L);
        assertThat(subBundle1.getLongArray(EventMetricDataConverter.ELAPSED_TIME_NANOS))
            .asList().containsExactly(99999999L);
        assertThat(subBundle1.getIntArray(AtomDataConverter.UID)).asList().containsExactly(1000);
        assertThat(Arrays.asList(subBundle1.getStringArray(AtomDataConverter.ACTIVITY_NAME)))
            .containsExactly("activityName");
        assertThat(subBundle1.getLongArray(AtomDataConverter.RSS_IN_BYTES))
            .asList().containsExactly(1234L);
        // TODO(b/200064146) add checks for uid and process_name
        assertThat(subBundle2.getLongArray(AtomDataConverter.RSS_IN_BYTES))
            .asList().containsExactly(4567L);
        assertThat(subBundle2.getLongArray(EventMetricDataConverter.ELAPSED_TIME_NANOS))
            .asList().containsExactly(445678901L);
    }
}
