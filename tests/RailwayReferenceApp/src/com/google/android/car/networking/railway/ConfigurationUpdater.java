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

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.EthernetManager;
import android.net.EthernetNetworkManagementException;
import android.net.EthernetNetworkUpdateRequest;
import android.net.IpConfiguration;
import android.net.LinkAddress;
import android.net.NetworkCapabilities;
import android.net.StaticIpConfiguration;
import android.os.OutcomeReceiver;

import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.List;

public final class ConfigurationUpdater {
    private final Context mApplicationContext;
    private final EthernetManager mEthernetManager;
    private final OutcomeReceiver<String, EthernetNetworkManagementException> mCallback;

    public ConfigurationUpdater(Context applicationContext,
            OutcomeReceiver<String, EthernetNetworkManagementException> callback) {
        mApplicationContext = applicationContext;
        mEthernetManager = applicationContext.getSystemService(EthernetManager.class);

        mCallback = callback;
    }

    public void updateNetworkConfiguration(String packageNames,
            String ipConfigurationText,
            String networkCapabilitiesText,
            String interfaceName)
            throws IllegalArgumentException, PackageManager.NameNotFoundException {

        IpConfiguration ipConfiguration = Strings.isNullOrEmpty(ipConfigurationText) ? null :
                new IpConfiguration.Builder()
                .setStaticIpConfiguration(new StaticIpConfiguration.Builder()
                        .setIpAddress(new LinkAddress(ipConfigurationText)).build())
                .build();

        NetworkCapabilities.Builder networkCapabilitiesBuilder = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET);

        networkCapabilitiesBuilder.setAllowedUids(
                UidToPackageNameConverter.convertToUids(mApplicationContext, packageNames));


        for (int capability : getCapabilitiesList(networkCapabilitiesText)) {
            networkCapabilitiesBuilder.addCapability(capability);
        }

        EthernetNetworkUpdateRequest request = new EthernetNetworkUpdateRequest.Builder()
                .setIpConfiguration(ipConfiguration)
                .setNetworkCapabilities(networkCapabilitiesBuilder.build())
                .build();

        mEthernetManager.updateConfiguration(interfaceName, request,
                mApplicationContext.getMainExecutor(), mCallback);
    }

    private static List<Integer> getCapabilitiesList(String capabilitiesText)
            throws NumberFormatException {
        List<Integer> capabilitiesList = new ArrayList<>();
        String[] capabilitiesTextArray = capabilitiesText.split(",");
        for (String capabilityText : capabilitiesTextArray) {
            capabilitiesList.add(Integer.valueOf(capabilityText));
        }
        return capabilitiesList;
    }
}
