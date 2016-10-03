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
package com.android.car.hal;

import android.car.CarInfoManager;
import android.os.Bundle;
import android.util.Log;

import com.android.car.CarLog;
import com.android.car.vehiclenetwork.VehicleNetwork;
import com.android.car.vehiclenetwork.VehicleNetworkConsts;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropConfig;
import com.android.car.vehiclenetwork.VehicleNetworkProto.VehiclePropValue;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class InfoHalService extends HalServiceBase {

    private final VehicleHal mHal;
    private Bundle mBasicInfo = new Bundle();

    public InfoHalService(VehicleHal hal) {
        mHal = hal;
    }

    @Override
    public void init() {
        //nothing to do
    }

    @Override
    public synchronized void release() {
        mBasicInfo = new Bundle();
    }

    @Override
    public synchronized List<VehiclePropConfig> takeSupportedProperties(
            List<VehiclePropConfig> allProperties) {
        VehicleNetwork vn = mHal.getVehicleNetwork();
        List<VehiclePropConfig> supported = new LinkedList<VehiclePropConfig>();
        for (VehiclePropConfig p: allProperties) {
            switch (p.getProp()) {
                case VehicleNetworkConsts.VEHICLE_PROPERTY_INFO_MAKE:
                    mBasicInfo.putString(CarInfoManager.BASIC_INFO_KEY_MANUFACTURER,
                            vn.getStringProperty(p.getProp()));
                    break;
                case VehicleNetworkConsts.VEHICLE_PROPERTY_INFO_MODEL:
                    mBasicInfo.putString(CarInfoManager.BASIC_INFO_KEY_MODEL,
                            vn.getStringProperty(p.getProp()));
                    break;
                case VehicleNetworkConsts.VEHICLE_PROPERTY_INFO_MODEL_YEAR:
                    mBasicInfo.putString(CarInfoManager.BASIC_INFO_KEY_MODEL_YEAR,
                            vn.getStringProperty(p.getProp()));
                    break;
                default: // not supported
                    break;
            }
        }
        return supported;
    }

    @Override
    public void handleHalEvents(List<VehiclePropValue> values) {
        for (VehiclePropValue v : values) {
            logUnexpectedEvent(v.getProp());
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*InfoHal*");
        writer.println("**BasicInfo:" + mBasicInfo);
    }

    public synchronized Bundle getBasicInfo() {
        return mBasicInfo;
    }

    private void logUnexpectedEvent(int property) {
       Log.w(CarLog.TAG_INFO, "unexpected HAL event for property 0x" +
               Integer.toHexString(property));
    }
}
