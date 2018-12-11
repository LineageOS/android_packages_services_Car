/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseSettings;
import android.car.trust.ICarTrustAgentBleCallback;
import android.car.trust.ICarTrustAgentBleService;
import android.car.trust.ICarTrustAgentEnrolmentCallback;
import android.car.trust.ICarTrustAgentTokenRequestDelegate;
import android.car.trust.ICarTrustAgentTokenResponseCallback;
import android.car.trust.ICarTrustAgentUnlockCallback;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.util.UUID;

/**
 * Abstracts enrolment and unlock token exchange via BluetoothLE (BLE).
 * {@link CarBleTrustAgent} and any enrolment client should bind to
 * {@link ICarTrustAgentBleService}.
 */
public class CarTrustAgentBleService extends SimpleBleServer {
    private static final String TAG = CarTrustAgentBleService.class.getSimpleName();

    private RemoteCallbackList<ICarTrustAgentBleCallback> mBleCallbacks;
    private RemoteCallbackList<ICarTrustAgentEnrolmentCallback> mEnrolmentCallbacks;
    private RemoteCallbackList<ICarTrustAgentUnlockCallback> mUnlockCallbacks;
    private ICarTrustAgentTokenRequestDelegate mTokenRequestDelegate;
    private ICarTrustAgentTokenResponseCallback mTokenResponseCallback;
    private CarTrustAgentBleWrapper mCarTrustBleService;

    private UUID mEnrolmentEscrowTokenUuid;
    private UUID mEnrolmentTokenHandleUuid;
    private BluetoothGattService mEnrolmentGattService;

    private UUID mUnlockEscrowTokenUuid;
    private UUID mUnlockTokenHandleUuid;
    private BluetoothGattService mUnlockGattService;

    private byte[] mCurrentUnlockToken;
    private Long mCurrentUnlockHandle;

    private SharedPreferences mTokenHandleSharedPreferences;

    @Override
    public void onCreate() {
        super.onCreate();
        mBleCallbacks = new RemoteCallbackList<>();
        mEnrolmentCallbacks = new RemoteCallbackList<>();
        mUnlockCallbacks = new RemoteCallbackList<>();
        mCarTrustBleService = new CarTrustAgentBleWrapper();

        mEnrolmentEscrowTokenUuid = UUID.fromString(getString(R.string.enrollment_token_uuid));
        mEnrolmentTokenHandleUuid = UUID.fromString(getString(R.string.enrollment_handle_uuid));
        mUnlockEscrowTokenUuid = UUID.fromString(getString(R.string.unlock_escrow_token_uiid));
        mUnlockTokenHandleUuid = UUID.fromString(getString(R.string.unlock_handle_uiid));

        setupEnrolmentBleServer();
        setupUnlockBleServer();

        mTokenHandleSharedPreferences = getSharedPreferences(
                getString(R.string.token_handle_shared_preferences),
                MODE_PRIVATE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mCarTrustBleService;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // keep it alive.
        return START_STICKY;
    }

    @Override
    public void onCharacteristicWrite(final BluetoothDevice device, int requestId,
            BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean
            responseNeeded, int offset, byte[] value) {
        UUID uuid = characteristic.getUuid();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onCharacteristicWrite received uuid: " + uuid);
        }

        if (uuid.equals(mEnrolmentEscrowTokenUuid)) {
            final int callbackCount = mEnrolmentCallbacks.beginBroadcast();
            for (int i = 0; i < callbackCount; i++) {
                try {
                    mEnrolmentCallbacks.getBroadcastItem(i).onEnrolmentDataReceived(value);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error callback onEnrolmentDataReceived", e);
                }
            }
            mEnrolmentCallbacks.finishBroadcast();
        } else if (uuid.equals(mUnlockEscrowTokenUuid)) {
            mCurrentUnlockToken = value;
            maybeSendUnlockToken();
        } else if (uuid.equals(mUnlockTokenHandleUuid)) {
            mCurrentUnlockHandle = getLong(value);
            maybeSendUnlockToken();
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothDevice device,
            int requestId, int offset, final BluetoothGattCharacteristic characteristic) {
        // Ignored read requests.
    }

    @Override
    protected void onAdvertiseStartSuccess() {
        final int callbackCount = mBleCallbacks.beginBroadcast();
        for (int i = 0; i < callbackCount; i++) {
            try {
                mBleCallbacks.getBroadcastItem(i).onBleServerStartSuccess();
            } catch (RemoteException e) {
                Log.e(TAG, "Error callback onBleServerStartSuccess", e);
            }
        }
        mBleCallbacks.finishBroadcast();
    }

    @Override
    protected void onAdvertiseStartFailure(int errorCode) {
        final int callbackCount = mBleCallbacks.beginBroadcast();
        for (int i = 0; i < callbackCount; i++) {
            try {
                mBleCallbacks.getBroadcastItem(i).onBleServerStartFailure(errorCode);
            } catch (RemoteException e) {
                Log.e(TAG, "Error callback onBleServerStartFailure", e);
            }
        }
        mBleCallbacks.finishBroadcast();
    }

    @Override
    protected void onAdvertiseDeviceConnected(BluetoothDevice device) {
        final int callbackCount = mBleCallbacks.beginBroadcast();
        for (int i = 0; i < callbackCount; i++) {
            try {
                mBleCallbacks.getBroadcastItem(i).onBleDeviceConnected(device);
            } catch (RemoteException e) {
                Log.e(TAG, "Error callback onBleDeviceConnected", e);
            }
        }
        mBleCallbacks.finishBroadcast();
    }

    @Override
    protected void onAdvertiseDeviceDisconnected(BluetoothDevice device) {
        final int callbackCount = mBleCallbacks.beginBroadcast();
        for (int i = 0; i < callbackCount; i++) {
            try {
                mBleCallbacks.getBroadcastItem(i).onBleDeviceDisconnected(device);
            } catch (RemoteException e) {
                Log.e(TAG, "Error callback onBleDeviceDisconnected", e);
            }
        }
        mBleCallbacks.finishBroadcast();
    }

    @Override
    public void onDestroy() {
        stopAdvertising(mEnrolmentAdvertisingCallback);
        stopAdvertising(mUnlockAdvertisingCallback);
        super.onDestroy();
    }

    private void setupEnrolmentBleServer() {
        mEnrolmentGattService = new BluetoothGattService(
                UUID.fromString(getString(R.string.enrollment_service_uuid)),
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Characteristic to describe the escrow token being used for unlock
        BluetoothGattCharacteristic enrolmentEscrowToken = new BluetoothGattCharacteristic(
                mEnrolmentEscrowTokenUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        // Characteristic to describe the handle being used for this escrow token
        BluetoothGattCharacteristic enrolmentTokenHandle = new BluetoothGattCharacteristic(
                mEnrolmentTokenHandleUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);

        mEnrolmentGattService.addCharacteristic(enrolmentEscrowToken);
        mEnrolmentGattService.addCharacteristic(enrolmentTokenHandle);
    }

    private void setupUnlockBleServer() {
        mUnlockGattService = new BluetoothGattService(
                UUID.fromString(getString(R.string.unlock_service_uuid)),
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Characteristic to describe the escrow token being used for unlock
        BluetoothGattCharacteristic unlockEscrowToken = new BluetoothGattCharacteristic(
                mUnlockEscrowTokenUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        // Characteristic to describe the handle being used for this escrow token
        BluetoothGattCharacteristic unlockTokenHandle = new BluetoothGattCharacteristic(
                mUnlockTokenHandleUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        mUnlockGattService.addCharacteristic(unlockEscrowToken);
        mUnlockGattService.addCharacteristic(unlockTokenHandle);
    }

    private synchronized void maybeSendUnlockToken() {
        if (mCurrentUnlockToken == null || mCurrentUnlockHandle == null) {
            return;
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Handle and token both received, requesting unlock. Time: "
                    + DateFormat.getDateInstance(DateFormat.SHORT).format(
                            System.currentTimeMillis()));
        }

        int callbackCount = mUnlockCallbacks.beginBroadcast();
        for (int i = 0; i < callbackCount; i++) {
            try {
                mUnlockCallbacks.getBroadcastItem(i).onUnlockDataReceived(
                        mCurrentUnlockToken, mCurrentUnlockHandle);
            } catch (RemoteException e) {
                Log.e(TAG, "Error callback onUnlockDataReceived", e);
            }
        }
        mUnlockCallbacks.finishBroadcast();
        mCurrentUnlockHandle = null;
        mCurrentUnlockToken = null;
    }

    private static byte[] getBytes(long primitive) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.SIZE / Byte.SIZE);
        buffer.putLong(0, primitive);
        return buffer.array();
    }

    private static long getLong(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.SIZE / Byte.SIZE);
        buffer.put(bytes);
        buffer.flip();
        return buffer.getLong();
    }

    private final AdvertiseCallback mEnrolmentAdvertisingCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            onAdvertiseStartSuccess();

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Successfully started advertising service");
            }
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "Failed to advertise, errorCode: " + errorCode);

            super.onStartFailure(errorCode);
            onAdvertiseStartFailure(errorCode);
        }
    };

    private final AdvertiseCallback mUnlockAdvertisingCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            onAdvertiseStartSuccess();

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Successfully started advertising service");
            }
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "Failed to advertise, errorCode: " + errorCode);

            super.onStartFailure(errorCode);
            onAdvertiseStartFailure(errorCode);
        }
    };

    private final class CarTrustAgentBleWrapper extends ICarTrustAgentBleService.Stub {
        @Override
        public void registerBleCallback(ICarTrustAgentBleCallback callback) {
            mBleCallbacks.register(callback);
        }

        @Override
        public void unregisterBleCallback(ICarTrustAgentBleCallback callback) {
            mBleCallbacks.unregister(callback);
        }

        @Override
        public void startEnrolmentAdvertising() {
            stopEnrolmentAdvertising();
            stopUnlockAdvertising();

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "startEnrolmentAdvertising");
            }
            startAdvertising(mEnrolmentGattService, mEnrolmentAdvertisingCallback);
        }

        @Override
        public void stopEnrolmentAdvertising() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "stopEnrolmentAdvertising");
            }

            stopAdvertising(mEnrolmentAdvertisingCallback);
        }

        @Override
        public void sendEnrolmentHandle(BluetoothDevice device, long handle) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "sendEnrolmentHandle: " + handle);
            }
            BluetoothGattCharacteristic enrolmentTokenHandle =
                    mEnrolmentGattService.getCharacteristic(mEnrolmentTokenHandleUuid);
            enrolmentTokenHandle.setValue(getBytes(handle));
            notifyCharacteristicChanged(device, enrolmentTokenHandle, false);
        }

        @Override
        public void registerEnrolmentCallback(ICarTrustAgentEnrolmentCallback callback) {
            mEnrolmentCallbacks.register(callback);
        }

        @Override
        public void unregisterEnrolmentCallback(ICarTrustAgentEnrolmentCallback callback) {
            mEnrolmentCallbacks.unregister(callback);
        }

        @Override
        public void startUnlockAdvertising() {
            stopUnlockAdvertising();
            stopEnrolmentAdvertising();

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "startUnlockAdvertising");
            }
            startAdvertising(mUnlockGattService, mUnlockAdvertisingCallback);
        }

        @Override
        public void stopUnlockAdvertising() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "stopUnlockAdvertising");
            }
            stopAdvertising(mUnlockAdvertisingCallback);
        }

        @Override
        public void registerUnlockCallback(ICarTrustAgentUnlockCallback callback) {
            mUnlockCallbacks.register(callback);
        }

        @Override
        public void unregisterUnlockCallback(ICarTrustAgentUnlockCallback callback) {
            mUnlockCallbacks.unregister(callback);
        }

        @Override
        public void setTokenRequestDelegate(ICarTrustAgentTokenRequestDelegate delegate) {
            mTokenRequestDelegate = delegate;
        }

        @Override
        public void revokeTrust() throws RemoteException {
            if (mTokenRequestDelegate != null) {
                mTokenRequestDelegate.revokeTrust();
            }
        }

        @Override
        public void addEscrowToken(byte[] token, int uid) throws RemoteException {
            if (mTokenRequestDelegate != null) {
                mTokenRequestDelegate.addEscrowToken(token, uid);
            }
        }

        @Override
        public void removeEscrowToken(long handle, int uid) throws RemoteException {
            if (mTokenRequestDelegate != null) {
                mTokenRequestDelegate.removeEscrowToken(handle, uid);
            }
        }

        @Override
        public void isEscrowTokenActive(long handle, int uid) throws RemoteException {
            if (mTokenRequestDelegate != null) {
                mTokenRequestDelegate.isEscrowTokenActive(handle, uid);
            }
        }

        @Override
        public void setTokenResponseCallback(ICarTrustAgentTokenResponseCallback callback) {
            mTokenResponseCallback = callback;
        }

        @Override
        public void onEscrowTokenAdded(byte[] token, long handle, int uid)
                throws RemoteException {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onEscrowTokenAdded handle:" + handle + " uid:" + uid);
            }

            mTokenHandleSharedPreferences.edit()
                    .putInt(String.valueOf(handle), uid)
                    .apply();
            if (mTokenResponseCallback != null) {
                mTokenResponseCallback.onEscrowTokenAdded(token, handle, uid);
            }
        }

        @Override
        public void onEscrowTokenRemoved(long handle, boolean successful) throws RemoteException {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onEscrowTokenRemoved handle:" + handle);
            }

            mTokenHandleSharedPreferences.edit()
                    .remove(String.valueOf(handle))
                    .apply();
            if (mTokenResponseCallback != null) {
                mTokenResponseCallback.onEscrowTokenRemoved(handle, successful);
            }
        }

        @Override
        public void onEscrowTokenActiveStateChanged(long handle, boolean active)
                throws RemoteException {
            if (mTokenResponseCallback != null) {
                mTokenResponseCallback.onEscrowTokenActiveStateChanged(handle, active);
            }
        }

        @Override
        public int getUserIdByEscrowTokenHandle(long tokenHandle) {
            return mTokenHandleSharedPreferences.getInt(String.valueOf(tokenHandle), -1);
        }
    }
}
