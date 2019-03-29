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
import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.car.trust.ICarTrustAgentBleCallback;
import android.car.trust.ICarTrustAgentEnrollment;
import android.car.trust.ICarTrustAgentEnrollmentCallback;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.car.Utils;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A service that is part of the CarTrustedDeviceService that is responsible for allowing a
 * phone to enroll as a trusted device.  The enrolled phone can then be used for authenticating a
 * user on the HU.  This implements the {@link android.car.trust.CarTrustAgentEnrollmentManager}
 * APIs that an app like Car Settings can call to conduct an enrollment.
 */
public class CarTrustAgentEnrollmentService extends ICarTrustAgentEnrollment.Stub {
    private static final String TAG = "CarTrustAgentEnroll";
    private static final String FAKE_AUTH_STRING = "000000";
    private final CarTrustedDeviceService mTrustedDeviceService;
    // List of clients listening to Enrollment state change events.
    private final List<EnrollmentStateClient> mEnrollmentStateClients = new ArrayList<>();
    // List of clients listening to BLE state changes events during enrollment.
    private final List<BleStateChangeClient> mBleStateChangeClients = new ArrayList<>();
    private final CarTrustAgentBleManager mCarTrustAgentBleManager;
    private CarTrustAgentEnrollmentRequestDelegate mEnrollmentDelegate;
    private Object mRemoteDeviceLock = new Object();
    @GuardedBy("mRemoteDeviceLock")
    private BluetoothDevice mRemoteEnrollmentDevice;
    @GuardedBy("this")
    private boolean mEnrollmentHandshakeAccepted;
    private final Map<Long, Boolean> mTokenActiveState = new HashMap<>();

    public CarTrustAgentEnrollmentService(CarTrustedDeviceService service,
            CarTrustAgentBleManager bleService) {
        mTrustedDeviceService = service;
        mCarTrustAgentBleManager = bleService;
    }

    public synchronized void init() {
        mCarTrustAgentBleManager.setupEnrollmentBleServer();
    }

    public synchronized void release() {
        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            client.mListenerBinder.unlinkToDeath(client, 0);
        }
        for (BleStateChangeClient client : mBleStateChangeClients) {
            client.mListenerBinder.unlinkToDeath(client, 0);
        }
        mEnrollmentStateClients.clear();
        setEnrollmentHandshakeAccepted(false);
    }

    // Implementing the ICarTrustAgentEnrollment interface

    /**
     * Begin BLE advertisement for Enrollment. This should be called from an app that conducts
     * the enrollment of the trusted device.
     */
    @Override
    public void startEnrollmentAdvertising() {
        // Stop any current broadcasts
        mTrustedDeviceService.getCarTrustAgentUnlockService().stopUnlockAdvertising();
        stopEnrollmentAdvertising();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "startEnrollmentAdvertising");
        }
        mCarTrustAgentBleManager.startEnrollmentAdvertising();
    }

    /**
     * Stop BLE advertisement for Enrollment
     */
    @Override
    public void stopEnrollmentAdvertising() {
        mCarTrustAgentBleManager.stopEnrollmentAdvertising();
    }

    @Override
    public void initiateEnrollmentHandshake(BluetoothDevice device) {
        // TODO(b/129029320) - this is not needed since the IHU plays the server
        // role and the secure handshake is initiated by the client.
    }

    /**
     * Called by the client to notify that the user has accepted a pairing code or any out-of-band
     * confirmation, and send confirmation signals to remote bluetooth device.
     *
     * @param device the remote Bluetooth device that will receive the signal.
     */
    @Override
    public void enrollmentHandshakeAccepted(BluetoothDevice device) {
        mCarTrustAgentBleManager.sendPairingCodeConfirmation(device);
        setEnrollmentHandshakeAccepted(true);
    }

    /**
     * Terminate the Enrollment process.  To be called when an error is encountered during
     * enrollment.  For example - user pressed cancel on pairing code confirmation or user
     * navigated away from the app before completing enrollment.
     */
    @Override
    public void terminateEnrollmentHandshake() {
        setEnrollmentHandshakeAccepted(false);
        // Disconnect from BLE
        mCarTrustAgentBleManager.disconnectRemoteDevice(mRemoteEnrollmentDevice);
    }

    /**
     * Returns if there is an active token for the given user and handle.
     *
     * @param handle handle corresponding to the escrow token
     * @param uid    user id
     * @return True if the escrow token is active, false if not
     */
    @Override
    public boolean isEscrowTokenActive(long handle, int uid) {
        if (mTokenActiveState.get(handle) != null) {
            return mTokenActiveState.get(handle);
        }
        return false;
    }

    @Override
    public void removeEscrowToken(long handle, int uid) {
        mEnrollmentDelegate.removeEscrowToken(handle, uid);
    }

    /**
     * Get the Handles corresponding to the token for the current user.  The client can use this
     * to list the trusted devices for the user.  This means that the client should maintain a map
     * of the handles:Bluetooth device names.
     *
     * @param uid user id
     * @return array of handles for the user.
     */
    @Override
    public long[] getEnrollmentHandlesForUser(int uid) {
        Set<String> handlesSet = mTrustedDeviceService.getSharedPrefs().getStringSet(
                String.valueOf(uid),
                new HashSet<>());
        long[] handles = new long[handlesSet.size()];
        int i = 0;
        for (String handle : handlesSet) {
            handles[i++] = Long.valueOf(handle);
        }
        return handles;
    }

    /**
     * Registers a {@link ICarTrustAgentEnrollmentCallback} to be notified for changes to the
     * enrollment state.
     *
     * @param listener {@link ICarTrustAgentEnrollmentCallback}
     */
    @Override
    public synchronized void registerEnrollmentCallback(ICarTrustAgentEnrollmentCallback listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener is null");
        }
        // If a new client is registering, create a new EnrollmentStateClient and add it to the list
        // of listening clients.
        EnrollmentStateClient client = findEnrollmentStateClientLocked(listener);
        if (client == null) {
            client = new EnrollmentStateClient(listener);
            try {
                listener.asBinder().linkToDeath(client, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot link death recipient to binder ", e);
                return;
            }
            mEnrollmentStateClients.add(client);
        }
    }

    void onEscrowTokenAdded(byte[] token, long handle, int uid) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onEscrowTokenAdded handle:" + handle + " uid:" + uid);
        }
        mTrustedDeviceService.getSharedPrefs().edit().putInt(String.valueOf(handle), uid).apply();
        Set<String> handles = mTrustedDeviceService.getSharedPrefs().getStringSet(
                String.valueOf(uid), new HashSet<>());
        handles.add(String.valueOf(handle));
        mTrustedDeviceService.getSharedPrefs().edit().putStringSet(String.valueOf(uid),
                handles).apply();

        if (mRemoteEnrollmentDevice == null) {
            Log.e(TAG, "onEscrowTokenAdded() but no remote device connected!");
            removeEscrowToken(handle, uid);
            return;
        }
        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            try {
                client.mListener.onEscrowTokenAdded(handle);
            } catch (RemoteException e) {
                Log.e(TAG, "onEscrowTokenAdded dispatch failed", e);
            }
        }
    }

    void onEscrowTokenRemoved(long handle, int uid) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onEscrowTokenRemoved handle:" + handle + " uid:" + uid);
        }
        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            try {
                client.mListener.onEscrowTokenRemoved(handle);
            } catch (RemoteException e) {
                Log.e(TAG, "onEscrowTokenAdded dispatch failed", e);
            }
        }
        SharedPreferences.Editor editor = mTrustedDeviceService.getSharedPrefs().edit();
        editor.remove(String.valueOf(handle));
        Set<String> handles = mTrustedDeviceService.getSharedPrefs().getStringSet(
                String.valueOf(uid), new HashSet<>());
        handles.remove(String.valueOf(handle));
        editor.putStringSet(String.valueOf(uid), handles);
        editor.apply();
    }

    void onEscrowTokenActiveStateChanged(long handle, boolean isTokenActive) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onEscrowTokenActiveStateChanged: " + Long.toHexString(handle));
        }
        mTokenActiveState.put(handle, isTokenActive);
        dispatchEscrowTokenActiveStateChanged(handle, isTokenActive);
        if (isTokenActive) {
            mCarTrustAgentBleManager.sendEnrollmentHandle(mRemoteEnrollmentDevice, handle);
        }
    }

    void onEnrollmentAdvertiseStartSuccess() {
        for (BleStateChangeClient client : mBleStateChangeClients) {
            try {
                client.mListener.onEnrollmentAdvertisingStarted();
            } catch (RemoteException e) {
                Log.e(TAG, "onAdvertiseSuccess dispatch failed", e);
            }
        }
    }

    void onEnrollmentAdvertiseStartFailure(int errorcode) {
        for (BleStateChangeClient client : mBleStateChangeClients) {
            try {
                client.mListener.onEnrollmentAdvertisingFailed(errorcode);
            } catch (RemoteException e) {
                Log.e(TAG, "onAdvertiseSuccess dispatch failed", e);
            }
        }
    }

    void onRemoteDeviceConnected(BluetoothDevice device) {
        synchronized (mRemoteDeviceLock) {
            mRemoteEnrollmentDevice = device;
        }
        for (BleStateChangeClient client : mBleStateChangeClients) {
            try {
                client.mListener.onBleEnrollmentDeviceConnected(device);
            } catch (RemoteException e) {
                Log.e(TAG, "onAdvertiseSuccess dispatch failed", e);
            }
        }
        //TODO(b/11788064) Fake Authentication to enable clients to go through the enrollment flow.
        fakeAuthentication();
    }

    void onRemoteDeviceDisconnected(BluetoothDevice device) {
        synchronized (mRemoteDeviceLock) {
            mRemoteEnrollmentDevice = null;
        }
        for (BleStateChangeClient client : mBleStateChangeClients) {
            try {
                client.mListener.onBleEnrollmentDeviceDisconnected(device);
            } catch (RemoteException e) {
                Log.e(TAG, "onAdvertiseSuccess dispatch failed", e);
            }
        }
    }

    void onEnrollmentDataReceived(byte[] value) {
        if (mEnrollmentDelegate == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Enrollment Delegate not set");
            }
            return;
        }
        // The phone is not expected to send any data until the user has accepted the
        // pairing.
        if (!mEnrollmentHandshakeAccepted) {
            Log.e(TAG, "User has not accepted the pairing code yet."
                    + Utils.byteArrayToHexString(value));
            return;
        }
        mEnrollmentDelegate.addEscrowToken(value, ActivityManager.getCurrentUser());
    }

    // TODO(b/11788064) Fake Authentication until we hook up the crypto lib
    private void fakeAuthentication() {
        if (mRemoteEnrollmentDevice == null) {
            Log.e(TAG, "Remote Device disconnected before Enrollment completed");
            return;
        }
        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            try {
                client.mListener.onAuthStringAvailable(mRemoteEnrollmentDevice, FAKE_AUTH_STRING);
            } catch (RemoteException e) {
                Log.e(TAG, "onAdvertiseSuccess dispatch failed", e);
            }
        }
    }

    private synchronized void setEnrollmentHandshakeAccepted(boolean accepted) {
        mEnrollmentHandshakeAccepted = accepted;
    }

    /**
     * Iterates through the list of registered Enrollment State Change clients -
     * {@link EnrollmentStateClient} and finds if the given client is already registered.
     *
     * @param listener Listener to look for.
     * @return the {@link EnrollmentStateClient} if found, null if not
     */
    @Nullable
    private EnrollmentStateClient findEnrollmentStateClientLocked(
            ICarTrustAgentEnrollmentCallback listener) {
        IBinder binder = listener.asBinder();
        // Find the listener by comparing the binder object they host.
        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            if (client.isHoldingBinder(binder)) {
                return client;
            }
        }
        return null;
    }

    /**
     * Unregister the given Enrollment State Change listener
     *
     * @param listener client to unregister
     */
    @Override
    public synchronized void unregisterEnrollmentCallback(
            ICarTrustAgentEnrollmentCallback listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener is null");
        }

        EnrollmentStateClient client = findEnrollmentStateClientLocked(listener);
        if (client == null) {
            Log.e(TAG, "unregisterEnrollmentCallback(): listener was not previously "
                    + "registered");
            return;
        }
        listener.asBinder().unlinkToDeath(client, 0);
        mEnrollmentStateClients.remove(client);
    }

    /**
     * Registers a {@link ICarTrustAgentBleCallback} to be notified for changes to the BLE state
     * changes.
     *
     * @param listener {@link ICarTrustAgentBleCallback}
     */
    @Override
    public synchronized void registerBleCallback(ICarTrustAgentBleCallback listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener is null");
        }
        // If a new client is registering, create a new EnrollmentStateClient and add it to the list
        // of listening clients.
        BleStateChangeClient client = findBleStateClientLocked(listener);
        if (client == null) {
            client = new BleStateChangeClient(listener);
            try {
                listener.asBinder().linkToDeath(client, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot link death recipient to binder " + e);
                return;
            }
            mBleStateChangeClients.add(client);
        }
    }

    /**
     * Iterates through the list of registered BLE State Change clients -
     * {@link BleStateChangeClient} and finds if the given client is already registered.
     *
     * @param listener Listener to look for.
     * @return the {@link BleStateChangeClient} if found, null if not
     */
    @Nullable
    private BleStateChangeClient findBleStateClientLocked(
            ICarTrustAgentBleCallback listener) {
        IBinder binder = listener.asBinder();
        // Find the listener by comparing the binder object they host.
        for (BleStateChangeClient client : mBleStateChangeClients) {
            if (client.isHoldingBinder(binder)) {
                return client;
            }
        }
        return null;
    }

    /**
     * Unregister the given BLE State Change listener
     *
     * @param listener client to unregister
     */
    @Override
    public synchronized void unregisterBleCallback(ICarTrustAgentBleCallback listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener is null");
        }

        BleStateChangeClient client = findBleStateClientLocked(listener);
        if (client == null) {
            Log.e(TAG, "unregisterBleCallback(): listener was not previously "
                    + "registered");
            return;
        }
        listener.asBinder().unlinkToDeath(client, 0);
        mBleStateChangeClients.remove(client);
    }

    /**
     * The interface that an enrollment delegate has to implement to add/remove escrow tokens.
     */
    interface CarTrustAgentEnrollmentRequestDelegate {
        /**
         * Add the given escrow token that was generated by the peer device that is being enrolled.
         *
         * @param token the 64 bit token
         * @param uid   user id
         */
        void addEscrowToken(byte[] token, int uid);

        /**
         * Remove the given escrow token.  This should be called when removing a trusted device.
         *
         * @param handle the 64 bit token
         * @param uid    user id
         */
        void removeEscrowToken(long handle, int uid);

        /**
         * Query if the token is active.  The result is asynchronously delivered through a callback
         * {@link CarTrustAgentEnrollmentService#onEscrowTokenActiveStateChanged(long, boolean)}
         *
         * @param handle the 64 bit token
         * @param uid    user id
         */
        void isEscrowTokenActive(long handle, int uid);
    }

    void setEnrollmentRequestDelegate(CarTrustAgentEnrollmentRequestDelegate delegate) {
        mEnrollmentDelegate = delegate;
    }

    void dump(PrintWriter writer) {
    }

    private void dispatchEscrowTokenActiveStateChanged(long handle, boolean active) {
        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            try {
                client.mListener.onEscrowTokenActiveStateChanged(handle, active);
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot notify client of a Token Activation change: " + active);
            }
        }
    }

    /**
     * Class that holds onto client related information - listener interface, process that hosts the
     * binder object etc.
     * <p>
     * It also registers for death notifications of the host.
     */
    private class EnrollmentStateClient implements IBinder.DeathRecipient {
        private final IBinder mListenerBinder;
        private final ICarTrustAgentEnrollmentCallback mListener;

        EnrollmentStateClient(ICarTrustAgentEnrollmentCallback listener) {
            mListener = listener;
            mListenerBinder = listener.asBinder();
        }

        @Override
        public void binderDied() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Binder died " + mListenerBinder);
            }
            mListenerBinder.unlinkToDeath(this, 0);
            synchronized (CarTrustAgentEnrollmentService.this) {
                mEnrollmentStateClients.remove(this);
            }
        }

        /**
         * Returns if the given binder object matches to what this client info holds.
         * Used to check if the listener asking to be registered is already registered.
         *
         * @return true if matches, false if not
         */
        public boolean isHoldingBinder(IBinder binder) {
            return mListenerBinder == binder;
        }
    }

    private class BleStateChangeClient implements IBinder.DeathRecipient {
        private final IBinder mListenerBinder;
        private final ICarTrustAgentBleCallback mListener;

        BleStateChangeClient(ICarTrustAgentBleCallback listener) {
            mListener = listener;
            mListenerBinder = listener.asBinder();
        }

        @Override
        public void binderDied() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Binder died " + mListenerBinder);
            }
            mListenerBinder.unlinkToDeath(this, 0);
            synchronized (CarTrustAgentEnrollmentService.this) {
                mBleStateChangeClients.remove(this);
            }
        }

        /**
         * Returns if the given binder object matches to what this client info holds.
         * Used to check if the listener asking to be registered is already registered.
         *
         * @return true if matches, false if not
         */
        public boolean isHoldingBinder(IBinder binder) {
            return mListenerBinder == binder;
        }

        public void onEnrollmentAdvertisementStarted() {
            try {
                mListener.onEnrollmentAdvertisingStarted();
            } catch (RemoteException e) {
                Log.e(TAG, "onEnrollmentAdvertisementStarted() failed", e);
            }
        }
    }
}
