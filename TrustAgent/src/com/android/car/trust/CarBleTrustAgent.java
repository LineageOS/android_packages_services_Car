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
 * limitations under the License
 */

package com.android.car.trust;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.UserSwitchObserver;
import android.bluetooth.BluetoothAdapter;
import android.car.trust.ICarTrustAgentBleService;
import android.car.trust.ICarTrustAgentTokenRequestDelegate;
import android.car.trust.ICarTrustAgentUnlockCallback;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.trust.TrustAgentService;
import android.util.Log;

/**
 * A BluetoothLE (BLE) based {@link TrustAgentService} that uses the escrow token unlock APIs. </p>
 *
 * This trust agent runs during direct boot and binds to a BLE service that listens for remote
 * devices to trigger an unlock. <p/>
 *
 * The permissions for this agent must be enabled as priv-app permissions for it to start.
 */
public class CarBleTrustAgent extends TrustAgentService {

    private static final String TAG = CarBleTrustAgent.class.getSimpleName();

    /**
     * {@link CarTrustAgentBleService} will callback this function when it receives both
     * handle and token.
     */
    private final ICarTrustAgentUnlockCallback mUnlockCallback =
            new ICarTrustAgentUnlockCallback.Stub() {
        @Override
        public void onUnlockDataReceived(byte[] token, long handle) throws RemoteException {
            UserHandle userHandle = getUserHandleByTokenHandle(handle);
            if (userHandle == null) {
                Log.e(TAG, "Unable to find user by token handle " + handle);
                return;
            }

            int uid = userHandle.getIdentifier();
            if (ActivityManager.getCurrentUser() != uid) {
                Log.d(TAG, "Switch to user: " + uid);
                // Try to unlock when user switch completes
                ActivityManager.getService().registerUserSwitchObserver(
                        getUserSwitchObserver(uid, token, handle), TAG);
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                am.switchUser(uid);
            } else {
                unlockUserInternally(uid, token, handle);
            }
        }
    };

    /**
     * Delegates the escrow token API calls from {@link CarTrustAgentBleService} to
     * {@link TrustAgentService}. Due to the asynchronous nature, the results will be posted to
     * {@link CarTrustAgentBleService} by the following calls
     * <ul>
     *     <li>{@link #onEscrowTokenAdded(byte[], long, UserHandle)}</li>
     *     <li>{@link #onEscrowTokenRemoved(long, boolean)}</li>
     *     <li>{@link #onEscrowTokenStateReceived(long, int)}</li>
     * </ul>
     */
    private final ICarTrustAgentTokenRequestDelegate mTokenRequestDelegate =
            new ICarTrustAgentTokenRequestDelegate.Stub() {
        @Override
        public void revokeTrust() {
            CarBleTrustAgent.this.revokeTrust();
        }

        @Override
        public void addEscrowToken(byte[] token, int uid) {
            CarBleTrustAgent.this.addEscrowToken(token, UserHandle.of(uid));
        }

        @Override
        public void removeEscrowToken(long handle, int uid) {
            CarBleTrustAgent.this.removeEscrowToken(handle, UserHandle.of(uid));
        }

        @Override
        public void isEscrowTokenActive(long handle, int uid) {
            CarBleTrustAgent.this.isEscrowTokenActive(handle, UserHandle.of(uid));
        }
    };

    /**
     * Service connection to {@link CarTrustAgentBleService}
     */
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "CarTrustAgentBleService connected");
            mCarTrustAgentBleServiceBound = true;
            mCarTrustAgentBleService = ICarTrustAgentBleService.Stub.asInterface(service);
            try {
                mCarTrustAgentBleService.registerUnlockCallback(mUnlockCallback);
                mCarTrustAgentBleService.setTokenRequestDelegate(mTokenRequestDelegate);
            } catch (RemoteException e) {
                Log.e(TAG, "Error registerUnlockCallback", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "CarTrustAgentBleService disconnected");
            if (mCarTrustAgentBleService != null) {
                try {
                    mCarTrustAgentBleService.unregisterUnlockCallback(mUnlockCallback);
                    mCarTrustAgentBleService.setTokenRequestDelegate(null);
                    mCarTrustAgentBleService.stopUnlockAdvertising();
                } catch (RemoteException e) {
                    Log.e(TAG, "Error unregisterUnlockCallback", e);
                }
                mCarTrustAgentBleService = null;
                mCarTrustAgentBleServiceBound = false;
            }
        }
    };

    /**
     * Receives the bluetooth state change broadcasts. Bluetooth is restarted when switching user,
     * we need to ensure calling {@link ICarTrustAgentBleService#startUnlockAdvertising} after
     * bluetooth is started.
     */
    private final BroadcastReceiver mBluetoothBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    onBluetoothStateChanged(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
                    break;
            }
        }
    };

    private ICarTrustAgentBleService mCarTrustAgentBleService;
    private boolean mCarTrustAgentBleServiceBound;

    /**
     * TODO: Currently it relies on {@link #onDeviceLocked()} and {@link #onDeviceUnlocked()}
     * callback, and these callbacks won't happen if the user has unlocked once.
     */
    private boolean mIsOnLockScreen;

    @Override
    public void onCreate() {
        super.onCreate();
        setManagingTrust(true);
        bindService(new Intent(this, CarTrustAgentBleService.class),
                mServiceConnection, Context.BIND_AUTO_CREATE);
        IntentFilter intentFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBluetoothBroadcastReceiver, intentFilter);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Car Trust agent shutting down");
        if (mCarTrustAgentBleServiceBound) {
            unbindService(mServiceConnection);
        }
        unregisterReceiver(mBluetoothBroadcastReceiver);
        super.onDestroy();
    }

    @Override
    public void onDeviceLocked() {
        super.onDeviceLocked();
        mIsOnLockScreen = true;
        if (mCarTrustAgentBleServiceBound) {
            try {
                mCarTrustAgentBleService.startUnlockAdvertising();
            } catch (RemoteException e) {
                Log.e(TAG, "Error startUnlockAdvertising", e);
            }
        }
    }

    @Override
    public void onDeviceUnlocked() {
        super.onDeviceUnlocked();
        mIsOnLockScreen = false;
        if (mCarTrustAgentBleServiceBound) {
            try {
                mCarTrustAgentBleService.stopUnlockAdvertising();
            } catch (RemoteException e) {
                Log.e(TAG, "Error stopUnlockAdvertising", e);
            }
        }
        // Revoke trust right after to enable keyguard when switching user
        revokeTrust();
    }

    private UserSwitchObserver getUserSwitchObserver(int uid,
            byte[] token, long handle) {
        return new UserSwitchObserver() {
            @Override
            public void onUserSwitchComplete(int newUserId) throws RemoteException {
                if (uid != newUserId) return;
                unlockUserInternally(uid, token, handle);
                ActivityManager.getService().unregisterUserSwitchObserver(this);
            }

            @Override
            public void onLockedBootComplete(int newUserId) {
                // ignored.
            }
        };
    }

    private void unlockUserInternally(int uid, byte[] token, long handle) {
        Log.d(TAG, "About to unlock user: " + uid);
        unlockUserWithToken(handle, token, UserHandle.of(uid));
        grantTrust("Granting trust from escrow token",
                0, FLAG_GRANT_TRUST_DISMISS_KEYGUARD);
    }

    private void onBluetoothStateChanged(int state) {
        Log.d(TAG, "onBluetoothStateChanged: " + state);
        if ((state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_BLE_ON)
                && mCarTrustAgentBleServiceBound
                && mIsOnLockScreen) {
            try {
                mCarTrustAgentBleService.startUnlockAdvertising();
            } catch (RemoteException e) {
                Log.e(TAG, "Error startUnlockAdvertising", e);
            }
        }
    }

    @Override
    public void onEscrowTokenRemoved(long handle, boolean successful) {
        if (mCarTrustAgentBleServiceBound) {
            try {
                mCarTrustAgentBleService.onEscrowTokenRemoved(handle, successful);
                Log.v(TAG, "Callback onEscrowTokenRemoved");
            } catch (RemoteException e) {
                Log.e(TAG, "Error callback onEscrowTokenRemoved", e);
            }
        }
    }

    @Override
    public void onEscrowTokenStateReceived(long handle, int tokenState) {
        boolean isActive = tokenState == TOKEN_STATE_ACTIVE;
        if (mCarTrustAgentBleServiceBound) {
            try {
                mCarTrustAgentBleService.onEscrowTokenActiveStateChanged(handle, isActive);
                Log.v(TAG, "Callback onEscrowTokenActiveStateChanged");
            } catch (RemoteException e) {
                Log.e(TAG, "Error callback onEscrowTokenActiveStateChanged", e);
            }
        }
    }

    @Override
    public void onEscrowTokenAdded(byte[] token, long handle, UserHandle user) {
        if (mCarTrustAgentBleServiceBound) {
            try {
                mCarTrustAgentBleService.onEscrowTokenAdded(token, handle, user.getIdentifier());
                Log.v(TAG, "Callback onEscrowTokenAdded");
            } catch (RemoteException e) {
                Log.e(TAG, "Error callback onEscrowTokenAdded", e);
            }
        }
    }

    private @Nullable UserHandle getUserHandleByTokenHandle(long tokenHandle) {
        if (mCarTrustAgentBleServiceBound) {
            try {
                int userId = mCarTrustAgentBleService.getUserIdByEscrowTokenHandle(tokenHandle);
                return userId < 0 ? null : UserHandle.of(userId);
            } catch (RemoteException e) {
                Log.e(TAG, "Error getUserHandleByTokenHandle");
            }
        }
        return null;
    }
}
