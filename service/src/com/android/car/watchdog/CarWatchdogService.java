/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.watchdog;

import static android.car.watchdog.CarWatchdogManager.TIMEOUT_CRITICAL;
import static android.car.watchdog.CarWatchdogManager.TIMEOUT_MODERATE;
import static android.car.watchdog.CarWatchdogManager.TIMEOUT_NORMAL;

import static com.android.car.CarLog.TAG_WATCHDOG;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.automotive.watchdog.ICarWatchdogClient;
import android.car.watchdog.ICarWatchdogService;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.VisibleForTesting;

import com.android.car.CarServiceBase;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Service to implement CarWatchdogManager API.
 *
 * <p>CarWatchdogService runs as car watchdog mediator, which checks clients' health status and
 * reports the result to car watchdog server.
 */
public final class CarWatchdogService extends ICarWatchdogService.Stub implements CarServiceBase {

    private static final boolean DEBUG = true; // STOPSHIP if true (b/151474489)
    private static final String TAG = TAG_WATCHDOG;
    private static final int[] ALL_TIMEOUTS =
            { TIMEOUT_CRITICAL, TIMEOUT_MODERATE, TIMEOUT_NORMAL };

    private final Context mContext;
    private final ICarWatchdogClientImpl mWatchdogClient;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    private final CarWatchdogDaemonHelper.OnConnectionChangeListener mConnectionListener =
            (connected) -> {
                if (connected) {
                    registerToDaemon();
                }
            };
    private final Object mLock = new Object();
    /*
     * Keeps the list of car watchdog client according to timeout:
     * key => timeout, value => ClientInfo list.
     * The value of SparseArray is guarded by mLock.
     */
    @GuardedBy("mLock")
    private final SparseArray<ArrayList<ClientInfo>> mClientMap = new SparseArray<>();
    /*
     * Keeps the map of car watchdog client being checked by CarWatchdogService according to
     * timeout: key => timeout, value => ClientInfo map.
     * The value is also a map: key => session id, value => ClientInfo.
     */
    @GuardedBy("mLock")
    private final SparseArray<SparseArray<ClientInfo>> mPingedClientMap = new SparseArray<>();
    /*
     * Keeps whether client health checking is being performed according to timeout:
     * key => timeout, value => boolean (whether client health checking is being performed).
     * The value of SparseArray is guarded by mLock.
     */
    @GuardedBy("mLock")
    private final SparseArray<Boolean> mClientCheckInProgress = new SparseArray<>();
    @GuardedBy("mLock")
    private final ArrayList<Integer> mClientsNotResponding = new ArrayList<>();
    @GuardedBy("mMainHandler")
    private int mLastSessionId;

    public CarWatchdogService(Context context) {
        this(context, new CarWatchdogDaemonHelper());
    }

    @VisibleForTesting
    public CarWatchdogService(Context context, CarWatchdogDaemonHelper carWatchdogDaemonHelper) {
        mContext = context;
        mCarWatchdogDaemonHelper = carWatchdogDaemonHelper;
        mWatchdogClient = new ICarWatchdogClientImpl(this);
    }

    @Override
    public void init() {
        for (int timeout : ALL_TIMEOUTS) {
            mClientMap.put(timeout, new ArrayList<ClientInfo>());
            mPingedClientMap.put(timeout, new SparseArray<ClientInfo>());
            mClientCheckInProgress.put(timeout, false);
        }
        mCarWatchdogDaemonHelper.addOnConnectionChangeListener(mConnectionListener);
        mCarWatchdogDaemonHelper.connect();
        if (DEBUG) {
            Log.d(TAG, "CarWatchdogService is initialized");
        }
    }

    @Override
    public void release() {
        unregisterFromDaemon();
        mCarWatchdogDaemonHelper.disconnect();
    }

    @Override
    public void dump(PrintWriter writer) {
        String indent = "  ";
        int count = 1;
        writer.println("*CarWatchdogService*");
        writer.println("Registered clients");
        synchronized (mLock) {
            for (int timeout : ALL_TIMEOUTS) {
                ArrayList<ClientInfo> clients = mClientMap.get(timeout);
                String timeoutStr = timeoutToString(timeout);
                for (int i = 0; i < clients.size(); i++, count++) {
                    ClientInfo clientInfo = clients.get(i);
                    writer.printf("%sclient #%d: timeout = %s, pid = %d\n",
                            indent, count, timeoutStr, clientInfo.pid);
                }
            }
        }
    }

    /**
     * Registers {@link android.automotive.watchdog. ICarWatchdogClient} to
     * {@link CarWatchdogService}.
     */
    @Override
    public void registerClient(ICarWatchdogClient client, int timeout) {
        ArrayList<ClientInfo> clients = mClientMap.get(timeout);
        if (clients == null) {
            Log.w(TAG, "Cannot register the client: invalid timeout");
            return;
        }
        synchronized (mLock) {
            IBinder binder = client.asBinder();
            for (int i = 0; i < clients.size(); i++) {
                ClientInfo clientInfo = clients.get(i);
                if (binder == clientInfo.client.asBinder()) {
                    Log.w(TAG,
                            "Cannot register the client: the client(pid:" + clientInfo.pid
                            + ") has been already registered");
                    return;
                }
            }
            int pid = Binder.getCallingPid();
            ClientInfo clientInfo = new ClientInfo(client, pid, timeout);
            try {
                clientInfo.linkToDeath();
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot register the client: linkToDeath to the client failed");
                return;
            }
            clients.add(clientInfo);
            if (DEBUG) {
                Log.d(TAG, "Client(pid: " + pid + ") is registered");
            }
        }
    }

    /**
     * Unregisters {@link android.automotive.watchdog. ICarWatchdogClient} from
     * {@link CarWatchdogService}.
     */
    @Override
    public void unregisterClient(ICarWatchdogClient client) {
        synchronized (mLock) {
            IBinder binder = client.asBinder();
            for (int timeout : ALL_TIMEOUTS) {
                ArrayList<ClientInfo> clients = mClientMap.get(timeout);
                for (int i = 0; i < clients.size(); i++) {
                    ClientInfo clientInfo = clients.get(i);
                    if (binder != clientInfo.client.asBinder()) {
                        continue;
                    }
                    clientInfo.unlinkToDeath();
                    clients.remove(i);
                    if (DEBUG) {
                        Log.d(TAG, "Client(pid: " + clientInfo.pid + ") is unregistered");
                    }
                    return;
                }
            }
        }
        Log.w(TAG, "Cannot unregister the client: the client has not been registered before");
        return;
    }

    /**
     * Tells {@link CarWatchdogService} that the client is alive.
     */
    @Override
    public void tellClientAlive(ICarWatchdogClient client, int sessionId) {
        synchronized (mLock) {
            for (int timeout : ALL_TIMEOUTS) {
                if (!mClientCheckInProgress.get(timeout)) {
                    continue;
                }
                SparseArray<ClientInfo> pingedClients = mPingedClientMap.get(timeout);
                ClientInfo clientInfo = pingedClients.get(sessionId);
                if (clientInfo != null && clientInfo.client.asBinder() == client.asBinder()) {
                    pingedClients.remove(sessionId);
                    return;
                }
            }
        }
    }

    private void registerToDaemon() {
        try {
            mCarWatchdogDaemonHelper.registerMediator(mWatchdogClient);
            if (DEBUG) {
                Log.d(TAG, "CarWatchdogService registers to car watchdog daemon");
            }
        } catch (RemoteException | IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG, "Cannot register to car watchdog daemon: " + e);
        }
    }

    private void unregisterFromDaemon() {
        try {
            mCarWatchdogDaemonHelper.unregisterMediator(mWatchdogClient);
            if (DEBUG) {
                Log.d(TAG, "CarWatchdogService unregisters from car watchdog daemon");
            }
        } catch (RemoteException | IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG, "Cannot unregister from car watchdog daemon: " + e);
        }
    }

    private void onClientDeath(ICarWatchdogClient client, int timeout) {
        synchronized (mLock) {
            removeClientLocked(client.asBinder(), timeout);
        }
    }

    private void doHealthCheck(int sessionId) {
        // For critical clients, the response status are checked just before reporting to car
        // watchdog daemon. For moderate and normal clients, the status are checked after allowed
        // delay per timeout.
        analyzeClientResponse(TIMEOUT_CRITICAL);
        reportHealthCheckResult(sessionId);
        sendPingToClients(TIMEOUT_CRITICAL);
        sendPingToClientsAndCheck(TIMEOUT_MODERATE);
        sendPingToClientsAndCheck(TIMEOUT_NORMAL);
    }

    private void analyzeClientResponse(int timeout) {
        // Clients which are not responding are stored in mClientsNotResponding, and will be dumped
        // and killed at the next response of CarWatchdogService to car watchdog daemon.
        SparseArray<ClientInfo> pingedClients = mPingedClientMap.get(timeout);
        synchronized (mLock) {
            for (int i = 0; i < pingedClients.size(); i++) {
                ClientInfo clientInfo = pingedClients.valueAt(i);
                mClientsNotResponding.add(clientInfo.pid);
                removeClientLocked(clientInfo.client.asBinder(), timeout);
            }
            mClientCheckInProgress.setValueAt(timeout, false);
        }
    }

    private void sendPingToClients(int timeout) {
        SparseArray<ClientInfo> pingedClients = mPingedClientMap.get(timeout);
        ArrayList<ClientInfo> clientsToCheck;
        synchronized (mLock) {
            pingedClients.clear();
            clientsToCheck = new ArrayList<>(mClientMap.get(timeout));
            for (int i = 0; i < clientsToCheck.size(); i++) {
                int sessionId = getNewSessionId();
                ClientInfo clientInfo = clientsToCheck.get(i);
                clientInfo.sessionId = sessionId;
                pingedClients.put(sessionId, clientInfo);
            }
            mClientCheckInProgress.setValueAt(timeout, true);
        }
        for (int i = 0; i < clientsToCheck.size(); i++) {
            ClientInfo clientInfo = clientsToCheck.get(i);
            try {
                clientInfo.client.checkIfAlive(clientInfo.sessionId, timeout);
            } catch (RemoteException e) {
                Log.w(TAG, "Sending a ping message to client(pid: " +  clientInfo.pid
                        + ") failed: " + e);
                synchronized (mLock) {
                    pingedClients.remove(clientInfo.sessionId);
                }
            }
        }
    }

    private void sendPingToClientsAndCheck(int timeout) {
        synchronized (mLock) {
            if (mClientCheckInProgress.get(timeout)) {
                return;
            }
        }
        sendPingToClients(timeout);
        mMainHandler.sendMessageDelayed(obtainMessage(CarWatchdogService::analyzeClientResponse,
                this, timeout), timeoutToDurationMs(timeout));
    }

    private int getNewSessionId() {
        if (++mLastSessionId <= 0) {
            mLastSessionId = 1;
        }
        return mLastSessionId;
    }

    @Nullable
    private ClientInfo removeClientLocked(IBinder clientBinder, int timeout) {
        ArrayList<ClientInfo> clients = mClientMap.get(timeout);
        for (int i = 0; i < clients.size(); i++) {
            ClientInfo clientInfo = clients.get(i);
            if (clientBinder == clientInfo.client.asBinder()) {
                clients.remove(i);
                return clientInfo;
            }
        }
        return null;
    }

    private void reportHealthCheckResult(int sessionId) {
        int[] clientsNotResponding;
        synchronized (mLock) {
            clientsNotResponding = toIntArray(mClientsNotResponding);
            mClientsNotResponding.clear();
        }
        try {
            mCarWatchdogDaemonHelper.tellMediatorAlive(mWatchdogClient, clientsNotResponding,
                    sessionId);
        } catch (RemoteException | IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG, "Cannot respond to car watchdog daemon (sessionId=" + sessionId + "): " + e);
        }
    }

    @NonNull
    private int[] toIntArray(@NonNull ArrayList<Integer> list) {
        int size = list.size();
        int[] intArray = new int[size];
        for (int i = 0; i < size; i++) {
            intArray[i] = list.get(i);
        }
        return intArray;
    }

    private String timeoutToString(int timeout) {
        switch (timeout) {
            case TIMEOUT_CRITICAL:
                return "critical";
            case TIMEOUT_MODERATE:
                return "moderate";
            case TIMEOUT_NORMAL:
                return "normal";
            default:
                Log.w(TAG, "Unknown timeout value");
                return "unknown";
        }
    }

    private long timeoutToDurationMs(int timeout) {
        switch (timeout) {
            case TIMEOUT_CRITICAL:
                return 3000L;
            case TIMEOUT_MODERATE:
                return 5000L;
            case TIMEOUT_NORMAL:
                return 10000L;
            default:
                Log.w(TAG, "Unknown timeout value");
                return 10000L;
        }
    }

    private final class ICarWatchdogClientImpl extends ICarWatchdogClient.Stub {
        private final WeakReference<CarWatchdogService> mService;

        private ICarWatchdogClientImpl(CarWatchdogService service) {
            mService = new WeakReference<>(service);
        }

        @Override
        public void checkIfAlive(int sessionId, int timeout) {
            CarWatchdogService service = mService.get();
            if (service == null) {
                Log.w(TAG, "CarWatchdogService is not available");
                return;
            }
            mMainHandler.sendMessage(obtainMessage(CarWatchdogService::doHealthCheck,
                    CarWatchdogService.this, sessionId));
        }
    }

    private final class ClientInfo implements IBinder.DeathRecipient {
        public ICarWatchdogClient client;
        public int pid;
        public int timeout;
        public volatile int sessionId;

        private ClientInfo(ICarWatchdogClient client, int pid, int timeout) {
            this.client = client;
            this.pid = pid;
            this.timeout = timeout;
        }

        private void linkToDeath() throws RemoteException {
            client.asBinder().linkToDeath(this, 0);
        }

        private void unlinkToDeath() {
            client.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            onClientDeath(client, timeout);
        }
    }
}
