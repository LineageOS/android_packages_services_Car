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
import android.util.Log;

import com.android.car.hal.VehicleHal;
import com.android.car.vehiclenetwork.IVehicleNetworkHalMock;
import com.android.car.vehiclenetwork.VehicleNetwork;
import com.android.car.vehiclenetwork.VehiclePropValueParcelable;

import java.io.PrintWriter;

/**
 * Service to allow testing / mocking vehicle HAL.
 * This service uses Vehicle HAL APIs directly (one exception) as vehicle HAL mocking anyway
 * requires accessing that level directly.
 */
public class CarTestService extends ICarTest.Stub implements CarServiceBase {
    private static final int VERSION = 1;

    private final Context mContext;
    private final VehicleNetwork mVehicleNetwork;
    private final ICarImpl mICarImpl;

    public CarTestService(Context context, ICarImpl carImpl) {
        mContext = context;
        mICarImpl = carImpl;
        mVehicleNetwork = VehicleHal.getInstance().getVehicleNetwork();
    }

    @Override
    public void init() {
        // TODO Auto-generated method stub
    }

    @Override
    public void release() {
        // TODO Auto-generated method stub
    }

    @Override
    public void dump(PrintWriter writer) {
        // TODO Auto-generated method stub
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public void injectEvent(VehiclePropValueParcelable value) {
        ICarImpl.assertVehicleHalMockPermission(mContext);
        mVehicleNetwork.injectEvent(value.value);
    }

    @Override
    public void startMocking(IVehicleNetworkHalMock mock) {
        ICarImpl.assertVehicleHalMockPermission(mContext);
        try {
            mVehicleNetwork.startMocking(mock);
            VehicleHal.getInstance().startMocking();
            mICarImpl.startMocking();
        } catch (Exception e) {
            Log.w(CarLog.TAG_TEST, "startMocking failed", e);
            throw e;
        }
        Log.i(CarLog.TAG_TEST, "start vehicle HAL mocking");
    }

    @Override
    public void stopMocking(IVehicleNetworkHalMock mock) {
        ICarImpl.assertVehicleHalMockPermission(mContext);
        mVehicleNetwork.stopMocking(mock);
        VehicleHal.getInstance().stopMocking();
        mICarImpl.stopMocking();
        Log.i(CarLog.TAG_TEST, "stop vehicle HAL mocking");
    }
}
