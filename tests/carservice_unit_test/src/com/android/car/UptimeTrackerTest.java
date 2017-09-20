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

import android.test.suitebuilder.annotation.MediumTest;

import junit.framework.TestCase;

@MediumTest
public class UptimeTrackerTest extends TestCase {
    static final String TAG = UptimeTrackerTest.class.getSimpleName();

    static final class TestTimingProvider implements UptimeTracker.TimingProvider {
        private long mCurrentTime = 0;
        private Runnable mRunnable = null;

        TestTimingProvider incrementTime(long by) {
            mCurrentTime += by;
            return this;
        }

        TestTimingProvider tick() {
            if (mRunnable != null) {
                mRunnable.run();
            }
            return this;
        }

        @Override
        public long getCurrentRealtime() {
            return mCurrentTime;
        }

        @Override
        public void schedule(Runnable r, long delay) {
            if (mRunnable != null) {
                throw new IllegalStateException("task already scheduled");
            }
            mRunnable = r;
        }

        @Override
        public void cancelAll() {
            mRunnable = null;
        }
    }

    private static final long SNAPSHOT_INTERVAL = 0; // actual time doesn't matter for this test

    public void testUptimeTrackerFromCleanSlate() throws Exception {
        TestTimingProvider timingProvider = new TestTimingProvider();
        try (TemporaryFile uptimeFile = new TemporaryFile(TAG)) {
            UptimeTracker uptimeTracker = new UptimeTracker(uptimeFile.getFile(),
                SNAPSHOT_INTERVAL, timingProvider);

            assertEquals(0, uptimeTracker.getTotalUptime());

            timingProvider.incrementTime(5000).tick();
            assertEquals(5000, uptimeTracker.getTotalUptime());

            timingProvider.tick();
            assertEquals(5000, uptimeTracker.getTotalUptime());

            timingProvider.incrementTime(1000).tick();
            assertEquals(6000, uptimeTracker.getTotalUptime());

            timingProvider.incrementTime(400).tick();
            assertEquals(6400, uptimeTracker.getTotalUptime());
        }
    }

    public void testUptimeTrackerWithHistoricalState() throws Exception {
        TestTimingProvider timingProvider = new TestTimingProvider();
        try (TemporaryFile uptimeFile = new TemporaryFile(TAG)) {
            uptimeFile.write("{\"uptime\" : 5000}");
            UptimeTracker uptimeTracker = new UptimeTracker(uptimeFile.getFile(),
                SNAPSHOT_INTERVAL, timingProvider);

            assertEquals(5000, uptimeTracker.getTotalUptime());

            timingProvider.incrementTime(5000).tick();
            assertEquals(10000, uptimeTracker.getTotalUptime());

            timingProvider.incrementTime(1000).tick();
            assertEquals(11000, uptimeTracker.getTotalUptime());
        }
    }

    public void testUptimeTrackerAcrossHistoricalState() throws Exception {
        TestTimingProvider timingProvider = new TestTimingProvider();
        try (TemporaryFile uptimeFile = new TemporaryFile(TAG)) {
            uptimeFile.write("{\"uptime\" : 5000}");
            UptimeTracker uptimeTracker = new UptimeTracker(uptimeFile.getFile(),
                SNAPSHOT_INTERVAL, timingProvider);

            assertEquals(5000, uptimeTracker.getTotalUptime());

            timingProvider.incrementTime(5000).tick();
            assertEquals(10000, uptimeTracker.getTotalUptime());

            timingProvider.incrementTime(500).tick();
            uptimeTracker.onDestroy();
            timingProvider.cancelAll();

            uptimeTracker = new UptimeTracker(uptimeFile.getFile(),
                SNAPSHOT_INTERVAL, timingProvider);

            timingProvider.incrementTime(3000).tick();
            assertEquals(13500, uptimeTracker.getTotalUptime());
        }
    }

    public void testUptimeTrackerShutdown() throws Exception {
        TestTimingProvider timingProvider = new TestTimingProvider();
        try (TemporaryFile uptimeFile = new TemporaryFile(TAG)) {
            UptimeTracker uptimeTracker = new UptimeTracker(uptimeFile.getFile(),
                SNAPSHOT_INTERVAL, timingProvider);

            timingProvider.incrementTime(6000);
            uptimeTracker.onDestroy();
            timingProvider.cancelAll();

            uptimeTracker = new UptimeTracker(uptimeFile.getFile(),
                SNAPSHOT_INTERVAL, timingProvider);
            assertEquals(6000, uptimeTracker.getTotalUptime());
        }
    }
}
