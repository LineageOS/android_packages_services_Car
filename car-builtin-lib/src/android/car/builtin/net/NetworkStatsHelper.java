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

package android.car.builtin.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.NetworkStats;

import java.util.Iterator;

/**
 * Helper class to get values from {@link NetworkStats}.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class NetworkStatsHelper {
    /**
     * Returns specific stats entry.
     *
     * @param networkStats - the instance of NetworkStats.
     * @param i - index of the {@link NetworkStats.Entry}.
     * @param recycle - a reuasable entry instance to avoid creating a new entry instance.
     * @throws ArrayIndexOutOfBoundsException when {@code i} is out of bound.
     */
    @NonNull
    public static NetworkStats.Entry getValues(
            @NonNull NetworkStats networkStats, int i, @Nullable NetworkStats.Entry recycle) {
        throw new UnsupportedOperationException(
                "Cannot access the hidden method NetworkStats.getValues");
    }

    /** Returns the number of entries in this object. */
    public static int size(@NonNull NetworkStats networkStats) {
        int count = 0;
        final Iterator it = networkStats.iterator();
        while (it.hasNext()) {
            it.next();
            count++;
        }
        return count;
    }

    /**
     * Returns the timestamp when this data was generated or time delta when {@link #subtract()}
     * method is used.
     */
    public static long getElapsedRealtime(@NonNull NetworkStats networkStats) {
        throw new UnsupportedOperationException(
                "Cannot access the hidden method NetworkStats.getElapsedRealtime");
    }

    /** Helper class to access details of {@link Entry} class. */
    public static final class EntryHelper {
        /** Returns the interface name. Or null if not specified. */
        @Nullable
        public static String getIface(@NonNull NetworkStats.Entry entry) {
            throw new UnsupportedOperationException(
                    "Cannot access the hidden method NetworkStats.entry.getIface");
        }

        /** Returns the uid. */
        public static int getUid(@NonNull NetworkStats.Entry entry) {
            return entry.getUid();
        }

        /** Returns usage state. */
        public static int getSet(@NonNull NetworkStats.Entry entry) {
            return entry.getSet();
        }

        /** Returns the tag. https://source.android.com/devices/tech/datausage/tags-explained. */
        public static int getTag(@NonNull NetworkStats.Entry entry) {
            return entry.getTag();
        }

        /** Returns the metered state. */
        public static int getMetered(@NonNull NetworkStats.Entry entry) {
            return entry.getMetered();
        }

        /** Returns the roaming state. */
        public static int getRoaming(@NonNull NetworkStats.Entry entry) {
            return entry.getRoaming();
        }

        /** Returns the default network status. */
        public static int getDefaultNetwork(@NonNull NetworkStats.Entry entry) {
            return entry.getDefaultNetwork();
        }

        /** Returns the number of bytes received. */
        public static long getRxBytes(@NonNull NetworkStats.Entry entry) {
            return entry.getRxBytes();
        }

        /** Returns the number of packets received. */
        public static long getRxPackets(@NonNull NetworkStats.Entry entry) {
            return entry.getRxPackets();
        }

        /** Returns the number of bytes transmitted. */
        public static long getTxBytes(@NonNull NetworkStats.Entry entry) {
            return entry.getTxBytes();
        }

        /** Returns the number of bytes transmitted. */
        public static long getTxPackets(@NonNull NetworkStats.Entry entry) {
            return entry.getTxPackets();
        }

        /** Returns the count of network operations performed. */
        public static long getOperations(@NonNull NetworkStats.Entry entry) {
            return entry.getOperations();
        }

        /** Returns true if the {@link Entry} is empty. */
        public static boolean isEmpty(@NonNull NetworkStats.Entry entry) {
            throw new UnsupportedOperationException(
                    "Cannot access the hidden method NetworkStats.entry.isEmpty");
        }

        /** Returns true of the given entries are equal. */
        public static boolean equals(
                @NonNull NetworkStats.Entry entry, @Nullable NetworkStats.Entry other) {
            throw new UnsupportedOperationException(
                    "Cannot access the hidden method NetworkStats.entry.equals");
        }

        private EntryHelper() {}
    }

    private NetworkStatsHelper() {}
}
