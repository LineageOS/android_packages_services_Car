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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

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

    @Mock private DataSubscriber mMockDataSubscriber;
    @Mock private StatsManagerProxy mStatsManager;
    private final FakeSharedPreferences mFakeSharedPref = new FakeSharedPreferences();

    private StatsPublisher mPublisher;

    @Before
    public void setUp() throws Exception {
        mPublisher = new StatsPublisher(mStatsManager, mFakeSharedPref);
        when(mMockDataSubscriber.getPublisherParam()).thenReturn(STATS_PUBLISHER_PARAMS_1);
        when(mMockDataSubscriber.getMetricsConfig()).thenReturn(METRICS_CONFIG);
        when(mMockDataSubscriber.getSubscriber()).thenReturn(SUBSCRIBER_1);
    }

    @Test
    public void testAddDataSubscriber_registersNewListener() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isTrue();
    }

    // TODO(b/189142577): add test cases when connecting to Statsd fails
}
