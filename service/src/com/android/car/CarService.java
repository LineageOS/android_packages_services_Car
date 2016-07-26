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

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Log;

import com.android.car.hal.VehicleHal;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class CarService extends Service {

    // main thread only
    private ICarImpl mICarImpl;

    @Override
    public void onCreate() {
        Log.i(CarLog.TAG_SERVICE, "Service onCreate");
        mICarImpl = ICarImpl.getInstance(this);
        SystemProperties.set("boot.car_service_created", "1");
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        Log.i(CarLog.TAG_SERVICE, "Service onDestroy");
        ICarImpl.releaseInstance();
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
            writer.println("*dump HAL*");
            VehicleHal.getInstance().dump(writer);
            writer.println("*dump services*");
            ICarImpl.getInstance(this).dump(writer);
        } else {
            ICarImpl.getInstance(this).execShellCmd(args, writer);
        }
    }
}