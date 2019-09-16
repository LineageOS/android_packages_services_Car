/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.car;

import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Arrays;

public class VmsPublishersInfo {
    private static final byte[] EMPTY_RESPONSE = new byte[0];

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArrayMap<InfoWrapper, Integer> mPublishersIds = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayList<InfoWrapper> mPublishersInfo = new ArrayList<>();

    private static class InfoWrapper {
        private final byte[] mInfo;

        InfoWrapper(byte[] info) {
            mInfo = info;
        }

        public byte[] getInfo() {
            return mInfo.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof InfoWrapper)) {
                return false;
            }
            InfoWrapper p = (InfoWrapper) o;
            return Arrays.equals(this.mInfo, p.mInfo);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(mInfo);
        }
    }

    /**
     * Retrieves the publisher ID for the given publisher information. If the publisher information
     * has not previously been seen, it will be assigned a new publisher ID.
     *
     * @param publisherInfo Publisher information to query or register.
     * @return Publisher ID for the given publisher information.
     */
    public int getIdForInfo(byte[] publisherInfo) {
        Integer publisherId;
        InfoWrapper wrappedPublisherInfo = new InfoWrapper(publisherInfo);
        synchronized (mLock) {
            // Check if publisher is already registered
            publisherId = mPublishersIds.get(wrappedPublisherInfo);
            if (publisherId == null) {
                // Add the new publisher and assign it the next ID
                mPublishersInfo.add(wrappedPublisherInfo);
                publisherId = mPublishersInfo.size();
                mPublishersIds.put(wrappedPublisherInfo, publisherId);
            }
        }
        return publisherId;
    }

    /**
     * Returns the publisher info associated with the given publisher ID.
     * @param publisherId Publisher ID to query.
     * @return Publisher info associated with the ID, or an empty array if publisher ID is unknown.
     */
    public byte[] getPublisherInfo(int publisherId) {
        synchronized (mLock) {
            return publisherId < 1 || publisherId > mPublishersInfo.size()
                    ? EMPTY_RESPONSE
                    : mPublishersInfo.get(publisherId - 1).getInfo();
        }
    }
}

