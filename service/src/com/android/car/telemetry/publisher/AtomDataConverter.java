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

import java.util.ArrayList;
import java.util.List;

/**
 * Class for converting atom data to {@link PersistableBundle} compatible format.
 */
public class AtomDataConverter {
    static final String UID = "uid";
    static final String PROCESS_NAME = "process_name";
    static final String ACTIVITY_NAME = "activity_name";
    static final String PAGE_FAULT = "page_fault";
    static final String PAGE_MAJOR_FAULT = "page_major_fault";
    static final String RSS_IN_BYTES = "rss_in_bytes";
    static final String CACHE_IN_BYTES = "cache_in_bytes";
    static final String SWAP_IN_BYTES = "swap_in_bytes";
    static final String STATE = "state";
    static final String OOM_ADJ_SCORE = "oom_adj_score";

    /**
     * Converts a list of atoms to separate the atoms fields values into arrays to be put into the
     * {@link PersistableBundle}.
     * The list of atoms must contain atoms of same type.
     * Only fields with types allowed in {@link PersistableBundle} are added to the bundle.
     *
     * @param atoms list of {@link AtomsProto.Atom} of the same type.
     * @param bundle the {@link PersistableBundle} to hold the converted atom fields.
     */
    static void convertAtomsList(List<AtomsProto.Atom> atoms, PersistableBundle bundle) {
        // The atoms are either pushed or pulled type atoms.
        switch (atoms.get(0).getPushedCase()) {
            case APP_START_MEMORY_STATE_CAPTURED:
                convertAppStartMemoryStateCapturedAtoms(atoms, bundle);
                break;
            default:
                break;
        }
        switch (atoms.get(0).getPulledCase()) {
            case PROCESS_MEMORY_STATE:
                convertProcessMemoryStateAtoms(atoms, bundle);
                break;
            default:
                break;
        }
    }

    /**
     * Converts {@link AtomsProto.AppStartMemoryStateCaptured} atoms.
     *
     * @param atoms the list of {@link AtomsProto.AppStartMemoryStateCaptured} atoms.
     * @param bundle the {@link PersistableBundle} to hold the converted atom fields.
     */
    private static void convertAppStartMemoryStateCapturedAtoms(
                List<AtomsProto.Atom> atoms, PersistableBundle bundle) {
        List<Integer> uid = null;
        List<String> processName = null;
        List<String> activityName = null;
        List<Long> pageFault = null;
        List<Long> pageMajorFault = null;
        List<Long> rssInBytes = null;
        List<Long> cacheInBytes = null;
        List<Long> swapInBytes = null;
        for (AtomsProto.Atom atom : atoms) {
            AtomsProto.AppStartMemoryStateCaptured atomData = atom.getAppStartMemoryStateCaptured();
            // Atom fields may be filtered thus not collected, need to check availability.
            if (atomData.hasUid()) {
                if (uid == null) {
                    uid = new ArrayList();
                }
                uid.add(atomData.getUid());
            }
            if (atomData.hasProcessName()) {
                if (processName == null) {
                    processName = new ArrayList<>();
                }
                processName.add(atomData.getProcessName());
            }
            if (atomData.hasActivityName()) {
                if (activityName == null) {
                    activityName = new ArrayList<>();
                }
                activityName.add(atomData.getActivityName());
            }
            if (atomData.hasPageFault()) {
                if (pageFault == null) {
                    pageFault = new ArrayList<>();
                }
                pageFault.add(atomData.getPageFault());
            }
            if (atomData.hasPageMajorFault()) {
                if (pageMajorFault == null) {
                    pageMajorFault = new ArrayList<>();
                }
                pageMajorFault.add(atomData.getPageMajorFault());
            }
            if (atomData.hasRssInBytes()) {
                if (rssInBytes == null) {
                    rssInBytes = new ArrayList<>();
                }
                rssInBytes.add(atomData.getRssInBytes());
            }
            if (atomData.hasCacheInBytes()) {
                if (cacheInBytes == null) {
                    cacheInBytes = new ArrayList<>();
                }
                cacheInBytes.add(atomData.getCacheInBytes());
            }
            if (atomData.hasSwapInBytes()) {
                if (swapInBytes == null) {
                    swapInBytes = new ArrayList<>();
                }
                swapInBytes.add(atomData.getSwapInBytes());
            }
        }
        if (uid != null) {
            bundle.putIntArray(UID, uid.stream().mapToInt(i -> i).toArray());
        }
        if (processName != null) {
            bundle.putStringArray(
                    PROCESS_NAME, processName.toArray(new String[0]));
        }
        if (activityName != null) {
            bundle.putStringArray(
                    ACTIVITY_NAME, activityName.toArray(new String[0]));
        }
        if (pageFault != null) {
            bundle.putLongArray(PAGE_FAULT, pageFault.stream().mapToLong(i -> i).toArray());
        }
        if (pageMajorFault != null) {
            bundle.putLongArray(
                    PAGE_MAJOR_FAULT, pageMajorFault.stream().mapToLong(i -> i).toArray());
        }
        if (rssInBytes != null) {
            bundle.putLongArray(RSS_IN_BYTES, rssInBytes.stream().mapToLong(i -> i).toArray());
        }
        if (cacheInBytes != null) {
            bundle.putLongArray(
                    CACHE_IN_BYTES, cacheInBytes.stream().mapToLong(i -> i).toArray());
        }
        if (swapInBytes != null) {
            bundle.putLongArray(
                    SWAP_IN_BYTES, swapInBytes.stream().mapToLong(i -> i).toArray());
        }
    }

    /**
     * Converts {@link AtomsProto.ProcessMemoryState} atoms.
     *
     * @param atoms the list of {@link AtomsProto.ProcessMemoryState} atoms.
     * @param bundle the {@link PersistableBundle} to hold the converted atom fields.
     */
    private static void convertProcessMemoryStateAtoms(
                List<AtomsProto.Atom> atoms, PersistableBundle bundle) {
        List<Integer> uid = null;
        List<String> processName = null;
        List<Integer> oomAdjScore = null;
        List<Long> pageFault = null;
        List<Long> pageMajorFault = null;
        List<Long> rssInBytes = null;
        List<Long> cacheInBytes = null;
        List<Long> swapInBytes = null;
        for (AtomsProto.Atom atom : atoms) {
            AtomsProto.ProcessMemoryState atomData = atom.getProcessMemoryState();
            // Atom fields may be filtered thus not collected, need to check availability.
            if (atomData.hasUid()) {
                if (uid == null) {
                    uid = new ArrayList();
                }
                uid.add(atomData.getUid());
            }
            if (atomData.hasProcessName()) {
                if (processName == null) {
                    processName = new ArrayList<>();
                }
                processName.add(atomData.getProcessName());
            }
            if (atomData.hasOomAdjScore()) {
                if (oomAdjScore == null) {
                    oomAdjScore = new ArrayList<>();
                }
                oomAdjScore.add(atomData.getOomAdjScore());
            }
            if (atomData.hasPageFault()) {
                if (pageFault == null) {
                    pageFault = new ArrayList<>();
                }
                pageFault.add(atomData.getPageFault());
            }
            if (atomData.hasPageMajorFault()) {
                if (pageMajorFault == null) {
                    pageMajorFault = new ArrayList<>();
                }
                pageMajorFault.add(atomData.getPageMajorFault());
            }
            if (atomData.hasRssInBytes()) {
                if (rssInBytes == null) {
                    rssInBytes = new ArrayList<>();
                }
                rssInBytes.add(atomData.getRssInBytes());
            }
            if (atomData.hasCacheInBytes()) {
                if (cacheInBytes == null) {
                    cacheInBytes = new ArrayList<>();
                }
                cacheInBytes.add(atomData.getCacheInBytes());
            }
            if (atomData.hasSwapInBytes()) {
                if (swapInBytes == null) {
                    swapInBytes = new ArrayList<>();
                }
                swapInBytes.add(atomData.getSwapInBytes());
            }
        }
        if (uid != null) {
            bundle.putIntArray(UID, uid.stream().mapToInt(i -> i).toArray());
        }
        if (processName != null) {
            bundle.putStringArray(
                    PROCESS_NAME, processName.toArray(new String[0]));
        }
        if (oomAdjScore != null) {
            bundle.putIntArray(
                    OOM_ADJ_SCORE, oomAdjScore.stream().mapToInt(i -> i).toArray());
        }
        if (pageFault != null) {
            bundle.putLongArray(PAGE_FAULT, pageFault.stream().mapToLong(i -> i).toArray());
        }
        if (pageMajorFault != null) {
            bundle.putLongArray(
                    PAGE_MAJOR_FAULT, pageMajorFault.stream().mapToLong(i -> i).toArray());
        }
        if (rssInBytes != null) {
            bundle.putLongArray(RSS_IN_BYTES, rssInBytes.stream().mapToLong(i -> i).toArray());
        }
        if (cacheInBytes != null) {
            bundle.putLongArray(
                    CACHE_IN_BYTES, cacheInBytes.stream().mapToLong(i -> i).toArray());
        }
        if (swapInBytes != null) {
            bundle.putLongArray(
                    SWAP_IN_BYTES, swapInBytes.stream().mapToLong(i -> i).toArray());
        }
    }
}
