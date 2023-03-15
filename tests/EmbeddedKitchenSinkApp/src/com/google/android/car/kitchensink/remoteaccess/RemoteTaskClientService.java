/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.google.android.car.kitchensink.remoteaccess;

import android.app.Service;
import android.car.Car;
import android.car.remoteaccess.CarRemoteAccessManager;
import android.car.remoteaccess.RemoteTaskClientRegistrationInfo;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.Executor;

public final class RemoteTaskClientService extends Service {

    private static final String TAG = RemoteTaskClientService.class.getSimpleName();

    private final RemoteTaskClient mRemoteTaskClient = new RemoteTaskClient();

    private Car mCar;
    private CarRemoteAccessManager mRemoteAccessManager;

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate");
        disconnectCar();
        mCar = Car.createCar(this, /* handler= */ null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (Car car, boolean ready) -> {
                    if (ready) {
                        Executor executor = getMainExecutor();
                        mRemoteAccessManager = (CarRemoteAccessManager) car.getCarManager(
                                Car.CAR_REMOTE_ACCESS_SERVICE);
                        mRemoteAccessManager.setRemoteTaskClient(executor, mRemoteTaskClient);
                    } else {
                        mCar = null;
                        mRemoteAccessManager = null;
                    }
                });
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind");
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        disconnectCar();
    }

    private void disconnectCar() {
        if (mCar != null && mCar.isConnected()) {
            mRemoteAccessManager.clearRemoteTaskClient();
            mCar.disconnect();
            mCar = null;
        }
    }

    private static final class RemoteTaskClient
            implements CarRemoteAccessManager.RemoteTaskClientCallback {

        @Override
        public void onRegistrationUpdated(RemoteTaskClientRegistrationInfo info) {
            Log.i(TAG, "Registration information updated: serviceId=" + info.getServiceId()
                    + ", vehicleId=" + info.getVehicleId() + ", processorId="
                    + info.getProcessorId() + ", clientId=" + info.getClientId());
        }

        @Override
        public void onRegistrationFailed() {
            Log.i(TAG, "Registration to CarRemoteAccessService failed");
        }

        @Override
        public void onRemoteTaskRequested(String taskId, byte[] data, int remainingTimeSec) {
            Log.i(TAG, "Remote task(" + taskId + ") is requested with " + remainingTimeSec
                    + " sec remaining");
        }

        @Override
        public void onShutdownStarting(CarRemoteAccessManager.CompletableRemoteTaskFuture future) {
            future.complete();
        }
    }
}
