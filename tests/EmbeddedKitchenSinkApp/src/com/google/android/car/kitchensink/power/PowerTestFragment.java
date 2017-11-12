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

package com.google.android.car.kitchensink.power;

import static java.lang.Integer.toHexString;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;

import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import java.util.ArrayList;
import java.util.List;

public class PowerTestFragment extends Fragment {
    private final boolean DBG = false;
    private final String TAG = "PowerTestFragment";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstance) {
        View v = inflater.inflate(R.layout.power_test, container, false);

        Button b = v.findViewById(R.id.btnPwrShutdown);
        b.setOnClickListener(this::shutdown);

        b = v.findViewById(R.id.btnPwrSleep);
        b.setOnClickListener(this::sleep);

        if(DBG) {
            Log.d(TAG, "Starting PowerTestFragment");
        }

        return v;
    }

    private void shutdown(View v) {
        if(DBG) {
            Log.d(TAG, "Calling shutdown method");
        }

        PowerManager pm = (PowerManager) getActivity().getSystemService(Context.POWER_SERVICE);
        pm.shutdown(/* confirm */ false, /* reason */ null, /* wait */ false);
        Log.d(TAG, "shutdown called!");
    }

    private void sleep(View v) {
        // TBD
        if(DBG) {
            Log.d(TAG, "Calling sleep method");
        }

        PowerManager pm = (PowerManager) getActivity().getSystemService(Context.POWER_SERVICE);
        pm.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN, PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
    }
}
