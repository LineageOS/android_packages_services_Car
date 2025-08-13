/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.car.audio;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.ICarPropertyEventListener;
import android.os.RemoteException;

import com.android.car.CarLog;
import com.android.car.CarPropertyService;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;

import java.util.List;

/**
 * Class to monitor the parked state of the car
 */
final class CarAudioParkedStateMonitor {

    private static final String TAG = CarLog.TAG_AUDIO;

    interface ParkedStateListener {
        void onParkedStateChanged(boolean isParked);
    }

    private final CarPropertyService mCarPropertyService;
    private final ParkedStateListener mListener;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private boolean mIsParked;

    private final ICarPropertyEventListener mCarPropertyEventCallback =
            new ICarPropertyEventListener.Stub() {
                @Override
                public void onEvent(List<CarPropertyEvent> events) throws RemoteException {
                    for (int i = 0; i < events.size(); i++) {
                        CarPropertyEvent event = events.get(i);
                        if (event.getCarPropertyValue().getPropertyId()
                                != VehiclePropertyIds.GEAR_SELECTION) {
                            continue;
                        }
                        boolean isParked = (Integer) event.getCarPropertyValue().getValue()
                                == VehicleGear.GEAR_PARK;
                        synchronized (mLock) {
                            if (mIsParked == isParked) {
                                continue;
                            }
                            mIsParked = isParked;
                            mListener.onParkedStateChanged(mIsParked);
                        }
                    }
                }
            };

    CarAudioParkedStateMonitor(CarPropertyService carPropertyService,
            ParkedStateListener listener) {
        mCarPropertyService = carPropertyService;
        mListener = listener;
        mCarPropertyService.registerListener(VehiclePropertyIds.GEAR_SELECTION,
                CarPropertyManager.SENSOR_RATE_ONCHANGE, mCarPropertyEventCallback);
        CarPropertyValue<Integer> gearSelection = mCarPropertyService.getProperty(
                VehiclePropertyIds.GEAR_SELECTION, /* areaId= */0);
        if (gearSelection != null) {
            mIsParked = gearSelection.getValue() == VehicleGear.GEAR_PARK;
        }
    }

    boolean isParked() {
        synchronized (mLock) {
            return mIsParked;
        }
    }

    void release() {
        mCarPropertyService.unregisterListener(VehiclePropertyIds.GEAR_SELECTION,
                mCarPropertyEventCallback);
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    void dump(IndentingPrintWriter writer) {
        writer.println("CarAudioParkedStateMonitor");
        writer.increaseIndent();
        writer.printf("Is Parked: %b\n", isParked());
        writer.decreaseIndent();
    }
}
