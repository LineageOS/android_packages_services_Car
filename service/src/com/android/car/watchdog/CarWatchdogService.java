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

import android.automotive.watchdog.ICarWatchdogClient;
import android.car.watchdog.ICarWatchdogService;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.car.CarServiceBase;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;

/**
 * Service to implement CarWatchdogManager API.
 *
 * <p>CarWatchdogService runs as car watchdog mediator, which checks clients' health status and
 * reports the result to car watchdog server.
 */
public final class CarWatchdogService extends ICarWatchdogService.Stub implements CarServiceBase {

    private final Context mContext;
    private final ICarWatchdogClientImpl mWatchdogClient;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    private final CarWatchdogDaemonHelper.OnConnectionChangeListener mConnectionListener =
            (connected) -> {
                if (connected) {
                    registerToDaemon();
                }
            };

    @VisibleForTesting
    public CarWatchdogService(Context context) {
        mContext = context;
        mWatchdogClient = new ICarWatchdogClientImpl(this);
        mCarWatchdogDaemonHelper = new CarWatchdogDaemonHelper();
    }

    @Override
    public void init() {
        mCarWatchdogDaemonHelper.addOnConnectionChangeListener(mConnectionListener);
        mCarWatchdogDaemonHelper.connect();
    }

    @Override
    public void release() {
        unregisterFromDaemon();
        mCarWatchdogDaemonHelper.disconnect();
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*CarWatchdogService*");
        // TODO(b/145556670): implement body.
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

    private void registerToDaemon() {
        try {
            mCarWatchdogDaemonHelper.registerMediator(mWatchdogClient);
        } catch (RemoteException | IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG_WATCHDOG, "Cannot register to car watchdog daemon: " + e);
        }
    }

    private void unregisterFromDaemon() {
        try {
            mCarWatchdogDaemonHelper.unregisterMediator(mWatchdogClient);
        } catch (RemoteException | IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG_WATCHDOG, "Cannot unregister from car watchdog daemon: " + e);
        }
    }

    private void doHealthCheck(int sessionId) {
        mMainHandler.post(() -> {
            try {
                // TODO(b/145556670): Check clients status and include them in the response.
                int[] clientsNotResponding = new int[0];
                mCarWatchdogDaemonHelper.tellMediatorAlive(mWatchdogClient, clientsNotResponding,
                        sessionId);
            } catch (RemoteException | IllegalArgumentException | IllegalStateException e) {
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
