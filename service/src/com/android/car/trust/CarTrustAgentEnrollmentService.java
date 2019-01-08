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
import android.car.trust.ICarTrustAgentBleCallback;
import android.car.trust.ICarTrustAgentEnrollment;
import android.car.trust.ICarTrustAgentEnrollmentCallback;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.car.CarServiceBase;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * A service that enables enrolling a phone as a trusted device for authenticating a user on the
 * IHU.  This implements the APIs that an enrollment app can call to conduct an enrollment.
 */
public class CarTrustAgentEnrollmentService extends ICarTrustAgentEnrollment.Stub implements
        CarServiceBase {
    private static final String TAG = "CarTrustAgentEnroll";
    private final Context mContext;
    // List of clients listening to Enrollment state change events.
    private final List<EnrollmentStateClient> mEnrollmentStateClients = new ArrayList<>();
    // List of clients listening to BLE state change events.
    private final List<BleStateChangeClient> mBleStateChangeClients = new ArrayList<>();

    public CarTrustAgentEnrollmentService(Context context) {
        mContext = context;
    }

    @Override
    public synchronized void init() {
    }

    @Override
    public synchronized void release() {
        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            client.mListenerBinder.unlinkToDeath(client, 0);
        }
        mEnrollmentStateClients.clear();
    }


    // Binder methods
    // TODO(b/120911995) The methods don't do anything yet.  The implementation will be checked in
    // a follow up CL.
    @Override
    public void startEnrollmentAdvertising() {
    }

    @Override
    public void stopEnrollmentAdvertising() {
    }

    @Override
    public void initiateEnrollmentHandshake(BluetoothDevice device) {
    }

    @Override
    public void enrollmentHandshakeAccepted() {
    }

    @Override
    public void terminateEnrollmentHandshake() {
    }

    @Override
    public void activateToken(long handle) {
    }

    @Override
    public void revokeTrust(long handle) {
    }

    @Override
    public int[] getEnrollmentHandlesForUser(int uid) {
        int[] handles = {};
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

    }

    @Override
    public void dump(PrintWriter writer) {
    }
}
