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

import static android.car.builtin.net.NetworkStatsServiceHelper.TRANSPORT_CELLULAR;
import static android.car.builtin.net.NetworkStatsServiceHelper.TRANSPORT_WIFI;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkTemplate.OEM_MANAGED_ALL;
import static android.net.NetworkTemplate.OEM_MANAGED_NO;
import static android.net.NetworkTemplate.OEM_MANAGED_PAID;
import static android.net.NetworkTemplate.OEM_MANAGED_PRIVATE;
import static android.net.NetworkTemplate.OEM_MANAGED_YES;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import android.car.builtin.net.NetworkStatsServiceHelper;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.Context;
import android.net.NetworkStats;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.ServiceManager;
import android.os.SystemClock;

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
    private static final int TAG_1 = 1;
    private static final int TAG_2 = 2;

    // Test network usage uids.
    private static final int UID_1 = 1;
    private static final int UID_2 = 2;
    private static final int UID_3 = 3;
    private static final int UID_4 = 4;

    @Mock private Context mMockContext;
    @Mock private NetworkStatsServiceHelper.Dependencies mNetworkStatsDependencies;

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
        mFakeService = new FakeNetworkStatsService(mNetworkStatsDependencies);
    }

    /** Emulates a restart by creating a new ConnectivityPublisher. */
    private ConnectivityPublisher createRestartedPublisher() {
        return new ConnectivityPublisher(
                this::onPublisherFailure,
                new NetworkStatsServiceHelper(mNetworkStatsDependencies),
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
        mFakeService.addMobileStats(UID_1, TAG_1, 5000, OEM_MANAGED_NO, mNow);
        mFakeService.addWifiStats(UID_1, TAG_2, 30, OEM_MANAGED_NO, mNow);
        mFakeService.addWifiStats(UID_2, TAG_2, 10, OEM_MANAGED_PAID, mNow);
        mFakeService.addWifiStats(UID_3, TAG_2, 6, OEM_MANAGED_PRIVATE, mNow);
        mPublisher.addDataSubscriber(mDataSubscriberCell);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mDataSubscriberCell.mPushedData).hasSize(1);
        PersistableBundle result = mDataSubscriberCell.get(0);
        // Matches only UID_1/TAG_1 above.
        assertThat(result.getLong("collectedAtMillis")).isGreaterThan(mNow);
        assertThat(result.getLong("durationMillis")).isGreaterThan(0);
        assertThat(result.getInt("size")).isEqualTo(1);
        assertThat(result.getIntArray("uid")).asList().containsExactly(UID_1);
        assertThat(result.getIntArray("tag")).asList().containsExactly(TAG_1);
        assertThat(result.getLongArray("txBytes")).asList().containsExactly(5000L);
        assertThat(result.getLongArray("rxBytes")).asList().containsExactly(5000L);
    }

    @Test
    public void testPullsOemManagedWifiStats() {
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls empty baseline netstats
        mFakeService.addMobileStats(UID_1, TAG_2, 5000, OEM_MANAGED_NO, mNow);
        mFakeService.addWifiStats(UID_1, TAG_1, 30, OEM_MANAGED_NO, mNow);
        mFakeService.addWifiStats(UID_2, TAG_NONE, 10, OEM_MANAGED_PAID, mNow);
        mFakeService.addWifiStats(UID_3, TAG_2, 6, OEM_MANAGED_PRIVATE, mNow);
        mPublisher.addDataSubscriber(mDataSubscriberWifiOemManaged);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mDataSubscriberWifiOemManaged.mPushedData).hasSize(1);
        PersistableBundle result = mDataSubscriberWifiOemManaged.get(0);
        // Matches only UID_2 + UID_3.
        assertThat(result.getLong("collectedAtMillis")).isGreaterThan(mNow);
        assertThat(result.getLong("durationMillis")).isGreaterThan(0);
        assertThat(result.getInt("size")).isEqualTo(2);
        assertThat(result.getIntArray("uid")).asList().containsExactly(UID_2, UID_3);
        assertThat(result.getIntArray("tag")).asList().containsExactly(TAG_NONE, TAG_2);
        assertThat(result.getLongArray("txBytes")).asList().containsExactly(10L, 6L);
        assertThat(result.getLongArray("rxBytes")).asList().containsExactly(10L, 6L);
    }

    @Test
    public void testPullsOemNotManagedWifiStats() {
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls empty baseline netstats
        mFakeService.addMobileStats(UID_4, TAG_2, 5000, OEM_MANAGED_NO, mNow);
        mFakeService.addWifiStats(UID_1, TAG_1, 30, OEM_MANAGED_NO, mNow);
        mFakeService.addWifiStats(UID_2, TAG_1, 10, OEM_MANAGED_PAID, mNow);
        mFakeService.addWifiStats(UID_3, TAG_1, 6, OEM_MANAGED_PRIVATE, mNow);
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mDataSubscriberWifi.mPushedData).hasSize(1);
        PersistableBundle result = mDataSubscriberWifi.get(0);
        // Matches only UID_1.
        assertThat(result.getLong("collectedAtMillis")).isGreaterThan(mNow);
        assertThat(result.getLong("durationMillis")).isGreaterThan(0);
        assertThat(result.getInt("size")).isEqualTo(1);
        assertThat(result.getIntArray("uid")).asList().containsExactly(UID_1);
        assertThat(result.getIntArray("tag")).asList().containsExactly(TAG_1);
        assertThat(result.getLongArray("txBytes")).asList().containsExactly(30L);
        assertThat(result.getLongArray("rxBytes")).asList().containsExactly(30L);
    }

    @Test
    public void testPullsStatsOnlyBetweenBootTimeMinus2HoursAndNow() {
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls empty baseline netstats
        long mBootTimeMillis = mNow - SystemClock.elapsedRealtime(); // since epoch
        long bootMinus30Mins = mBootTimeMillis - Duration.ofMinutes(30).toMillis();
        long bootMinus5Hours = mBootTimeMillis - Duration.ofHours(5).toMillis();
        mFakeService.addMobileStats(UID_1, TAG_2, 5000, OEM_MANAGED_NO, mNow);
        mFakeService.addWifiStats(UID_1, TAG_1, 10, OEM_MANAGED_NO, mNow);
        mFakeService.addWifiStats(UID_2, TAG_1, 10, OEM_MANAGED_NO, bootMinus30Mins);
        mFakeService.addWifiStats(UID_3, TAG_1, 7, OEM_MANAGED_NO, bootMinus5Hours);
        mPublisher.addDataSubscriber(mDataSubscriberWifi);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mDataSubscriberWifi.mPushedData).hasSize(1);
        PersistableBundle result = mDataSubscriberWifi.get(0);
        // Only UID_1 and UID_2 are fetched, because other stats are outside
        // of the time range.
        assertThat(result.getLong("collectedAtMillis")).isGreaterThan(mNow);
        assertThat(result.getLong("durationMillis")).isGreaterThan(0);
        assertThat(result.getInt("size")).isEqualTo(2);
        assertThat(result.getIntArray("uid")).asList().containsExactly(UID_1, UID_2);
        assertThat(result.getIntArray("tag")).asList().containsExactly(TAG_1, TAG_1);
        assertThat(result.getLongArray("txBytes")).asList().containsExactly(10L, 10L);
        assertThat(result.getLongArray("rxBytes")).asList().containsExactly(10L, 10L);
    }

    @Test
    public void testPushesDataAsNotLarge() {
        mPublisher.addDataSubscriber(mDataSubscriberWifi);
        mFakeService.addWifiStats(UID_1, TAG_1, 10, OEM_MANAGED_NO, mNow);

        mFakeHandlerWrapper.dispatchQueuedMessages();

        // The final data should not be marked "large".
        assertThat(mDataSubscriberWifi.mPushedData.get(0).mIsLargeData).isFalse();
    }

    @Test
    public void testSubtractsFromInitialPull() {
        long someTimeAgo = mNow - Duration.ofMinutes(1).toMillis();
        mFakeService.addWifiStats(UID_4, TAG_1, 10, OEM_MANAGED_PRIVATE, someTimeAgo);
        mFakeService.addWifiStats(UID_4, TAG_1, 11, OEM_MANAGED_PAID, someTimeAgo);
        mFakeService.addWifiStats(UID_4, TAG_1, 12, OEM_MANAGED_NO, someTimeAgo);
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls 10, 11, 12 bytes.

        // A hack to force the publisher to compute the diff from the initial pull.
        // Otherwise we'll get "(100 + 12) - 12".
        mFakeService.clearNetworkStats();
        mFakeService.addWifiStats(UID_4, TAG_1, 100, OEM_MANAGED_NO, mNow);
        mPublisher.addDataSubscriber(mDataSubscriberWifi);
        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mDataSubscriberWifi.mPushedData).hasSize(1);
        PersistableBundle result = mDataSubscriberWifi.get(0);
        assertThat(result.getLong("collectedAtMillis")).isGreaterThan(mNow);
        assertThat(result.getLong("durationMillis")).isGreaterThan(0);
        assertThat(result.getInt("size")).isEqualTo(1);
        assertThat(result.getIntArray("uid")).asList().containsExactly(UID_4);
        assertThat(result.getIntArray("tag")).asList().containsExactly(TAG_1);
        assertThat(result.getLongArray("txBytes")).asList().containsExactly(100L - 12L);
        assertThat(result.getLongArray("rxBytes")).asList().containsExactly(100L - 12L);
    }

    @Test
    public void testSubtractsFromThePreviousPull() {
        // ==== 0th (initial) pull.
        long someTimeAgo = mNow - Duration.ofMinutes(1).toMillis();
        mFakeService.addWifiStats(UID_4, TAG_1, 12, OEM_MANAGED_NO, someTimeAgo);
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls 12 bytes

        // ==== 1st pull.
        mFakeService.addWifiStats(UID_4, TAG_1, 200, OEM_MANAGED_NO, someTimeAgo);
        mPublisher.addDataSubscriber(mDataSubscriberWifi);
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls 200 + 12 bytes

        assertThat(mDataSubscriberWifi.mPushedData).hasSize(1);
        PersistableBundle result = mDataSubscriberWifi.get(0);
        assertThat(result.getInt("size")).isEqualTo(1);
        assertThat(result.getIntArray("uid")).asList().containsExactly(UID_4);
        assertThat(result.getIntArray("tag")).asList().containsExactly(TAG_1);
        // It's 200, because it subtracts previous pull 12 from (200 + 12).
        assertThat(result.getLongArray("txBytes")).asList().containsExactly(200L);

        // ==== 2nd pull.
        mFakeService.addWifiStats(UID_4, TAG_1, 1000, OEM_MANAGED_NO, mNow);
        mFakeHandlerWrapper.dispatchQueuedMessages(); // pulls 200 + 12 + 1000 bytes

        assertThat(mDataSubscriberWifi.mPushedData).hasSize(2);
        result = mDataSubscriberWifi.get(1);
        assertThat(result.getInt("size")).isEqualTo(1);
        assertThat(result.getIntArray("uid")).asList().containsExactly(UID_4);
        assertThat(result.getIntArray("tag")).asList().containsExactly(TAG_1);
        // It's 1000, because it subtracts previous pull (200 + 12) from (200 + 12 + 1000).
        assertThat(result.getLongArray("txBytes")).asList().containsExactly(1000L);
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
     * A fake for {@link NetworkStatsServiceHelper.Dependencies}.
     *
     * <p>Internally {@link android.net.NetworkStatsService} uses multiple recorders for tagged,
     * untagged and other netstats. That's why {@link NetworkStats#getTotal()} may not work as
     * expected, it filters out tagged entries.
     *
     * <p>It returns an empty {@link NetworkStats} unless fake stats not added using {@link
     * #addWifiStats()} or {@link #addMobileStats()}.
     */
    private static class FakeNetworkStatsService {
        private final NetworkStatsServiceHelper.Dependencies mMockDeps;
        private final ArrayList<NetworkStatsExt> mNetworkStatsExts = new ArrayList<>();
        private final HashMap<String, Integer> mMethodCallCount = new HashMap<>();

        private FakeNetworkStatsService(NetworkStatsServiceHelper.Dependencies mockDeps) {
            mMockDeps = mockDeps;
            doAnswer(this::getSummaryForAllUid)
                    .when(mMockDeps)
                    .getSummaryForAllUid(anyInt(), anyInt(), anyLong(), anyLong());
        }

        private NetworkStats makeStats(int uid, int tag, int rxTx) {
            final NetworkStats stats =
                    new NetworkStats(/* elapsedRealTime= */ 0L, /* initialSize= */ 0);
            return stats.addEntry(
                    new NetworkStats.Entry(
                            /* iface= */ null,
                            uid,
                            NetworkStats.SET_FOREGROUND,
                            tag,
                            NetworkStats.METERED_YES,
                            NetworkStats.ROAMING_YES,
                            NetworkStats.DEFAULT_NETWORK_YES,
                            /* rxBytes= */ rxTx,
                            /* rxPackets= */ rxTx,
                            /* txBytes= */ rxTx,
                            /* txPackets= */ rxTx,
                            /* operations= */ 0));
        }

        /** Adds {@link NetworkStatsExt} that will be returned by {@code getSummaryForAllUid()}. */
        public void addWifiStats(int uid, int tag, int rxTx, int oemManaged, long timestampMillis) {
            NetworkStatsExt statsExt =
                    new NetworkStatsExt(
                            TRANSPORT_WIFI, oemManaged, timestampMillis, makeStats(uid, tag, rxTx));
            mNetworkStatsExts.add(statsExt);
        }

        /** Adds {@link NetworkStatsExt} that will be returned by {@code getSummaryForAllUid()}. */
        public void addMobileStats(
                int uid, int tag, int rxTx, int oemManaged, long timestampMillis) {
            NetworkStatsExt statsExt =
                    new NetworkStatsExt(
                            TRANSPORT_CELLULAR,
                            oemManaged,
                            timestampMillis,
                            makeStats(uid, tag, rxTx));
            mNetworkStatsExts.add(statsExt);
        }

        public void clearNetworkStats() {
            mNetworkStatsExts.clear();
        }

        /** Returns the map of API calls count. */
        public HashMap<String, Integer> getMethodCallCount() {
            return new HashMap<>(mMethodCallCount);
        }

        private NetworkStats getSummaryForAllUid(InvocationOnMock invocation) {
            increaseMethodCall("getSummaryForAllUid", 1);
            int transport = invocation.getArgument(0);
            int oemManaged = invocation.getArgument(1);
            long start = invocation.getArgument(2);
            long end = invocation.getArgument(3);
            // This is how NetworkStats is initialized in the original service.
            NetworkStats result = new NetworkStats(end - start, /* initialSize= */ 0);
            for (NetworkStatsExt statsExt : mNetworkStatsExts) {
                // NOTE: the actual implementation uses network buckets instead of simple time
                //       range checking.
                if (statsExt.mTimestampMillis < start || statsExt.mTimestampMillis > end) {
                    continue;
                }
                if (matches(transport, oemManaged, statsExt)) {
                    result = result.add(statsExt.mNetworkStats);
                }
            }
            return result;
        }

        // Works similar to android.net.NetworkTemplate.matches().
        private boolean matches(int transport, int oemManaged, NetworkStatsExt statsExt) {
            if (transport != statsExt.mTransport) return false;
            return (oemManaged == OEM_MANAGED_ALL)
                    || (oemManaged == OEM_MANAGED_YES && statsExt.mOemManaged != OEM_MANAGED_NO)
                    || (oemManaged == statsExt.mOemManaged);
        }

        private void increaseMethodCall(String methodName, int count) {
            mMethodCallCount.put(methodName, mMethodCallCount.getOrDefault(methodName, 0) + count);
        }
    }

    /**
     * A wrapper for {@link NetworkStats} identified with conditions. It's used by a fake {@code
     * getSummaryForAllUid()} above to find the matching {@link NetworkStats} by the given
     * conditions. It uses the same {@code rxTx} values for received/transmitted bytes and packets
     * for simplifying the tests.
     *
     * <p>Think of its instance as a "network usage data" for the bunch of similar packets
     * transmitted over the same socket.
     */
    private static class NetworkStatsExt {
        private final int mTransport;
        private final int mOemManaged;
        private final long mTimestampMillis;
        private final NetworkStats mNetworkStats;

        NetworkStatsExt(int transport, int oemManaged, long timestampMillis, NetworkStats stats) {
            mTransport = transport;
            mOemManaged = oemManaged;
            mTimestampMillis = timestampMillis;
            mNetworkStats = stats;
        }
    }
}
