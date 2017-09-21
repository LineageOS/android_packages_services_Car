/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.google.android.car.kitchensink.storagelifetime;

import android.annotation.Nullable;
import android.car.Car;
import android.car.storagemonitoring.CarStorageMonitoringManager;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;

public class StorageLifetimeFragment extends Fragment {
    private static final String TAG = "CAR.STORAGELIFETIME.KS";

    private KitchenSinkActivity mActivity;
    private TextView mStorageWearInfo;
    private CarStorageMonitoringManager mStorageManager;

    // TODO(egranata): put this somewhere more useful than KitchenSink
    private static String preEolToString(int preEol) {
        switch (preEol) {
            case 1: return "normal";
            case 2: return "warning";
            case 3: return "urgent";
            default:
                return "unknown";
        }
    }

    @Nullable
    @Override
    public View onCreateView(
            LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.storagewear, container, false);
        mActivity = (KitchenSinkActivity) getHost();
        mStorageWearInfo = (TextView) view.findViewById(R.id.storage_wear_info);
        return view;
    }

    private void reloadInfo() {
        try {
            mStorageManager =
                (CarStorageMonitoringManager) mActivity.getCar().getCarManager(
                        Car.STORAGE_MONITORING_SERVICE);

            mStorageWearInfo.setText("Wear estimate: " +
                mStorageManager.getWearEstimate() + "\nPre EOL indicator: " +
                preEolToString(mStorageManager.getPreEolIndicatorStatus()));
        } catch (android.car.CarNotConnectedException|
                 android.support.car.CarNotConnectedException e) {
            Log.e(TAG, "Car not connected or not supported", e);
        }

    }

    @Override
    public void onResume() {
        super.onResume();
        reloadInfo();
    }
}
