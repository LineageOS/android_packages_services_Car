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

package android.car.builtin.net;

import static android.net.NetworkTemplate.MATCH_BLUETOOTH;
import static android.net.NetworkTemplate.MATCH_ETHERNET;
import static android.net.NetworkTemplate.MATCH_MOBILE;
import static android.net.NetworkTemplate.MATCH_WIFI;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.usage.NetworkStatsManager;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.os.RemoteException;

import com.android.net.module.util.NetworkStatsUtils;

/**
 * Provides access to {@link NetworkStatsManager} calls. It lazily connects to {@link
 * NetworkStatsManager} when necessary.
 *
 * @deprecated NetworkStats related code is moving to the mainline module, Platform code cannot
 *             access binder interfaces any more such as INetworkStatsService, NetworkStatsManager
 *             will expose system APIs to fetch netstats summary instead then. See b/214304284 for
 *             more details.
 *
 * @hide
 */
@Deprecated
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class NetworkStatsServiceHelper {
    // Supported transports by NetworkStatsServiceHelper and underlying NetworkTemplate predicate.
    // The values match android.net.NetworkCapabilities
    public static final int TRANSPORT_CELLULAR = 0;
    public static final int TRANSPORT_WIFI = 1;
    public static final int TRANSPORT_BLUETOOTH = 2;
    public static final int TRANSPORT_ETHERNET = 3;

    // The values match android.net.NetworkTemplate.OEM_MANAGED_*.
    public static final int OEM_MANAGED_ALL = -1;
    public static final int OEM_MANAGED_NO = 0;  // netstats for OEM not managed networks
    public static final int OEM_MANAGED_YES = -2;  // netstats for any OEM managed networks

    @NonNull
    private final Dependencies mDeps;

    public NetworkStatsServiceHelper(@NonNull Dependencies deps) {
        mDeps = deps;
    }

    /**
     * The dependencies needed by {@link #NetworkStatsServiceHelper}. Methods are intent to be
     * overridden by the test.
     */
    public static class Dependencies {
        @NonNull
        private final NetworkStatsManager mNetworkStatsManager;
        public Dependencies(@NonNull NetworkStatsManager networkStatsManager) {
            mNetworkStatsManager = networkStatsManager;
        }

        /**
         * Returns the network layer usage summary per UID for traffic for given transport and
         * oemManaged values.
         *
         * Note that while non-tagged statistics is returned to represent
         * the overall traffic, tagged data is also attached to provide mode detail of tagging
         * information.
         */
        @NonNull
        public NetworkStats getSummaryForAllUid(
                int transport, int oemManaged, long startMillis, long endMillis) {
            final android.app.usage.NetworkStats publicNonTaggedStats =
                    mNetworkStatsManager.querySummary(buildNetworkTemplate(transport, oemManaged),
                            startMillis, endMillis);
            final NetworkStats nonTaggedStats =
                    NetworkStatsUtils.fromPublicNetworkStats(publicNonTaggedStats);
            final android.app.usage.NetworkStats publicTaggedStats =
                    mNetworkStatsManager.queryTaggedSummary(
                            buildNetworkTemplate(transport, oemManaged),
                            startMillis, endMillis);
            final NetworkStats taggedStats = NetworkStatsUtils.fromPublicNetworkStats(
                    publicTaggedStats);
            return taggedStats.add(nonTaggedStats);
        }
    }

    /**
     * Returns the network layer usage summary per UID for traffic for given transport and
     * oemManaged values.
     *
     * Note that while non-tagged statistics is returned to represent
     * the overall traffic, tagged data is also attached to provide mode detail of tagging
     * information.
     *
     * @param transport - one of TRANSPORT_* constants defined above.
     * @param oemManaged - one of OEM_MANAGED_* constants defined above.
     * @param startMillis - returns netstats starting from this timestamp (wall time).
     * @param endMillis - returns netstats ending to this timestamp (wall time).
     * @param callingPackage - calling package name for permission checks.
     */
    @NonNull
    public NetworkStats getSummaryForAllUid(
            int transport,
            int oemManaged,
            long startMillis,
            long endMillis,
            @NonNull String callingPackage)
            throws RemoteException {
        return mDeps.getSummaryForAllUid(transport, oemManaged, startMillis, endMillis);
    }

    private static NetworkTemplate buildNetworkTemplate(int transport, int oemManaged) {
        return new NetworkTemplate.Builder(getMatchForTransport(transport)).setOemManaged(
                getTemplateOemManaged(oemManaged)).build();
    }

    /** Converts the transport value to the {@link NetworkTemplate} MATCH_* value. */
    private static int getMatchForTransport(int transport) {
        switch (transport) {
            case TRANSPORT_CELLULAR:
                return MATCH_MOBILE;
            case TRANSPORT_WIFI:
                return MATCH_WIFI;
            case TRANSPORT_BLUETOOTH:
                return MATCH_BLUETOOTH;
            case TRANSPORT_ETHERNET:
                return MATCH_ETHERNET;
            default:
                throw new IllegalArgumentException("Unsupported transport " + transport);
        }
    }

    /**
     * Converts {@link NetworkStatsServiceHelper} oemManaged value to the {@link NetworkTemplate}
     * OEM_MANAGED_* value.
     */
    private static int getTemplateOemManaged(int oemManaged) {
        switch (oemManaged) {
            case OEM_MANAGED_ALL:
                return NetworkTemplate.OEM_MANAGED_ALL;
            case OEM_MANAGED_NO:
                return NetworkTemplate.OEM_MANAGED_NO;
            case OEM_MANAGED_YES:
                return NetworkTemplate.OEM_MANAGED_YES;
            default:
                throw new IllegalArgumentException("Unsupported oemManaged " + oemManaged);
        }
    }
}
