/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.google.android.car.pbapsink;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.client.pbap.BluetoothPbapClient;
import android.content.ContentResolver;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.util.Log;
import android.util.Pair;

import com.android.vcard.VCardEntry;
import com.google.android.car.pbapsink.R;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * These are the possible paths that can be pulled:
 *       BluetoothPbapClient.PB_PATH;
 *       BluetoothPbapClient.SIM_PB_PATH;
 *       BluetoothPbapClient.ICH_PATH;
 *       BluetoothPbapClient.SIM_ICH_PATH;
 *       BluetoothPbapClient.OCH_PATH;
 *       BluetoothPbapClient.SIM_OCH_PATH;
 *       BluetoothPbapClient.MCH_PATH;
 *       BluetoothPbapClient.SIM_MCH_PATH;
 */
public class PbapPCEClient extends Service implements PbapHandler.PbapListener {
    private static final String TAG = "PbapPCEClient";

    private final Queue<PullRequest> mPendingRequests = new ArrayDeque<PullRequest>();
    private final AtomicBoolean mPendingPull = new AtomicBoolean(false);
    private BluetoothDevice mDevice;
    private BluetoothPbapClient mClient;
    private boolean mClientConnected = false;
    private PbapHandler mHandler;
    private Handler mSelfHandler;
    private PullRequest mLastPull;
    private Account mAccount = null;

    private AccountManager mAccountManager;
    private DeleteCallLogTask mDeleteCallLogTask;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        mSelfHandler = new Handler(getMainLooper());
        mHandler = new PbapHandler(this);
        mAccountManager = AccountManager.get(this);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        mHandler = null;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onReceive intent=" + intent);
        String action = intent != null ? intent.getAction() : null;

        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            BluetoothDevice device =
                    (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            // Handle a new device connecting.
            handleConnect(device);
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED.equals(action) ||
                   BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            BluetoothDevice device =
                    (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            // Handle a device disconnecting.
            handleDisconnect(device);
        } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_TURNING_OFF ||
                state == BluetoothAdapter.STATE_OFF) {
                handleDisconnect(null);
            }
        }else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            handleBootComplete();
        }

        // We always start the service as STICKY. But if we want to kill the service (on a
        // disconnect or on maintenance tasks such as ACTION_BOOT_COMPLETE we post a new runnable
        // which will run *after* START_STICKY is returned.
        return START_STICKY;
    }

    private synchronized boolean maybePull() {
        if (!mClientConnected) {
            Log.w(TAG, "Client not connected yet -- will execute on next cycle.");
            return false;
        }

        return maybePullLocked();
    }

    private boolean maybePullLocked() {
        // I _think_ this is ok. Maybe.
        if (mPendingPull.compareAndSet(false, true)) {
            if (mPendingRequests.isEmpty()) {
                mPendingPull.set(false);
                return false;
            }

            if (mClient != null) {
                mLastPull = mPendingRequests.remove();
                Log.d(TAG, "Pulling phone book from: " + mLastPull.path);
                return mClient.pullPhoneBook(mLastPull.path);
            }
        }
        return false;
    }

    private void pullComplete() {
        mPendingPull.set(false);
        maybePull();
    }

    @Override
    public void onPhoneBookPullDone(List<VCardEntry> entries) {
        mLastPull.onPullComplete(true, entries);
        pullComplete();
    }

    @Override
    public void onPhoneBookError() {
        Log.d(TAG, "Error, mLastPull = "  + mLastPull);
        mLastPull.onPullComplete(false, null);
        pullComplete();
    }

    @Override
    public synchronized void onPbapClientConnected(boolean status) {
        mClientConnected = status;
        if (mClientConnected == false) {
            // If we are disconnected then whatever the current device is we should simply clean up.
            handleDisconnect(null);
        }
        if (mClientConnected == true) maybePullLocked();
    }

    private void handleConnect(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalStateException(TAG + ":Connect with null device!");
        } else if (mDevice != null && !mDevice.equals(device)) {
            // Check that we are not already connected to an existing different device.
            // Since the device can be connected to multiple external devices -- we use the honor
            // protocol and only accept the first connecting device.
            Log.e(TAG, ":Got a connected event when connected to a different device. " +
                  "existing = " + mDevice + " new = " + device);
            return;
        } else if (device.equals(mDevice)) {
            Log.w(TAG, "Got a connected event for the same device. Ignoring!");
            return;
        }

        // Cancel any pending delete tasks that might race.
        if (mDeleteCallLogTask != null) {
            mDeleteCallLogTask.cancel(true);
        }
        mDeleteCallLogTask = new DeleteCallLogTask();

        // Cleanup any existing accounts if we get a connected event but previous account state was
        // left hanging (such as unclean shutdown).
        removeUncleanAccounts();

        // Update the device.
        mDevice = device;
        mClient = new BluetoothPbapClient(mDevice, mHandler);
        mClient.connect();

        // Add the account. This should give us a place to stash the data.
        addAccount(device.getAddress());
        downloadPhoneBook();
        downloadCallLogs();
    }

    private void handleDisconnect(BluetoothDevice device) {
        if (device == null) {
            // If we have a null device then disconnect the current device.
            device = mDevice;
        } else if (mDevice == null) {
            Log.w(TAG, "No existing device connected to service - ignoring device = " + device);
            return;
        } else if (!mDevice.equals(device)) {
            Log.w(TAG, "Existing device different from disconnected device. existing = " + mDevice +
                       " disconnecting device = " + device);
            return;
        }

        if (device != null) {
            removeAccount(mAccount);
            mAccount = null;
        }
        resetState();

        // We stop the service in separate runnable which can run *after* onStartCommand (which
        // calls this function) has finished.
        stopSelfInSeparateRunnable();
    }

    private void handleBootComplete() {
        if (mDevice != null) {
            // We are already connected - hence boot complete came a bit too late. Ignore.
            Log.w(TAG, "Boot complete received when we are connected to device = " + mDevice +
                       " . Ignoring this broadcast.");
            return;
        }

        // Device is NULL, we go on remove any unclean shutdown accounts.
        removeUncleanAccounts();
        resetState();
        stopSelfInSeparateRunnable();
    }

    private void resetState() {
        if (mClient != null) {
            // This should abort any inflight messages.
            mClient.disconnect();
        }
        mClient = null;

        if (mDeleteCallLogTask != null &&
            mDeleteCallLogTask.getStatus() == AsyncTask.Status.PENDING) {
            mDeleteCallLogTask.execute();
        }
        mDevice = null;
        mAccount = null;
    }

    // Stop the service always after onStartCommand has been finished running.
    private void stopSelfInSeparateRunnable() {
        mSelfHandler.post(
            new Runnable() {
                @Override
                public void run() {
                    stopSelf();
                }
            }
        );
    }

    private void removeUncleanAccounts() {
        // Find all accounts that match the type "pbap" and delete them. This section is
        // executed only if the device was shut down in an unclean state and contacts persisted.
        Account[] accounts =
            mAccountManager.getAccountsByType(getString(R.string.account_type));
        Log.w(TAG, "Found " + accounts.length + " unclean accounts");
        for (Account acc : accounts) {
            Log.w(TAG, "Deleting " + acc);
            // The device ID is the name of the account.
            removeAccount(acc);
        }
    }

    private void downloadCallLogs() {
        // Download Incoming Call Logs.
        CallLogPullRequest ichCallLog = new CallLogPullRequest(this, BluetoothPbapClient.ICH_PATH);
        addPullRequest(ichCallLog);

        // Downoad Outgoing Call Logs.
        CallLogPullRequest ochCallLog = new CallLogPullRequest(this, BluetoothPbapClient.OCH_PATH);
        addPullRequest(ochCallLog);

        // Downoad Missed Call Logs.
        CallLogPullRequest mchCallLog = new CallLogPullRequest(this, BluetoothPbapClient.MCH_PATH);
        addPullRequest(mchCallLog);
    }

    private void downloadPhoneBook() {
        // Download the phone book.
        PhonebookPullRequest pb = new PhonebookPullRequest(this, mAccount);
        addPullRequest(pb);
    }

    private class DeleteCallLogTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... unused) {
            if (isCancelled()) {
                return null;
            }

            getContentResolver().delete(CallLog.Calls.CONTENT_URI, null, null);
            Log.d(TAG, "Call logs deleted.");
            return null;
        }
    }

    private boolean addAccount(String id) {
        mAccount = new Account(id, getString(R.string.account_type));
        if (mAccountManager.addAccountExplicitly(mAccount, null, null)) {
            Log.d(TAG, "Added account " + mAccount);
            return true;
        }
        throw new IllegalStateException(TAG + ":Failed to add account!");
    }

    private boolean removeAccount(Account acc) {
        if (mAccountManager.removeAccountExplicitly(acc)) {
            Log.d(TAG, "Removed account " + acc);
            return true;
        }
        Log.e(TAG, "Failed to remove account " + mAccount);
        return false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind " + intent);
        return null;
    }

    public void addPullRequest(PullRequest r) {
        Log.d(TAG, "pull request mClient=" + mClient + " connected= " +
            mClientConnected + " mDevice=" + mDevice + " path= " + r.path);
        if (mClient == null || mDevice == null) {
            // It seems we want to pull but the bt connection isn't up, fail it
            // immediately.
            Log.w(TAG, "aborting pull request.");
            r.onPullComplete(false, null);
            return;
        }
        mPendingRequests.add(r);
        maybePull();
    }
}
