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

import android.os.Bundle;
import android.support.car.annotation.ValueTypeDef;

/**
 * Utility to retrieve various static information from a car. For given string keys, there can be
 * different types of values and right query API like {@link #getFloat(String)} for float
 * type, and {@link #getInt(String)} for int type, should be used. Passing a key string to wrong
 * API will lead into {@link IllegalArgumentException}. All get* apis return null if requested
 * property is not supported by the car. So caller should always check for null result.
 */
public abstract class CarInfoManager implements CarManagerBase {

    /**
     * Manufacturer of the car.
     */
    @ValueTypeDef(type = String.class)
    public static final String KEY_MANUFACTURER = "android.car.manufacturer";
    /**
     * Model name of the car. This information may not necessarily allow distinguishing different
     * car models as the same name may be used for different cars depending on manufacturers.
     */
    @ValueTypeDef(type = String.class)
    public static final String KEY_MODEL = "android.car.model";
    /**
     * Model year of the car.
     */
    @ValueTypeDef(type = Integer.class)
    public static final String KEY_MODEL_YEAR = "android.car.model-year";
    /**
     * Unique identifier for the car. This is not VIN, and id is persistent until user resets it.
     */
    @ValueTypeDef(type = String.class)
    public static final String KEY_VEHICLE_ID = "android.car.vehicle-id";

    /** Manufacturer of the head unit.*/
    @ValueTypeDef(type = String.class)
    public static final String KEY_HEAD_UNIT_MAKE = "android.car.headUnitMake";
    /** Model of the head unit.*/
    @ValueTypeDef(type = String.class)
    public static final String KEY_HEAD_UNIT_MODEL = "android.car.headUnitModel";
    /** Head Unit software build */
    @ValueTypeDef(type = String.class)
    public static final String KEY_HEAD_UNIT_SOFTWARE_BUILD = "android.car.headUnitSoftwareBuild";
    /** Head Unit software version */
    @ValueTypeDef(type = String.class)
    public static final String KEY_HEAD_UNIT_SOFTWARE_VERSION = "android.car.headUnitSoftwareVersion";
    /** Where is the driver's seat.  One of the DRIVER_SIDE_* constants */
    @ValueTypeDef(type = Integer.class)
    public static final String KEY_DRIVER_POSITION = "android.car.driverPosition";

    /** Location of the driver: left */
    public static final int DRIVER_SIDE_LEFT   = 0;
    /** Location of the driver: right */
    public static final int DRIVER_SIDE_RIGHT  = 1;
    /** Location of the driver: center */
    public static final int DRIVER_SIDE_CENTER = 2;

    /**
     * Returns the value for the given key or {@link Float#NaN} if the key is not supported.
     */
    public abstract float getFloat(String key)
            throws CarNotConnectedException, IllegalArgumentException;

    /**
     * Returns the value for the given key or {@link Integer#MIN_VALUE} if the key is not supported.
     */
    public abstract int getInt(String key)
            throws CarNotConnectedException, IllegalArgumentException;

    /**
     * Returns the value for the given key or {@link Long#MIN_VALUE} if the key is not supported.
     */
    public abstract long getLong(String key)
            throws CarNotConnectedException, IllegalArgumentException;

    /**
     * Returns the value for the given key or null if the key is not supported.
     */
    public abstract String getString(String key)
            throws CarNotConnectedException, IllegalArgumentException;

    /**
     * get Bundle for the given key. This is intended for passing vendor specific data for key
     * defined only for the car vendor. Vendor extension can be used for other APIs like
     * getInt / getString, but this is for passing more complex data.
     * @param key
     * @hide
     */
    public abstract Bundle getBundle(String key)
            throws CarNotConnectedException, IllegalArgumentException;
}
