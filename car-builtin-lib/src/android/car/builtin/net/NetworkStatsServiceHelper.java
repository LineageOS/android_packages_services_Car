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

import static android.net.NetworkStats.DEFAULT_NETWORK_ALL;
import static android.net.NetworkStats.METERED_ALL;
import static android.net.NetworkStats.ROAMING_ALL;
import static android.net.NetworkTemplate.MATCH_BLUETOOTH;
import static android.net.NetworkTemplate.MATCH_ETHERNET;
import static android.net.NetworkTemplate.MATCH_MOBILE_WILDCARD;
import static android.net.NetworkTemplate.MATCH_WIFI_WILDCARD;
import static android.net.NetworkTemplate.NETWORK_TYPE_ALL;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.annotations.GuardedBy;

/**
 * Provides access to {@link INetworkStatsService} calls. It lazily connects to {@link
 * INetworkStatsService} when necessary.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class NetworkStatsServiceHelper {
    // Supported transports by NetworkStatsServiceHelper and underlying NetworkTemplate predicate.
    // The values match androix.net.NetworkCapabilities
    public static final int TRANSPORT_CELLULAR = 0;
    public static final int TRANSPORT_WIFI = 1;
    public static final int TRANSPORT_BLUETOOTH = 2;
    public static final int TRANSPORT_ETHERNET = 3;

    // The values match android.net.NetworkTemplate.OEM_MANAGED_*.
    public static final int OEM_MANAGED_ALL = -1;
    public static final int OEM_MANAGED_NO = 0;  // netstats for OEM not managed networks
    public static final int OEM_MANAGED_YES = -2;  // netstats for any OEM managed networks

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private INetworkStatsService mService;

    /**
     * Returns the network layer usage summary per UID for traffic for given transport and
     * oemManaged values.
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
        // No need to close the session, it just resets the internal state.
        return openSessionForUsageStats(callingPackage)
                .getSummaryForAllUid(
                        buildNetworkTemplate(transport, oemManaged),
                        startMillis,
                        endMillis,
                        /* includeTags= */ true);
    }

    private INetworkStatsSession openSessionForUsageStats(String callingPackage)
            throws RemoteException {
        INetworkStatsService service;
        synchronized (mLock) {
            if (mService == null) {
                mService =
                        INetworkStatsService.Stub.asInterface(
                                ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
            }
            service = mService;
        }
        if (service == null) {
            throw new RemoteException("INetworkStatsService is not ready.");
        }
        return service.openSessionForUsageStats(/* flags= */ 0, callingPackage);
    }

    private NetworkTemplate buildNetworkTemplate(int transport, int oemManaged) {
        return new NetworkTemplate(
                getMatchForTransport(transport),
                /* subscriberId= */ null,
                /* matchSubscriberIds= */ null,
                /* networkId= */ null,
                METERED_ALL,
                ROAMING_ALL,
                DEFAULT_NETWORK_ALL,
                NETWORK_TYPE_ALL,
                getTemplateOemManaged(oemManaged));
    }

    /** Converts the transport value to the {@link NetworkTemplate} MATCH_* value. */
    private int getMatchForTransport(int transport) {
        switch (transport) {
            case TRANSPORT_CELLULAR:
                return MATCH_MOBILE_WILDCARD;
            case TRANSPORT_WIFI:
                return MATCH_WIFI_WILDCARD;
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
    private int getTemplateOemManaged(int oemManaged) {
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
