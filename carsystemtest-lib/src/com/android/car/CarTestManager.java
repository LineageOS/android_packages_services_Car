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

import android.os.RemoteException;
import android.support.car.CarManagerBase;

import com.android.car.vehiclenetwork.IVehicleNetworkHalMock;
import com.android.car.vehiclenetwork.VehicleNetwork.VehicleNetworkHalMock;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfigs;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;
import com.android.car.vehiclenetwork.VehicleNetwork;
import com.android.car.vehiclenetwork.VehiclePropConfigsParcelable;
import com.android.car.vehiclenetwork.VehiclePropValueParcelable;
import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;

public class CarTestManager implements CarManagerBase {

    private final ICarTest mService;

    @GuardedBy("this")
    private VehicleNetworkHalMock mHalMock;
    private IVehicleNetworkHalMockImpl mHalMockImpl;

    public CarTestManager(ICarTest service) {
        mService = service;
    }

    @Override
    public void onCarDisconnected() {
        // should not happen for embedded
    }

    public synchronized void injectEvent(VehiclePropValue value) {
        try {
            mService.injectEvent(new VehiclePropValueParcelable(value));
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    /**
     * Start mocking vehicle HAL. It is somewhat strange to re-use interface in lower level
     * API, but this is only for testing, and interface is exactly the same.
     * @param mock
     */
    public synchronized void startMocking(VehicleNetworkHalMock mock) {
        mHalMock = mock;
        mHalMockImpl = new IVehicleNetworkHalMockImpl(this);
        try {
            mService.startMocking(mHalMockImpl);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    public synchronized void stopMocking() {
        try {
            mService.stopMocking(mHalMockImpl);
        } catch (RemoteException e) {
            handleRemoteException(e);
        } finally {
            mHalMock = null;
            mHalMockImpl = null;
        }
    }

    private synchronized VehicleNetworkHalMock getHalMock() {
        return mHalMock;
    }

    private void handleRemoteException(RemoteException e) {
        //TODO
    }

    private static class IVehicleNetworkHalMockImpl extends IVehicleNetworkHalMock.Stub {
        private final WeakReference<CarTestManager> mTestManager;

        private IVehicleNetworkHalMockImpl(CarTestManager testManager) {
            mTestManager = new WeakReference<CarTestManager>(testManager);
        }

        @Override
        public VehiclePropConfigsParcelable onListProperties() {
            CarTestManager testManager = mTestManager.get();
            if (testManager == null) {
                return null;
            }
            VehiclePropConfigs configs = testManager.getHalMock().onListProperties();
            return new VehiclePropConfigsParcelable(configs);
        }

        @Override
        public void onPropertySet(VehiclePropValueParcelable value) {
            CarTestManager testManager = mTestManager.get();
            if (testManager == null) {
                return;
            }
            testManager.getHalMock().onPropertySet(value.value);
        }

        @Override
        public VehiclePropValueParcelable onPropertyGet(int property) {
            CarTestManager testManager = mTestManager.get();
            if (testManager == null) {
                return null;
            }
            VehiclePropValue value = testManager.getHalMock().onPropertyGet(property);
            return new VehiclePropValueParcelable(value);
        }

        @Override
        public void onPropertySubscribe(int property, int sampleRate) {
            CarTestManager testManager = mTestManager.get();
            if (testManager == null) {
                return;
            }
            testManager.getHalMock().onPropertySubscribe(property, sampleRate);
        }

        @Override
        public void onPropertyUnsubscribe(int property) {
            CarTestManager testManager = mTestManager.get();
            if (testManager == null) {
                return;
            }
            testManager.getHalMock().onPropertyUnsubscribe(property);
        }
    }
}
