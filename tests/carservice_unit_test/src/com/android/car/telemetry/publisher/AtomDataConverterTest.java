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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.List;

@RunWith(JUnit4.class)
public class AtomDataConverterTest {
    @Test
    public void testConvertPushedAtomsListWithUnsetFields_putsCorrectDataToPersistableBundle() {
        List<AtomsProto.Atom> pushedAtomsList = Arrays.asList(
                AtomsProto.Atom.newBuilder()
                        .setAppStartMemoryStateCaptured(
                                AtomsProto.AppStartMemoryStateCaptured.newBuilder()
                                        .setUid(1000)
                                        .setActivityName("activityName1")
                                        .setRssInBytes(1234L))
                        .build(),
                AtomsProto.Atom.newBuilder()
                        .setAppStartMemoryStateCaptured(
                                AtomsProto.AppStartMemoryStateCaptured.newBuilder()
                                        .setUid(1100)
                                        .setActivityName("activityName2")
                                        .setRssInBytes(2345L))
                        .build()
        );
        PersistableBundle bundle = new PersistableBundle();

        AtomDataConverter.convertAtomsList(pushedAtomsList, bundle);

        assertThat(bundle.size()).isEqualTo(3);
        assertThat(bundle.getIntArray(AtomDataConverter.UID))
            .asList().containsExactly(1000, 1100).inOrder();
        assertThat(Arrays.asList(bundle.getStringArray(AtomDataConverter.ACTIVITY_NAME)))
            .containsExactly("activityName1", "activityName2").inOrder();
        assertThat(bundle.getLongArray(AtomDataConverter.RSS_IN_BYTES))
            .asList().containsExactly(1234L, 2345L).inOrder();
    }

    @Test
    public void testConvertPulledAtomsListWithUnsetFields_putsCorrectDataToPersistableBundle() {
        List<AtomsProto.Atom> pulledAtomsList = Arrays.asList(
                AtomsProto.Atom.newBuilder()
                        .setProcessMemoryState(AtomsProto.ProcessMemoryState.newBuilder()
                                .setUid(1000)
                                .setProcessName("processName1")
                                .setRssInBytes(1234L))
                        .build(),
                AtomsProto.Atom.newBuilder()
                        .setProcessMemoryState(AtomsProto.ProcessMemoryState.newBuilder()
                                .setUid(1100)
                                .setProcessName("processName2")
                                .setRssInBytes(2345L))
                        .build()
        );
        PersistableBundle bundle = new PersistableBundle();

        AtomDataConverter.convertAtomsList(pulledAtomsList, bundle);

        assertThat(bundle.size()).isEqualTo(3);
        assertThat(bundle.getIntArray(AtomDataConverter.UID))
            .asList().containsExactly(1000, 1100).inOrder();
        assertThat(Arrays.asList(bundle.getStringArray(AtomDataConverter.PROCESS_NAME)))
            .containsExactly("processName1", "processName2").inOrder();
        assertThat(bundle.getLongArray(AtomDataConverter.RSS_IN_BYTES))
            .asList().containsExactly(1234L, 2345L).inOrder();
    }

    @Test
    public void testConvertAppStartMemoryStateCapturedAtoms_putsCorrectDataToPersistableBundle() {
        List<AtomsProto.Atom> atomsList = Arrays.asList(
                AtomsProto.Atom.newBuilder()
                        .setAppStartMemoryStateCaptured(
                                AtomsProto.AppStartMemoryStateCaptured.newBuilder()
                                        .setUid(1000)
                                        .setProcessName("processName")
                                        .setActivityName("activityName")
                                        .setPageFault(59L)
                                        .setPageMajorFault(34L)
                                        .setRssInBytes(1234L)
                                        .setCacheInBytes(234L)
                                        .setSwapInBytes(111L))
                        .build()
        );
        PersistableBundle bundle = new PersistableBundle();

        AtomDataConverter.convertAtomsList(atomsList, bundle);

        assertThat(bundle.size()).isEqualTo(8);
        assertThat(bundle.getIntArray(AtomDataConverter.UID)).asList().containsExactly(1000);
        assertThat(Arrays.asList(bundle.getStringArray(AtomDataConverter.PROCESS_NAME)))
            .containsExactly("processName");
        assertThat(Arrays.asList(bundle.getStringArray(AtomDataConverter.ACTIVITY_NAME)))
            .containsExactly("activityName");
        assertThat(bundle.getLongArray(AtomDataConverter.PAGE_FAULT))
            .asList().containsExactly(59L);
        assertThat(bundle.getLongArray(AtomDataConverter.PAGE_MAJOR_FAULT))
            .asList().containsExactly(34L);
        assertThat(bundle.getLongArray(AtomDataConverter.RSS_IN_BYTES))
            .asList().containsExactly(1234L);
        assertThat(bundle.getLongArray(AtomDataConverter.CACHE_IN_BYTES))
            .asList().containsExactly(234L);
        assertThat(bundle.getLongArray(AtomDataConverter.SWAP_IN_BYTES))
            .asList().containsExactly(111L);
    }

    @Test
    public void testConvertProcessMemoryStateAtoms_putsCorrectDataToPersistableBundle() {
        List<AtomsProto.Atom> atomsList = Arrays.asList(
                AtomsProto.Atom.newBuilder()
                        .setProcessMemoryState(AtomsProto.ProcessMemoryState.newBuilder()
                                .setUid(1000)
                                .setProcessName("processName")
                                .setOomAdjScore(100)
                                .setPageFault(59L)
                                .setPageMajorFault(34L)
                                .setRssInBytes(1234L)
                                .setCacheInBytes(234L)
                                .setSwapInBytes(111L))
                        .build()
        );
        PersistableBundle bundle = new PersistableBundle();

        AtomDataConverter.convertAtomsList(atomsList, bundle);

        assertThat(bundle.size()).isEqualTo(8);
        assertThat(bundle.getIntArray(AtomDataConverter.UID)).asList().containsExactly(1000);
        assertThat(Arrays.asList(bundle.getStringArray(AtomDataConverter.PROCESS_NAME)))
            .containsExactly("processName");
        assertThat(bundle.getIntArray(AtomDataConverter.OOM_ADJ_SCORE))
            .asList().containsExactly(100);
        assertThat(bundle.getLongArray(AtomDataConverter.PAGE_FAULT))
            .asList().containsExactly(59L);
        assertThat(bundle.getLongArray(AtomDataConverter.PAGE_MAJOR_FAULT))
            .asList().containsExactly(34L);
        assertThat(bundle.getLongArray(AtomDataConverter.RSS_IN_BYTES))
            .asList().containsExactly(1234L);
        assertThat(bundle.getLongArray(AtomDataConverter.CACHE_IN_BYTES))
            .asList().containsExactly(234L);
        assertThat(bundle.getLongArray(AtomDataConverter.SWAP_IN_BYTES))
            .asList().containsExactly(111L);
    }
}
