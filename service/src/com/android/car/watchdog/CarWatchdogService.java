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

import android.automotive.watchdog.ICarWatchdogClient;
import android.car.watchdog.ICarWatchdogService;

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

    private final ICarWatchdogClientImpl mWatchogClient;

    CarWatchdogService() {
        mWatchogClient = new ICarWatchdogClientImpl(this);
    }

    @Override
    public void init() {
        // TODO(b/145556670): implement body.
    }

    @Override
    public void release() {
        // TODO(b/145556670): implement body.
    }

    @Override
    public void dump(PrintWriter writer) {
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

    private static final class ICarWatchdogClientImpl extends ICarWatchdogClient.Stub {
        private final WeakReference<CarWatchdogService> mService;

        private ICarWatchdogClientImpl(CarWatchdogService service) {
            mService = new WeakReference<>(service);
        }

        @Override
        public void checkIfAlive(int sessionId, int timeout) {
            // TODO(b/145556670): implement body.
        }
    }
}
