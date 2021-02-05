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
    private EditText mOEMPrivateEditText;
    private EditText mOEMPaidEditText;
    private Button mApplyConfigurationBtn;

    private PersonalStorage mPersonalStorage;
    private OemNetworkPreferencesWrapper mOemNetworkPreferencesWrapper;

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
        mOEMPaidEditText = v.findViewById(R.id.OEMPaidEditText);
        mOEMPrivateEditText = v.findViewById(R.id.OEMPrivateEditText);
        mApplyConfigurationBtn = v.findViewById(R.id.applyConfigurationBtn);
    }

    /** Defines actions of the buttons on the page */
    private void defineButtonActions() {
        mApplyConfigurationBtn.setOnClickListener(view -> onApplyConfigurationBtnClick());
    }

    /** Sets default values of text fields */
    private void setDefaultValues() {
        mOEMPaidEditText.setText(Utils.toString(mPersonalStorage.getOEMPaidAppSet()));
        mOEMPrivateEditText.setText(Utils.toString(mPersonalStorage.getOEMPrivateAppSet()));
    }

    private void onApplyConfigurationBtnClick() {
        Set<String> oemPaidApps = Utils.toSet(mOEMPaidEditText.getText().toString());
        Set<String> oemPrivateApps = Utils.toSet(mOEMPrivateEditText.getText().toString());

        mOemNetworkPreferencesWrapper.applyPolicy(oemPaidApps, oemPrivateApps);

        // Persist latest policy
        mPersonalStorage.saveOEMPaidAppSet(oemPaidApps);
        mPersonalStorage.saveOEMPrivateAppSet(oemPrivateApps);
    }
}
