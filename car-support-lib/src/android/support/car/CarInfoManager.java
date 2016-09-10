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
 * Utility to retrieve static information from a car. String keys require different types of
 * values, so be sure to query using the correct API (such as {@link #getFloat(String)} for float
 * type and {@link #getInt(String)} for int type). Passing a key string to the wrong
 * API causes an {@link IllegalArgumentException}. Get* APIs return null if the requested
 * property is not supported by the car.
 */
public abstract class CarInfoManager implements CarManagerBase {

    /**
     * Manufacturer of the car.
     */
    @ValueTypeDef(type = String.class)
    public static final String KEY_MANUFACTURER = "android.car.manufacturer";
    /**
     * Model name of the car. This information may not distinguish between different
     * car models as some manufacturers use the same name for different cars.
     */
    @ValueTypeDef(type = String.class)
    public static final String KEY_MODEL = "android.car.model";
    /**
     * Model year of the car.
     */
    @ValueTypeDef(type = Integer.class)
    public static final String KEY_MODEL_YEAR = "android.car.model-year";
    /**
     * Unique identifier for the car, persistent until reset by the user. This is not the VIN.
     */
    @ValueTypeDef(type = String.class)
    public static final String KEY_VEHICLE_ID = "android.car.vehicle-id";

    /** Manufacturer of the head unit.*/
    @ValueTypeDef(type = String.class)
    public static final String KEY_HEAD_UNIT_MAKE = "android.car.headUnitMake";
    /** Model of the head unit.*/
    @ValueTypeDef(type = String.class)
    public static final String KEY_HEAD_UNIT_MODEL = "android.car.headUnitModel";
    /** Software build of the head unit. */
    @ValueTypeDef(type = String.class)
    public static final String KEY_HEAD_UNIT_SOFTWARE_BUILD = "android.car.headUnitSoftwareBuild";
    /** Software version of the head unit. */
    @ValueTypeDef(type = String.class)
    public static final String KEY_HEAD_UNIT_SOFTWARE_VERSION = "android.car.headUnitSoftwareVersion";
    /** Location of driver's seat (one of the DRIVER_SIDE_* constants). */
    @ValueTypeDef(type = Integer.class)
    public static final String KEY_DRIVER_POSITION = "android.car.driverPosition";

    /** Location of the driver: left. */
    public static final int DRIVER_SIDE_LEFT   = 0;
    /** Location of the driver: right. */
    public static final int DRIVER_SIDE_RIGHT  = 1;
    /** Location of the driver: center. */
    public static final int DRIVER_SIDE_CENTER = 2;

    /**
     * Return the value for the given key.
     * @param key One of the KEY_* constants defined in this API or provided by manufacturer
     * extensions.
     * @return The value or {@link Float#NaN} if the key is not supported or populated.
     */
    public abstract float getFloat(String key)
            throws CarNotConnectedException, IllegalArgumentException;

    /**
     * Return the value for the given key.
     * @param key One of the KEY_* constants defined in this API or provided by manufacturer
     * extensions.
     * @return The value or {@link Integer#MIN_VALUE} if the key is not supported or
     * populated.
     */
    public abstract int getInt(String key)
            throws CarNotConnectedException, IllegalArgumentException;

    /**
     * Return the value for the given key.
     * @param key One of the KEY_* constants defined in this API or provided by manufacturer
     * extensions.
     * @return The value or {@link Long#MIN_VALUE} if the key is not supported or
     * populated.
     */
    public abstract long getLong(String key)
            throws CarNotConnectedException, IllegalArgumentException;

    /**
     * Return the value for the given key.
     * @param key One of the KEY_* constants defined in this API or provided by manufacturer
     * extensions.
     * @return The value or {@code null} if the key is not supported or populated.
     */
    public abstract String getString(String key)
            throws CarNotConnectedException, IllegalArgumentException;

    /**
     * Retrieve a {@link Bundle} for the given key. Intended for passing vendor-specific
     * data defined by car manufacturers. Vendor extensions can use other APIs (such as
     * {@link #getString(String)}), but this API is for passing complex data.
     * @param key One of the KEY_* constants defined in this API or provided by manufacturer
     * extensions.
     * @return The specified {@link Bundle} or {@code null} if the key is not supported or
     * populated.
     * @hide
     */
    public abstract Bundle getBundle(String key)
            throws CarNotConnectedException, IllegalArgumentException;
}
