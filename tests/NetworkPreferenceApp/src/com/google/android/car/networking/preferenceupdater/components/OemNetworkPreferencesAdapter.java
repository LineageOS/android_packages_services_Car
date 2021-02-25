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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.OemNetworkPreferences;
import android.util.Log;
import android.util.SparseArray;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Class wraps OemNetworkPreferences (PANS) APIs and routine around it */
public final class OemNetworkPreferencesAdapter {
    private static final String TAG = OemNetworkPreferencesAdapter.class.getSimpleName();

    // Seconds to wait for setOemNetworkPreference() call to complete
    private static final int PANS_CALL_TIMEOUT_SEC = 5;

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

    public static final int OEM_NETWORK_PREFERENCE_OEM_PAID =
            android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID;
    public static final int OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK =
            android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK;
    public static final int OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY =
            android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY;
    public static final int OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY =
            android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY;

    public OemNetworkPreferencesAdapter(Context context) {
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
    }

    /**
     * Accepts mapping <OemNetworkPreference.<id>> -> <Set of applications> and calls PANS APIs to
     * apply them.
     */
    public void applyPreference(@Nullable SparseArray<Set<String>> preference) {
        Log.d(TAG, "Applying new OEM Network Preferences ...");
        OemNetworkPreferences.Builder builder = new OemNetworkPreferences.Builder();
        addPreference(OEM_NETWORK_PREFERENCE_OEM_PAID, builder, preference);
        addPreference(OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK, builder, preference);
        addPreference(OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY, builder, preference);
        addPreference(OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY, builder, preference);
        // We want to create listener and wait for the call to end before proceeding further.
        // To address that better, we will use CompletableFuture.
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        mConnectivityManager.setOemNetworkPreference(
                builder.build(), r -> r.run(), () -> future.complete(true));
        // We don't want to be blocked and wait for results forver, thus we will wait for 5 seconds
        // before cancelling future.
        try {
            future.get(PANS_CALL_TIMEOUT_SEC, TimeUnit.SECONDS);
            Log.d(TAG, "New OEM Network Preferences are now applied.");
        } catch (Exception ex) {
            /**
             * From 4 potential exceptions: - CancellationException - if this future was cancelled -
             * ExecutionException - if this future completed exceptionally - InterruptedException -
             * if the current thread was interrupted while waiting - TimeoutException - if the wait
             * timed out For now since we are not handling exceptions customly, we simply print the
             * exception and silence it. Might consider popping message or something if this
             * happens.
             */
            Log.e(TAG, "Call into setOemNetworkPreference() has failed with exception", ex);
        }
    }

    /**
     * This should reset the OEM Network preferences set by this application or by any other
     * application which calls into setOemNetworkPreferences() API.
     */
    public void resetNetworkPreferences() {
        // Considering that applyPreference will call into setOemNetworkPreference() all we need
        // is to pass null and it will delete PersonalStorage data and will reset PANS.
        applyPreference(null);
    }

    private void addPreference(
            int prefId,
            @Nullable OemNetworkPreferences.Builder builder,
            @NonNull SparseArray<Set<String>> preference) {
        if (preference != null && preference.contains(prefId)) {
            Set<String> apps = preference.get(prefId);
            for (String app : apps) {
                builder.addNetworkPreference(app, prefId);
            }
        }
    }
}
