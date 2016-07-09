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

    public CabinHalService(VehicleHal vehicleHal) {
        super(vehicleHal, TAG, DBG);
    }

    // Convert the Cabin public API property ID to HAL property ID
    @Override
    protected int managerToHalPropId(int propId) {
        switch (propId) {
            case CabinPropertyId.DOOR_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_DOOR_POS;
            case CabinPropertyId.DOOR_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_DOOR_MOVE;
            case CabinPropertyId.DOOR_LOCK:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_DOOR_LOCK;
            case CabinPropertyId.MIRROR_Z_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_Z_POS;
            case CabinPropertyId.MIRROR_Z_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_Z_MOVE;
            case CabinPropertyId.MIRROR_Y_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_Y_POS;
            case CabinPropertyId.MIRROR_Y_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_Y_MOVE;
            case CabinPropertyId.MIRROR_LOCK:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_LOCK;
            case CabinPropertyId.MIRROR_FOLD:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_FOLD;
            case CabinPropertyId.SEAT_MEMORY_SELECT:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_MEMORY_SELECT;
            case CabinPropertyId.SEAT_MEMORY_SET:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_MEMORY_SET;
            case CabinPropertyId.SEAT_BELT_BUCKLED:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BELT_BUCKLED;
            case CabinPropertyId.SEAT_BELT_HEIGHT_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BELT_HEIGHT_POS;
            case CabinPropertyId.SEAT_BELT_HEIGHT_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BELT_HEIGHT_MOVE;
            case CabinPropertyId.SEAT_FORE_AFT_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_FORE_AFT_POS;
            case CabinPropertyId.SEAT_FORE_AFT_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_FORE_AFT_MOVE;
            case CabinPropertyId.SEAT_BACKREST_ANGLE_1_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BACKREST_ANGLE_1_POS;
            case CabinPropertyId.SEAT_BACKREST_ANGLE_1_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BACKREST_ANGLE_1_MOVE;
            case CabinPropertyId.SEAT_BACKREST_ANGLE_2_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BACKREST_ANGLE_2_POS;
            case CabinPropertyId.SEAT_BACKREST_ANGLE_2_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BACKREST_ANGLE_2_MOVE;
            case CabinPropertyId.SEAT_HEIGHT_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEIGHT_POS;
            case CabinPropertyId.SEAT_HEIGHT_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEIGHT_MOVE;
            case CabinPropertyId.SEAT_DEPTH_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_DEPTH_POS;
            case CabinPropertyId.SEAT_DEPTH_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_DEPTH_MOVE;
            case CabinPropertyId.SEAT_TILT_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_TILT_POS;
            case CabinPropertyId.SEAT_TILT_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_TILT_MOVE;
            case CabinPropertyId.SEAT_LUMBAR_FORE_AFT_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_LUMBAR_FORE_AFT_POS;
            case CabinPropertyId.SEAT_LUMBAR_FORE_AFT_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_LUMBAR_FORE_AFT_MOVE;
            case CabinPropertyId.SEAT_LUMBAR_SIDE_SUPPORT_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_LUMBAR_SIDE_SUPPORT_POS;
            case CabinPropertyId.SEAT_LUMBAR_SIDE_SUPPORT_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_LUMBAR_SIDE_SUPPORT_MOVE;
            case CabinPropertyId.SEAT_HEADREST_HEIGHT_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_HEIGHT_POS;
            case CabinPropertyId.SEAT_HEADREST_HEIGHT_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_HEIGHT_MOVE;
            case CabinPropertyId.SEAT_HEADREST_ANGLE_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_ANGLE_POS;
            case CabinPropertyId.SEAT_HEADREST_ANGLE_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_ANGLE_MOVE;
            case CabinPropertyId.SEAT_HEADREST_FORE_AFT_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_FORE_AFT_POS;
            case CabinPropertyId.SEAT_HEADREST_FORE_AFT_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_FORE_AFT_MOVE;
            case CabinPropertyId.WINDOW_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_POS;
            case CabinPropertyId.WINDOW_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_MOVE;
            case CabinPropertyId.WINDOW_VENT_POS:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_VENT_POS;
            case CabinPropertyId.WINDOW_VENT_MOVE:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_VENT_MOVE;
            case CabinPropertyId.WINDOW_LOCK:
                return VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_LOCK;
            default:
                throw new IllegalArgumentException("propId " + propId + " not supported");
        }
    }

    // Convert he HAL specific property ID to Cabin public API
    @Override
    protected int halToManagerPropId(int halPropId) {
        switch (halPropId) {
            case VehicleNetworkConsts.VEHICLE_PROPERTY_DOOR_POS:
                return CabinPropertyId.DOOR_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_DOOR_MOVE:
                return CabinPropertyId.DOOR_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_DOOR_LOCK:
                return CabinPropertyId.DOOR_LOCK;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_Z_POS:
                return CabinPropertyId.MIRROR_Z_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_Z_MOVE:
                return CabinPropertyId.MIRROR_Z_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_Y_POS:
                return CabinPropertyId.MIRROR_Y_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_Y_MOVE:
                return CabinPropertyId.MIRROR_Y_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_LOCK:
                return CabinPropertyId.MIRROR_LOCK;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_MIRROR_FOLD:
                return CabinPropertyId.MIRROR_FOLD;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_MEMORY_SELECT:
                return CabinPropertyId.SEAT_MEMORY_SELECT;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_MEMORY_SET:
                return CabinPropertyId.SEAT_MEMORY_SET;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BELT_BUCKLED:
                return CabinPropertyId.SEAT_BELT_BUCKLED;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BELT_HEIGHT_POS:
                return CabinPropertyId.SEAT_BELT_HEIGHT_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BELT_HEIGHT_MOVE:
                return CabinPropertyId.SEAT_BELT_HEIGHT_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_FORE_AFT_POS:
                return CabinPropertyId.SEAT_FORE_AFT_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_FORE_AFT_MOVE:
                return CabinPropertyId.SEAT_FORE_AFT_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BACKREST_ANGLE_1_POS:
                return CabinPropertyId.SEAT_BACKREST_ANGLE_1_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BACKREST_ANGLE_1_MOVE:
                return CabinPropertyId.SEAT_BACKREST_ANGLE_1_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BACKREST_ANGLE_2_POS:
                return CabinPropertyId.SEAT_BACKREST_ANGLE_2_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_BACKREST_ANGLE_2_MOVE:
                return CabinPropertyId.SEAT_BACKREST_ANGLE_2_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEIGHT_POS:
                return CabinPropertyId.SEAT_HEIGHT_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEIGHT_MOVE:
                return CabinPropertyId.SEAT_HEIGHT_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_DEPTH_POS:
                return CabinPropertyId.SEAT_DEPTH_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_DEPTH_MOVE:
                return CabinPropertyId.SEAT_DEPTH_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_TILT_POS:
                return CabinPropertyId.SEAT_TILT_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_TILT_MOVE:
                return CabinPropertyId.SEAT_TILT_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_LUMBAR_FORE_AFT_POS:
                return CabinPropertyId.SEAT_LUMBAR_FORE_AFT_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_LUMBAR_FORE_AFT_MOVE:
                return CabinPropertyId.SEAT_LUMBAR_FORE_AFT_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_LUMBAR_SIDE_SUPPORT_POS:
                return CabinPropertyId.SEAT_LUMBAR_SIDE_SUPPORT_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_LUMBAR_SIDE_SUPPORT_MOVE:
                return CabinPropertyId.SEAT_LUMBAR_SIDE_SUPPORT_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_HEIGHT_POS:
                return CabinPropertyId.SEAT_HEADREST_HEIGHT_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_HEIGHT_MOVE:
                return CabinPropertyId.SEAT_HEADREST_HEIGHT_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_ANGLE_POS:
                return CabinPropertyId.SEAT_HEADREST_ANGLE_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_ANGLE_MOVE:
                return CabinPropertyId.SEAT_HEADREST_ANGLE_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_FORE_AFT_POS:
                return CabinPropertyId.SEAT_HEADREST_FORE_AFT_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_SEAT_HEADREST_FORE_AFT_MOVE:
                return CabinPropertyId.SEAT_HEADREST_FORE_AFT_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_POS:
                return CabinPropertyId.WINDOW_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_MOVE:
                return CabinPropertyId.WINDOW_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_VENT_POS:
                return CabinPropertyId.WINDOW_VENT_POS;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_VENT_MOVE:
                return CabinPropertyId.WINDOW_VENT_MOVE;
            case VehicleNetworkConsts.VEHICLE_PROPERTY_WINDOW_LOCK:
                return CabinPropertyId.WINDOW_LOCK;
            default:
                throw new IllegalArgumentException("halPropId " + halPropId + " not supported");
        }
    }
}
