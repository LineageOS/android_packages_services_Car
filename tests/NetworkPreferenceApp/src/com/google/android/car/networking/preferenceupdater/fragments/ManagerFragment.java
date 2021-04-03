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

import static android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID;
import static android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK;
import static android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY;
import static android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY;

import android.content.Context;
import android.net.NetworkIdentity;
import android.net.NetworkTemplate;
import android.net.OemNetworkPreferences.OemNetworkPreference;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
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
import com.google.android.car.networking.preferenceupdater.components.MetricDisplay;
import com.google.android.car.networking.preferenceupdater.components.OemNetworkPreferencesAdapter;
import com.google.android.car.networking.preferenceupdater.components.PersonalStorage;
import com.google.android.car.networking.preferenceupdater.utils.Utils;

import java.util.Set;

public final class ManagerFragment extends Fragment {
    private static final String TAG = ManagerFragment.class.getSimpleName();

    public static final String METRIC_MSG_OEM_PREFERENCE_KEY = "oem_preference";
    public static final String METRIC_MSG_OEM_PREFERENCE_TX_KEY = "oem_preference_tx";
    public static final String METRIC_MSG_OEM_PREFERENCE_RX_KEY = "oem_preference_rx";

    private PersonalStorage mPersonalStorage;
    private OemNetworkPreferencesAdapter mOemNetworkPreferencesAdapter;
    private CarDriverDistractionManagerAdapter mCarDriverDistractionManagerAdapter;

    // Metric Display components
    private MetricDisplay mMetricDisplay;
    private TextView mOemPaidRxBytesTextView;
    private TextView mOemPaidTxBytesTextView;
    private TextView mOemPrivateRxBytesTextView;
    private TextView mOemPrivateTxBytesTextView;
    private TextView mOemTotalRxBytesTextView;
    private TextView mOemTotalTxBytesTextView;
    // Metric display handler that updates indicators
    public final Handler mMetricMessageHandler =
            new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    Bundle bundle = msg.getData();
                    int oem_preference = bundle.getInt(METRIC_MSG_OEM_PREFERENCE_KEY);
                    long tx = bundle.getLong(METRIC_MSG_OEM_PREFERENCE_TX_KEY);
                    long rx = bundle.getLong(METRIC_MSG_OEM_PREFERENCE_RX_KEY);
                    updateMetricIndicatorByType(oem_preference, tx, rx);
                }
            };

    private EditText mOEMPaidAppsEditText;
    private EditText mOEMPaidNoFallbackAppsEditText;
    private EditText mOEMPaidOnlyAppsEditText;
    private EditText mOEMPrivateOnlyAppsEditText;
    private TextView mCurrentPANSStatusTextView;
    private ToggleButton mReapplyPANSOnBootToggleButton;
    private Button mApplyConfigurationBtn;
    private Button mResetNetworkPreferencesBtn;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.manager, container, false);
        Context context = getActivity();

        mPersonalStorage = new PersonalStorage(context);
        mOemNetworkPreferencesAdapter = new OemNetworkPreferencesAdapter(context);
        mCarDriverDistractionManagerAdapter = new CarDriverDistractionManagerAdapter(context);
        mMetricDisplay = new MetricDisplay(context, mMetricMessageHandler);

        defineViewsFromFragment(v);
        defineButtonActions();
        setDefaultValues();

        // When we start app for the first time, means it never applied any PANS policies.
        // Set the text to false.
        updatePansPolicyInEffectStatus(false);

        // Let's start watching OEM traffic and updating indicators
        mMetricDisplay.startWatching();

        return v;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mMetricDisplay.stopWatching();
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
        mResetNetworkPreferencesBtn = v.findViewById(R.id.resetNetworkPreferencesBtn);
        // Since our Metric Display is going to be alive, we want to pass our TextView components
        // into MetricDisplay instance to simplify refresh logic.
        mOemPaidRxBytesTextView = v.findViewById(R.id.oemPaidRxBytesTextView);
        mOemPaidTxBytesTextView = v.findViewById(R.id.oemPaidTxBytesTextView);
        mOemPrivateRxBytesTextView = v.findViewById(R.id.oemPrivateRxBytesTextView);
        mOemPrivateTxBytesTextView = v.findViewById(R.id.oemPrivateTxBytesTextView);
        mOemTotalRxBytesTextView = v.findViewById(R.id.totalOemManagedRxBytesTextView);
        mOemTotalTxBytesTextView = v.findViewById(R.id.totalOemManagedTxBytesTextView);
        mMetricDisplay.setMainActivity(this);
    }

    private void updateMetricIndicatorByType(int type, long tx, long rx) {
        switch (type) {
            case NetworkIdentity.OEM_PAID:
                mOemPaidTxBytesTextView.setText("" + tx);
                mOemPaidRxBytesTextView.setText("" + rx);
                break;
            case NetworkIdentity.OEM_PRIVATE:
                mOemPrivateTxBytesTextView.setText("" + tx);
                mOemPrivateRxBytesTextView.setText("" + rx);
                break;
            case NetworkTemplate.OEM_MANAGED_YES:
                mOemTotalTxBytesTextView.setText("" + tx);
                mOemTotalRxBytesTextView.setText("" + rx);
                break;
            default:
                Log.e(TAG, "Unknown NetworkIdentity " + type);
        }
    }

    /** Defines actions of the buttons on the page */
    private void defineButtonActions() {
        mApplyConfigurationBtn.setOnClickListener(view -> onApplyConfigurationBtnClick());
        mReapplyPANSOnBootToggleButton.setOnCheckedChangeListener(
                (buttonView, isChecked) ->
                        mPersonalStorage.saveReapplyPansOnBootCompleteState(true));
        mResetNetworkPreferencesBtn.setOnClickListener(view -> resetNetworkPreferences());
    }

    private void resetNetworkPreferences() {
        mOemNetworkPreferencesAdapter.resetNetworkPreferences();
        mPersonalStorage.resetNetworkPreferences();
        setDefaultValues();
    }

    /** Sets default values of text fields */
    private void setDefaultValues() {
        mOEMPaidAppsEditText.setText(getFromStorage(OEM_NETWORK_PREFERENCE_OEM_PAID));
        mOEMPaidNoFallbackAppsEditText.setText(
                getFromStorage(OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK));
        mOEMPaidOnlyAppsEditText.setText(getFromStorage(OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY));
        mOEMPrivateOnlyAppsEditText.setText(
                getFromStorage(OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY));
        mReapplyPANSOnBootToggleButton.setChecked(
                mPersonalStorage.getReapplyPansOnBootCompleteState());
    }

    private String getFromStorage(@OemNetworkPreference int type) {
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
                OEM_NETWORK_PREFERENCE_OEM_PAID,
                Utils.toSet(mOEMPaidAppsEditText.getText().toString()));
        preference.put(
                OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK,
                Utils.toSet(mOEMPaidNoFallbackAppsEditText.getText().toString()));
        preference.put(
                OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY,
                Utils.toSet(mOEMPaidOnlyAppsEditText.getText().toString()));
        preference.put(
                OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY,
                Utils.toSet(mOEMPrivateOnlyAppsEditText.getText().toString()));

        mOemNetworkPreferencesAdapter.applyPreference(preference);

        // Persist latest preference
        mPersonalStorage.store(preference);

        // Notify app that PANS policy is now in effect
        updatePansPolicyInEffectStatus(true);
    }
}
