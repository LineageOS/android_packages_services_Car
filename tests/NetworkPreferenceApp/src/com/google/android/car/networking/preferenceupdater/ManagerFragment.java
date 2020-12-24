/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.google.android.car.networking.preferenceupdater;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.fragment.app.Fragment;

import java.util.HashSet;
import java.util.List;

public final class ManagerFragment extends Fragment {
    private static final String TAG = ManagerFragment.class.getSimpleName();
    private static final String KEY_PREFERENCE_APP = "key_preference_app";
    private static final String KEY_OEM_PAID_APPS_LIST = "key_oem_paid_apps_list";
    private static final String KEY_OEM_PRIVATE_APPS_LIST = "key_oem_private_apps_list";

    private EditText mOEMInternalEditText;
    private EditText mOEMPaidEditText;

    private Button mApplyConfigurationBtn;

    private List<String> mOEMPaidAppsList;
    private List<String> mOEMPrivateAppsList;

    private SharedPreferences mSharedPrefs;

    private ConnectivityManager mConnectivityManager;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.manager, container, false);
        Context context = getActivity();
        mSharedPrefs = context.getSharedPreferences(KEY_PREFERENCE_APP, Context.MODE_PRIVATE);
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);

        loadNetworkPreferences();
        defineViewsFromFragment(v);
        defineButtonActions();
        setDefaultValues();

        return v;
    }

    /** Loads network preferences from SharedPreferences and saves them in instance variables */
    private void loadNetworkPreferences() {
        mOEMPaidAppsList = Utils.toList(
                mSharedPrefs.getStringSet(KEY_OEM_PAID_APPS_LIST, new HashSet<String>()));
        mOEMPrivateAppsList = Utils.toList(
                mSharedPrefs.getStringSet(KEY_OEM_PRIVATE_APPS_LIST, new HashSet<String>()));
    }

    /** Finds all views on the fragments and stores them in instance variables */
    private void defineViewsFromFragment(View v) {
        mOEMPaidEditText = v.findViewById(R.id.OEMPaidEditText);
        mOEMInternalEditText = v.findViewById(R.id.OEMInternalEditText);
        mApplyConfigurationBtn = v.findViewById(R.id.applyConfigurationBtn);
    }

    /** Defines actions of the buttons on the page */
    private void defineButtonActions() {
        mApplyConfigurationBtn.setOnClickListener(view -> onApplyConfigurationBtnClick());
    }

    /** Sets default values of text fields */
    private void setDefaultValues() {
        mOEMPaidEditText.setText(Utils.toString(mOEMPaidAppsList));
        mOEMInternalEditText.setText(Utils.toString(mOEMPrivateAppsList));
    }

    /** Persists OEM Network Preferences in SharedPreferences */
    private void persistOEMNetworkPreferences() {
        mOEMPaidAppsList = Utils.toList(mOEMPaidEditText.getText().toString());
        mOEMPrivateAppsList = Utils.toList(mOEMInternalEditText.getText().toString());
        SharedPreferences.Editor editor = mSharedPrefs.edit();
        editor.putStringSet(KEY_OEM_PAID_APPS_LIST, Utils.toSet(mOEMPaidAppsList));
        editor.putStringSet(KEY_OEM_PRIVATE_APPS_LIST, Utils.toSet(mOEMPrivateAppsList));
        editor.apply();
    }

    // TODO(serikb): Uncomment when PANS released
    // private OemNetworkPreferences generateOEMNetworkPReferences() {
    //     OemNetworkPreferences.Builder prefsBuilder = new OemNetworkPreferences.Builder();
    //     prefsBuilder.addNetworkPreference(
    //             OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY, mOEMPaidAppsList);
    //     prefsBuilder.addNetworkPreference(
    //             OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY,
    //             mOEMPrivateAppsList);
    //     return prefsBuilder.build();
    // }

    private void onApplyConfigurationBtnClick() {
        Log.d(TAG, "Applying PANS...");

        // Step 1. Generate OemNetworkPreferences for both paid and internal
        // TODO(serikb): Uncomment when PANS released
        // OemNetworkPreferences prefs = generateOEMNetworkPReferences();

        // Step 2. Make a SystemAPI call
        // TODO(serikb): Uncomment when PANS released
        // mConnectivityManager.setOemNetworkPreference(prefs);

        // Step 3. If Step 2 succeeded, we need to persist that configuration in SharedPreferences
        persistOEMNetworkPreferences();
    }
}
