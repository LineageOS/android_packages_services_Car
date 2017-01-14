/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.os.SystemClock.elapsedRealtime;

import android.annotation.Nullable;
import android.hardware.vehicle.V2_0.IVehicle;
import android.hardware.vehicle.V2_0.VehiclePropConfig;
import android.hardware.vehicle.V2_0.VehiclePropValue;
import android.os.RemoteException;
import android.util.Log;
import com.android.car.vehiclehal.VehiclePropValueBuilder;
import java.util.Objects;

final class Utils {
    private Utils() {}

    private static final String TAG = Utils.class.getSimpleName();

    @Nullable
    static <T> T tryWithDeadline(long waitMilliseconds, java.util.function.Supplier<T> f) {
        f = Objects.requireNonNull(f);
        T object = f.get();
        long start = elapsedRealtime();
        while (object == null && (start + waitMilliseconds) > elapsedRealtime()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException("Sleep was interrupted", e);
            }

            object = f.get();
        }
        return object;
    }

    static String dumpVehiclePropValue(VehiclePropValue vpv) {
        vpv = Objects.requireNonNull(vpv);
        return "prop = "
                + vpv.prop
                + '\n'
                + "areaId = "
                + vpv.areaId
                + '\n'
                + "timestamp = "
                + vpv.timestamp
                + '\n'
                + "int32Values = "
                + vpv.value.int32Values
                + '\n'
                + "floatValues = "
                + vpv.value.floatValues
                + '\n'
                + "int64Values ="
                + vpv.value.int64Values
                + '\n'
                + "bytes = "
                + vpv.value.bytes
                + '\n'
                + "string = "
                + vpv.value.stringValue
                + '\n';
    }

    static boolean isVhalPropertyAvailable(IVehicle vehicle, int prop) throws RemoteException {
        return vehicle.getAllPropConfigs()
                .stream()
                .anyMatch((VehiclePropConfig config) -> config.prop == prop);
    }

    static VehiclePropValue readVhalProperty(
            IVehicle vehicle,
            int propertyId,
            java.util.function.BiFunction<Integer, VehiclePropValue, Boolean> f) {
        return readVhalProperty(vehicle, propertyId, 0, f);
    }

    static VehiclePropValue readVhalProperty(
            IVehicle vehicle,
            int propertyId,
            int areaId,
            java.util.function.BiFunction<Integer, VehiclePropValue, Boolean> f) {
        vehicle = Objects.requireNonNull(vehicle);
        VehiclePropValue request =
                VehiclePropValueBuilder.newBuilder(propertyId).setAreaId(areaId).build();
        VehiclePropValue vpv[] = new VehiclePropValue[] {null};
        try {
            vehicle.get(
                    request,
                    (int status, VehiclePropValue propValue) -> {
                        if (f.apply(status, propValue)) {
                            vpv[0] = propValue;
                        }
                    });
        } catch (RemoteException e) {
            Log.w(TAG, "attempt to read VHAL property 0x" + Integer.toHexString(propertyId)
                       + " from area " + areaId + " caused RemoteException: ", e);
        }
        return vpv[0];
    }
}
