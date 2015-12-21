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

package android.support.car;

import android.os.RemoteException;
import android.support.car.annotation.ValueTypeDef;

import java.lang.reflect.Field;
import java.util.HashMap;

/**
 * Utility to retrieve various static information from car. For given string keys, there can be
 * different types of values and right query API like {@link #getFloat(String)} for float
 * type, and {@link #getInt(String)} for int type, should be used. Passing a key string to wrong
 * API will lead into {@link IllegalArgumentException}. All get* apis return null if requested
 * property is not supported by the car. So caller should always check for null result.
 */
public class CarInfoManager implements CarManagerBase {

    /**
     * Manufacturer of the car.
     */
    @ValueTypeDef(type = String.class)
    public static final String KEY_MANUFACTURER = "manufacturer";
    /**
     * Model name of the car. This information may not necessarily allow distinguishing different
     * car models as the same name may be used for different cars depending on manufacturers.
     */
    @ValueTypeDef(type = String.class)
    public static final String KEY_MODEL = "model";
    /**
     * Model year of the car in AC.
     */
    @ValueTypeDef(type = Integer.class)
    public static final String KEY_MODEL_YEAR = "model-year";
    /**
     * Unique identifier for the car. This is not VIN, and id is persistent until user resets it.
     */
    @ValueTypeDef(type = String.class)
    public static final String KEY_VEHICLE_ID = "vehicle-id";

    //TODO
    //@ValueTypeDef(type = Integer.class)
    //public static final String KEY_DRIVER_POSITION = "driver-position";

    //TODO
    //@ValueTypeDef(type = int[].class)
    //public static final String KEY_SEAT_CONFIGURATION = "seat-configuration";

    //TODO
    //@ValueTypeDef(type = Integer.class)
    //public static final String KEY_WINDOW_CONFIGURATION = "window-configuration";

    //TODO: MT, AT, CVT, ...
    //@ValueTypeDef(type = Integer.class)
    //public static final String KEY_TRANSMISSION_TYPE = "transmission-type";

    //TODO add: transmission gear available selection, gear available steps
    //          drive wheel: FWD, RWD, AWD, 4WD

    private final ICarInfo mService;

    /**
     * Retrieve floating point information for car.
     * @param key
     * @return null if the key is not supported.
     * @throws CarNotConnectedException
     * @throws IllegalArgumentException
     */
    public Float getFloat(String key) throws CarNotConnectedException, IllegalArgumentException {
        try {
            float[] v = mService.getFloat(key);
            if (v != null) {
                return v[0];
            }
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            //ignore as CarApi will handle disconnection anyway.
        }
        return null;
    }

    public Integer getInt(String key) throws CarNotConnectedException, IllegalArgumentException {
        try {
            int[] v = mService.getInt(key);
            if (v != null) {
                return v[0];
            }
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            //ignore as CarApi will handle disconnection anyway.
        }
        return null;
    }

    public Long getLong(String key) throws CarNotConnectedException, IllegalArgumentException {
        try {
            long[] v = mService.getLong(key);
            if (v != null) {
                return v[0];
            }
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            //ignore as CarApi will handle disconnection anyway.
        }
        return null;
    }

    public String getString(String key) throws CarNotConnectedException, IllegalArgumentException {
        try {
            return mService.getString(key);
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            //ignore as CarApi will handle disconnection anyway.
        }
        return null;
    }

    //TODO add more get*

    /** @hide */
    CarInfoManager(ICarInfo service) {
        mService = service;
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        //nothing to do
    }
}
