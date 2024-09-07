/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.google.android.car.kitchensink.connectivity.datasubscription;

import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.car.datasubscription.DataSubscriptionStatus;

import com.google.android.car.kitchensink.R;

public class DataSubscriptionTestFragment extends Fragment {
    private static final String TAG = DataSubscriptionTestFragment.class.getSimpleName();

    public static final String FRAGMENT_NAME = "DataSubscription";

    public static final String CAR_DATA_SUBSCRIPTION_STATUS = "car_data_subscription_status";

    private TextView mMessage;

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.data_subscription_fragment, container, false);
        mMessage = view.findViewById(R.id.message);
        Button inactiveButton = view.findViewById(R.id.inactive);
        inactiveButton.setOnClickListener(v -> {
            setDataSubscriptionStatus(DataSubscriptionStatus.INACTIVE);
        });

        Button trialButton = view.findViewById(R.id.trial);
        trialButton.setOnClickListener(v -> {
            setDataSubscriptionStatus(DataSubscriptionStatus.TRIAL);
        });

        Button paidButton = view.findViewById(R.id.paid);
        paidButton.setOnClickListener(v -> {
            setDataSubscriptionStatus(DataSubscriptionStatus.PAID);
        });
        return view;
    }

    private void setDataSubscriptionStatus(@DataSubscriptionStatus int dataSubscription) {
        Settings.Global.putInt(getContext().getContentResolver(), CAR_DATA_SUBSCRIPTION_STATUS,
                dataSubscription);
        setMessage(getDataSubscriptionStatus());
    }

    private int getDataSubscriptionStatus() {
        try {
            return Settings.Global.getInt(getContext().getContentResolver(),
                    CAR_DATA_SUBSCRIPTION_STATUS);
        } catch (Settings.SettingNotFoundException e) {
            Log.e(TAG, "Can't get Data Subscription status");
        }
        return -1;
    }

    private void setMessage(@DataSubscriptionStatus int dataSubscription) {
        mMessage.setText(getContext().getResources().getString(R.string.data_subscription_message,
                dataSubscription));
    }
}
