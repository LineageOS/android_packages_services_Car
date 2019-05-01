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

import android.annotation.Nullable;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseSettings;
import android.content.Context;
import android.util.Log;

import com.android.car.CarLocalServices;
import com.android.car.R;

import java.util.UUID;

/**
 * A BLE Service that is used for communicating with the trusted peer device.  This extends from a
 * more generic {@link BLEManager} and has more context on the BLE requirements for the Trusted
 * device feature. It has knowledge on the GATT services and characteristics that are specific to
 * the Trusted Device feature.
 */
class CarTrustAgentBleManager extends BleManager {
    private static final String TAG = "CarTrustBLEManager";

    private CarTrustedDeviceService mCarTrustedDeviceService;
    private CarTrustAgentEnrollmentService mCarTrustAgentEnrollmentService;
    private CarTrustAgentUnlockService mCarTrustAgentUnlockService;

    // Enrollment Service and Characteristic UUIDs
    private UUID mEnrollmentServiceUuid;
    private UUID mEnrollmentEscrowTokenUuid;
    private UUID mEnrollmentTokenHandleUuid;
    private BluetoothGattService mEnrollmentGattService;

    // Unlock Service and Characteristic UUIDs
    private UUID mUnlockServiceUuid;
    private UUID mUnlockEscrowTokenUuid;
    private UUID mUnlockTokenHandleUuid;
    private BluetoothGattService mUnlockGattService;

    CarTrustAgentBleManager(Context context) {
        super(context);
    }

    // Overriding some of the {@link BLEManager} methods to be specific for Trusted Device feature.
    @Override
    public void onRemoteDeviceConnected(BluetoothDevice device) {
        if (getTrustedDeviceService() != null) {
            if (device.getName() == null) {
                retrieveDeviceName(device);
            }
            getTrustedDeviceService().onRemoteDeviceConnected(device);
        }
    }

    @Override
    public void onRemoteDeviceDisconnected(BluetoothDevice device) {
        if (getTrustedDeviceService() != null) {
            getTrustedDeviceService().onRemoteDeviceDisconnected(device);
        }
    }

    @Override
    protected void onDeviceNameRetrieved(@Nullable String deviceName) {
        if (getTrustedDeviceService() != null) {
            getTrustedDeviceService().onDeviceNameRetrieved(deviceName);
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothDevice device, int requestId,
            BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean
            responseNeeded, int offset, byte[] value) {
        UUID uuid = characteristic.getUuid();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onCharacteristicWrite received uuid: " + uuid);
        }
        if (uuid.equals(mEnrollmentEscrowTokenUuid)) {
            if (getEnrollmentService() != null) {
                getEnrollmentService().onEnrollmentDataReceived(value);
            }
        } else if (uuid.equals(mUnlockEscrowTokenUuid)) {
            if (getUnlockService() != null) {
                getUnlockService().onUnlockTokenReceived(value);
            }
        } else if (uuid.equals(mUnlockTokenHandleUuid)) {
            if (getUnlockService() != null) {
                getUnlockService().onUnlockHandleReceived(value);
            }
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothDevice device,
            int requestId, int offset, final BluetoothGattCharacteristic characteristic) {
        // Ignored read requests.
    }

    @Nullable
    private CarTrustedDeviceService getTrustedDeviceService() {
        if (mCarTrustedDeviceService == null) {
            mCarTrustedDeviceService = CarLocalServices.getService(
                    CarTrustedDeviceService.class);
        }
        return mCarTrustedDeviceService;
    }

    @Nullable
    private CarTrustAgentEnrollmentService getEnrollmentService() {
        if (mCarTrustAgentEnrollmentService != null) {
            return mCarTrustAgentEnrollmentService;
        }

        if (getTrustedDeviceService() != null) {
            mCarTrustAgentEnrollmentService =
                    getTrustedDeviceService().getCarTrustAgentEnrollmentService();
        }
        return mCarTrustAgentEnrollmentService;
    }

    @Nullable
    private CarTrustAgentUnlockService getUnlockService() {
        if (mCarTrustAgentUnlockService != null) {
            return mCarTrustAgentUnlockService;
        }

        if (getTrustedDeviceService() != null) {
            mCarTrustAgentUnlockService =
                    getTrustedDeviceService().getCarTrustAgentUnlockService();
        }
        return mCarTrustAgentUnlockService;
    }

    /**
     * Setup the BLE GATT server for Enrollment. The GATT server for Enrollment comprises of
     * one GATT Service and 2 characteristics - one for the escrow token to be generated and sent
     * from the phone and the other for the handle generated and sent by the Head unit.
     */
    void setupEnrollmentBleServer() {
        mEnrollmentServiceUuid = UUID.fromString(
                getContext().getString(R.string.enrollment_service_uuid));
        mEnrollmentEscrowTokenUuid = UUID.fromString(
                getContext().getString(R.string.enrollment_token_uuid));
        mEnrollmentTokenHandleUuid = UUID.fromString(
                getContext().getString(R.string.enrollment_handle_uuid));

        mEnrollmentGattService = new BluetoothGattService(mEnrollmentServiceUuid,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Characteristic to describe the escrow token being used for unlock
        BluetoothGattCharacteristic tokenCharacteristic = new BluetoothGattCharacteristic(
                mEnrollmentEscrowTokenUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        // Characteristic to describe the handle being used for this escrow token
        BluetoothGattCharacteristic handleCharacteristic = new BluetoothGattCharacteristic(
                mEnrollmentTokenHandleUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);

        mEnrollmentGattService.addCharacteristic(tokenCharacteristic);
        mEnrollmentGattService.addCharacteristic(handleCharacteristic);
    }

    /**
     * Setup the BLE GATT server for Unlocking the Head unit.  The GATT server for this phase also
     * comprises of 1 Service and 2 characteristics.  However both the token and the handle are
     * sent ftrom the phone to the head unit.
     */
    void setupUnlockBleServer() {
        mUnlockServiceUuid = UUID.fromString(
                getContext().getString(R.string.unlock_service_uuid));
        mUnlockEscrowTokenUuid = UUID.fromString(
                getContext().getString(R.string.unlock_escrow_token_uuid));
        mUnlockTokenHandleUuid = UUID.fromString(
                getContext().getString(R.string.unlock_handle_uuid));

        mUnlockGattService = new BluetoothGattService(mUnlockServiceUuid,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Characteristic to describe the escrow token being used for unlock
        BluetoothGattCharacteristic tokenCharacteristic = new BluetoothGattCharacteristic(
                mUnlockEscrowTokenUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        // Characteristic to describe the handle being used for this escrow token
        BluetoothGattCharacteristic handleCharacteristic = new BluetoothGattCharacteristic(
                mUnlockTokenHandleUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        mUnlockGattService.addCharacteristic(tokenCharacteristic);
        mUnlockGattService.addCharacteristic(handleCharacteristic);
    }

    void startEnrollmentAdvertising() {
        startAdvertising(mEnrollmentGattService, mEnrollmentAdvertisingCallback);
    }

    void stopEnrollmentAdvertising() {
        stopAdvertising(mEnrollmentAdvertisingCallback);
    }

    void startUnlockAdvertising() {
        startAdvertising(mUnlockGattService, mUnlockAdvertisingCallback);
    }

    void stopUnlockAdvertising() {
        stopAdvertising(mUnlockAdvertisingCallback);
    }

    void disconnectRemoteDevice() {
        stopGattServer();
    }

    /**
     * Sends the given handshake message to the specified device.
     *
     * <p>The message is expected to be a message that will continue the encryption handshake and
     * eventually set up a secure channel for communication between the given device and this
     * head unit.
     *
     * @param device The device to send the message to.
     * @param message A message that will continue the encryption handshake.
     */
    void sendEncryptionHandshakeMessage(BluetoothDevice device, byte[] message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "sendEncryptionHandshakeMessage to: " + device.getAddress());
        }

        BluetoothGattCharacteristic enrollmentToken = mEnrollmentGattService.getCharacteristic(
                mEnrollmentEscrowTokenUuid);
        enrollmentToken.setValue(message);
        notifyCharacteristicChanged(device, enrollmentToken, false);
    }

    /**
     * Sends the handle corresponding to the escrow token that was generated by the phone to the
     * phone.
     *
     * @param device the BLE peer device to send the handle to.
     * @param handle the handle corresponding to the escrow token
     */
    void sendEnrollmentHandle(BluetoothDevice device, byte[] handle) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "sendEnrollmentHandle to " + device.getAddress());
        }
        BluetoothGattCharacteristic enrollmentHandle = mEnrollmentGattService.getCharacteristic(
                mEnrollmentTokenHandleUuid);
        enrollmentHandle.setValue(handle);
        notifyCharacteristicChanged(device, enrollmentHandle, false);
    }

    /**
     * Sends the given pairing code confirmation signal to the specified device.
     *
     * @param device the BLE peer device to send the signal to.
     * @param confirmationSignal The signal that will let the given {@link device} know that
     *                           the pairing code has been accepted by the user.
     */
    void sendPairingCodeConfirmation(BluetoothDevice device, byte[] confirmationSignal) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "sendPairingCodeConfirmation: " + device.getAddress());
        }
        BluetoothGattCharacteristic confirmation = mEnrollmentGattService.getCharacteristic(
                mEnrollmentTokenHandleUuid);
        confirmation.setValue(confirmationSignal);
        notifyCharacteristicChanged(device, confirmation, false);
    }


    private final AdvertiseCallback mEnrollmentAdvertisingCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            if (getEnrollmentService() != null) {
                getEnrollmentService().onEnrollmentAdvertiseStartSuccess();
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Successfully started advertising service");
            }
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "Failed to advertise, errorCode: " + errorCode);

            super.onStartFailure(errorCode);
            if (getEnrollmentService() != null) {
                getEnrollmentService().onEnrollmentAdvertiseStartFailure();
            }
        }
    };

    private final AdvertiseCallback mUnlockAdvertisingCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Unlock Advertising onStartSuccess");
            }
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "Failed to advertise, errorCode: " + errorCode);
            super.onStartFailure(errorCode);
        }
    };
}
