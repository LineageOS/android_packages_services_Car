/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.google.android.car.networking.railway;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CurrentEthernetNetworksViewModel extends AndroidViewModel {
    private final MutableLiveData<Map<Network, NetworkInfo>> mNetworks = new MutableLiveData<>();
    private final Application mApplication;
    private final ConnectivityManager mConnectivityManager;
    private final ConnectivityManager.NetworkCallback mNetworkCallback;

    public LiveData<Map<Network, NetworkInfo>> getNetworksLiveData() {
        return mNetworks;
    }

    public CurrentEthernetNetworksViewModel(Application application,
            ConnectivityManager.NetworkCallback networkCallback) {
        super(application);

        mApplication = application;
        mNetworkCallback = networkCallback;

        mConnectivityManager  =
                mApplication.getSystemService(ConnectivityManager.class);

        assert mConnectivityManager != null;
        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(
                        NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
        mConnectivityManager.registerNetworkCallback(request, networkCallback);
    }

    @Override
    protected void onCleared() {
        super.onCleared();

        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
    }

    public void updateNetwork(Network network, NetworkCapabilities networkCapabilities) {
        Map<Network, NetworkInfo> networkNetworkInfoMap =
                Objects.requireNonNull(mNetworks.getValue());

        if (networkNetworkInfoMap.containsKey(network)) {
            networkNetworkInfoMap.get(network).mNetworkCapabilities = networkCapabilities;
        } else {
            NetworkInfo networkInfo = new NetworkInfo();
            networkInfo.mNetworkCapabilities = networkCapabilities;
            networkNetworkInfoMap.put(network, networkInfo);
        }

        mNetworks.setValue(networkNetworkInfoMap);
    }

    public void updateNetwork(Network network, LinkProperties linkProperties) {
        Map<Network, NetworkInfo> networkNetworkInfoMap =
                Objects.requireNonNull(mNetworks.getValue());

        if (networkNetworkInfoMap.containsKey(network)) {
            networkNetworkInfoMap.get(network).mLinkProperties = linkProperties;
        } else {
            NetworkInfo networkInfo = new NetworkInfo();
            networkInfo.mLinkProperties = linkProperties;
            networkNetworkInfoMap.put(network, networkInfo);
        }

        mNetworks.setValue(networkNetworkInfoMap);
    }

    public void updateNetwork(Network network, boolean available) {
        Map<Network, NetworkInfo> networkNetworkInfoMap =
                mNetworks.getValue() == null ? new HashMap<>() : mNetworks.getValue();

        if (available && !networkNetworkInfoMap.containsKey(network)) {
            networkNetworkInfoMap.put(network, new NetworkInfo());
        } else if (!available) {
            networkNetworkInfoMap.remove(network);
        }

        mNetworks.setValue(networkNetworkInfoMap);
    }

    public String getCurrentEthernetNetworksText(Map<Network, NetworkInfo> networksMap) {
        StringBuilder sb = new StringBuilder();
        Map<Network, NetworkInfo> networkNetworkInfoMap = Objects.requireNonNull(networksMap);
        for (Network network : networkNetworkInfoMap.keySet()) {
            NetworkInfo networkInfo = networkNetworkInfoMap.get(network);

            if (networkInfo.mLinkProperties != null) {
                sb.append("- ");
                sb.append(networkInfo.mLinkProperties.getInterfaceName());

                sb.append("\n\t");
                sb.append("ip: ");
                sb.append(networkInfo.mLinkProperties.getLinkAddresses().get(
                        0).getAddress().getHostAddress());
            }

            if (networkInfo.mNetworkCapabilities != null) {
                sb.append("\n\t");
                sb.append("capabilities: ");
                sb.append(Arrays.toString(networkInfo.mNetworkCapabilities.getCapabilities()));

                if (networkInfo.mNetworkCapabilities.getAllowedUids().size() > 0) {
                    sb.append("\n\t");
                    sb.append("allowed apps: ");
                    Set<Integer> allowedUids = networkInfo.mNetworkCapabilities.getAllowedUids();
                    sb.append(
                            UidToPackageNameConverter.convertToPackageNames(mApplication,
                                    allowedUids));
                }
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    public static class NetworkInfo {
        NetworkCapabilities mNetworkCapabilities;
        LinkProperties mLinkProperties;
    }
}
