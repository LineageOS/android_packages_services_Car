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

package android.car;

import android.annotation.StringDef;
import android.car.annotation.ValueTypeDef;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Utility to retrieve various static information from car. Each data are grouped as {@link Bundle}
 * and relevant data can be checked from {@link Bundle} using pre-specified keys.
 */
public final class CarInfoManager implements CarManagerBase {

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

    /** @hide */
    @StringDef({
        BASIC_INFO_KEY_MANUFACTURER,
        BASIC_INFO_KEY_MODEL,
        BASIC_INFO_KEY_MODEL_YEAR,
        BASIC_INFO_KEY_VEHICLE_ID
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BasicInfoKeys {}

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
     * Get {@link android.os.Bundle} containing basic car information. Check
     * {@link #BASIC_INFO_KEY_MANUFACTURER}, {@link #BASIC_INFO_KEY_MODEL},
     * {@link #BASIC_INFO_KEY_MODEL_YEAR}, and {@link #BASIC_INFO_KEY_VEHICLE_ID} for supported
     * keys in the {@link android.os.Bundle}.
     * @return {@link android.os.Bundle} containing basic car info.
     * @throws CarNotConnectedException
     */
    public Bundle getBasicInfo() throws CarNotConnectedException {
        try {
            return mService.getBasicInfo();
        } catch (IllegalStateException e) {
            CarApiUtil.checkCarNotConnectedExceptionFromCarService(e);
        } catch (RemoteException e) {
            throw new CarNotConnectedException(e);
        }
        return null;
    }

    /** @hide */
    CarInfoManager(IBinder service) {
        mService = ICarInfo.Stub.asInterface(service);
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        //nothing to do
    }
}
