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

import static android.net.NetworkIdentity.OEM_NONE;
import static android.net.NetworkIdentity.OEM_PAID;
import static android.net.NetworkIdentity.OEM_PRIVATE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.car.builtin.net.NetworkStatsServiceHelper;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.NetworkIdentity;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.os.IBinder;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;

import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.TelemetryProto.ConnectivityPublisher.OemType;
import com.android.car.telemetry.TelemetryProto.ConnectivityPublisher.Transport;
import com.android.car.telemetry.databroker.DataSubscriber;
import com.android.car.test.FakeHandlerWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class ConnectivityPublisherTest extends AbstractExtendedMockitoTestCase {
    private static final TelemetryProto.Publisher PUBLISHER_WIFI_OEM_NONE =
            TelemetryProto.Publisher.newBuilder()
                    .setConnectivity(
                            TelemetryProto.ConnectivityPublisher.newBuilder()
                                    .setTransport(Transport.TRANSPORT_WIFI)
                                    .setOemType(OemType.OEM_NONE))
                    .build();
    private static final TelemetryProto.Publisher PUBLISHER_WIFI_OEM_MANAGED =
            TelemetryProto.Publisher.newBuilder()
                    .setConnectivity(
                            TelemetryProto.ConnectivityPublisher.newBuilder()
                                    .setTransport(Transport.TRANSPORT_WIFI)
                                    .setOemType(OemType.OEM_MANAGED))
                    .build();
    private static final TelemetryProto.Publisher PUBLISHER_CELL_OEM_NONE =
            TelemetryProto.Publisher.newBuilder()
                    .setConnectivity(
                            TelemetryProto.ConnectivityPublisher.newBuilder()
                                    .setTransport(Transport.TRANSPORT_CELLULAR)
                                    .setOemType(OemType.OEM_NONE))
                    .build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_WIFI_OEM_NONE =
            TelemetryProto.Subscriber.newBuilder()
                    .setHandler("empty_handler")
                    .setPublisher(PUBLISHER_WIFI_OEM_NONE)
                    .build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_WIFI_OEM_MANAGED =
            TelemetryProto.Subscriber.newBuilder()
                    .setHandler("empty_handler")
                    .setPublisher(PUBLISHER_WIFI_OEM_MANAGED)
                    .build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_CELL_OEM_NONE =
            TelemetryProto.Subscriber.newBuilder()
                    .setHandler("empty_handler")
                    .setPublisher(PUBLISHER_CELL_OEM_NONE)
                    .build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("myconfig")
                    .setVersion(1)
                    .addSubscribers(SUBSCRIBER_WIFI_OEM_NONE)
                    .addSubscribers(SUBSCRIBER_WIFI_OEM_MANAGED)
                    .addSubscribers(SUBSCRIBER_CELL_OEM_NONE)
                    .setScript("function empty_handler()\nend")
                    .build();

    /** See {@code ConnectivityPublisher#pullInitialNetstats()}. */
    private static final int BASELINE_PULL_COUNT = 8;

    // Test network usage tags.
    private static final int TAG_0 = 0;
    private static final int TAG_1 = 1;

    // Test network usage uids.
    private static final int UID_0 = 0;
    private static final int UID_1 = 1;
    private static final int UID_2 = 2;
    private static final int UID_3 = 3;

    @Mock private Context mMockContext;

    private final long mNow = System.currentTimeMillis(); // since epoch

    private final FakeHandlerWrapper mFakeHandlerWrapper =
            new FakeHandlerWrapper(Looper.getMainLooper(), FakeHandlerWrapper.Mode.QUEUEING);

    private final FakeDataSubscriber mDataSubscriberWifi =
            new FakeDataSubscriber(METRICS_CONFIG, SUBSCRIBER_WIFI_OEM_NONE);
    private final FakeDataSubscriber mDataSubscriberWifiOemManaged =
            new FakeDataSubscriber(METRICS_CONFIG, SUBSCRIBER_WIFI_OEM_MANAGED);
    private final FakeDataSubscriber mDataSubscriberCell =
            new FakeDataSubscriber(METRICS_CONFIG, SUBSCRIBER_CELL_OEM_NONE);

    private ConnectivityPublisher mPublisher; // subject
    private FakeNetworkStatsService mFakeService;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder.spyStatic(ServiceManager.class);
    }

    @Before
    public void setUp() throws Exception {
        mPublisher = createRestartedPublisher();
        mFakeService = new FakeNetworkStatsService();
    }

    /** Emulates a restart by creating a new ConnectivityPublisher. */
    private ConnectivityPublisher createRestartedPublisher() {
        return new ConnectivityPublisher(
                this::onPublisherFailure,
                new NetworkStatsServiceHelper(),
                mFakeHandlerWrapper.getMockHandler(),
                mMockContext);
    }

    @Test
    public void testAddDataSubscriber_storesIt_andStartsPeriodicPull() {
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        assertThat(mPublisher.hasDataSubscriber(mDataSubscriberWifi)).isTrue();
        // One for initial pull, second one for this addDataSubscriber.
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(2);
    }

    @Test
    public void testRemoveDataSubscriber_removesIt() {
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        mPublisher.removeDataSubscriber(mDataSubscriberWifi);

        assertThat(mPublisher.hasDataSubscriber(mDataSubscriberWifi)).isFalse();
        // Only initial pull left, because we removed the last subscriber.
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(1);
    }

    @Test
    public void testRemoveDataSubscriber_leavesPeriodicTask_ifOtherSubscriberExists() {
        mPublisher.addDataSubscriber(mDataSubscriberWifi);
        mPublisher.addDataSubscriber(mDataSubscriberCell);

        mPublisher.removeDataSubscriber(mDataSubscriberWifi);

        assertThat(mPublisher.hasDataSubscriber(mDataSubscriberWifi)).isFalse();
        // Only initial pull left + periodic puller.
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(2);
    }

    @Test
    public void testRemoveDataSubscriber_givenWrongSubscriber_doesNothing() {
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        mPublisher.removeDataSubscriber(mDataSubscriberCell);

        assertThat(mPublisher.hasDataSubscriber(mDataSubscriberWifi)).isTrue();
        // One for initial pull, second one for this addDataSubscriber.
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(2);
    }

    @Test
    public void testRemoveAllDataSubscribers_removesAll_andStopsPeriodicPull() {
        mPublisher.addDataSubscriber(mDataSubscriberWifi);
        mPublisher.addDataSubscriber(mDataSubscriberCell);

        mPublisher.removeAllDataSubscribers();

        assertThat(mPublisher.hasDataSubscriber(mDataSubscriberWifi)).isFalse();
        // Only initial pull left, because we removed the subscribers.
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(1);
    }

    @Test
    public void testSchedulesNextPeriodicPullInHandler() {
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls empty baseline netstats
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        mFakeHandlerWrapper.dispatchQueuedMessages(); // Current pull.

        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(1); // Next pull.
    }

    @Test
    public void testPullsOnlyNecessaryData() {
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls empty baseline netstats
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        // Pulls netstats only once for wifi.
        assertThat(mFakeService.getMethodCallCount())
                .containsEntry("getSummaryForAllUid", BASELINE_PULL_COUNT + 1);
    }

    @Test
    public void testPullsOnlyNecessaryData_wifiAndMobile() {
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls empty baseline netstats
        mPublisher.addDataSubscriber(mDataSubscriberWifi);
        mPublisher.addDataSubscriber(mDataSubscriberCell);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mFakeService.getMethodCallCount())
                .containsEntry("getSummaryForAllUid", BASELINE_PULL_COUNT + 2);
    }

    @Test
    public void testPullsMobileStats() {
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls empty baseline netstats
        mFakeService.addMobileStats(UID_0, TAG_0, 5000, OEM_NONE, mNow);
        mFakeService.addWifiStats(UID_1, TAG_1, 30, OEM_NONE, mNow);
        mFakeService.addWifiStats(UID_2, TAG_1, 10, OEM_PAID, mNow);
        mFakeService.addWifiStats(UID_3, TAG_1, 6, OEM_PRIVATE, mNow);
        mPublisher.addDataSubscriber(mDataSubscriberCell);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mDataSubscriberCell.mPushedData).hasSize(1);
        // Matches only UID_0.
        assertThat(mDataSubscriberCell.get(0).getLong("tmp_total_rx_bytes")).isEqualTo(5000);
    }

    @Test
    public void testPullsOemManagedWifiStats() {
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls empty baseline netstats
        mFakeService.addMobileStats(UID_0, TAG_0, 5000, OEM_NONE, mNow);
        mFakeService.addWifiStats(UID_1, TAG_1, 30, OEM_NONE, mNow);
        mFakeService.addWifiStats(UID_2, TAG_1, 10, OEM_PAID, mNow);
        mFakeService.addWifiStats(UID_3, TAG_1, 6, OEM_PRIVATE, mNow);
        mPublisher.addDataSubscriber(mDataSubscriberWifiOemManaged);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mDataSubscriberWifiOemManaged.mPushedData).hasSize(1);
        // Matches only UID_2 + UID_3.
        assertThat(mDataSubscriberWifiOemManaged.get(0).getLong("tmp_total_rx_bytes"))
                .isEqualTo(16);
    }

    @Test
    public void testPullsOemNotManagedWifiStats() {
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls empty baseline netstats
        mFakeService.addMobileStats(UID_0, TAG_0, 5000, OEM_NONE, mNow);
        mFakeService.addWifiStats(UID_1, TAG_1, 30, OEM_NONE, mNow);
        mFakeService.addWifiStats(UID_2, TAG_1, 10, OEM_PAID, mNow);
        mFakeService.addWifiStats(UID_3, TAG_1, 6, OEM_PRIVATE, mNow);
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mDataSubscriberWifi.mPushedData).hasSize(1);
        // Matches only UID_1.
        assertThat(mDataSubscriberWifi.get(0).getLong("tmp_total_rx_bytes")).isEqualTo(30);
    }

    @Test
    public void testPullsStatsOnlyBetweenBootTimeMinus2HoursAndNow() {
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls empty baseline netstats
        long mBootTimeMillis = mNow - SystemClock.elapsedRealtime(); // since epoch
        long bootMinus30Mins = mBootTimeMillis - Duration.ofMinutes(30).toMillis();
        long bootMinus5Hours = mBootTimeMillis - Duration.ofHours(5).toMillis();
        mFakeService.addMobileStats(UID_1, TAG_0, 5000, OEM_NONE, mNow);
        mFakeService.addWifiStats(UID_1, TAG_1, 10, OEM_NONE, mNow);
        mFakeService.addWifiStats(UID_2, TAG_1, 10, OEM_NONE, bootMinus30Mins);
        mFakeService.addWifiStats(UID_3, TAG_1, 7, OEM_NONE, bootMinus5Hours);
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mDataSubscriberWifi.mPushedData).hasSize(1);
        // Only UID_1 and UID_2 are fetched (10 + 10 = 20), because other stats are outside
        // of the time range.
        assertThat(mDataSubscriberWifi.get(0).getLong("tmp_total_rx_bytes")).isEqualTo(20);
    }

    @Test
    public void testPushesDataAsNotLarge() {
        mPublisher.addDataSubscriber(mDataSubscriberWifi);
        mFakeService.addWifiStats(UID_1, TAG_1, 10, OEM_NONE, mNow);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        // The final data should not be marked "large".
        assertThat(mDataSubscriberWifi.mPushedData.get(0).mIsLargeData).isFalse();
    }

    @Test
    public void testSubtractsFromInitialBaseline() {
        long someTimeAgo = mNow - Duration.ofMinutes(1).toMillis();
        mFakeService.addWifiStats(UID_0, TAG_1, 10, OEM_PRIVATE, someTimeAgo);
        mFakeService.addWifiStats(UID_0, TAG_1, 11, OEM_PAID, someTimeAgo);
        mFakeService.addWifiStats(UID_0, TAG_1, 12, OEM_NONE, someTimeAgo);
        mFakeHandlerWrapper.dispatchQueuedMessages();  // pulls 10, 11, 12 bytes.

        // A hack to force the publisher to compute the diff from the baseline.
        // Otherwise we'll get "(100 + 12) - 12".
        mFakeService.clearNetworkStats();
        mFakeService.addWifiStats(UID_0, TAG_1, 100, OEM_NONE, mNow);
        mPublisher.addDataSubscriber(mDataSubscriberWifi);
        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mDataSubscriberWifi.mPushedData).hasSize(1);
        assertThat(mDataSubscriberWifi.get(0).getLong("tmp_total_rx_bytes")).isEqualTo(100 - 12);
    }

    @Test
    public void testSubtractsFromThePreviousPull() {
        // ==== 0th (initial) pull.
        long someTimeAgo = mNow - Duration.ofMinutes(1).toMillis();
        mFakeService.addWifiStats(UID_0, TAG_1, 12, OEM_NONE, someTimeAgo);
        mFakeHandlerWrapper.dispatchQueuedMessages();  // pulls 12 bytes

        // ==== 1st pull.
        mFakeService.addWifiStats(UID_0, TAG_1, 200, OEM_NONE, someTimeAgo);
        mPublisher.addDataSubscriber(mDataSubscriberWifi);
        mFakeHandlerWrapper.dispatchQueuedMessages();  // pulls 200 + 12 bytes

        assertThat(mDataSubscriberWifi.mPushedData).hasSize(1);
        // It's 200, because it subtracts previous pull 12 from (200 + 12).
        assertThat(mDataSubscriberWifi.get(0).getLong("tmp_total_rx_bytes")).isEqualTo(200);

        // ==== 2nd pull.
        mFakeService.addWifiStats(UID_0, TAG_1, 1000, OEM_NONE, mNow);
        mFakeHandlerWrapper.dispatchQueuedMessages();  // pulls 200 + 12 + 1000 bytes

        assertThat(mDataSubscriberWifi.mPushedData).hasSize(2);
        // It's 1000, because it subtracts previous pull (200 + 12) from (200 + 12 + 1000).
        assertThat(mDataSubscriberWifi.get(1).getLong("tmp_total_rx_bytes")).isEqualTo(1000);
    }

    private void onPublisherFailure(
            AbstractPublisher publisher,
            List<TelemetryProto.MetricsConfig> affectedConfigs,
            Throwable error) {}

    private static class FakeDataSubscriber extends DataSubscriber {
        private final ArrayList<PushedData> mPushedData = new ArrayList<>();

        FakeDataSubscriber(
                TelemetryProto.MetricsConfig metricsConfig, TelemetryProto.Subscriber subscriber) {
            super(/* dataBroker= */ null, metricsConfig, subscriber);
        }

        @Override
        public void push(PersistableBundle data, boolean isLargeData) {
            mPushedData.add(new PushedData(data, isLargeData));
        }

        /** Returns the pushed data by the given index. */
        PersistableBundle get(int index) {
            return mPushedData.get(index).mData;
        }
    }

    /** Data pushed to a subscriber. */
    private static class PushedData {
        private final PersistableBundle mData;
        private final boolean mIsLargeData;

        PushedData(PersistableBundle data, boolean isLargeData) {
            mData = data;
            mIsLargeData = isLargeData;
        }
    }

    /**
     * A fake for {@link INetworkStatsService} and {@link INetworkStatsSession}.
     *
     * <p>Internally {@link android.net.NetworkStatsService} uses multiple recorders for tagged,
     * untagged and other netstats. That's why {@link NetworkStats#getTotal()} may not work as
     * expected, it filters out tagged entries.
     *
     * <p>It returns an empty {@link NetworkStats} unless fake stats not added using
     * {@link #addWifiStats()} or {@link #addMobileStats()}.
     */
    private static class FakeNetworkStatsService {
        private final IBinder mMockBinder = mock(IBinder.class);
        private final INetworkStatsService mMockNetworkStatsService =
                mock(INetworkStatsService.class);
        private final INetworkStatsSession mMockNetworkStatsSession =
                mock(INetworkStatsSession.class);
        private final ArrayList<NetworkStatsExt> mNetworkStats = new ArrayList<>();
        private final HashMap<String, Integer> mMethodCallCount = new HashMap<>();

        private FakeNetworkStatsService() throws RemoteException {
            when(mMockBinder.queryLocalInterface(anyString())).thenReturn(mMockNetworkStatsService);
            doReturn(mMockBinder)
                    .when(() -> ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
            when(mMockNetworkStatsService.openSessionForUsageStats(anyInt(), any()))
                    .thenReturn(mMockNetworkStatsSession);
            doAnswer(this::getSummaryForAllUidInternal)
                    .when(mMockNetworkStatsSession)
                    .getSummaryForAllUid(any(), anyLong(), anyLong(), anyBoolean());
        }

        /** Adds {@link NetworkStatsExt} that will be returned by {@code getSummaryForAllUid()}. */
        public void addWifiStats(int uid, int tag, int rxTx, int oemManaged, long timestampMillis) {
            NetworkStatsExt stats =
                    new NetworkStatsExt(
                            new NetworkIdentity(
                                    ConnectivityManager.TYPE_WIFI,
                                    NetworkIdentity.SUBTYPE_COMBINED, // not used in matching
                                    /* subscriberId= */ null,
                                    /* networkId= */ "guest-wifi",
                                    /* roaming= */ false,
                                    /* metered= */ false,
                                    /* defaultNetwork= */ false,
                                    oemManaged),
                            timestampMillis);
            stats.insertEntry(/* iface= */ "iface0", uid, tag, rxTx);
            mNetworkStats.add(stats);
        }

        /** Adds {@link NetworkStatsExt} that will be returned by {@code getSummaryForAllUid()}. */
        public void addMobileStats(
                int uid, int tag, int rxTx, int oemManaged, long timestampMillis) {
            NetworkStatsExt stats =
                    new NetworkStatsExt(
                            new NetworkIdentity(
                                    ConnectivityManager.TYPE_MOBILE,
                                    /* subType= */ TelephonyManager.NETWORK_TYPE_GPRS,
                                    /* subscriberId= */ "subscriber1",
                                    /* networkId= */ null,
                                    /* roaming= */ false,
                                    /* metered= */ false,
                                    /* defaultNetwork= */ true,
                                    oemManaged),
                            timestampMillis);
            stats.insertEntry(/* iface= */ "iface1", uid, tag, rxTx);
            mNetworkStats.add(stats);
        }

        public void clearNetworkStats() {
            mNetworkStats.clear();
        }

        /** Returns the map of API calls count. */
        public HashMap<String, Integer> getMethodCallCount() {
            return new HashMap<>(mMethodCallCount);
        }

        private NetworkStats getSummaryForAllUidInternal(InvocationOnMock invocation) {
            increaseMethodCall("getSummaryForAllUid", 1);
            NetworkTemplate template = invocation.getArgument(0);
            long start = invocation.getArgument(1);
            long end = invocation.getArgument(2);
            boolean includeTags = invocation.getArgument(3);
            assertThat(includeTags).isTrue(); // ConnectiityPublisher always needs network tags.
            // This is how NetworkStats is initialized in the original service.
            NetworkStats result = new NetworkStats(end - start, /* initialSize= */ 0);
            for (NetworkStatsExt stats : mNetworkStats) {
                // NOTE: the actual implementation uses network buckets instead of simple time
                //       range checking.
                if (stats.mTimestampMillis < start || stats.mTimestampMillis > end) {
                    continue;
                }
                if (template.matches(stats.mIdentity)) {
                    result = result.add(stats.mNetworkStats);
                }
            }
            return result;
        }

        private void increaseMethodCall(String methodName, int count) {
            mMethodCallCount.put(methodName, mMethodCallCount.getOrDefault(methodName, 0) + count);
        }
    }

    /**
     * A wrapper for {@link NetworkStats} identified with {@link NetworkIdentity}. It's used by a
     * fake {@code getSummaryForAllUidInternal()} above to find the matching {@link NetworkStats} by
     * the given {@link NetworkTemplate} predicate. It uses the same {@code rxTx} values for
     * received/transmitted bytes and packets for simplifying the tests.
     *
     * <p>Think of its instance as a "network usage data" for the bunch of similar packets
     * transmitted over the same socket.
     */
    private static class NetworkStatsExt {
        private final NetworkStats mNetworkStats;
        private final NetworkIdentity mIdentity;
        private final long mTimestampMillis;

        NetworkStatsExt(NetworkIdentity identity, long timestampMillis) {
            mIdentity = identity;
            mTimestampMillis = timestampMillis;
            mNetworkStats = new NetworkStats(/* elapsedRealtime= */ 0, /* initialSize= */ 0);
        }

        void insertEntry(@Nullable String iface, int uid, int tag, int rxTx) {
            mNetworkStats.insertEntry(
                    new NetworkStats.Entry(
                            iface,
                            uid,
                            /* set= */ NetworkStats.SET_DEFAULT, // background usage
                            tag,
                            mIdentity.getMetered()
                                    ? NetworkStats.METERED_YES
                                    : NetworkStats.METERED_NO,
                            mIdentity.getRoaming()
                                    ? NetworkStats.ROAMING_YES
                                    : NetworkStats.ROAMING_NO,
                            mIdentity.getDefaultNetwork()
                                    ? NetworkStats.DEFAULT_NETWORK_YES
                                    : NetworkStats.DEFAULT_NETWORK_NO,
                            /* rxBytes= */ rxTx,
                            /* rxPackets= */ rxTx,
                            /* txBytes= */ rxTx,
                            /* txPackets= */ rxTx,
                            /* operations= */ 0));
        }
    }
}
