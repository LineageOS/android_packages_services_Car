/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.car.kitchensink.connectivity;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.google.android.car.kitchensink.R;

import java.util.ArrayList;

@SuppressLint("SetTextI18n")
public class ConnectivityFragment extends Fragment {
    private static final String TAG = ConnectivityFragment.class.getSimpleName();

    private final Handler mHandler = new Handler();
    private final ArrayList<String> mNetworks = new ArrayList<>();

    private ConnectivityManager mConnectivityManager;
    private ArrayAdapter<String> mNetworksAdapter;

    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            showToast("onAvailable, netId: " + network);
            refreshNetworks();
        }

        @Override
        public void onLost(Network network) {
            showToast("onLost, netId: " + network);
            refreshNetworks();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mConnectivityManager = getActivity().getSystemService(ConnectivityManager.class);

        mConnectivityManager.addDefaultNetworkActiveListener(() -> refreshNetworks());
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.connectivity_fragment, container, false);

        ListView networksView = view.findViewById(R.id.networks);
        mNetworksAdapter = new ArrayAdapter<>(getActivity(), R.layout.list_item, mNetworks);
        networksView.setAdapter(mNetworksAdapter);

        setClickAction(view, R.id.networksRefresh, this::refreshNetworks);
        setClickAction(view, R.id.networkRequestOemPaid, this::requestOemPaid);
        setClickAction(view, R.id.networkRequestEth1, this::requestEth1);
        setClickAction(view, R.id.networkReleaseNetwork, this::releaseNetworkRequest);

        return view;
    }

    private void releaseNetworkRequest() {
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        showToast("Release request sent");
    }

    private void requestEth1() {
        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .setNetworkSpecifier("eth1")
                .build();
        mConnectivityManager.requestNetwork(request, mNetworkCallback, mHandler);
    }

    private void requestOemPaid() {
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID)
                .build();

        mConnectivityManager.requestNetwork(request, mNetworkCallback, mHandler);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshNetworks();
    }

    private void setClickAction(View view, int id, Runnable action) {
        view.findViewById(id).setOnClickListener(v -> action.run());
    }

    private void refreshNetworks() {
        mNetworks.clear();

        for (Network network : mConnectivityManager.getAllNetworks()) {
            boolean isDefault = sameNetworkId(network, mConnectivityManager.getActiveNetwork());
            NetworkCapabilities nc = mConnectivityManager.getNetworkCapabilities(network);
            boolean isOemPaid = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID);
            boolean isInternet = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

            NetworkInfo networkInfo = mConnectivityManager.getNetworkInfo(network);

            mNetworks.add("netId: " + network.netId
                    + (isInternet ? " [INTERNET]" : "")
                    + (isDefault ? " [DEFAULT]" : "")
                    + (isOemPaid ? " [OEM-paid]" : "") + nc + " " + networkInfo);
        }

        mNetworksAdapter.notifyDataSetChanged();
    }

    private void showToast(String text) {
        Log.d(TAG, "showToast: " + text);
        Toast.makeText(getContext(), text, Toast.LENGTH_LONG).show();
    }

    private static boolean sameNetworkId(Network net1, Network net2) {
        return net1 != null && net2 != null && net1.netId == net2.netId;

    }
}
