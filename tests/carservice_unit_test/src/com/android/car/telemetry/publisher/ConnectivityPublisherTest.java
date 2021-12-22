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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import static java.util.concurrent.TimeUnit.MICROSECONDS;

import android.car.builtin.net.NetworkStatsServiceHelper;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.Context;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.NetworkStats;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.SystemClock;

import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.TelemetryProto.ConnectivityPublisher.OemType;
import com.android.car.telemetry.TelemetryProto.ConnectivityPublisher.Transport;
import com.android.car.telemetry.databroker.DataBroker;
import com.android.car.telemetry.databroker.DataSubscriber;
import com.android.car.test.FakeHandlerWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class ConnectivityPublisherTest extends AbstractExtendedMockitoTestCase {
    private static final TelemetryProto.Publisher PUBLISHER_PARAMS_WIFI =
            TelemetryProto.Publisher.newBuilder()
                    .setConnectivity(
                            TelemetryProto.ConnectivityPublisher.newBuilder()
                                    .setTransport(Transport.TRANSPORT_WIFI)
                                    .setOemType(OemType.OEM_NONE))
                    .build();
    private static final TelemetryProto.Publisher PUBLISHER_PARAMS_CELL =
            TelemetryProto.Publisher.newBuilder()
                    .setConnectivity(
                            TelemetryProto.ConnectivityPublisher.newBuilder()
                                    .setTransport(Transport.TRANSPORT_CELLULAR)
                                    .setOemType(OemType.OEM_MANAGED))
                    .build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_WIFI =
            TelemetryProto.Subscriber.newBuilder()
                    .setHandler("handler_fn_wifi")
                    .setPublisher(PUBLISHER_PARAMS_WIFI)
                    .build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_CELL =
            TelemetryProto.Subscriber.newBuilder()
                    .setHandler("handler_fn_2")
                    .setPublisher(PUBLISHER_PARAMS_CELL)
                    .build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("myconfig")
                    .setVersion(1)
                    .addSubscribers(SUBSCRIBER_WIFI)
                    .addSubscribers(SUBSCRIBER_CELL)
                    .build();

    /** See {@code ConnectivityPublisher#pullInitialNetstats()}. */
    private static final int BASELINE_PULL_COUNT = 8;

    private final NetworkStats mNetworkStatsEmpty =
            new NetworkStats(/* elapsedRealtime= */ 0, /* initialSize= */ 0);

    @Mock private IBinder mMockBinder;
    @Mock private Context mMockContext;
    @Mock private INetworkStatsService mMockNetworkStatsService;
    @Mock private INetworkStatsSession mMockNetworkStatsSession;
    @Mock private DataBroker mMockDataBroker;

    private final FakeHandlerWrapper mFakeHandlerWrapper =
            new FakeHandlerWrapper(Looper.getMainLooper(), FakeHandlerWrapper.Mode.QUEUEING);

    private ConnectivityPublisher mPublisher; // subject

    private DataSubscriber mDataSubscriberWifi;
    private DataSubscriber mDataSubscriberCell;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder.spyStatic(ServiceManager.class);
    }

    @Before
    public void setUp() throws Exception {
        mPublisher = createRestartedPublisher();
        when(mMockBinder.queryLocalInterface(anyString())).thenReturn(mMockNetworkStatsService);
        doReturn(mMockBinder).when(() -> ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
        when(mMockNetworkStatsService.openSessionForUsageStats(anyInt(), any()))
                .thenReturn(mMockNetworkStatsSession);
        mDataSubscriberWifi = new DataSubscriber(mMockDataBroker, METRICS_CONFIG, SUBSCRIBER_WIFI);
        mDataSubscriberCell = new DataSubscriber(mMockDataBroker, METRICS_CONFIG, SUBSCRIBER_CELL);
    }

    /** Emulates a restart by creating a new ConnectivityPublisher. */
    private ConnectivityPublisher createRestartedPublisher() throws Exception {
        return new ConnectivityPublisher(
                this::onPublisherFailure,
                new NetworkStatsServiceHelper(),
                mFakeHandlerWrapper.getMockHandler(),
                mMockContext);
    }

    @Test
    public void testAddDataSubscriber_storesIt_andStartsPeriodicPull() throws Exception {
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        assertThat(mPublisher.hasDataSubscriber(mDataSubscriberWifi)).isTrue();
        // One for initial pull, second one for this addDataSubscriber.
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(2);
    }

    @Test
    public void testRemoveDataSubscriber_removesIt() throws Exception {
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        mPublisher.removeDataSubscriber(mDataSubscriberWifi);

        assertThat(mPublisher.hasDataSubscriber(mDataSubscriberWifi)).isFalse();
        // Only initial pull left, because we removed the last subscriber.
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(1);
    }

    @Test
    public void testRemoveDataSubscriber_givenWrongSubscriber_doesNothing() throws Exception {
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        mPublisher.removeDataSubscriber(mDataSubscriberCell);

        assertThat(mPublisher.hasDataSubscriber(mDataSubscriberWifi)).isTrue();
        // One for initial pull, second one for this addDataSubscriber.
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(2);
    }

    @Test
    public void testRemoveAllDataSubscribers_removesAll_andStopsPeriodicPull() throws Exception {
        mPublisher.addDataSubscriber(mDataSubscriberWifi);
        mPublisher.addDataSubscriber(mDataSubscriberCell);

        mPublisher.removeAllDataSubscribers();

        assertThat(mPublisher.hasDataSubscriber(mDataSubscriberWifi)).isFalse();
        // Only initial pull left, because we removed the subscribers.
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(1);
    }

    @Test
    public void testPullsFromNetworkStatsService() throws Exception {
        when(mMockNetworkStatsSession.getSummaryForAllUid(
                        any(), anyLong(), anyLong(), anyBoolean()))
                .thenReturn(mNetworkStatsEmpty);
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        verify(mMockNetworkStatsService, times(BASELINE_PULL_COUNT + 1))
                .openSessionForUsageStats(anyInt(), any());
        ArgumentCaptor<Long> startCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mMockNetworkStatsSession, times(BASELINE_PULL_COUNT + 1))
                .getSummaryForAllUid(any(), startCaptor.capture(), anyLong(), anyBoolean());
        final long elapsedMillisSinceBoot = SystemClock.elapsedRealtime(); // including sleep
        final long currentTimeInMillis = MICROSECONDS.toMillis(SystemClock.currentTimeMicro());
        // Make sure that "start" time is earlier than the boot time.
        assertThat(startCaptor.getValue()).isLessThan(currentTimeInMillis - elapsedMillisSinceBoot);
    }

    @Test
    public void testSchedulesNextPeriodicPullInHandler() throws Exception {
        when(mMockNetworkStatsSession.getSummaryForAllUid(
                        any(), anyLong(), anyLong(), anyBoolean()))
                .thenReturn(mNetworkStatsEmpty);
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        mFakeHandlerWrapper.dispatchQueuedMessages(); // Current pull.

        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(1); // Next pull.
    }

    private void onPublisherFailure(
            AbstractPublisher publisher,
            List<TelemetryProto.MetricsConfig> affectedConfigs,
            Throwable error) {}
}
