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
package com.google.android.car.networking.preferenceupdater.fragments;

import android.content.Context;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.fragment.app.Fragment;

import com.google.android.car.networking.preferenceupdater.R;
import com.google.android.car.networking.preferenceupdater.components.OemNetworkPreferencesWrapper;
import com.google.android.car.networking.preferenceupdater.components.PersonalStorage;
import com.google.android.car.networking.preferenceupdater.utils.Utils;

import java.util.Set;

public final class ManagerFragment extends Fragment {
    private PersonalStorage mPersonalStorage;
    private OemNetworkPreferencesWrapper mOemNetworkPreferencesWrapper;

    private EditText mOEMPaidAppsEditText;
    private EditText mOEMPaidNoFallbackAppsEditText;
    private EditText mOEMPaidOnlyAppsEditText;
    private EditText mOEMPrivateOnlyAppsEditText;
    private Button mApplyConfigurationBtn;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.manager, container, false);
        Context context = getActivity();

        mPersonalStorage = new PersonalStorage(context);
        mOemNetworkPreferencesWrapper = new OemNetworkPreferencesWrapper(context);

        defineViewsFromFragment(v);
        defineButtonActions();
        setDefaultValues();

        return v;
    }

    /** Finds all views on the fragments and stores them in instance variables */
    private void defineViewsFromFragment(View v) {
        mOEMPaidAppsEditText = v.findViewById(R.id.OEMPaidAppsEditText);
        mOEMPaidNoFallbackAppsEditText = v.findViewById(R.id.OEMPaidNoFallbackAppsEditText);
        mOEMPaidOnlyAppsEditText = v.findViewById(R.id.OEMPaidOnlyAppsEditText);
        mOEMPrivateOnlyAppsEditText = v.findViewById(R.id.OEMPrivateOnlyAppsEditText);
        mApplyConfigurationBtn = v.findViewById(R.id.applyConfigurationBtn);
    }

    /** Defines actions of the buttons on the page */
    private void defineButtonActions() {
        mApplyConfigurationBtn.setOnClickListener(view -> onApplyConfigurationBtnClick());
    }

    /** Sets default values of text fields */
    private void setDefaultValues() {
        mOEMPaidAppsEditText.setText(
                getFromStorage(OemNetworkPreferencesWrapper.OEM_NETWORK_PREFERENCE_OEM_PAID));
        mOEMPaidNoFallbackAppsEditText.setText(
                getFromStorage(
                        OemNetworkPreferencesWrapper.OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK));
        mOEMPaidOnlyAppsEditText.setText(
                getFromStorage(OemNetworkPreferencesWrapper.OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY));
        mOEMPrivateOnlyAppsEditText.setText(
                getFromStorage(
                        OemNetworkPreferencesWrapper.OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY));
    }

    private String getFromStorage(@OemNetworkPreferencesWrapper.Type int type) {
        return Utils.toString(mPersonalStorage.get(type));
    }

    private void onApplyConfigurationBtnClick() {
        SparseArray<Set<String>> preference = new SparseArray<>();
        preference.put(
                OemNetworkPreferencesWrapper.OEM_NETWORK_PREFERENCE_OEM_PAID,
                Utils.toSet(mOEMPaidAppsEditText.getText().toString()));
        preference.put(
                OemNetworkPreferencesWrapper.OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK,
                Utils.toSet(mOEMPaidNoFallbackAppsEditText.getText().toString()));
        preference.put(
                OemNetworkPreferencesWrapper.OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY,
                Utils.toSet(mOEMPaidOnlyAppsEditText.getText().toString()));
        preference.put(
                OemNetworkPreferencesWrapper.OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY,
                Utils.toSet(mOEMPrivateOnlyAppsEditText.getText().toString()));

        mOemNetworkPreferencesWrapper.applyPreference(preference);

        // Persist latest preference
        mPersonalStorage.store(preference);
    }
}
