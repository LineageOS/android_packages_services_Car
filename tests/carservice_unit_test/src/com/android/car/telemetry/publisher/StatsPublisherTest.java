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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.SystemClock;

import com.android.car.telemetry.StatsLogProto;
import com.android.car.telemetry.StatsdConfigProto;
import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.databroker.DataSubscriber;
import com.android.car.test.FakeHandlerWrapper;
import com.android.car.test.FakeSharedPreferences;

import com.google.common.collect.Range;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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

    private static final StatsLogProto.ConfigMetricsReportList EMPTY_STATS_REPORT =
            StatsLogProto.ConfigMetricsReportList.newBuilder().build();

    private StatsPublisher mPublisher;  // subject
    private final FakeSharedPreferences mFakeSharedPref = new FakeSharedPreferences();
    private final FakeHandlerWrapper mFakeHandlerWrapper =
            new FakeHandlerWrapper(Looper.getMainLooper(), FakeHandlerWrapper.Mode.QUEUEING);
    @Mock private DataSubscriber mMockDataSubscriber;
    @Mock private StatsManagerProxy mStatsManager;

    @Captor private ArgumentCaptor<PersistableBundle> mBundleCaptor;

    @Before
    public void setUp() throws Exception {
        mPublisher = createRestartedPublisher();
        when(mMockDataSubscriber.getPublisherParam()).thenReturn(STATS_PUBLISHER_PARAMS_1);
        when(mMockDataSubscriber.getMetricsConfig()).thenReturn(METRICS_CONFIG);
        when(mMockDataSubscriber.getSubscriber()).thenReturn(SUBSCRIBER_1);
    }

    /**
     * Emulates a restart by creating a new StatsPublisher. StatsManager and SharedPreference
     * stays the same.
     */
    private StatsPublisher createRestartedPublisher() {
        return new StatsPublisher(
                this::onPublisherFailure,
                mStatsManager,
                mFakeSharedPref,
                mFakeHandlerWrapper.getMockHandler());
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

    @Test
    public void testAddDataSubscriber_queuesPeriodicTaskInTheHandler() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(1);
        Message msg = mFakeHandlerWrapper.getQueuedMessages().get(0);
        long expectedPullPeriodMillis = 10 * 60 * 1000;  // 10 minutes
        assertThatMessageIsScheduledWithGivenDelay(msg, expectedPullPeriodMillis);
    }

    @Test
    public void testRemoveDataSubscriber_removesPeriodicStatsdReportPull() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        mPublisher.removeDataSubscriber(mMockDataSubscriber);

        assertThat(mFakeHandlerWrapper.getQueuedMessages()).isEmpty();
    }

    @Test
    public void testRemoveAllDataSubscriber_removesPeriodicStatsdReportPull() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        mPublisher.removeAllDataSubscribers();

        assertThat(mFakeHandlerWrapper.getQueuedMessages()).isEmpty();
    }

    @Test
    public void testAfterDispatchItSchedulesANewPullReportTask() throws Exception {
        mPublisher.addDataSubscriber(mMockDataSubscriber);
        Message firstMessage = mFakeHandlerWrapper.getQueuedMessages().get(0);
        when(mStatsManager.getReports(anyLong())).thenReturn(EMPTY_STATS_REPORT.toByteArray());

        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(1);
        Message newMessage = mFakeHandlerWrapper.getQueuedMessages().get(0);
        assertThat(newMessage).isNotEqualTo(firstMessage);
        long expectedPullPeriodMillis = 10 * 60 * 1000;  // 10 minutes
        assertThatMessageIsScheduledWithGivenDelay(newMessage, expectedPullPeriodMillis);
    }

    @Test
    public void testPullsStatsdReport() throws Exception {
        mPublisher.addDataSubscriber(mMockDataSubscriber);
        when(mStatsManager.getReports(anyLong())).thenReturn(
                StatsLogProto.ConfigMetricsReportList.newBuilder()
                        // add 2 empty reports
                        .addReports(StatsLogProto.ConfigMetricsReport.newBuilder())
                        .addReports(StatsLogProto.ConfigMetricsReport.newBuilder())
                        .build().toByteArray());

        mFakeHandlerWrapper.dispatchQueuedMessages();

        verify(mMockDataSubscriber).push(mBundleCaptor.capture());
        assertThat(mBundleCaptor.getValue().getInt("reportsCount")).isEqualTo(2);
    }

    // TODO(b/189143813): add test cases when connecting to Statsd fails
    // TODO(b/189143813): add test cases for handling config version upgrades

    private void onPublisherFailure(AbstractPublisher publisher, Throwable error) { }

    private static void assertThatMessageIsScheduledWithGivenDelay(Message msg, long delayMillis) {
        long expectedTimeMillis = SystemClock.uptimeMillis() + delayMillis;
        long deltaMillis = 1000;  // +/- 1 seconds is good enough for testing
        assertThat(msg.getWhen()).isIn(Range
                .closed(expectedTimeMillis - deltaMillis, expectedTimeMillis + deltaMillis));
    }
}
