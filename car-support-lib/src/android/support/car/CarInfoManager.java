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
import android.support.annotation.StringDef;
import android.support.car.annotation.ValueTypeDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Utility to retrieve various static information from car. Each data are grouped as {@link Bundle}
 * and relevant data can be checked from {@link Bundle} using pre-specified keys.
 */
public abstract class CarInfoManager implements CarManagerBase {

    /**
     * Key for manufacturer of the car. Should be used for {@link android.os.Bundle} acquired from
     * {@link #getBasicCarInfo()}.
     */
    @ValueTypeDef(type = String.class)
    public static final String BASIC_INFO_KEY_MANUFACTURER = "android.car.manufacturer";
    /**
     * Key for model name of the car. This information may not necessarily allow distinguishing
     * different car models as the same name may be used for different cars depending on
     * manufacturers. Should be used for {@link android.os.Bundle} acquired from
     * {@link #getBasicCarInfo()}.
     */
    @ValueTypeDef(type = String.class)
    public static final String BASIC_INFO_KEY_MODEL = "android.car.model";
    /**
     * Key for model year of the car in AC. Should be used for {@link android.os.Bundle} acquired
     * from {@link #getBasicCarInfo()}.
     */
    @ValueTypeDef(type = Integer.class)
    public static final String BASIC_INFO_KEY_MODEL_YEAR = "android.car.model-year";
    /**
     * Key for unique identifier for the car. This is not VIN, and id is persistent until user
     * resets it. Should be used for {@link android.os.Bundle} acquired from
     * {@link #getBasicCarInfo()}.
     */
    @ValueTypeDef(type = String.class)
    public static final String BASIC_INFO_KEY_VEHICLE_ID = "android.car.vehicle-id";

    /** Manufacturer of the head unit.*/
    @ValueTypeDef(type = String.class)
    public static final String BASIC_INFO_KEY_HEAD_UNIT_MAKE = "android.car.headUnitMake";
    /** Model of the head unit.*/
    @ValueTypeDef(type = String.class)
    public static final String BASIC_INFO_KEY_HEAD_UNIT_MODEL = "android.car.headUnitModel";
    /** Software build of the head unit. */
    @ValueTypeDef(type = String.class)
    public static final String BASIC_INFO_KEY_HEAD_UNIT_SOFTWARE_BUILD =
        "android.car.headUnitSoftwareBuild";
    /** Software version of the head unit. */
    @ValueTypeDef(type = String.class)
    public static final String BASIC_INFO_KEY_HEAD_UNIT_SOFTWARE_VERSION =
        "android.car.headUnitSoftwareVersion";
    /** Location of driver's seat (one of the BASIC_INFO_DRIVER_SIDE_* constants). */
    @ValueTypeDef(type = Integer.class)
    public static final String BASIC_INFO_KEY_DRIVER_POSITION = "android.car.driverPosition";

    /** @hide */
    @StringDef({
        BASIC_INFO_KEY_MANUFACTURER,
        BASIC_INFO_KEY_MODEL,
        BASIC_INFO_KEY_MODEL_YEAR,
        BASIC_INFO_KEY_VEHICLE_ID,
        BASIC_INFO_KEY_HEAD_UNIT_MAKE,
        BASIC_INFO_KEY_HEAD_UNIT_MODEL,
        BASIC_INFO_KEY_HEAD_UNIT_SOFTWARE_BUILD,
        BASIC_INFO_KEY_HEAD_UNIT_SOFTWARE_VERSION,
        BASIC_INFO_KEY_DRIVER_POSITION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BasicInfoKeys {}

    /** Location of the driver: left. */
    public static final int BASIC_INFO_DRIVER_SIDE_LEFT   = 0;
    /** Location of the driver: right. */
    public static final int BASIC_INFO_DRIVER_SIDE_RIGHT  = 1;
    /** Location of the driver: center. */
    public static final int BASIC_INFO_DRIVER_SIDE_CENTER = 2;

    /**
     * Get {@link android.os.Bundle} containing basic car information. Check
     * {@link #BASIC_INFO_KEY_MANUFACTURER}, {@link #BASIC_INFO_KEY_MODEL},
     * {@link #BASIC_INFO_KEY_MODEL_YEAR}, and {@link #BASIC_INFO_KEY_VEHICLE_ID} for supported
     * keys in the {@link android.os.Bundle}.
     * @return {@link android.os.Bundle} containing basic car info.
     * @throws CarNotConnectedException
     */
    public abstract Bundle getBasicInfo() throws CarNotConnectedException;
}
