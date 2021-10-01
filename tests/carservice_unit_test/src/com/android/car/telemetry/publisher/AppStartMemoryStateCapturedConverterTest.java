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

import static org.junit.Assert.assertThrows;

import android.os.PersistableBundle;
import android.util.SparseArray;

import com.android.car.telemetry.AtomsProto.AppStartMemoryStateCaptured;
import com.android.car.telemetry.AtomsProto.Atom;
import com.android.car.telemetry.StatsLogProto.DimensionsValue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public class AppStartMemoryStateCapturedConverterTest {
    private static final Atom ATOM_A =
            Atom.newBuilder()
                    .setAppStartMemoryStateCaptured(AppStartMemoryStateCaptured.newBuilder()
                            .setActivityName("activityName1")
                            .setPageFault(59L)
                            .setPageMajorFault(34L)
                            .setRssInBytes(1234L)
                            .setCacheInBytes(234L)
                            .setSwapInBytes(111L))
                    .build();

    private static final Atom ATOM_B =
            Atom.newBuilder()
                    .setAppStartMemoryStateCaptured(AppStartMemoryStateCaptured.newBuilder()
                            .setActivityName("activityName2")
                            .setPageFault(99L)
                            .setPageMajorFault(55L)
                            .setRssInBytes(2345L)
                            .setCacheInBytes(345L)
                            .setSwapInBytes(222L))
                    .build();

    private static final Atom ATOM_MISMATCH =
            Atom.newBuilder()
                    .setAppStartMemoryStateCaptured(AppStartMemoryStateCaptured.newBuilder()
                            // Some fields are not set, creating mismatch with above atoms
                            .setPageFault(100L)
                            .setPageMajorFault(66L)
                            .setCacheInBytes(456L)
                            .setSwapInBytes(333L))
                    .build();

    private static final List<Integer> DIM_FIELDS_IDS = Arrays.asList(1, 2);
    private static final Long HASH_1 = HashUtils.murmur2Hash64("process.name.1");
    private static final Long HASH_2 = HashUtils.murmur2Hash64("process.name.2");
    private static final Map<Long, String> HASH_STR_MAP = new HashMap<>();
    static {
        HASH_STR_MAP.put(HASH_1, "process.name.1");
        HASH_STR_MAP.put(HASH_2, "process.name.2");
    }

    private static final List<DimensionsValue> DV_PAIR_A =
            Arrays.asList(
                    DimensionsValue.newBuilder().setValueInt(1000).build(),
                    DimensionsValue.newBuilder().setValueStrHash(HASH_1).build());

    private static final List<DimensionsValue> DV_PAIR_B =
            Arrays.asList(
                    DimensionsValue.newBuilder().setValueInt(2000).build(),
                    DimensionsValue.newBuilder().setValueStrHash(HASH_2).build());

    private static final List<DimensionsValue> DV_PAIR_MALFORMED =
            Arrays.asList(
                    DimensionsValue.newBuilder().setValueInt(3000).build(),
                    // Wrong format since leaf level dimension value should set value, not field
                    DimensionsValue.newBuilder().setField(3).build());

    @Test
    public void testConvertAtomsListWithDimensionValues_putsCorrectDataToPersistableBundle()
            throws StatsConversionException {
        List<Atom> atomsList = Arrays.asList(ATOM_A, ATOM_B);
        List<List<DimensionsValue>> dimensionsValuesList = Arrays.asList(DV_PAIR_A, DV_PAIR_B);
        AppStartMemoryStateCapturedConverter converter =
                new AppStartMemoryStateCapturedConverter();
        SparseArray<AtomFieldAccessor<AppStartMemoryStateCaptured>> accessorMap =
                converter.getAtomFieldAccessorMap();

        PersistableBundle bundle = converter.convert(atomsList, DIM_FIELDS_IDS,
                dimensionsValuesList, HASH_STR_MAP);

        assertThat(bundle.size()).isEqualTo(8);
        assertThat(bundle.getIntArray(accessorMap.get(1).getFieldName()))
            .asList().containsExactly(1000, 2000).inOrder();
        assertThat(Arrays.asList(bundle.getStringArray(accessorMap.get(2).getFieldName())))
            .containsExactly("process.name.1", "process.name.2").inOrder();
        assertThat(Arrays.asList(bundle.getStringArray(accessorMap.get(3).getFieldName())))
            .containsExactly("activityName1", "activityName2").inOrder();
        assertThat(bundle.getLongArray(accessorMap.get(4).getFieldName()))
            .asList().containsExactly(59L, 99L).inOrder();
        assertThat(bundle.getLongArray(accessorMap.get(5).getFieldName()))
            .asList().containsExactly(34L, 55L).inOrder();
        assertThat(bundle.getLongArray(accessorMap.get(6).getFieldName()))
            .asList().containsExactly(1234L, 2345L).inOrder();
        assertThat(bundle.getLongArray(accessorMap.get(7).getFieldName()))
            .asList().containsExactly(234L, 345L).inOrder();
        assertThat(bundle.getLongArray(accessorMap.get(8).getFieldName()))
            .asList().containsExactly(111L, 222L).inOrder();
    }

    @Test
    public void testAtomSetFieldInconsistency_throwsException() {
        List<Atom> atomsList = Arrays.asList(ATOM_A, ATOM_MISMATCH);
        List<List<DimensionsValue>> dimensionsValuesList = Arrays.asList(DV_PAIR_A, DV_PAIR_B);

        AppStartMemoryStateCapturedConverter converter =
                new AppStartMemoryStateCapturedConverter();

        assertThrows(
                StatsConversionException.class,
                () -> converter.convert(
                        atomsList,
                        DIM_FIELDS_IDS,
                        dimensionsValuesList,
                        HASH_STR_MAP));
    }

    @Test
    public void testMalformedDimensionValue_throwsException() {
        List<Atom> atomsList = Arrays.asList(ATOM_A, ATOM_B);
        List<List<DimensionsValue>> dimensionsValuesList =
                Arrays.asList(DV_PAIR_A, DV_PAIR_MALFORMED);

        AppStartMemoryStateCapturedConverter converter =
                new AppStartMemoryStateCapturedConverter();

        assertThrows(
                StatsConversionException.class,
                () -> converter.convert(
                        atomsList,
                        DIM_FIELDS_IDS,
                        dimensionsValuesList,
                        HASH_STR_MAP));
    }
}
