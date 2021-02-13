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
package com.google.android.car.networking.preferenceupdater.components;

import android.annotation.IntDef;
import android.content.Context;
import android.net.ConnectivityManager;
import android.util.SparseArray;
// import android.net.OemNetworkPreferences;

import java.util.Set;

/** Class wraps OemNetworkPreferences (PANS) APIs and routine around it */
public final class OemNetworkPreferencesAdapter {
    private ConnectivityManager mConnectivityManager;

    // Convert constants from OemNetworkPreferences into enum
    @IntDef(
            prefix = {"OEM_NETWORK_PREFERENCE_"},
            value = {
                OEM_NETWORK_PREFERENCE_OEM_PAID,
                OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK,
                OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY,
                OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY,
            })
    public @interface Type {}

    // Uncomment origins once OemNetworkPreferences class is exposed.
    public static final int OEM_NETWORK_PREFERENCE_OEM_PAID = 1;
    //        android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID;
    public static final int OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK = 2;
    //        android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK;
    public static final int OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY = 3;
    //        android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY;
    public static final int OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY = 4;
    //        android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY;

    public OemNetworkPreferencesAdapter(Context context) {
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
    }

    public void applyPreference(SparseArray<Set<String>> preference) {
        /*
        OemNetworkPreferences.Builder prefsBuilder = new OemNetworkPreferences.Builder();
        prefsBuilder.addNetworkPreference(
                OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY, oemPaidApps);
        prefsBuilder.addNetworkPreference(
                OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY, oemPrivateApps);
        mConnectivityManager.setOemNetworkPreference(prefsBuilder.build());
        */
    }
}
