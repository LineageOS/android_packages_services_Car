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

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.fragment.app.Fragment;

public final class ManagerFragment extends Fragment {
    private static final String TAG = ManagerFragment.class.getSimpleName();

    private EditText mOEMInternalEditText;
    private EditText mOEMPaidEditText;

    private Button mApplyConfigurationBtn;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.manager, container, false);

        defineViewsFromFragment(v);

        defineButtonActions();

        return v;
    }

    private void defineViewsFromFragment(View v) {
        mOEMPaidEditText = v.findViewById(R.id.OEMPaidEditText);
        mOEMInternalEditText = v.findViewById(R.id.OEMInternalEditText);
        mApplyConfigurationBtn = v.findViewById(R.id.applyConfigurationBtn);
    }

    private void defineButtonActions() {
        mApplyConfigurationBtn.setOnClickListener(view -> onApplyConfigurationBtnClick());
    }

    private void onApplyConfigurationBtnClick() {
        Log.d(TAG, "Applying PANS...");
        // TODO(b/170911632): Implement PANS API calls
    }
}
