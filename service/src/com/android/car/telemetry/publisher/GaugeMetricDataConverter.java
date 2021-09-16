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

import com.android.car.telemetry.AtomsProto;
import com.android.car.telemetry.StatsLogProto;

import java.util.ArrayList;
import java.util.List;

/**
 * Class for converting gauge metric data to {@link PersistableBundle} compatible format.
 */
public class GaugeMetricDataConverter {
    static final String DIMENSION_DELIMITOR = "-";
    static final String ELAPSED_TIME = "elapsed_timestamp_nanos";

    /**
     * Converts a list of {@link StatsLogProto.GaugeMetricData} to {@link PersistableBundle}
     * format such that along with the elapsed time array each field of the atom has an associated
     * array containing the field's data in order received, matching the elapsed time array order.
     * The atoms are extracted out of the each bucket while preserving the order they had in the
     * bucket.
     *
     * Example:
     * {
     *   elapsed_timestamp_nanos: [32948395739, 45623453646, ...]
     *   uid: [1000, 1100, ...]
     *   ...
     * }
     *
     * @param gaugeDataList the list of {@link StatsLogProto.GaugeMetricData} to be converted.
     * @param bundle the {@link PersistableBundle} to hold the converted values.
     */
    static void convertGaugeDataList(
            List<StatsLogProto.GaugeMetricData> gaugeDataList, PersistableBundle bundle) {
        // TODO(b/200064146): translate the dimension strings to get uid and package_name.
        List<Long> elapsedTimes = new ArrayList<>();
        List<AtomsProto.Atom> atoms = new ArrayList<>();
        for (StatsLogProto.GaugeMetricData gaugeData : gaugeDataList) {
            for (StatsLogProto.GaugeBucketInfo bi : gaugeData.getBucketInfoList()) {
                elapsedTimes.addAll(bi.getElapsedTimestampNanosList());
                atoms.addAll(bi.getAtomList());
            }
        }
        AtomDataConverter.convertAtomsList(atoms, bundle);
        bundle.putLongArray(
                ELAPSED_TIME, elapsedTimes.stream().mapToLong(i -> i).toArray());
    }
}
