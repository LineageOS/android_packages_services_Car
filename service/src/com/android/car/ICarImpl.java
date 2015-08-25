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
import android.support.car.ICar;
import android.support.car.ICarConnectionListener;
import android.util.Log;

import com.android.car.hal.VehicleHal;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;

public class ICarImpl extends ICar.Stub {
    private static final int VERSION = 1;

    @GuardedBy("ICarImpl.class")
    private static ICarImpl sInstance = null;

    private final Context mContext;
    private final VehicleHal mHal;

    private final CarSensorService mCarSensorService;
    private final CarInfoService mCarInfoService;
    private final CarServiceBase[] mAllServices;

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
        mHal = VehicleHal.getInstance(serviceContext.getApplicationContext());
        mCarInfoService = new CarInfoService(serviceContext);
        mCarSensorService = new CarSensorService(serviceContext);
        // Be careful with order. Service depending on other service should be inited later.
        mAllServices = new CarServiceBase[] {
                mCarInfoService,
                mCarSensorService };
    }

    private void init() {
        for (CarServiceBase service: mAllServices) {
            service.init();
        }
        mCarSensorService.init();
    }

    private void release() {
        // release done in opposite order from init
        for (int i = mAllServices.length - 1; i >= 0; i--) {
            mAllServices[i].release();
        }
        VehicleHal.releaseInstance();
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
            case Car.INFO_SERVICE:
                return mCarInfoService;
            default:
                Log.w(CarLog.TAG_SERVICE, "getCarService for unknown service:" + serviceName);
                return null;
        }
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

    void dump(PrintWriter writer) {
        writer.println("*Dump all services*");
        for (CarServiceBase service: mAllServices) {
            service.dump(writer);
        }
    }
}
