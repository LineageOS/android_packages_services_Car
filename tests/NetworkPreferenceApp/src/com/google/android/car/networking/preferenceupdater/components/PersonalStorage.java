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

import static com.google.android.car.networking.preferenceupdater.components.OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_OEM_PAID;
import static com.google.android.car.networking.preferenceupdater.components.OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK;
import static com.google.android.car.networking.preferenceupdater.components.OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY;
import static com.google.android.car.networking.preferenceupdater.components.OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.ArraySet;
import android.util.SparseArray;

import com.google.android.car.networking.preferenceupdater.R;

import java.util.Arrays;
import java.util.Set;

/** This class will abstragate storage where OEM network policies are persisted */
public final class PersonalStorage {
    private static final String KEY_PREFERENCE_APP = "key_preference_app";

    private static final String KEY_OEM_PAID_APPS = "key_oem_paid_apps";
    private static final String KEY_OEM_PAID_NO_FALLBACK_APPS = "key_oem_paid_no_fallback_apps";
    private static final String KEY_OEM_PAID_ONLY_APPS = "key_oem_paid_only_apps";
    private static final String KEY_OEM_PRIVATE_ONLY_APPS = "key_oem_private_only_apps";
    private static final String KEY_REAPPLY_PANS_ON_BOOT_COMPLETE =
            "key_reapply_pans_on_boot_complete";

    private final Set<String> mDefaultOemPaidApps;
    private final Set<String> mDefaultOemPaidNoFallbackApps;
    private final Set<String> mDefaultOemPaidOnlyApps;
    private final Set<String> mDefaultOemPrivateOnlyApps;

    private SharedPreferences mSharedPrefs;
    private Context mContext;

    public PersonalStorage(Context ctx) {
        mContext = ctx;
        mSharedPrefs = ctx.getSharedPreferences(KEY_PREFERENCE_APP, Context.MODE_PRIVATE);
        // Loading default values, will be used in case there is nothing in SharedPreferences
        mDefaultOemPaidApps = getRes(R.array.config_network_preference_oem_paid_apps);
        mDefaultOemPaidNoFallbackApps =
                getRes(R.array.config_network_preference_oem_paid_no_fallback_apps);
        mDefaultOemPaidOnlyApps = getRes(R.array.config_network_preference_oem_paid_only);
        mDefaultOemPrivateOnlyApps = getRes(R.array.config_network_preference_oem_private_only);
    }

    public Set<String> get(@OemNetworkPreferencesAdapter.Type int type) {
        switch (type) {
            case OEM_NETWORK_PREFERENCE_OEM_PAID:
                return mSharedPrefs.getStringSet(KEY_OEM_PAID_APPS, mDefaultOemPaidApps);
            case OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK:
                return mSharedPrefs.getStringSet(
                        KEY_OEM_PAID_NO_FALLBACK_APPS, mDefaultOemPaidNoFallbackApps);
            case OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY:
                return mSharedPrefs.getStringSet(KEY_OEM_PAID_ONLY_APPS, mDefaultOemPaidOnlyApps);
            case OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY:
                return mSharedPrefs.getStringSet(
                        KEY_OEM_PRIVATE_ONLY_APPS, mDefaultOemPrivateOnlyApps);
            default:
                return null;
        }
    }

    public SparseArray<Set<String>> getAllPrefs() {
        SparseArray<Set<String>> prefs = new SparseArray<>();
        prefs.put(
                OEM_NETWORK_PREFERENCE_OEM_PAID,
                mSharedPrefs.getStringSet(KEY_OEM_PAID_APPS, mDefaultOemPaidApps));
        prefs.put(
                OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK,
                mSharedPrefs.getStringSet(
                        KEY_OEM_PAID_NO_FALLBACK_APPS, mDefaultOemPaidNoFallbackApps));
        prefs.put(
                OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY,
                mSharedPrefs.getStringSet(KEY_OEM_PAID_ONLY_APPS, mDefaultOemPaidOnlyApps));
        prefs.put(
                OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY,
                mSharedPrefs.getStringSet(KEY_OEM_PRIVATE_ONLY_APPS, mDefaultOemPrivateOnlyApps));
        return prefs;
    }

    public void store(SparseArray<Set<String>> preference) {
        SharedPreferences.Editor editor = mSharedPrefs.edit();
        Set<String> st = null;

        st = preference.get(OEM_NETWORK_PREFERENCE_OEM_PAID);
        if (st != null) editor.putStringSet(KEY_OEM_PAID_APPS, st);

        st = preference.get(OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK);
        if (st != null) editor.putStringSet(KEY_OEM_PAID_NO_FALLBACK_APPS, st);

        st = preference.get(OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY);
        if (st != null) editor.putStringSet(KEY_OEM_PAID_ONLY_APPS, st);

        st = preference.get(OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY);
        if (st != null) editor.putStringSet(KEY_OEM_PRIVATE_ONLY_APPS, st);

        editor.apply();
    }

    public void saveReapplyPansOnBootCompleteState(boolean checked) {
        SharedPreferences.Editor editor = mSharedPrefs.edit();
        editor.putBoolean(KEY_REAPPLY_PANS_ON_BOOT_COMPLETE, checked);
        editor.apply();
    }

    public boolean getReapplyPansOnBootCompleteState() {
        return mSharedPrefs.getBoolean(KEY_REAPPLY_PANS_ON_BOOT_COMPLETE, false);
    }

    private ArraySet<String> getRes(int resId) {
        return new ArraySet<String>(Arrays.asList(mContext.getResources().getStringArray(resId)));
    }
}
