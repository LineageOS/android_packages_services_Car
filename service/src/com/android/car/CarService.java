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
package com.android.car;

import static android.os.SystemClock.elapsedRealtime;

import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.vehicle.V2_0.IVehicle;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class CarService extends Service {

    /** Default vehicle HAL service name. */
    private static final String VEHICLE_SERVICE_NAME = "Vehicle";

    private static final long WAIT_FOR_VEHICLE_HAL_TIMEOUT_MS = 10_000;

    private ICarImpl mICarImpl;

    @Override
    public void onCreate() {
        Log.i(CarLog.TAG_SERVICE, "Service onCreate");
        IVehicle vehicle = getVehicle(WAIT_FOR_VEHICLE_HAL_TIMEOUT_MS);
        if (vehicle == null) {
            throw new IllegalStateException("Vehicle HAL service is not available.");
        }

        mICarImpl = new ICarImpl(this, vehicle, SystemInterface.getDefault(this));
        mICarImpl.init();
        SystemProperties.set("boot.car_service_created", "1");
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        Log.i(CarLog.TAG_SERVICE, "Service onDestroy");
        mICarImpl.release();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // keep it alive.
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mICarImpl;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            writer.println("Permission Denial: can't dump CarService from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }
        if (args == null || args.length == 0) {
            writer.println("*dump car service*");
            mICarImpl.dump(writer);
        } else {
            mICarImpl.execShellCmd(args, writer);
        }
    }

    @Nullable
    private IVehicle getVehicle(long waitMilliseconds) {
        IVehicle vehicle = getVehicle();
        long start = elapsedRealtime();
        while (vehicle == null && (start + waitMilliseconds) > elapsedRealtime()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException("Sleep was interrupted", e);
            }

            vehicle = getVehicle();
        }
        return vehicle;
    }

    @Nullable
    private IVehicle getVehicle() {
        return IVehicle.getService(VEHICLE_SERVICE_NAME);
    }
}