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

import static com.android.car.telemetry.TelemetryProto.StatsPublisher.SystemMetric.APP_START_MEMORY_STATE_CAPTURED;
import static com.android.car.telemetry.publisher.StatsPublisher.APP_START_MEMORY_STATE_CAPTURED_ATOM_MATCHER_ID;
import static com.android.car.telemetry.publisher.StatsPublisher.APP_START_MEMORY_STATE_CAPTURED_EVENT_METRIC_ID;
import static com.android.car.telemetry.publisher.StatsPublisher.ATOM_APP_START_MEMORY_STATE_CAPTURED_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.car.telemetry.StatsdConfigProto;
import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.databroker.DataSubscriber;
import com.android.car.test.FakeSharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class StatsPublisherTest {
    private static final TelemetryProto.Publisher STATS_PUBLISHER_PARAMS_1 =
            TelemetryProto.Publisher.newBuilder()
                    .setStats(TelemetryProto.StatsPublisher.newBuilder()
                            .setSystemMetric(APP_START_MEMORY_STATE_CAPTURED))
                    .build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_1 =
            TelemetryProto.Subscriber.newBuilder()
                    .setHandler("handler_fn_1")
                    .setPublisher(STATS_PUBLISHER_PARAMS_1)
                    .build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("myconfig")
                    .setVersion(1)
                    .addSubscribers(SUBSCRIBER_1)
                    .build();

    private static final long SUBSCRIBER_1_HASH = -8101507323446050791L;  // Used as ID.

    private static final StatsdConfigProto.StatsdConfig STATSD_CONFIG_1 =
            StatsdConfigProto.StatsdConfig.newBuilder()
                    .setId(SUBSCRIBER_1_HASH)
                    .addAtomMatcher(StatsdConfigProto.AtomMatcher.newBuilder()
                            .setId(APP_START_MEMORY_STATE_CAPTURED_ATOM_MATCHER_ID)
                            .setSimpleAtomMatcher(
                                    StatsdConfigProto.SimpleAtomMatcher.newBuilder()
                                            .setAtomId(
                                                    ATOM_APP_START_MEMORY_STATE_CAPTURED_ID)))
                    .addEventMetric(StatsdConfigProto.EventMetric.newBuilder()
                            .setId(APP_START_MEMORY_STATE_CAPTURED_EVENT_METRIC_ID)
                            .setWhat(APP_START_MEMORY_STATE_CAPTURED_ATOM_MATCHER_ID))
                    .addAllowedLogSource("AID_SYSTEM")
                    .build();

    private StatsPublisher mPublisher;  // subject
    private final FakeSharedPreferences mFakeSharedPref = new FakeSharedPreferences();
    @Mock private DataSubscriber mMockDataSubscriber;
    @Mock private StatsManagerProxy mStatsManager;

    @Before
    public void setUp() throws Exception {
        mPublisher = new StatsPublisher(mStatsManager, mFakeSharedPref);
        when(mMockDataSubscriber.getPublisherParam()).thenReturn(STATS_PUBLISHER_PARAMS_1);
        when(mMockDataSubscriber.getMetricsConfig()).thenReturn(METRICS_CONFIG);
        when(mMockDataSubscriber.getSubscriber()).thenReturn(SUBSCRIBER_1);
    }

    /**
     * Emulates a restart by creating a new StatsPublisher. StatsManager and SharedPreference
     * stays the same.
     */
    private StatsPublisher createRestartedPublisher() {
        return new StatsPublisher(mStatsManager, mFakeSharedPref);
    }

    @Test
    public void testAddDataSubscriber_registersNewListener() throws Exception {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        verify(mStatsManager, times(1))
                .addConfig(SUBSCRIBER_1_HASH, STATSD_CONFIG_1.toByteArray());
        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isTrue();
    }

    @Test
    public void testAddDataSubscriber_sameVersion_addsToStatsdOnce() throws Exception {
        mPublisher.addDataSubscriber(mMockDataSubscriber);
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        verify(mStatsManager, times(1))
                .addConfig(SUBSCRIBER_1_HASH, STATSD_CONFIG_1.toByteArray());
        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isTrue();
    }

    @Test
    public void testAddDataSubscriber_whenRestarted_addsToStatsdOnce() throws Exception {
        mPublisher.addDataSubscriber(mMockDataSubscriber);
        StatsPublisher publisher2 = createRestartedPublisher();

        publisher2.addDataSubscriber(mMockDataSubscriber);

        verify(mStatsManager, times(1))
                .addConfig(SUBSCRIBER_1_HASH, STATSD_CONFIG_1.toByteArray());
        assertThat(publisher2.hasDataSubscriber(mMockDataSubscriber)).isTrue();
    }

    @Test
    public void testRemoveDataSubscriber_removesFromStatsd() throws Exception {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        mPublisher.removeDataSubscriber(mMockDataSubscriber);

        verify(mStatsManager, times(1)).removeConfig(SUBSCRIBER_1_HASH);
        assertThat(mFakeSharedPref.getAll().isEmpty()).isTrue();  // also removes from SharedPref.
        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isFalse();
    }

    @Test
    public void testRemoveDataSubscriber_ifNotFound_nothingHappensButCallsStatsdRemove()
            throws Exception {
        mPublisher.removeDataSubscriber(mMockDataSubscriber);

        // It should try removing StatsdConfig from StatsD, in case it was added there before and
        // left dangled.
        verify(mStatsManager, times(1)).removeConfig(SUBSCRIBER_1_HASH);
        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isFalse();
    }

    @Test
    public void testRemoveAllDataSubscriber_whenRestarted_removesFromStatsdAndClears()
            throws Exception {
        mPublisher.addDataSubscriber(mMockDataSubscriber);
        StatsPublisher publisher2 = createRestartedPublisher();

        publisher2.removeAllDataSubscribers();

        verify(mStatsManager, times(1)).removeConfig(SUBSCRIBER_1_HASH);
        assertThat(mFakeSharedPref.getAll().isEmpty()).isTrue();  // also removes from SharedPref.
        assertThat(publisher2.hasDataSubscriber(mMockDataSubscriber)).isFalse();
    }

    // TODO(b/189142577): add test cases when connecting to Statsd fails
    // TODO(b/189142577): add test cases for handling config version upgrades
}
