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

import android.os.PersistableBundle;

import com.android.car.telemetry.StatsLogProto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class for converting metrics report list data to {@link PersistableBundle} compatible format.
 */
public class ConfigMetricsReportListConverter {
    /**
     * Converts metrics report list to map of metric_id to {@link PersistableBundle} format where
     * each PersistableBundle containing arrays of metric fields data.
     *
     * Example:
     * Given a ConfigMetricsReportList like this:
     * {
     *   reports: {
     *     metrics: {
     *       metric_id: 1234
     *       event_metrics: {
     *         data: {...}
     *       }
     *       metric_id: 2345
     *       event_metrics: {
     *         data: {...}
     *       }
     *       metric_id: 3456
     *       gauge_metrics: {
     *         data: {...}
     *       }
     *     }
     *     metrics: {
     *       metric_id: 3456
     *       gauge_metrics: {
     *         data: {...}
     *       }
     *     }
     *   }
     * }
     * Will result in a map of this form:
     * {
     *   "1234" : {...}  // PersistableBundle containing metric 1234's data
     *   "2345" : {...}
     *   "3456" : {...}
     * }
     *
     * @param reportList the {@link StatsLogProto.ConfigMetricsReportList} to be converted.
     * @return a {@link PersistableBundle} containing mapping of metric id to metric data.
     */
    static Map<Long, PersistableBundle> convert(StatsLogProto.ConfigMetricsReportList reportList) {
        // Map metric id to StatsLogReport list so that separate reports can be combined.
        Map<Long, List<StatsLogProto.StatsLogReport>> metricsStatsReportMap = new HashMap<>();
        // ConfigMetricsReportList is for one config. Normally only 1 report exists unless
        // the previous report did not upload after shutdown, then at most 2 reports can exist.
        for (StatsLogProto.ConfigMetricsReport report : reportList.getReportsList()) {
            // Each statsReport is for a different metric in the report.
            for (StatsLogProto.StatsLogReport statsReport : report.getMetricsList()) {
                Long metricId = statsReport.getMetricId();
                if (!metricsStatsReportMap.containsKey(metricId)) {
                    metricsStatsReportMap.put(
                            metricId, new ArrayList<StatsLogProto.StatsLogReport>());
                }
                metricsStatsReportMap.get(metricId).add(statsReport);
            }
        }
        Map<Long, PersistableBundle> metricIdBundleMap = new HashMap<>();
        // For each metric extract the metric data list from the combined stats reports,
        // convert to bundle data.
        for (Map.Entry<Long, List<StatsLogProto.StatsLogReport>>
                    entry : metricsStatsReportMap.entrySet()) {
            PersistableBundle statsReportBundle = new PersistableBundle();
            Long metricId = entry.getKey();
            List<StatsLogProto.StatsLogReport> statsReportList = entry.getValue();
            switch (statsReportList.get(0).getDataCase()) {
                case EVENT_METRICS:
                    List<StatsLogProto.EventMetricData> eventDataList = new ArrayList<>();
                    for (StatsLogProto.StatsLogReport statsReport : statsReportList) {
                        eventDataList.addAll(statsReport.getEventMetrics().getDataList());
                    }
                    EventMetricDataConverter.convertEventDataList(
                            eventDataList, statsReportBundle);
                    break;
                case GAUGE_METRICS:
                    List<StatsLogProto.GaugeMetricData> gaugeDataList = new ArrayList<>();
                    for (StatsLogProto.StatsLogReport statsReport : statsReportList) {
                        gaugeDataList.addAll(statsReport.getGaugeMetrics().getDataList());
                    }
                    GaugeMetricDataConverter.convertGaugeDataList(
                            gaugeDataList, statsReportBundle);
                    break;
                default:
                    break;
            }
            metricIdBundleMap.put(metricId, statsReportBundle);
        }
        return metricIdBundleMap;
    }
}
