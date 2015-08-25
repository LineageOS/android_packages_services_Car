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

import android.support.car.CarInfoManager;
import android.util.Log;

import com.android.car.CarLog;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class InfoHalService extends HalServiceBase {

    private final VehicleHal mHal;
    private final HashMap<String, HalProperty> mInfoNameToHalPropertyMap =
            new HashMap<String, HalProperty>();

    public InfoHalService(VehicleHal hal) {
        mHal = hal;
    }

    @Override
    public void init() {
        //nothing to do
    }

    @Override
    public synchronized void release() {
        mInfoNameToHalPropertyMap.clear();
    }

    @Override
    public synchronized List<HalProperty> takeSupportedProperties(List<HalProperty> allProperties) {
        List<HalProperty> supported = new LinkedList<HalProperty>();
        for (HalProperty p: allProperties) {
            String infoName = getInfoStringFromProperty(p.propertyType);
            if (infoName != null) {
                supported.add(p);
                mInfoNameToHalPropertyMap.put(infoName, p);
            }
        }
        return supported;
    }

    @Override
    public void handleBooleanHalEvent(int property, boolean value, long timeStamp) {
        logUnexpectedEvent(property);
    }

    @Override
    public void handleIntHalEvent(int property, int value, long timeStamp) {
        logUnexpectedEvent(property);
    }

    @Override
    public void handleFloatHalEvent(int property, float value, long timeStamp) {
        logUnexpectedEvent(property);
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*InfoHal*");
        writer.println("**Supported properties**");
        for (HalProperty p : mInfoNameToHalPropertyMap.values()) {
            writer.println(p.toString());
        }
    }

    public int[] getInt(String key) {
        HalProperty prop = getHalPropertyFromInfoString(key);
        if (prop == null) {
            return null;
        }
        // no lock here as get can take time and multiple get should be possible.
        int v = mHal.getIntProperty(prop);
        return new int[] { v };
    }

    public long[] getLong(String key) {
        HalProperty prop = getHalPropertyFromInfoString(key);
        if (prop == null) {
            return null;
        }
        // no lock here as get can take time and multiple get should be possible.
        long v = mHal.getLongProperty(prop);
        return new long[] { v };
    }

    public float[] getFloat(String key) {
        HalProperty prop = getHalPropertyFromInfoString(key);
        if (prop == null) {
            return null;
        }
        // no lock here as get can take time and multiple get should be possible.
        float v = mHal.getFloatProperty(prop);
        return new float[] { v };
    }

    public String getString(String key) {
        HalProperty prop = getHalPropertyFromInfoString(key);
        if (prop == null) {
            return null;
        }
        // no lock here as get can take time and multiple get should be possible.
        return mHal.getStringProperty(prop);
    }

    private synchronized HalProperty getHalPropertyFromInfoString(String key) {
        return mInfoNameToHalPropertyMap.get(key);
    }

    private void logUnexpectedEvent(int property) {
       Log.w(CarLog.TAG_INFO, "unexpected HAL event for property 0x" +
               Integer.toHexString(property));
    }

    private static String getInfoStringFromProperty(int property) {
        switch (property) {
            case HalPropertyConst.VEHICLE_PROPERTY_INFO_MAKE:
                return CarInfoManager.KEY_MANUFACTURER;
            case HalPropertyConst.VEHICLE_PROPERTY_INFO_MODEL:
                return CarInfoManager.KEY_MODEL;
            case HalPropertyConst.VEHICLE_PROPERTY_INFO_MODEL_YEAR:
                return CarInfoManager.KEY_MODEL_YEAR;
            case HalPropertyConst.VEHICLE_PROPERTY_INFO_VIN:
                return CarInfoManager.KEY_VEHICLE_ID;
            //TODO add more properties
            default:
                return null;
        }
    }
}
