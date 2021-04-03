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

import static com.google.android.car.networking.preferenceupdater.components.OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_ARRAY;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.OemNetworkPreferences;
import android.net.OemNetworkPreferences.OemNetworkPreference;
import android.util.SparseArray;

import java.util.Set;

/** This class will abstragate storage where OEM network policies are persisted */
public final class PersonalStorage {
    private static final String KEY_PREFERENCE_APP = "key_preference_app";

    private static final String KEY_REAPPLY_PANS_ON_BOOT_COMPLETE =
            "key_reapply_pans_on_boot_complete";

    private final SharedPreferences mSharedPrefs;
    private final Context mContext;

    public PersonalStorage(Context ctx) {
        mContext = ctx;
        mSharedPrefs = ctx.getSharedPreferences(KEY_PREFERENCE_APP, Context.MODE_PRIVATE);
    }

    public Set<String> get(@OemNetworkPreference int type) {
        return mSharedPrefs.getStringSet(
                OemNetworkPreferences.oemNetworkPreferenceToString(type), getPrefApps(type));
    }

    public void store(SparseArray<Set<String>> preference) {
        SharedPreferences.Editor editor = mSharedPrefs.edit();
        for (int type : OEM_NETWORK_PREFERENCE_ARRAY) {
            if (preference.contains(type)) {
                editor.putStringSet(
                        OemNetworkPreferences.oemNetworkPreferenceToString(type),
                        preference.get(type));
            }
        }
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

    public SparseArray<Set<String>> getAllPrefApps() {
        SparseArray<Set<String>> prefs = new SparseArray<>();
        for (int type : OEM_NETWORK_PREFERENCE_ARRAY) {
            prefs.put(type, getPrefApps(type));
        }
        return prefs;
    }

    /** This will reset values to default and save it to the personal storage. */
    public void resetNetworkPreferences() {
        SparseArray<Set<String>> prefs = new SparseArray<>();
        for (int type : OEM_NETWORK_PREFERENCE_ARRAY) {
            prefs.put(type, OemAppsManager.getDefaultAppsFor(mContext, type));
        }
        store(prefs);
        saveReapplyPansOnBootCompleteState(false);
    }

    private Set<String> getPrefApps(@OemNetworkPreference int type) {
        return mSharedPrefs.getStringSet(
                OemNetworkPreferences.oemNetworkPreferenceToString(type),
                OemAppsManager.getDefaultAppsFor(mContext, type));
    }
}
