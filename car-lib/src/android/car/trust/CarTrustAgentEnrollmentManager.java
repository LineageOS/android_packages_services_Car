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

package android.car.trust;

import static android.car.Car.PERMISSION_CAR_ENROLL_TRUST;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.bluetooth.BluetoothDevice;
import android.car.CarManagerBase;
import android.car.CarNotConnectedException;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


/**
 * APIs to help enroll a remote device as a trusted device that can be used to authenticate a user
 * in the head unit.
 * <p>
 * The call sequence to add a new trusted device from the client should be as follows:
 * <ol>
 * <li> setEnrollmentCallback()
 * <li> setBleCallback(bleCallback)
 * <li> startEnrollmentAdvertising()
 * <li> wait for onEnrollmentAdvertisingStarted() or
 * <li> wait for onBleEnrollmentDeviceConnected() and check if the device connected is the right
 *  one.
 * <li> initiateEnrollmentHandhake()
 * <li> wait for onAuthStringAvailable() to get the pairing code to display to the user
 * <li> enrollmentHandshakeAccepted() after user confirms the pairing code
 * <li> wait for onEscrowTokenAdded()
 * <li> Authenticate user's credentials by showing the lock screen
 * <li> activateToken()
 * <li> wait for onEscrowTokenActiveStateChanged() to add the device as a trusted device and show
 * in the list
 * </ol>
 *
 * @hide
 */
@SystemApi
public final class CarTrustAgentEnrollmentManager implements CarManagerBase {
    private static final String TAG = "CarTrustEnrollMgr";
    private final Context mContext;
    private final ICarTrustAgentEnrollment mEnrollmentService;
    private Object mListenerLock = new Object();
    @GuardedBy("mListenerLock")
    private CarTrustAgentEnrollmentCallback mEnrollmentCallback;
    @GuardedBy("mListenerLock")
    private CarTrustAgentBleCallback mBleCallback;
    @GuardedBy("mListenerLock")
    private final ListenerToEnrollmentService mListenerToEnrollmentService =
            new ListenerToEnrollmentService(this);
    private final ListenerToBleService mListenerToBleService = new ListenerToBleService(this);


    /** @hide */
    public CarTrustAgentEnrollmentManager(IBinder service, Context context, Handler handler) {
        mContext = context;
        mEnrollmentService = ICarTrustAgentEnrollment.Stub.asInterface(service);
    }

    /** @hide */
    @Override
    public synchronized void onCarDisconnected() {
    }

    /**
     * Starts broadcasting enrollment UUID on BLE.
     * Phones can scan and connect for the enrollment process to begin.
     */
    @RequiresPermission(PERMISSION_CAR_ENROLL_TRUST)
    public void startEnrollmentAdvertising() throws CarNotConnectedException {
        try {
            mEnrollmentService.startEnrollmentAdvertising();
        } catch (RemoteException e) {
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Stops Enrollment advertising.
     */
    @RequiresPermission(PERMISSION_CAR_ENROLL_TRUST)
    public void stopEnrollmentAdvertising() throws CarNotConnectedException {
        try {
            mEnrollmentService.stopEnrollmentAdvertising();
        } catch (RemoteException e) {
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Initiates the handshake with the phone for enrollment.  This should be called after the
     * user has confirmed the phone that is requesting enrollment.
     *
     * @param device the remote Bluetooth device that is trying to enroll.
     */
    @RequiresPermission(PERMISSION_CAR_ENROLL_TRUST)
    public void initiateEnrollmentHandshake(BluetoothDevice device)
            throws CarNotConnectedException {
        try {
            mEnrollmentService.initiateEnrollmentHandshake(device);
        } catch (RemoteException e) {
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Confirms that the enrollment handshake has been accepted by the user.  This should be called
     * after the user has confirmed the verification code displayed on the UI.
     */
    @RequiresPermission(PERMISSION_CAR_ENROLL_TRUST)
    public void enrollmentHandshakeAccepted() throws CarNotConnectedException {
        try {
            mEnrollmentService.enrollmentHandshakeAccepted();
        } catch (RemoteException e) {
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Provides an option to quit enrollment if the pairing code doesn't match for example.
     */
    @RequiresPermission(PERMISSION_CAR_ENROLL_TRUST)
    public void terminateEnrollmentHandshake() throws CarNotConnectedException {
        try {
            mEnrollmentService.terminateEnrollmentHandshake();
        } catch (RemoteException e) {
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Activate the newly added escrow token.
     *
     * @param handle the handle corresponding to the escrow token
     */
    @RequiresPermission(PERMISSION_CAR_ENROLL_TRUST)
    public void activateToken(long handle) throws CarNotConnectedException {
        try {
            mEnrollmentService.activateToken(handle);
        } catch (RemoteException e) {
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Revoke trust for the remote device denoted by the handle.
     *
     * @param handle the handle associated with the escrow token
     */
    @RequiresPermission(PERMISSION_CAR_ENROLL_TRUST)
    public void revokeTrust(long handle) throws CarNotConnectedException {
        try {
            mEnrollmentService.revokeTrust(handle);
        } catch (RemoteException e) {
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Register for enrollment event callbacks.
     *
     * @param callback The callback methods to call, null to unregister
     */
    @RequiresPermission(PERMISSION_CAR_ENROLL_TRUST)
    public void setEnrollmentCallback(@Nullable CarTrustAgentEnrollmentCallback callback)
            throws CarNotConnectedException {
        if (callback == null) {
            unregisterEnrollmentCallback();
        } else {
            registerEnrollmentCallback(callback);
        }
    }

    private void registerEnrollmentCallback(CarTrustAgentEnrollmentCallback callback)
            throws CarNotConnectedException {
        synchronized (mListenerLock) {
            if (callback != null && mEnrollmentCallback == null) {
                try {
                    mEnrollmentService.registerEnrollmentCallback(mListenerToEnrollmentService);
                    mEnrollmentCallback = callback;
                } catch (RemoteException e) {
                    throw new CarNotConnectedException(e);
                }
            }
        }
    }

    private void unregisterEnrollmentCallback() throws CarNotConnectedException {
        synchronized (mListenerLock) {
            if (mEnrollmentCallback != null) {
                try {
                    mEnrollmentService.unregisterEnrollmentCallback(mListenerToEnrollmentService);
                } catch (RemoteException e) {
                    throw new CarNotConnectedException(e);
                }
                mEnrollmentCallback = null;
            }
        }
    }

    /**
     * Register for general BLE callbacks
     *
     * @param callback The callback methods to call, null to unregister
     */
    @RequiresPermission(PERMISSION_CAR_ENROLL_TRUST)
    public void setBleCallback(@Nullable CarTrustAgentBleCallback callback)
            throws CarNotConnectedException {
        if (callback == null) {
            unregisterBleCallback();
        } else {
            registerBleCallback(callback);
        }
    }

    private void registerBleCallback(CarTrustAgentBleCallback callback)
            throws CarNotConnectedException {
        synchronized (mListenerLock) {
            if (callback != null && mBleCallback == null) {
                try {
                    mEnrollmentService.registerBleCallback(mListenerToBleService);
                    mBleCallback = callback;
                } catch (RemoteException e) {
                    throw new CarNotConnectedException(e);
                }
            }
        }
    }

    private void unregisterBleCallback() throws CarNotConnectedException {
        synchronized (mListenerLock) {
            if (mBleCallback != null) {
                try {
                    mEnrollmentService.unregisterBleCallback(mListenerToBleService);
                } catch (RemoteException e) {
                    throw new CarNotConnectedException(e);
                }
                mBleCallback = null;
            }
        }
    }

    /**
     * Provides a list of enrollment handles for the given user id.
     * Each enrollment handle corresponds to a trusted device for the given user.
     *
     * @param uid user id.
     * @return list of the Enrollment handles for the user id.
     */
    @RequiresPermission(PERMISSION_CAR_ENROLL_TRUST)
    public List<Integer> getEnrollmentHandlesForUser(int uid) throws CarNotConnectedException {
        try {
            return Arrays.stream(
                    mEnrollmentService.getEnrollmentHandlesForUser(uid)).boxed().collect(
                    Collectors.toList());
        } catch (RemoteException e) {
            throw new CarNotConnectedException(e);
        }
    }

    /**
     * Callback interface for Trusted device enrollment applications to implement.  The applications
     * get notified on various enrollment state change events.
     */
    public interface CarTrustAgentEnrollmentCallback {
        /**
         * Communicate about failure/timeouts in the handshake process.
         *
         * @param device the remote device trying to enroll
         * @param errorCode information on what failed.
         */
        void onEnrollmentHandshakeFailure(BluetoothDevice device, int errorCode);

        /**
         * Present the pairing/authentication string to the user.
         *
         * @param device the remote device trying to enroll
         * @param authString the authentication string to show to the user to confirm across
         *                   both devices
         */
        void onAuthStringAvailable(BluetoothDevice device, String authString);

        /**
         * Escrow token was received and the Trust Agent framework has generated a corresponding
         * handle.
         *
         * @param handle the handle associated with the escrow token.
         */
        void onEscrowTokenAdded(long handle);

        /**
         * Escrow token corresponding to the given handle has been removed.
         *
         * @param handle  the handle associated with the escrow token.
         * @param success status of the revoke operation.
         */
        void onTrustRevoked(long handle, boolean success);

        /**
         * Escrow token's active state changed.
         *
         * @param handle the handle associated with the escrow token
         * @param active True if token has been activated, false if not.
         */
        void onEscrowTokenActiveStateChanged(long handle, boolean active);

    }

    /**
     * Callback interface for Trusted device enrollment applications to implement.  The applications
     * get notified on various BLE state change events that happen during trusted device enrollment.
     */
    public interface CarTrustAgentBleCallback {
        /**
         * Indicates a remote device connected on BLE.
         */
        void onBleEnrollmentDeviceConnected(BluetoothDevice device);

        /**
         * Indicates a remote device disconnected on BLE.
         */
        void onBleEnrollmentDeviceDisconnected(BluetoothDevice device);

        /**
         * Indicates that the device is broadcasting for trusted device enrollment on BLE.
         */
        void onEnrollmentAdvertisingStarted();

        /**
         * Indicates a failure in BLE broadcasting for enrollment.
         */
        void onEnrollmentAdvertisingFailed(int errorCode);
    }

    // TODO(ramperry) - call the client callbacks from the methods of this listener to the service
    // callbacks.
    private class ListenerToEnrollmentService extends ICarTrustAgentEnrollmentCallback.Stub {
        private final WeakReference<CarTrustAgentEnrollmentManager> mMgr;

        ListenerToEnrollmentService(CarTrustAgentEnrollmentManager mgr) {
            mMgr = new WeakReference<>(mgr);
        }

        /**
         * Communicate about failure/timeouts in the handshake process.
         */
        public void onEnrollmentHandshakeFailure(BluetoothDevice device, int errorCode) {
        }

        /**
         * Present the pairing/authentication string to the user.
         */
        public void onAuthStringAvailable(BluetoothDevice device, byte[] authString) {
        }

        /**
         * Escrow token was received and the Trust Agent framework has generated a corresponding
         * handle.
         */
        public void onEscrowTokenAdded(long handle) {
        }

        /**
         * Escrow token corresponding to the given handle has been removed.
         */
        public void onTrustRevoked(long handle, boolean success) {
        }

        /**
         * Escrow token's active state changed.
         */
        public void onEscrowTokenActiveStateChanged(long handle, boolean active) {
        }
    }

    // TODO(ramperry) - call the client callbacks from the methods of this listener to the service
    // callbacks.
    private class ListenerToBleService extends ICarTrustAgentBleCallback.Stub {
        private final WeakReference<CarTrustAgentEnrollmentManager> mMgr;

        ListenerToBleService(CarTrustAgentEnrollmentManager mgr) {
            mMgr = new WeakReference<>(mgr);
        }

        /**
         * Called when the GATT server is started and BLE is successfully advertising for
         * enrollment.
         */
        public void onEnrollmentAdvertisingStarted() {
        }

        /**
         * Called when the BLE enrollment advertisement fails to start.
         * see AdvertiseCallback#ADVERTISE_FAILED_* for possible error codes.
         */
        public void onEnrollmentAdvertisingFailed(int errorCode) {
        }

        /**
         * Called when a remote device is connected on BLE.
         */
        public void onBleEnrollmentDeviceConnected(BluetoothDevice device) {
        }

        /**
         * Called when a remote device is disconnected on BLE.
         */
        public void onBleEnrollmentDeviceDisconnected(BluetoothDevice device) {
        }
    }
}
