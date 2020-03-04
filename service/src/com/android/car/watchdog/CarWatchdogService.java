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

import static com.android.car.CarLog.TAG_WATCHDOG;

import android.annotation.Nullable;
import android.automotive.watchdog.ICarWatchdog;
import android.automotive.watchdog.ICarWatchdogClient;
import android.car.watchdog.ICarWatchdogService;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.car.CarServiceBase;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;

/**
 * Service to implement CarWatchdogManager API.
 *
 * <p>CarWatchdogService runs as car watchdog mediator, which checks clients' health status and
 * reports the result to car watchdog server.
 */
public final class CarWatchdogService extends ICarWatchdogService.Stub implements CarServiceBase {

    private static final long CAR_WATCHDOG_DAEMON_BIND_RETRY_INTERVAL_MS = 500;
    private static final long CAR_WATCHDOG_DAEMON_FIND_MARGINAL_TIME_MS = 300;
    private static final int CAR_WATCHDOG_DAEMON_BIND_MAX_RETRY = 3;
    private static final String CAR_WATCHDOG_DAEMON_INTERFACE =
            "android.automotive.watchdog.ICarWatchdog/default";

    private final Context mContext;
    private final ICarWatchdogClientImpl mWatchdogClient;
    private final Object mLock = new Object();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    @GuardedBy("mLock")
    private @Nullable ICarWatchdog mCarWatchdogDaemon;

    public CarWatchdogService(Context context) {
        // Car watchdog daemon is found at init().
        this(context, /* daemon= */ null);
    }

    @VisibleForTesting
    public CarWatchdogService(Context context, @Nullable ICarWatchdog daemon) {
        mContext = context;
        // For testing, we use the given car watchdog daemon.
        mCarWatchdogDaemon = daemon;
        mWatchdogClient = new ICarWatchdogClientImpl(this);
    }

    @Override
    public void init() {
        ICarWatchdog daemon;
        synchronized (mLock) {
            daemon = mCarWatchdogDaemon;
        }
        if (daemon == null) {
            connectToDaemon(CAR_WATCHDOG_DAEMON_BIND_MAX_RETRY);
        }
    }

    @Override
    public void release() {
        ICarWatchdog daemon;
        synchronized (mLock) {
            daemon = mCarWatchdogDaemon;
            mCarWatchdogDaemon = null;
        }
        try {
            daemon.unregisterClient(mWatchdogClient);
        } catch (RemoteException e) {
            Log.w(TAG_WATCHDOG, "Cannot unregister from car watchdog daemon: " + e);
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*CarWatchdogService*");
        synchronized (mLock) {
            writer.printf("bound to car watchddog daemon: %b\n", mCarWatchdogDaemon != null);
        }
    }

    @Override
    public void registerClient(ICarWatchdogClient client, int timeout) {
        // TODO(b/145556670): implement body.
    }

    @Override
    public void unregisterClient(ICarWatchdogClient client) {
        // TODO(b/145556670): implement body.
    }

    @Override
    public void tellClientAlive(ICarWatchdogClient client, int sessionId) {
        // TODO(b/145556670): implement body.
    }

    private void connectToDaemon(int retryCount) {
        if (retryCount <= 0) {
            Log.e(TAG_WATCHDOG, "Cannot reconnect to car watchdog daemon after retrying "
                    + CAR_WATCHDOG_DAEMON_BIND_MAX_RETRY + " times");
            return;
        }
        if (makeBinderConnection()) {
            Log.i(TAG_WATCHDOG, "Connected to car watchdog daemon");
            return;
        }
        mMainHandler.postDelayed(() -> {
            connectToDaemon(retryCount - 1);
        }, CAR_WATCHDOG_DAEMON_BIND_RETRY_INTERVAL_MS);
    }

    private boolean makeBinderConnection() {
        long currentTimeMs = System.currentTimeMillis();
        IBinder binder = ServiceManager.getService(CAR_WATCHDOG_DAEMON_INTERFACE);
        if (binder == null) {
            Log.w(TAG_WATCHDOG, "Getting car watchdog daemon binder failed");
            return false;
        }
        long elapsedTimeMs = System.currentTimeMillis() - currentTimeMs;
        if (elapsedTimeMs > CAR_WATCHDOG_DAEMON_FIND_MARGINAL_TIME_MS) {
            Log.wtf(TAG_WATCHDOG,
                    "Finding car watchdog daemon took too long(" + elapsedTimeMs + "ms)");
        }
        try {
            binder.linkToDeath(new DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.w(TAG_WATCHDOG, "Car watchdog daemon died: reconnecting");
                    synchronized (mLock) {
                        mCarWatchdogDaemon = null;
                    }
                    mMainHandler.postDelayed(() -> {
                        connectToDaemon(CAR_WATCHDOG_DAEMON_BIND_MAX_RETRY);
                    }, CAR_WATCHDOG_DAEMON_BIND_RETRY_INTERVAL_MS);
                }
            }, 0);
        } catch (RemoteException e) {
            Log.w(TAG_WATCHDOG, "Linking to binder death recipient failed: " + e);
            return false;
        }

        ICarWatchdog daemon = ICarWatchdog.Stub.asInterface(binder);
        if (daemon == null) {
            Log.w(TAG_WATCHDOG, "Getting car watchdog daemon interface failed");
            return false;
        }
        synchronized (mLock) {
            mCarWatchdogDaemon = daemon;
        }
        try {
            daemon.registerMediator(mWatchdogClient);
        } catch (RemoteException e) {
            // Nothing that we can do further.
            Log.w(TAG_WATCHDOG, "Cannot register to car watchdog daemon: " + e);
        } catch (IllegalArgumentException e) {
            // Do nothing.
            Log.w(TAG_WATCHDOG, "Already registered as mediator: " + e);
        }
        return true;
    }

    private void doHealthCheck(int sessionId) {
        mMainHandler.post(() -> {
            ICarWatchdog daemon;
            synchronized (mLock) {
                if (mCarWatchdogDaemon == null) {
                    return;
                }
                daemon = mCarWatchdogDaemon;
            }
            try {
                // TODO(b/145556670): Check clients status and include them in the response.
                int[] clientsNotResponding = new int[0];
                daemon.tellMediatorAlive(mWatchdogClient, clientsNotResponding, sessionId);
            } catch (RemoteException e) {
                Log.w(TAG_WATCHDOG, "Cannot respond to car watchdog daemon (sessionId="
                        + sessionId + "): " + e);
            }
        });
    }

    private static final class ICarWatchdogClientImpl extends ICarWatchdogClient.Stub {
        private final WeakReference<CarWatchdogService> mService;

        private ICarWatchdogClientImpl(CarWatchdogService service) {
            mService = new WeakReference<>(service);
        }

        @Override
        public void checkIfAlive(int sessionId, int timeout) {
            CarWatchdogService service = mService.get();
            if (service == null) {
                Log.w(TAG_WATCHDOG, "CarWatchdogService is not available");
                return;
            }
            service.doHealthCheck(sessionId);
        }
    }
}
