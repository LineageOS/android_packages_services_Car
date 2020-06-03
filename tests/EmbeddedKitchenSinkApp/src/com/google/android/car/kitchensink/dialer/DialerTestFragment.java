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
package com.google.android.car.kitchensink.dialer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.internal.telecom.IInCallService;

import com.google.android.car.kitchensink.R;

/**
 * Test CarDialerApp and InCallService implementations
 */
public class DialerTestFragment extends Fragment {
    private static final String TAG = "DialerTestFragment";
    private IInCallService mService;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "connected");
            mService = IInCallService.Stub.asInterface(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "disconnected");
            mService = null;
        }
    };

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.dialer_test, container, false);

        // OnClick: Binds kitchensink to InCallService
        Button bindButton = view.findViewById(R.id.bind_btn);
        bindButton.setOnClickListener((v) -> {
            try {
                Log.d(TAG, "bind");
                Intent intent = new Intent("android.telecom.InCallService");
                intent.setPackage("com.android.car.dialer");
                getActivity().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
        });

        // OnClick: Trigger InCallService#onBringToFront
        Button bringToFrontButton = view.findViewById(R.id.bring_to_front_btn);
        bringToFrontButton.setOnClickListener((v) -> {
            Log.d(TAG, "bringToFront");
            TelecomManager manager = (TelecomManager) getContext().getSystemService(
                    Context.TELECOM_SERVICE);
            manager.showInCallScreen(true);
        });

        return view;
    }
}
