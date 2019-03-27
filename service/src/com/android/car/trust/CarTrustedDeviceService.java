/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.trust;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.android.car.CarServiceBase;
import com.android.car.R;

import java.io.PrintWriter;

/**
 * The part of the Car service that enables the Trusted device feature.  Trusted Device is a feature
 * where a remote device is enrolled as a trusted device that can authorize an Android user in lieu
 * of the user entering a password or PIN.
 * <p>
 * It is comprised of the {@link CarTrustAgentEnrollmentService} for handling enrollment and
 * {@link CarTrustAgentUnlockService} for handling unlock/auth.
 *
 */
public class CarTrustedDeviceService implements CarServiceBase {
    private static final String TAG = CarTrustedDeviceService.class.getSimpleName();
    private final Context mContext;
    private CarTrustAgentEnrollmentService mCarTrustAgentEnrollmentService;
    private CarTrustAgentUnlockService mCarTrustAgentUnlockService;
    private CarTrustAgentBleManager mCarTrustAgentBleManager;
    private SharedPreferences mTrustAgentTokenPreferences;


    public CarTrustedDeviceService(Context context) {
        mContext = context;
        mCarTrustAgentBleManager = new CarTrustAgentBleManager(context);
        mCarTrustAgentEnrollmentService = new CarTrustAgentEnrollmentService(this,
                mCarTrustAgentBleManager);
        mCarTrustAgentUnlockService = new CarTrustAgentUnlockService(this,
                mCarTrustAgentBleManager);
    }

    @Override
    public synchronized void init() {
        mCarTrustAgentEnrollmentService.init();
        mCarTrustAgentUnlockService.init();
    }

    @Override
    public synchronized void release() {
        mCarTrustAgentBleManager.cleanup();
        mCarTrustAgentEnrollmentService.release();
        mCarTrustAgentUnlockService.release();
    }

    /**
     * Returns the internal {@link CarTrustAgentEnrollmentService} instance.
     */
    public CarTrustAgentEnrollmentService getCarTrustAgentEnrollmentService() {
        return mCarTrustAgentEnrollmentService;
    }

    /**
     * Returns the internal {@link CarTrustAgentUnlockService} instance.
     */
    public CarTrustAgentUnlockService getCarTrustAgentUnlockService() {
        return mCarTrustAgentUnlockService;
    }

    /**
     * Returns User Id for the given token handle
     *
     * @param handle The handle corresponding to the escrow token
     * @return User id corresponding to the handle
     */
    int getUserHandleByTokenHandle(long handle) {
        return getSharedPrefs().getInt(String.valueOf(handle), -1);
    }

    void onRemoteDeviceConnected(BluetoothDevice device) {
        mCarTrustAgentEnrollmentService.onRemoteDeviceConnected(device);
        mCarTrustAgentUnlockService.onRemoteDeviceConnected(device);
    }

    void onRemoteDeviceDisconnected(BluetoothDevice device) {
        mCarTrustAgentEnrollmentService.onRemoteDeviceDisconnected(device);
        mCarTrustAgentUnlockService.onRemoteDeviceDisconnected(device);
    }

    void cleanupBleService() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "cleanupBleService");
        }
        mCarTrustAgentBleManager.stopGattServer();
        mCarTrustAgentBleManager.stopEnrollmentAdvertising();
        mCarTrustAgentBleManager.stopUnlockAdvertising();
    }

    SharedPreferences getSharedPrefs() {
        if (mTrustAgentTokenPreferences != null) {
            return mTrustAgentTokenPreferences;
        }
        mTrustAgentTokenPreferences = mContext.getSharedPreferences(
                mContext.getString(R.string.token_handle_shared_preferences), Context.MODE_PRIVATE);
        return mTrustAgentTokenPreferences;
    }

    @Override
    public void dump(PrintWriter writer) {
    }
}
