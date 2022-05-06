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

import android.app.AlertDialog;
import android.app.Application;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.EthernetNetworkManagementException;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MainActivity extends FragmentActivity {
    private static final String TAG = MainActivity.class.getName();
    private ConfigurationUpdater mConfigurationUpdater;
    private CurrentEthernetNetworksViewModel mCurrentEthernetNetworksViewModel;

    private final List<Button> mButtons = new ArrayList<>();

    private EditText mAllowedPackageNames;
    private EditText mIpConfiguration;
    private EditText mInterfaceName;
    private EditText mNetworkCapabilities;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAllowedPackageNames = findViewById(R.id.allowedPackageNamesInput);
        mIpConfiguration = findViewById(R.id.ipConfigurationInput);
        mInterfaceName = findViewById(R.id.interfaceNameInput);
        mNetworkCapabilities = findViewById(R.id.networkCapabilitiesInput);

        Button updateButton = findViewById(R.id.updateButton);
        updateButton.setOnClickListener(v -> onUpdateButtonClick());

        mButtons.add(updateButton);
        // TODO(b/225226520): add connectButton to list when its function has been defined
        // TODO(b/225226520): add enable/disableButton to list when implemented

        TextView currentEthernetNetworksOutput = findViewById(R.id.currentEthernetNetworksOutput);

        mCurrentEthernetNetworksViewModel =
                new ViewModelProvider(this,
                        new CurrentEthernetNetworksViewModelFactory(
                                (Application) getApplicationContext(),
                                new NetworkCallback()))
                        .get(CurrentEthernetNetworksViewModel.class);
        mCurrentEthernetNetworksViewModel.getNetworksLiveData().observe(this,
                networkNetworkInfoMap -> currentEthernetNetworksOutput
                        .setText(mCurrentEthernetNetworksViewModel
                                .getCurrentEthernetNetworksText(networkNetworkInfoMap)));

        mConfigurationUpdater = new ConfigurationUpdater(getApplicationContext(),
                new OutcomeReceiver<String, EthernetNetworkManagementException>() {
                    @Override
                    public void onResult(String result) {
                        showDialogWithTitle(result);
                    }

                    @Override
                    public void onError(EthernetNetworkManagementException error) {
                        OutcomeReceiver.super.onError(error);
                        showDialogWithTitle(error.getLocalizedMessage());
                    }
                });
    }

    private void setButtonsEnabled(boolean enabled) {
        for (Button button : mButtons) {
            button.setEnabled(enabled);
        }
    }

    private void showDialogWithTitle(String title) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setOnDismissListener(d -> setButtonsEnabled(true))
                .create();
        dialog.show();
    }

    private void onUpdateButtonClick() {
        setButtonsEnabled(false);
        Log.d(TAG, "configuration update started");
        try {
            mConfigurationUpdater.updateNetworkConfiguration(
                    mAllowedPackageNames.getText().toString(),
                    mIpConfiguration.getText().toString(),
                    mNetworkCapabilities.getText().toString(),
                    mInterfaceName.getText().toString());
        } catch (IllegalArgumentException | PackageManager.NameNotFoundException e) {
            showDialogWithTitle(e.getLocalizedMessage());
        }
    }

    private class NetworkCallback extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            super.onAvailable(network);
            Log.d(TAG, "Network " + network + " available");

            runOnUiThread(() -> mCurrentEthernetNetworksViewModel.updateNetwork(
                            network, /* available= */ true)
            );
        }

        @Override
        public void onLost(Network network) {
            super.onLost(network);
            Log.d(TAG, "Network " + network + " lost");

            runOnUiThread(() -> mCurrentEthernetNetworksViewModel.updateNetwork(
                            network, /* available= */ false)
            );
        }

        @Override
        public void onCapabilitiesChanged(Network network,
                NetworkCapabilities networkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities);
            Log.d(TAG, "Network " + network + " capabilities changed to "
                    + Arrays.toString(networkCapabilities.getCapabilities()));

            runOnUiThread(() -> mCurrentEthernetNetworksViewModel.updateNetwork(
                            network, networkCapabilities)
            );
        }

        @Override
        public void onLinkPropertiesChanged(Network network,
                LinkProperties linkProperties) {
            super.onLinkPropertiesChanged(network, linkProperties);
            Log.d(TAG, "Network " + network + " link properties changed");

            MainActivity.this.runOnUiThread(
                    () -> mCurrentEthernetNetworksViewModel.updateNetwork(
                            network, linkProperties)
            );
        }
    }

    private static class CurrentEthernetNetworksViewModelFactory extends
            ViewModelProvider.AndroidViewModelFactory {
        private final Application mApplication;
        private final ConnectivityManager.NetworkCallback mNetworkCallback;

        private CurrentEthernetNetworksViewModelFactory(
                Application application, ConnectivityManager.NetworkCallback networkCallback) {
            super(application);
            mApplication = application;
            mNetworkCallback = networkCallback;
        }

        @NonNull
        @Override
        public <T extends ViewModel> T create(Class<T> modelClass) {
            return modelClass.cast(
                    new CurrentEthernetNetworksViewModel(mApplication, mNetworkCallback));
        }
    }

}
