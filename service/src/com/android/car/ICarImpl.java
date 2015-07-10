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

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.car.Car;
import android.support.car.CarInfo;
import android.support.car.CarUiInfo;
import android.support.car.ICar;
import android.support.car.ICarConnectionListener;
import android.support.car.ICarSensor;
import android.util.Log;

import com.android.car.hal.Hal;
import com.android.internal.annotations.GuardedBy;

public class ICarImpl extends ICar.Stub {
    private static final int VERSION = 1;

    @GuardedBy("ICarImpl.class")
    private static ICarImpl sInstance = null;

    private final Context mContext;
    private final Hal mHal;

    private final CarSensorService mCarSensorService;

    public synchronized static ICarImpl getInstance(Context serviceContext) {
        if (sInstance == null) {
            sInstance = new ICarImpl(serviceContext);
            sInstance.init();
        }
        return sInstance;
    }

    public synchronized static void releaseInstance() {
        if (sInstance == null) {
            return;
        }
        sInstance.release();
        sInstance = null;
    }

    public ICarImpl(Context serviceContext) {
        mContext = serviceContext;
        mHal = Hal.getInstance(serviceContext.getApplicationContext());
        mCarSensorService = new CarSensorService(serviceContext);
    }

    private void init() {
        mCarSensorService.init();
    }

    private void release() {
        mCarSensorService.release();
        Hal.releaseInstance();
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public IBinder getCarService(String serviceName) {
        switch (serviceName) {
            case Car.SENSOR_SERVICE:
                return mCarSensorService;
            default:
                Log.w(CarLog.TAG_SERVICE, "getCarService for unknown service:" + serviceName);
                return null;
        }
    }

    @Override
    public CarInfo getCarInfo() {
        //TODO
        return null;
    }

    @Override
    public CarUiInfo getCarUiInfo() {
        //TODO
        return null;
    }

    @Override
    public boolean isConnectedToCar() {
        return true; // always connected in embedded
    }

    @Override
    public int getCarConnectionType() {
        return Car.CONNECTION_TYPE_EMBEDDED;
    }

    @Override
    public void registerCarConnectionListener(int clientVersion, ICarConnectionListener listener) {
        //TODO
    }

    @Override
    public void unregisterCarConnectionListener(ICarConnectionListener listener) {
        //TODO
    }

    @Override
    public boolean startCarActivity(Intent intent) {
        //TODO
        return false;
    }
}
