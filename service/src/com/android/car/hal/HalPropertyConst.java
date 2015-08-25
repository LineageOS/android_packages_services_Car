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

//TODO Auto generate from vehicle.h
package com.android.car.hal;

public class HalPropertyConst {
    public static final int VEHICLE_PROPERTY_INVALD = 0;
    public static final int VEHICLE_PROPERTY_INFO_VIN = 0x00000100;
    public static final int VEHICLE_PROPERTY_INFO_MAKE = 0x00000101;
    public static final int VEHICLE_PROPERTY_INFO_MODEL = 0x00000102;
    public static final int VEHICLE_PROPERTY_INFO_MODEL_YEAR  = 0x00000103;
    public static final int VEHICLE_PROPERTY_PERF_VEHICLE_SPEED = 0x00000207;
    public static final int VEHICLE_PROPERTY_GEAR_SELECTION  = 0x00000400;
    public static final int VEHICLE_PROPERTY_CURRENT_GEAR = 0x00000401;
    public static final int VEHICLE_PROPERTY_PARKING_BRAKE_ON = 0x00000402;
    public static final int VEHICLE_PROPERTY_DRIVING_STATUS = 0x00000404;
    public static final int VEHICLE_PROPERTY_NIGHT_MODE = 0x00000407;

    public static class VehicleValueType {
        public static final int VEHICLE_VALUE_TYPE_SHOUD_NOT_USE       = 0x00;
        public static final int VEHICLE_VALUE_TYPE_STRING              = 0x01;
        public static final int VEHICLE_VALUE_TYPE_FLOAT               = 0x02;
        public static final int VEHICLE_VALUE_TYPE_INT64               = 0x03;
        public static final int VEHICLE_VALUE_TYPE_INT32               = 0x04;
        public static final int VEHICLE_VALUE_TYPE_BOOLEAN             = 0x05;
        public static final int VEHICLE_VALUE_TYPE_HVAC_FAN_SPEED           = 0x06;
        public static final int VEHICLE_VALUE_TYPE_HVAC_FAN_DIRECTION       = 0x07;
        public static final int  VEHICLE_VALUE_TYPE_HVAC_ZONE_TEMPERATURE    = 0x08;
        public static final int VEHICLE_VALUE_TYPE_HVAC_DEFROST_ON          = 0x09;
        public static final int VEHICLE_VALUE_TYPE_HVAC_AC_ON               = 0x0A;
    }

    public static class VehiclePropChangeMode {
        public static final int VEHICLE_PROP_CHANGE_MODE_STATIC         = 0x00;
        public static final int VEHICLE_PROP_CHANGE_MODE_ON_CHANGE      = 0x01;
        public static final int VEHICLE_PROP_CHANGE_MODE_CONTINUOUS     = 0x02;
    }

    public static class VehiclePropAccess {
        public static final int PROP_ACCESS_READ  = 0x01;
        public static final int PROP_ACCESS_WRITE = 0x02;
        public static final int PROP_ACCESS_READ_WRITE = 0x03;
    }

    public int getVehicleValueType(int property) {
        switch (property) {
            case VEHICLE_PROPERTY_INFO_VIN:
                return VehicleValueType.VEHICLE_VALUE_TYPE_STRING;
            case VEHICLE_PROPERTY_INFO_MAKE:
                return VehicleValueType.VEHICLE_VALUE_TYPE_STRING;
            case VEHICLE_PROPERTY_INFO_MODEL:
                return VehicleValueType.VEHICLE_VALUE_TYPE_STRING;
            case VEHICLE_PROPERTY_INFO_MODEL_YEAR:
                return VehicleValueType.VEHICLE_VALUE_TYPE_INT32;
            case VEHICLE_PROPERTY_PERF_VEHICLE_SPEED:
                return VehicleValueType.VEHICLE_VALUE_TYPE_FLOAT;
            case VEHICLE_PROPERTY_GEAR_SELECTION:
                return VehicleValueType.VEHICLE_VALUE_TYPE_INT32;
            case VEHICLE_PROPERTY_CURRENT_GEAR:
                return VehicleValueType.VEHICLE_VALUE_TYPE_INT32;
            case VEHICLE_PROPERTY_PARKING_BRAKE_ON:
                return VehicleValueType.VEHICLE_VALUE_TYPE_BOOLEAN;
            case VEHICLE_PROPERTY_DRIVING_STATUS:
                return VehicleValueType.VEHICLE_VALUE_TYPE_INT32;
            case VEHICLE_PROPERTY_NIGHT_MODE:
                return VehicleValueType.VEHICLE_VALUE_TYPE_BOOLEAN;
        }
        return VehicleValueType.VEHICLE_VALUE_TYPE_SHOUD_NOT_USE;
    }
}
