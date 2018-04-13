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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A service that receives escrow token enrollment requests from remote devices.
 */
public class CarEnrolmentService extends SimpleBleServer {

    private final Set<OnEnrolmentDataReceivedListener> mEnrolmentListeners = new HashSet<>();
    private final IBinder mBinder = new EnrolmentServiceBinder();

    private BluetoothGattService mEnrolmentService;
    private BluetoothGattCharacteristic mEnrolmentEscrowToken;
    private BluetoothGattCharacteristic mEnrolmentTokenHandle;

    @Override
    public void onCreate() {
        super.onCreate();
        setupEnrolmentService();
    }

    public void start() {
        ParcelUuid uuid = new ParcelUuid(
                UUID.fromString(getString(R.string.enrollment_service_uuid)));
        Log.d(Utils.LOG_TAG, "CarEnrolmentService start with uuid: " + uuid);
        start(uuid, mEnrolmentService, new Handler());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCharacteristicWrite(BluetoothDevice device,
            int requestId, BluetoothGattCharacteristic characteristic,
            boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
        if (characteristic.getUuid().equals(mEnrolmentEscrowToken.getUuid())) {
            Log.d(Utils.LOG_TAG, "Enrolment token received, value: " + Utils.getLong(value));
            for (OnEnrolmentDataReceivedListener callback : mEnrolmentListeners) {
                callback.onEnrolmentDataReceived(value);
            }
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothDevice device,
            int requestId, int offset, BluetoothGattCharacteristic characteristic) {
        //Enrolment service should not have any read requests.
    }

    /**
     * Register {@link OnEnrolmentDataReceivedListener} to receive the enrolment data
     * @param listener
     */
    public void registerEnrolmentListener(OnEnrolmentDataReceivedListener listener) {
        mEnrolmentListeners.add(listener);
    }

    /**
     * Unregister {@link OnEnrolmentDataReceivedListener} from receiving enrolment data
     * @param listener
     */
    public void unregisterEnrolmentListener(OnEnrolmentDataReceivedListener listener) {
        mEnrolmentListeners.remove(listener);
    }

    public void sendHandle(long handle, BluetoothDevice device) {
        mEnrolmentTokenHandle.setValue(Utils.getBytes(handle));

        Log.d(Utils.LOG_TAG, "Sending notification for EscrowToken Handle");
        notifyCharacteristicChanged(device, mEnrolmentTokenHandle, false);
    }

    public class EnrolmentServiceBinder extends Binder {
        public CarEnrolmentService getService() {
            return CarEnrolmentService.this;
        }
    }

    // Create services and characteristics for enrolling new unlocking escrow tokens
    private void setupEnrolmentService() {
        mEnrolmentService = new BluetoothGattService(
                UUID.fromString(getString(R.string.enrollment_service_uuid)),
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Characteristic to describe the escrow token being used for unlock
        mEnrolmentEscrowToken = new BluetoothGattCharacteristic(
                UUID.fromString(getString(R.string.enrollment_token_uuid)),
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        // Characteristic to describe the handle being used for this escrow token
        mEnrolmentTokenHandle = new BluetoothGattCharacteristic(
                UUID.fromString(getString(R.string.enrollment_handle_uuid)),
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);

        mEnrolmentService.addCharacteristic(mEnrolmentEscrowToken);
        mEnrolmentService.addCharacteristic(mEnrolmentTokenHandle);
    }

    /**
     * Interface to receive enrolment data.
     */
    public interface OnEnrolmentDataReceivedListener {
        /**
         * Called when the enrolment data is received
         * @param token enrolment data
         */
        void onEnrolmentDataReceived(byte[] token);
    }
}
