/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.car.kitchensink.cluster;

import android.annotation.Nullable;
import android.car.Car;
import android.car.CarAppFocusManager;
import android.car.navigation.CarNavigationStatusManager;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.car.cluster.navigation.NavigationState;
import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Contains functions to test instrument cluster API.
 */
public class InstrumentClusterFragment extends Fragment {
    private static final String TAG = "Cluster.KitchenSink";

    private static final int DISPLAY_IN_CLUSTER_PERMISSION_REQUEST = 1;

    private CarNavigationStatusManager mCarNavigationStatusManager;
    private CarAppFocusManager mCarAppFocusManager;
    private Car mCarApi;
    private Timer mTimer;
    private NavigationState[] mNavStateData;
    private Button mTurnByTurnButton;

    private ServiceConnection mCarServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Connected to Car Service");
            mCarNavigationStatusManager = (CarNavigationStatusManager) mCarApi
                    .getCarManager(Car.CAR_NAVIGATION_SERVICE);
            mCarAppFocusManager = (CarAppFocusManager) mCarApi
                    .getCarManager(Car.APP_FOCUS_SERVICE);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Disconnect from Car Service");
        }
    };

    private final CarAppFocusManager.OnAppFocusOwnershipCallback mFocusCallback =
            new CarAppFocusManager.OnAppFocusOwnershipCallback() {
        @Override
        public void onAppFocusOwnershipLost(@CarAppFocusManager.AppFocusType int appType) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onAppFocusOwnershipLost, appType: " + appType);
            }
            Toast.makeText(getContext(), getText(R.string.cluster_nav_app_context_loss),
                    Toast.LENGTH_LONG).show();
        }

        @Override
        public void onAppFocusOwnershipGranted(@CarAppFocusManager.AppFocusType int appType) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onAppFocusOwnershipGranted, appType: " + appType);
            }
        }
    };
    private CarAppFocusManager.OnAppFocusChangedListener mOnAppFocusChangedListener =
            (appType, active) -> {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "onAppFocusChanged, appType: " + appType + " active: " + active);
                }
            };


    private void initCarApi() {
        if (mCarApi != null && mCarApi.isConnected()) {
            mCarApi.disconnect();
            mCarApi = null;
        }

        mCarApi = Car.createCar(getContext(), mCarServiceConnection);
        mCarApi.connect();
    }

    /**
     * Loads sample navigation data from the "nav_state_data.json" file.
     */
    @NonNull
    private NavigationState[] getNavStateData() {
        try {
            Gson gson = new GsonBuilder().create();
            String navStateData = getRawResourceAsString(R.raw.nav_state_data);
            NavigationState[] navigationState = gson.fromJson(navStateData,
                    NavigationState[].class);
            return navigationState;
        } catch (IOException ex) {
            Log.e(TAG, "Unable to read navigation state data", ex);
            return new NavigationState[0];
        }
    }

    /**
     * Loads a raw resource as a single string.
     */
    @NonNull
    private String getRawResourceAsString(@IdRes int resId) throws IOException {
        InputStream inputStream = getResources().openRawResource(resId);
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder builder = new StringBuilder();
        for (String line; (line = reader.readLine()) != null; ) {
            builder.append(line).append("\n");
        }
        return builder.toString();
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.instrument_cluster, container, false);

        view.findViewById(R.id.cluster_start_button).setOnClickListener(v -> initCluster());
        view.findViewById(R.id.cluster_stop_button).setOnClickListener(v -> stopCluster());

        mTurnByTurnButton = view.findViewById(R.id.cluster_turn_left_button);
        mTurnByTurnButton.setOnClickListener(v -> toggleSendTurn());

        return view;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        initCarApi();
        super.onCreate(savedInstanceState);
    }

    /**
     * Enables/disables sending turn-by-turn data through the {@link CarNavigationStatusManager}
     */
    private void toggleSendTurn() {
        // If we haven't yet load the sample navigation state data, do so.
        if (mNavStateData == null) {
            mNavStateData = getNavStateData();
        }

        // Toggle a timer to send update periodically.
        if (mTimer == null) {
            startSendTurn();
        } else {
            stopSendTurn();
        }
    }

    private void startSendTurn() {
        if (mTimer != null) {
            stopSendTurn();
        }
        if (!hasFocus()) {
            Toast.makeText(getContext(), getText(R.string.cluster_not_started), Toast.LENGTH_LONG)
                    .show();
            return;
        }
        mTimer = new Timer();
        mTimer.schedule(new TimerTask() {
            private int mPos;

            @Override
            public void run() {
                sendTurn(mNavStateData[mPos]);
                mPos = (mPos + 1) % mNavStateData.length;
            }
        }, 0, 1000);
        mTurnByTurnButton.setText(R.string.cluster_stop_guidance);
    }

    private void stopSendTurn() {
        if (mTimer != null) {
            mTimer.cancel();
            mTimer = null;
        }
        mTurnByTurnButton.setText(R.string.cluster_start_guidance);
    }

    /**
     * Sends one update of the navigation state through the {@link CarNavigationStatusManager}
     */
    private void sendTurn(@NonNull NavigationState state) {
        Bundle bundle = new Bundle();
        bundle.putParcelable("navstate", state.toParcelable());
        mCarNavigationStatusManager.sendEvent(1, bundle);
        Log.i(TAG, "Sending nav state: " + state);
    }

    private void initCluster() {
        if (hasFocus()) {
            Log.i(TAG, "Already has focus");
            return;
        }
        mCarAppFocusManager.addFocusListener(mOnAppFocusChangedListener,
                CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
        mCarAppFocusManager.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,
                mFocusCallback);
        Log.i(TAG, "Focus requested");
    }

    private boolean hasFocus() {
        boolean ownsFocus = mCarAppFocusManager.isOwningFocus(mFocusCallback,
                CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Owns APP_FOCUS_TYPE_NAVIGATION: " + ownsFocus);
        }
        return ownsFocus;
    }

    private void stopCluster() {
        stopSendTurn();
        mCarAppFocusManager.removeFocusListener(mOnAppFocusChangedListener,
                CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
        mCarAppFocusManager.abandonAppFocus(mFocusCallback,
                CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION);
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume!");
        if (getActivity().checkSelfPermission(android.car.Car.PERMISSION_CAR_DISPLAY_IN_CLUSTER)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Requesting: " + android.car.Car.PERMISSION_CAR_DISPLAY_IN_CLUSTER);

            requestPermissions(new String[] {android.car.Car.PERMISSION_CAR_DISPLAY_IN_CLUSTER},
                    DISPLAY_IN_CLUSTER_PERMISSION_REQUEST);
        } else {
            Log.i(TAG, "All required permissions granted");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (DISPLAY_IN_CLUSTER_PERMISSION_REQUEST == requestCode) {
            for (int i = 0; i < permissions.length; i++) {
                boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                Log.i(TAG, "onRequestPermissionsResult, requestCode: " + requestCode
                        + ", permission: " + permissions[i] + ", granted: " + granted);
            }
        }
    }
}
