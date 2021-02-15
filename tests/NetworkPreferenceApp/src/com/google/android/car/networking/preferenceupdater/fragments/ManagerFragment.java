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
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.fragment.app.Fragment;

import com.google.android.car.networking.preferenceupdater.R;
import com.google.android.car.networking.preferenceupdater.components.CarDriverDistractionManagerAdapter;
import com.google.android.car.networking.preferenceupdater.components.OemNetworkPreferencesAdapter;
import com.google.android.car.networking.preferenceupdater.components.PersonalStorage;
import com.google.android.car.networking.preferenceupdater.utils.Utils;

import java.util.Set;

public final class ManagerFragment extends Fragment {
    private static final String TAG = ManagerFragment.class.getSimpleName();

    private PersonalStorage mPersonalStorage;
    private OemNetworkPreferencesAdapter mOemNetworkPreferencesAdapter;
    private CarDriverDistractionManagerAdapter mCarDriverDistractionManagerAdapter;

    private EditText mOEMPaidAppsEditText;
    private EditText mOEMPaidNoFallbackAppsEditText;
    private EditText mOEMPaidOnlyAppsEditText;
    private EditText mOEMPrivateOnlyAppsEditText;
    private TextView mCurrentPANSStatusTextView;
    private ToggleButton mReapplyPANSOnBootToggleButton;
    private Button mApplyConfigurationBtn;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.manager, container, false);
        Context context = getActivity();

        mPersonalStorage = new PersonalStorage(context);
        mOemNetworkPreferencesAdapter = new OemNetworkPreferencesAdapter(context);
        mCarDriverDistractionManagerAdapter = new CarDriverDistractionManagerAdapter(context);

        defineViewsFromFragment(v);
        defineButtonActions();
        setDefaultValues();

        // When we start app for the first time, means it never applied any PANS policies.
        // Set the text to false.
        updatePansPolicyInEffectStatus(false);

        return v;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mCarDriverDistractionManagerAdapter.destroy();
    }

    /** Finds all views on the fragments and stores them in instance variables */
    private void defineViewsFromFragment(View v) {
        mOEMPaidAppsEditText = v.findViewById(R.id.OEMPaidAppsEditText);
        mOEMPaidNoFallbackAppsEditText = v.findViewById(R.id.OEMPaidNoFallbackAppsEditText);
        mOEMPaidOnlyAppsEditText = v.findViewById(R.id.OEMPaidOnlyAppsEditText);
        mOEMPrivateOnlyAppsEditText = v.findViewById(R.id.OEMPrivateOnlyAppsEditText);
        mCurrentPANSStatusTextView = v.findViewById(R.id.currentPANSStatusTextView);
        mReapplyPANSOnBootToggleButton = v.findViewById(R.id.reapplyPANSOnBootToggleButton);
        mApplyConfigurationBtn = v.findViewById(R.id.applyConfigurationBtn);
    }

    /** Defines actions of the buttons on the page */
    private void defineButtonActions() {
        mApplyConfigurationBtn.setOnClickListener(view -> onApplyConfigurationBtnClick());
        mReapplyPANSOnBootToggleButton.setOnCheckedChangeListener(
                (buttonView, isChecked) ->
                        mPersonalStorage.saveReapplyPansOnBootCompleteState(true));
    }

    /** Sets default values of text fields */
    private void setDefaultValues() {
        mOEMPaidAppsEditText.setText(
                getFromStorage(OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_OEM_PAID));
        mOEMPaidNoFallbackAppsEditText.setText(
                getFromStorage(
                        OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK));
        mOEMPaidOnlyAppsEditText.setText(
                getFromStorage(OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY));
        mOEMPrivateOnlyAppsEditText.setText(
                getFromStorage(
                        OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY));
        mReapplyPANSOnBootToggleButton.setChecked(
                mPersonalStorage.getReapplyPansOnBootCompleteState());
    }

    private String getFromStorage(@OemNetworkPreferencesAdapter.Type int type) {
        return Utils.toString(mPersonalStorage.get(type));
    }

    // TODO(178245727): This should be updated once ag/13587171 merged.
    private void updatePansPolicyInEffectStatus(boolean status) {
        // Whenever we apply PANS logic, we save it to the PersonalStorage. Meaning we can use
        // PersonalStorage as the check for having any policy set or not.
        mCurrentPANSStatusTextView.setText(status ? "Yes" : "No");
    }

    private void onApplyConfigurationBtnClick() {
        Log.i(TAG, "[NetworkPreferenceApp] PANS Policy was applied!");
        // First we want to make sure that we are allowed to change
        if (!mCarDriverDistractionManagerAdapter.allowedToBeDistracted()) {
            // We are not allowed to apply PANS changes. Do nothing.
            Log.w(TAG, "Network preferences will not be updated, due to Driver Distraction Mode");
            return;
        }
        SparseArray<Set<String>> preference = new SparseArray<>();
        preference.put(
                OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_OEM_PAID,
                Utils.toSet(mOEMPaidAppsEditText.getText().toString()));
        preference.put(
                OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK,
                Utils.toSet(mOEMPaidNoFallbackAppsEditText.getText().toString()));
        preference.put(
                OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY,
                Utils.toSet(mOEMPaidOnlyAppsEditText.getText().toString()));
        preference.put(
                OemNetworkPreferencesAdapter.OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY,
                Utils.toSet(mOEMPrivateOnlyAppsEditText.getText().toString()));

        mOemNetworkPreferencesAdapter.applyPreference(preference);

        // Persist latest preference
        mPersonalStorage.store(preference);

        // Notify app that PANS policy is now in effect
        updatePansPolicyInEffectStatus(true);
    }
}
