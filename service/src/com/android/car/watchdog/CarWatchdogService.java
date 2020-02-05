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
import android.os.Looper;
import android.os.Process;
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
        this(context, null);
    }

    @VisibleForTesting
    CarWatchdogService(Context context, ICarWatchdog daemon) {
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
            daemon = ICarWatchdog.Stub.asInterface(
                    ServiceManager.getService(CAR_WATCHDOG_DAEMON_INTERFACE));
            if (daemon == null) {
                Log.wtf(TAG_WATCHDOG, "Cannot initialize because no watchdog daemon is found");
                Process.killProcess(Process.myPid());
            }
        }
        try {
            daemon.registerMediator(mWatchdogClient);
        } catch (RemoteException e) {
            daemon = null;
            Log.w(TAG_WATCHDOG, "Cannot register to car watchdog daemon: " + e);
        } catch (IllegalArgumentException e) {
            // Do nothing.
            Log.w(TAG_WATCHDOG, "Already registered as mediator: " + e);
        }
        synchronized (mLock) {
            mCarWatchdogDaemon = daemon;
        }
    }

    @Override
    public void release() {
        ICarWatchdog daemon;
        synchronized (mLock) {
            daemon = mCarWatchdogDaemon;
            mCarWatchdogDaemon = null;
        }
        if (daemon != null) {
            try {
                daemon.unregisterClient(mWatchdogClient);
            } catch (RemoteException e) {
                Log.w(TAG_WATCHDOG, "Cannot unregister from car watchdog daemon: " + e);
            }
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
