/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.car.hardware.cabin.CarCabinManager.CabinPropertyId;

import com.android.car.vehiclenetwork.VehicleNetworkConsts;

public class CabinHalService extends PropertyHalServiceBase {
    private static final boolean DBG = true;
    private static final String TAG = "CAR.CABIN.HAL";

    private final ManagerToHalPropIdMap mMgrHalPropIdMap = ManagerToHalPropIdMap.create(new int[] {
            CabinPropertyId.DOOR_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_DOOR_POS,

            CabinPropertyId.DOOR_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_DOOR_MOVE,

            CabinPropertyId.DOOR_LOCK,
            VehicleNetworkConsts.VEHICLE_PROPERTY_DOOR_LOCK,

            CabinPropertyId.MIRROR_Z_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_Z_POS,

            CabinPropertyId.MIRROR_Z_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_Z_MOVE,

            CabinPropertyId.MIRROR_Y_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_Y_POS,

            CabinPropertyId.MIRROR_Y_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_Y_MOVE,

            CabinPropertyId.MIRROR_LOCK,
            VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_LOCK,

            CabinPropertyId.MIRROR_FOLD,
            VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_FOLD,

            CabinPropertyId.SEAT_MEMORY_SELECT,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_MEMORY_SELECT,

            CabinPropertyId.SEAT_MEMORY_SET,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_MEMORY_SET,

            CabinPropertyId.SEAT_BELT_BUCKLED,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BELT_BUCKLED,

            CabinPropertyId.SEAT_BELT_HEIGHT_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BELT_HEIGHT_POS,

            CabinPropertyId.SEAT_BELT_HEIGHT_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BELT_HEIGHT_MOVE,

            CabinPropertyId.SEAT_FORE_AFT_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_FORE_AFT_POS,

            CabinPropertyId.SEAT_FORE_AFT_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_FORE_AFT_MOVE,

            CabinPropertyId.SEAT_BACKREST_ANGLE_1_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BACKREST_ANGLE_1_POS,

            CabinPropertyId.SEAT_BACKREST_ANGLE_1_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BACKREST_ANGLE_1_MOVE,

            CabinPropertyId.SEAT_BACKREST_ANGLE_2_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BACKREST_ANGLE_2_POS,

            CabinPropertyId.SEAT_BACKREST_ANGLE_2_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BACKREST_ANGLE_2_MOVE,

            CabinPropertyId.SEAT_HEIGHT_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEIGHT_POS,

            CabinPropertyId.SEAT_HEIGHT_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEIGHT_MOVE,

            CabinPropertyId.SEAT_DEPTH_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_DEPTH_POS,

            CabinPropertyId.SEAT_DEPTH_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_DEPTH_MOVE,

            CabinPropertyId.SEAT_TILT_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_TILT_POS,

            CabinPropertyId.SEAT_TILT_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_TILT_MOVE,

            CabinPropertyId.SEAT_LUMBAR_FORE_AFT_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_LUMBAR_FORE_AFT_POS,

            CabinPropertyId.SEAT_LUMBAR_FORE_AFT_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_LUMBAR_FORE_AFT_MOVE,

            CabinPropertyId.SEAT_LUMBAR_SIDE_SUPPORT_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_LUMBAR_SIDE_SUPPORT_POS,

            CabinPropertyId.SEAT_LUMBAR_SIDE_SUPPORT_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_LUMBAR_SIDE_SUPPORT_MOVE,

            CabinPropertyId.SEAT_HEADREST_HEIGHT_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_HEIGHT_POS,

            CabinPropertyId.SEAT_HEADREST_HEIGHT_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_HEIGHT_MOVE,

            CabinPropertyId.SEAT_HEADREST_ANGLE_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_ANGLE_POS,

            CabinPropertyId.SEAT_HEADREST_ANGLE_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_ANGLE_MOVE,

            CabinPropertyId.SEAT_HEADREST_FORE_AFT_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_FORE_AFT_POS,

            CabinPropertyId.SEAT_HEADREST_FORE_AFT_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_FORE_AFT_MOVE,

            CabinPropertyId.WINDOW_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_POS,

            CabinPropertyId.WINDOW_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_MOVE,

            CabinPropertyId.WINDOW_VENT_POS,
            VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_VENT_POS,

            CabinPropertyId.WINDOW_VENT_MOVE,
            VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_VENT_MOVE,

            CabinPropertyId.WINDOW_LOCK,
            VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_LOCK
    });

    public CabinHalService(VehicleHal vehicleHal) {
        super(vehicleHal, TAG, DBG);
    }

    // Convert the Cabin public API property ID to HAL property ID
    @Override
    protected int managerToHalPropId(int propId) {
        return mMgrHalPropIdMap.getHalPropId(propId);
    }

    // Convert he HAL specific property ID to Cabin public API
    @Override
    protected int halToManagerPropId(int halPropId) {
        return mMgrHalPropIdMap.getManagerPropId(halPropId);
    }
}
