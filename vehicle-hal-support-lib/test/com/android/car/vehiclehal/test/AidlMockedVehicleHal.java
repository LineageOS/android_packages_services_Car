/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.car.vehiclehal.test;

import android.hardware.automotive.vehicle.GetValueRequests;
import android.hardware.automotive.vehicle.IVehicle;
import android.hardware.automotive.vehicle.IVehicleCallback;
import android.hardware.automotive.vehicle.SetValueRequests;
import android.hardware.automotive.vehicle.SubscribeOptions;
import android.hardware.automotive.vehicle.VehiclePropConfigs;
import android.hardware.automotive.vehicle.VehiclePropValue;
import android.os.RemoteException;

public class AidlMockedVehicleHal extends IVehicle.Stub {

    /**
    * Interface for handler of each property.
    */
    public interface VehicleHalPropertyHandler {
        default void onPropertySet(VehiclePropValue value) {}
        default VehiclePropValue onPropertyGet(VehiclePropValue value) {
            return null;
        }
        default void onPropertySubscribe(int property, float sampleRate) {}
        default void onPropertyUnsubscribe(int property) {}

        VehicleHalPropertyHandler NOP = new VehicleHalPropertyHandler() {};
    }

    @Override public VehiclePropConfigs getAllPropConfigs() throws RemoteException {
        // TODO(b/216195629): Mock this method.
        return null;
    }

    @Override public VehiclePropConfigs getPropConfigs(int[] props) throws RemoteException {
        // TODO(b/216195629): Mock this method.
        return null;
    }

    @Override public void getValues(IVehicleCallback callback, GetValueRequests requests)
            throws RemoteException {
        // TODO(b/216195629): Mock this method.
    }

    @Override public void setValues(IVehicleCallback callback, SetValueRequests requests)
            throws RemoteException {
        // TODO(b/216195629): Mock this method.
    }

    @Override public void subscribe(IVehicleCallback callback, SubscribeOptions[] options,
            int maxSharedMemoryFileCount) throws RemoteException {
        // TODO(b/216195629): Mock this method.
    }

    @Override public void unsubscribe(IVehicleCallback callback, int[] propIds)
            throws RemoteException {
        // TODO(b/216195629): Mock this method.
    }

    @Override public void returnSharedMemory(IVehicleCallback callback, long sharedMemoryId)
            throws RemoteException {
        // TODO(b/216195629): Mock this method.
    }

}
