/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.google.android.car.kitchensink.audio;

import static com.google.android.car.kitchensink.audio.AudioUtils.getCurrentZoneId;

import android.car.Car;
import android.car.media.CarAudioManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.car.kitchensink.R;

public final class AudioConfigurationTestFragment extends Fragment {
    public static final String FRAGMENT_NAME = "audio configurations";
    private static final String TAG = "CAR.AUDIO.KS.CONFIGURATION";
    private Context mContext;
    private Car mCar;
    private CarAudioManager mCarAudioManager;
    private ZoneConfigSelectionController mZoneConfigController;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
        Log.i(TAG, "onCreateView");
        View view = inflater.inflate(R.layout.audio_configuration, container, false);
        connectCar(view);

        return view;
    }

    @Override
    public void onDestroyView() {
        Log.i(TAG, "onDestroyView");
        mZoneConfigController.release();
        mCar.disconnect();
        super.onDestroyView();
    }

    private void connectCar(View view) {
        mContext = getContext();
        mCar = Car.createCar(mContext, /* handler= */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER, (car, ready) -> {
                    if (!ready) {
                        return;
                    }
                    mCarAudioManager = (CarAudioManager) car.getCarManager(Car.AUDIO_SERVICE);
                    handleSetUpZoneConfigurationSelection(view);
                    FragmentManager fragmentManager = getChildFragmentManager();
                    AudioPlayersTabControllers.setUpAudioPlayersTab(view, fragmentManager);
                });
    }

    private void handleSetUpZoneConfigurationSelection(View view) {
        try {
            mZoneConfigController = new ZoneConfigSelectionController(view, mCarAudioManager,
                    mContext, getCurrentZoneId(mContext, mCarAudioManager),
                    this::updateDeviceAddressPlayer);
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup car audio zone config selection view", e);
        }
    }

    private void updateDeviceAddressPlayer() {

    }
}
