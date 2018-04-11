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
 * limitations under the License
 */
package com.android.car.trust;

import static com.android.car.trust.CarBleTrustAgent.ACTION_ADD_TOKEN_RESULT;
import static com.android.car.trust.CarBleTrustAgent.ACTION_TOKEN_STATUS_RESULT;
import static com.android.car.trust.CarBleTrustAgent.INTENT_EXTRA_TOKEN_HANDLE;
import static com.android.car.trust.CarBleTrustAgent.INTENT_EXTRA_TOKEN_STATUS;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.TextView;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.android.car.trust.CarEnrolmentService.OnEnrolmentDataReceivedListener;
import com.android.car.trust.SimpleBleServer.ConnectionCallback;

/**
 * Setup activity that binds {@link CarEnrolmentService} and starts the enrolment process.
 */
public class CarEnrolmentActivity extends Activity {
    private static final String SP_HANDLE_KEY = "sp-test";
    private static final int FINE_LOCATION_REQUEST_CODE = 42;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(Utils.LOG_TAG, "Received broadcast: " + action);

            if (ACTION_TOKEN_STATUS_RESULT.equals(action)) {
                boolean tokenActive = intent.getBooleanExtra(INTENT_EXTRA_TOKEN_STATUS, false);
                appendOutputText("Is token active? " + tokenActive + " handle: " + mHandle);
            } else if (ACTION_ADD_TOKEN_RESULT.equals(action)) {
                final long handle = intent.getLongExtra(INTENT_EXTRA_TOKEN_HANDLE, -1);

                runOnUiThread(() -> {
                    mPrefs.edit().putLong(SP_HANDLE_KEY, handle).apply();
                    Log.d(Utils.LOG_TAG, "stored new handle");
                });

                mEnrolmentService.sendHandle(handle, mDevice);
                appendOutputText("Escrow Token Added. Handle: " + handle
                        + "\nLock and unlock the device to activate token");
            }
        }
    };

    private final ConnectionCallback mConnectionCallback = new ConnectionCallback() {
        @Override
        public void onServerStarted() {
            appendOutputText("Server started");
        }

        @Override
        public void onServerStartFailed(int errorCode) {
            appendOutputText("Server failed to start, error code: " + errorCode);
        }

        @Override
        public void onDeviceConnected(BluetoothDevice device) {
            mDevice = device;
            appendOutputText("Device connected: " + device.getName()
                    + " addr: " + device.getAddress());
        }
    };

    private final OnEnrolmentDataReceivedListener mEnrolmentListener = (byte[] token) -> {
        appendOutputText("Enrolment data received ");
        addEscrowToken(token);
    };

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            mServiceBound = true;
            CarEnrolmentService.EnrolmentServiceBinder binder
                    = (CarEnrolmentService.EnrolmentServiceBinder) service;
            mEnrolmentService = binder.getService();
            mEnrolmentService.registerEnrolmentListener(mEnrolmentListener);
            mEnrolmentService.registerConnectionCallback(mConnectionCallback);
            mEnrolmentService.start();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mEnrolmentService.unregisterEnrolmentListener(mEnrolmentListener);
            mEnrolmentService.unregisterConnectionCallback(mConnectionCallback);
            mEnrolmentService = null;
            mServiceBound = false;
        }
    };

    private TextView mOutputText;
    private long mHandle;
    private CarEnrolmentService mEnrolmentService;
    private BluetoothDevice mDevice;
    private boolean mServiceBound;
    private LocalBroadcastManager mLocalBroadcastManager;
    private SharedPreferences mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.car_enrolment_activity);
        mOutputText = findViewById(R.id.textfield);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this /* context */);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TOKEN_STATUS_RESULT);
        filter.addAction(ACTION_ADD_TOKEN_RESULT);

        mLocalBroadcastManager = LocalBroadcastManager.getInstance(this /* context */);
        mLocalBroadcastManager.registerReceiver(mReceiver, filter);

        findViewById(R.id.start_button).setOnClickListener((view) -> {
            Intent bindIntent = new Intent(this, CarEnrolmentService.class);
            bindService(bindIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        });

        findViewById(R.id.revoke_trust_button).setOnClickListener((view) -> {
            Intent revokeIntent = new Intent(CarBleTrustAgent.ACTION_REVOKE_TRUST);
            revokeIntent.setPackage(getPackageName());
            sendBroadcast(revokeIntent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] { android.Manifest.permission.ACCESS_FINE_LOCATION },
                    FINE_LOCATION_REQUEST_CODE);
        }

        if (!mPrefs.contains(SP_HANDLE_KEY)) {
            appendOutputText("No handles found.");
            return;
        }

        try {
            mHandle = mPrefs.getLong(SP_HANDLE_KEY, -1);
            Log.d(Utils.LOG_TAG, "onResume, checking handle active: " + mHandle);
            isTokenActive(mHandle);
        } catch (RemoteException e) {
            Log.e(Utils.LOG_TAG, "Error checking if token is valid");
            appendOutputText("Error checking if token is valid");
        }
    }

    @Override
    protected void onDestroy() {
        if (mServiceBound) {
            unbindService(mServiceConnection);
        }
        super.onDestroy();
    }

    private void appendOutputText(final String text) {
        runOnUiThread(() -> mOutputText.append("\n" + text));
    }

    private void isTokenActive(long handle) throws RemoteException {
        Intent intent = new Intent();
        intent.setAction(CarBleTrustAgent.ACTION_IS_TOKEN_ACTIVE);
        intent.putExtra(CarBleTrustAgent.INTENT_EXTRA_TOKEN_HANDLE, handle);

        mLocalBroadcastManager.sendBroadcast(intent);
    }

    private void addEscrowToken(byte[] token) {
        long handle;

        if (mPrefs.contains(SP_HANDLE_KEY)) {
            handle = mPrefs.getLong(SP_HANDLE_KEY, -1);
            appendOutputText("Removing old token, handle value: " + handle);
            Intent intent = new Intent();
            intent.setAction(CarBleTrustAgent.ACTION_REMOVE_TOKEN);
            intent.putExtra(CarBleTrustAgent.INTENT_EXTRA_TOKEN_HANDLE, handle);
            mLocalBroadcastManager.sendBroadcast(intent);
        }

        Intent intent = new Intent();
        intent.setAction(CarBleTrustAgent.ACTION_ADD_TOKEN);
        intent.putExtra(CarBleTrustAgent.INTENT_EXTRA_ESCROW_TOKEN, token);

        mLocalBroadcastManager.sendBroadcast(intent);
    }
}
