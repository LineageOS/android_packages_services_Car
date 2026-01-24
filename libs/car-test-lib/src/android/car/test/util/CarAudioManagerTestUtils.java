/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.car.test.util;

import static android.car.test.mocks.JavaMockitoHelper.silentAwait;

import android.car.media.CarAudioManager;
import android.car.media.CarVolumeGroupEvent;
import android.car.media.CarVolumeGroupEventCallback;
import android.car.media.CarVolumeGroupInfo;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Test utility class to be used by CarAudioManager tests.
 */
public final class CarAudioManagerTestUtils {

    private static final String TAG = CarAudioManagerTestUtils.class.getSimpleName();

    private static final long WAIT_TIMEOUT_MS = 5_000;

    public static final class SyncCarVolumeCallback extends CarAudioManager.CarVolumeCallback {

        private final CountDownLatch mGroupVolumeChangeLatch = new CountDownLatch(1);
        private final CountDownLatch mGroupMuteChangeLatch = new CountDownLatch(1);
        private final CountDownLatch mMasterMuteChangeLatch = new CountDownLatch(1);

        public int zoneId;
        public int groupId;

        public boolean receivedGroupVolumeChanged() {
            return silentAwait(mGroupVolumeChangeLatch, WAIT_TIMEOUT_MS);
        }

        public boolean receivedGroupMuteChanged() {
            return silentAwait(mGroupMuteChangeLatch, WAIT_TIMEOUT_MS);
        }

        public boolean receivedMasterMuteChanged() {
            return silentAwait(mMasterMuteChangeLatch, WAIT_TIMEOUT_MS);
        }

        @Override
        public void onGroupVolumeChanged(int zoneId, int groupId, int flags) {
            Log.v(TAG, "onGroupVolumeChanged");
            mGroupVolumeChangeLatch.countDown();
        }

        @Override
        public void onMasterMuteChanged(int zoneId, int flags) {
            Log.v(TAG, "onMasterMuteChanged");
            mMasterMuteChangeLatch.countDown();
        }

        @Override
        public void onGroupMuteChanged(int zoneId, int groupId, int flags) {
            Log.v(TAG, "onGroupMuteChanged");
            this.zoneId = zoneId;
            this.groupId = groupId;
            mGroupMuteChangeLatch.countDown();
        }
    }

    public static final class TestCarVolumeGroupEventCallback implements
            CarVolumeGroupEventCallback {

        private static final int ANY_ZONE = -1;
        private static final int ANY_GROUP = -1;

        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private final List<CarVolumeGroupEvent> mEvents = new ArrayList<>();
        @GuardedBy("mLock")
        private int mEventTypes = 0;

        public void waitForVolumeGroupEvent() throws InterruptedException {
            waitForVolumeGroupEvent(ANY_ZONE, ANY_GROUP);
        }

        /**
         * Waits for a volume group event that matches the specified {@code zoneId} and
         * {@code groupId}.
         *
         * <p><b>Note:</b> calling this function more than once with the same arguments without
         * {@link #reset()} in between misbehaves.
         *
         * @param zoneId the ID of the audio zone to wait for
         * @param groupId the ID of the volume group to wait for
         * @throws InterruptedException if the thread is interrupted while waiting
         */
        public void waitForVolumeGroupEvent(int zoneId, int groupId) throws InterruptedException {
            long startTime = SystemClock.uptimeMillis();
            synchronized (mLock) {
                while (getCarVolumeGroupInfoLocked(zoneId, groupId) == null) {
                    long remainingTime = startTime + WAIT_TIMEOUT_MS - SystemClock.uptimeMillis();
                    if (remainingTime <= 0) {
                        break;
                    }
                    mLock.wait(remainingTime);
                }
            }
        }

        public boolean receivedVolumeGroupEvents() throws InterruptedException {
            long startTime = SystemClock.uptimeMillis();
            synchronized (mLock) {
                while (mEvents.isEmpty()) {
                    long remainingTime = startTime + WAIT_TIMEOUT_MS - SystemClock.uptimeMillis();
                    if (remainingTime <= 0) {
                        break;
                    }
                    mLock.wait(remainingTime);
                }
                return !mEvents.isEmpty();
            }
        }

        public int getEventTypes() {
            synchronized (mLock) {
                return mEventTypes;
            }
        }

        public CarVolumeGroupInfo getCarVolumeGroupInfo(int zoneId, int groupId) {
            synchronized (mLock) {
                return getCarVolumeGroupInfoLocked(zoneId, groupId);
            }
        }

        @GuardedBy("mLock")
        private CarVolumeGroupInfo getCarVolumeGroupInfoLocked(int zoneId, int groupId) {
            for (int i = mEvents.size() - 1; i >= 0; i--) {
                CarVolumeGroupEvent event = mEvents.get(i);
                List<CarVolumeGroupInfo> infos = event.getCarVolumeGroupInfos();
                for (int j = 0; j < infos.size(); j++) {
                    CarVolumeGroupInfo info = infos.get(j);
                    if ((info.getZoneId() == zoneId || zoneId == ANY_ZONE)
                            && (info.getId() == groupId || groupId == ANY_GROUP)) {
                        return info;
                    }
                }
            }
            return null;
        }

        public void reset() {
            synchronized (mLock) {
                mEvents.clear();
                mEventTypes = 0;
                mLock.notifyAll();
            }
        }

        @Override
        public void onVolumeGroupEvent(List<CarVolumeGroupEvent> volumeGroupEvents) {
            synchronized (mLock) {
                mEvents.addAll(volumeGroupEvents);
                for (int i = 0; i < volumeGroupEvents.size(); i++) {
                    CarVolumeGroupEvent currentEvent = volumeGroupEvents.get(i);
                    mEventTypes |= currentEvent.getEventTypes();
                }
                Log.v(TAG, "onVolumeGroupEvent events " + volumeGroupEvents);
                mLock.notifyAll();
            }
        }
    }

    private CarAudioManagerTestUtils() {
    }
}
