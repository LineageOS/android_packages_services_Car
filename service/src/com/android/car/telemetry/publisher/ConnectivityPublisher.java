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

import static com.android.car.telemetry.CarTelemetryService.DEBUG;

import android.car.builtin.net.NetworkStatsHelper;
import android.car.builtin.net.NetworkStatsServiceHelper;
import android.car.builtin.util.Slogf;
import android.content.Context;
import android.net.NetworkStats;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;

import com.android.car.CarLog;
import com.android.car.telemetry.TelemetryProto;
import com.android.car.telemetry.TelemetryProto.ConnectivityPublisher.OemType;
import com.android.car.telemetry.TelemetryProto.ConnectivityPublisher.Transport;
import com.android.car.telemetry.TelemetryProto.Publisher.PublisherCase;
import com.android.car.telemetry.databroker.DataSubscriber;
import com.android.internal.util.Preconditions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Publisher implementation for {@link TelemetryProto.ConnectivityPublisher}.
 *
 * <p>This publisher pulls netstats periodically from NetworkStatsService. It publishes statistics
 * between the last pull and now. The {@link NetworkStats} are stored in NetworkStatsService in
 * 2 hours buckets, we won't be able to get precise netstats if we use the buckets mechanism,
 * that's why we will be storing baseline (or previous) netstats in ConnectivityPublisher to find
 * netstats diff between now and the last pull.
 */
public class ConnectivityPublisher extends AbstractPublisher {
    // The default bucket duration used when query a snapshot from NetworkStatsService. The value
    // should be sync with NetworkStatsService#DefaultNetworkStatsSettings#getUidConfig.
    private static final long NETSTATS_UID_DEFAULT_BUCKET_DURATION_MILLIS =
            Duration.ofHours(2).toMillis();

    // TODO(b/202115033): Flatten the load spike by pulling reports for each MetricsConfigs
    //                    using separate periodical timers.
    // TODO(b/197905656): Use driving sessions to compute network usage statistics.
    private static final Duration PULL_NETSTATS_PERIOD = Duration.ofMinutes(1);

    // Assign the method to {@link Runnable}, otherwise the handler fails to remove it.
    private final Runnable mPullNetstatsPeriodically = this::pullNetstatsPeriodically;

    private final Context mContext;

    // Use ArrayMap instead of HashMap to improve memory usage. It doesn't store more than 100s
    // of items, and it's good enough performance-wise.
    private final ArrayMap<QueryParam, ArrayList<DataSubscriber>> mSubscribers = new ArrayMap<>();

    // Stores previous netstats for computing network usage since the last pull.
    private final ArrayMap<QueryParam, NetworkStats> mTransportPreviousNetstats = new ArrayMap<>();

    // All the methods in this class are expected to be called on this handler's thread.
    private final Handler mTelemetryHandler;

    private NetworkStatsServiceHelper mNetworkStatsService;
    private boolean mIsPullingNetstats = false;

    ConnectivityPublisher(
            PublisherFailureListener failureListener,
            NetworkStatsServiceHelper networkStatsService,
            Handler telemetryHandler,
            Context context) {
        super(failureListener);
        mNetworkStatsService = networkStatsService;
        mTelemetryHandler = telemetryHandler;
        mContext = context;
        for (Transport transport : Transport.values()) {
            if (transport.equals(Transport.TRANSPORT_UNDEFINED)) {
                continue;
            }
            for (OemType oemType : OemType.values()) {
                if (oemType.equals(OemType.OEM_UNDEFINED)) {
                    continue;
                }
                mSubscribers.put(QueryParam.build(transport, oemType), new ArrayList<>());
            }
        }
        // Pull baseine netstats to compute statistics for all the QueryParam combinations since
        // boot. It's needed to calculate netstats since the boot.
        mTelemetryHandler.post(this::pullInitialNetstats);
    }

    @Override
    public void addDataSubscriber(DataSubscriber subscriber) {
        TelemetryProto.Publisher publisherParam = subscriber.getPublisherParam();
        Preconditions.checkArgument(
                publisherParam.getPublisherCase() == PublisherCase.CONNECTIVITY,
                "Subscribers only with ConnectivityPublisher are supported by this class.");

        mSubscribers.get(QueryParam.forSubscriber(subscriber)).add(subscriber);
        if (!mIsPullingNetstats) {
            if (DEBUG) {
                Slogf.d(CarLog.TAG_TELEMETRY, "NetStats will be pulled in " + PULL_NETSTATS_PERIOD);
            }
            mIsPullingNetstats = true;
            mTelemetryHandler.postDelayed(
                    mPullNetstatsPeriodically, PULL_NETSTATS_PERIOD.toMillis());
        }
    }

    @Override
    public void removeDataSubscriber(DataSubscriber subscriber) {
        mSubscribers.get(QueryParam.forSubscriber(subscriber)).remove(subscriber);
        if (isSubscribersEmpty()) {
            mIsPullingNetstats = false;
            mTelemetryHandler.removeCallbacks(mPullNetstatsPeriodically);
        }
    }

    @Override
    public void removeAllDataSubscribers() {
        for (int i = 0; i < mSubscribers.size(); i++) {
            mSubscribers.valueAt(i).clear();
        }
        mIsPullingNetstats = false;
        mTelemetryHandler.removeCallbacks(mPullNetstatsPeriodically);
    }

    @Override
    public boolean hasDataSubscriber(DataSubscriber subscriber) {
        return mSubscribers.get(QueryParam.forSubscriber(subscriber)).contains(subscriber);
    }

    private void pullInitialNetstats() {
        try {
            for (QueryParam param : mSubscribers.keySet()) {
                mTransportPreviousNetstats.put(param, getSummaryForAllUid(param));
            }
        } catch (RemoteException e) {
            // Can't do much if the NetworkStatsService is not available. Next netstats pull
            // will update the baseline netstats.
            Slogf.w(CarLog.TAG_TELEMETRY, e);
        }
    }

    private void pullNetstatsPeriodically() {
        if (!mIsPullingNetstats) {
            return;
        }

        for (int i = 0; i < mSubscribers.size(); i++) {
            ArrayList<DataSubscriber> subscribers = mSubscribers.valueAt(i);
            if (subscribers.isEmpty()) {
                continue;
            }
            pullAndForwardNetstatsToSubscribers(mSubscribers.keyAt(i), subscribers);
        }

        if (DEBUG) {
            Slogf.d(CarLog.TAG_TELEMETRY, "NetStats will be pulled in " + PULL_NETSTATS_PERIOD);
        }
        mTelemetryHandler.postDelayed(mPullNetstatsPeriodically, PULL_NETSTATS_PERIOD.toMillis());
    }

    private void pullAndForwardNetstatsToSubscribers(
            QueryParam param, ArrayList<DataSubscriber> subscribers) {
        NetworkStats netstats;
        try {
            netstats = getSummaryForAllUid(param);
        } catch (RemoteException | NullPointerException e) {
            // If the NetworkStatsService is not available, it retries in the next pull.
            Slogf.w(CarLog.TAG_TELEMETRY, e);
            return;
        }
        NetworkStats baseline = mTransportPreviousNetstats.get(param);
        mTransportPreviousNetstats.put(param, netstats); // update the baseline
        if (baseline == null) {
            Slogf.w(
                    CarLog.TAG_TELEMETRY,
                    "Baseline is null for param %s. Will try again in the next pull.",
                    param);
            return;
        }
        NetworkStats diff = netstats.subtract(baseline); // network usage since last pull
        // TODO(b/197905656): Convert to persistable bundle.
        PersistableBundle data = new PersistableBundle();
        data.putLong("tmp_size", NetworkStatsHelper.size(diff));
        data.putLong("tmp_duration_millis", NetworkStatsHelper.getElapsedRealtime(diff));
        for (DataSubscriber subscriber : subscribers) {
            subscriber.push(data);
        }
    }

    /**
     * Creates a snapshot of NetworkStats since boot for the given QueryParam, but adds 1 bucket
     * duration before boot as a buffer to ensure at least one full bucket will be included. Note
     * that this should be only used to calculate diff since the snapshot might contains some
     * traffic before boot.
     *
     * <p>This method might block the thread for several seconds.
     *
     * <p>TODO(b/197905656): run this method on a separate thread for better performance.
     */
    private NetworkStats getSummaryForAllUid(QueryParam param) throws RemoteException {
        long currentTimeInMillis = System.currentTimeMillis();
        long elapsedMillisSinceBoot = SystemClock.elapsedRealtime(); // including sleep

        // TODO(b/197905656): consider using the current netstats bucket value
        //                    from Settings.Global.NETSTATS_UID_BUCKET_DURATION.
        return mNetworkStatsService.getSummaryForAllUid(
                param.getTransport(),
                param.getOemManaged(),
                /* start= */ currentTimeInMillis
                        - elapsedMillisSinceBoot
                        - NETSTATS_UID_DEFAULT_BUCKET_DURATION_MILLIS,
                /* end= */ currentTimeInMillis,
                mContext.getOpPackageName());
    }

    private boolean isSubscribersEmpty() {
        for (int i = 0; i < mSubscribers.size(); i++) {
            if (!mSubscribers.valueAt(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parameters to query data from NetworkStatsService. Converts {@link Transport} and
     * {@link OemType} values into NetworkStatsService supported values.
     */
    private static class QueryParam {
        private int mTransport;
        private int mOemManaged;

        static QueryParam forSubscriber(DataSubscriber subscriber) {
            TelemetryProto.ConnectivityPublisher publisher =
                    subscriber.getPublisherParam().getConnectivity();
            return build(publisher.getTransport(), publisher.getOemType());
        }

        static QueryParam build(Transport transport, OemType oemType) {
            return new QueryParam(getNetstatsTransport(transport), getNetstatsOemManaged(oemType));
        }

        private QueryParam(int transport, int oemManaged) {
            mTransport = transport;
            mOemManaged = oemManaged;
        }

        int getTransport() {
            return mTransport;
        }

        int getOemManaged() {
            return mOemManaged;
        }

        private static int getNetstatsTransport(Transport transport) {
            switch (transport) {
                case TRANSPORT_CELLULAR:
                    return NetworkStatsServiceHelper.TRANSPORT_CELLULAR;
                case TRANSPORT_ETHERNET:
                    return NetworkStatsServiceHelper.TRANSPORT_ETHERNET;
                case TRANSPORT_WIFI:
                    return NetworkStatsServiceHelper.TRANSPORT_WIFI;
                case TRANSPORT_BLUETOOTH:
                    return NetworkStatsServiceHelper.TRANSPORT_BLUETOOTH;
                default:
                    throw new IllegalArgumentException("Unexpected transport " + transport.name());
            }
        }

        private static int getNetstatsOemManaged(OemType oemType) {
            switch (oemType) {
                case OEM_NONE:
                    return NetworkStatsServiceHelper.OEM_MANAGED_NO;
                case OEM_MANAGED:
                    return NetworkStatsServiceHelper.OEM_MANAGED_YES;
                default:
                    throw new IllegalArgumentException("Unexpected oem_type " + oemType.name());
            }
        }

        @Override
        public String toString() {
            return "QueryParam(transport=" + mTransport + ", oemManaged=" + mOemManaged + ")";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mTransport, mOemManaged);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof QueryParam)) {
                return false;
            }
            QueryParam other = (QueryParam) obj;
            return mTransport == other.mTransport && mOemManaged == other.mOemManaged;
        }
    }
}
