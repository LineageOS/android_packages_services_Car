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
package com.android.car.hal;

import static android.car.VehiclePropertyIds.INITIAL_USER_INFO;

import android.annotation.Nullable;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Service used to integrate the OEM's custom user management with Android's.
 */
public final class UserHalService extends HalServiceBase {

    private static final String TAG = CarLog.TAG_USER;

    // TODO(b/146207078): STOPSHIP - change to false before R is launched
    private static final boolean DBG = true;

    private final Object mLock = new Object();

    private final VehicleHal mHal;

    @GuardedBy("mLock")
    private final SparseArray<VehiclePropConfig> mProperties = new SparseArray<>(1);

    public UserHalService(VehicleHal hal) {
        mHal = hal;
    }

    @Override
    public void init() {
        if (DBG) Log.d(TAG, "init()");

        int size = mProperties.size();
        for (int i = 0; i < size; i++) {
            VehiclePropConfig config = mProperties.valueAt(i);
            if (VehicleHal.isPropertySubscribable(config)) {
                if (DBG) Log.d(TAG, "subscribing to property " + config.prop);
                mHal.subscribeProperty(this, config.prop);
            }
        }
    }

    @Override
    public void release() {
        if (DBG) Log.d(TAG, "release()");
    }

    @Override
    public void handleHalEvents(List<VehiclePropValue> values) {
        if (DBG) Log.d(TAG, "handleHalEvents(): " + values);
    }

    @Override
    public void handlePropertySetError(int property, int area) {
        if (DBG) Log.d(TAG, "handlePropertySetError(" + property + "/" + area + ")");
    }

    @Override
    @Nullable
    public Collection<VehiclePropConfig> takeSupportedProperties(
            Collection<VehiclePropConfig> allProperties) {
        ArrayList<VehiclePropConfig> supported = new ArrayList<>();
        synchronized (mLock) {
            for (VehiclePropConfig config : allProperties) {
                switch (config.prop) {
                    case INITIAL_USER_INFO:
                        supported.add(config);
                        mProperties.put(config.prop, config);
                        break;
                }

            }
        }
        return supported.isEmpty() ? null : supported;
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*User HAL*\n");
        writer.println("Nothing to dump yet...");
    }
}
