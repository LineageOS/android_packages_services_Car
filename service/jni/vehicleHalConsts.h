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

//TODO auto-generate this from vehicle.h

namespace android {

#include <hardware/vehicle.h>

vehicle_value_type getPropertyDataType(int property) {
    switch (property) {
    case VEHICLE_PROPERTY_INFO_VIN:
        return VEHICLE_VALUE_TYPE_STRING;
    case VEHICLE_PROPERTY_INFO_MAKE:
        return VEHICLE_VALUE_TYPE_STRING;
    case VEHICLE_PROPERTY_INFO_MODEL:
        return VEHICLE_VALUE_TYPE_STRING;
    case VEHICLE_PROPERTY_INFO_MODEL_YEAR:
        return VEHICLE_VALUE_TYPE_INT32;
    case VEHICLE_PROPERTY_PERF_VEHICLE_SPEED:
        return VEHICLE_VALUE_TYPE_FLOAT;
    case VEHICLE_PROPERTY_GEAR_SELECTION:
        return VEHICLE_VALUE_TYPE_INT32;
    case VEHICLE_PROPERTY_CURRENT_GEAR:
        return VEHICLE_VALUE_TYPE_INT32;
    case VEHICLE_PROPERTY_PARKING_BRAKE_ON:
        return VEHICLE_VALUE_TYPE_BOOLEAN;
    case VEHICLE_PROPERTY_DRIVE_STATE:
        return VEHICLE_VALUE_TYPE_INT32;
    case VEHICLE_PROPERTY_NIGHT_MODE:
        return VEHICLE_VALUE_TYPE_BOOLEAN;
    }
    return VEHICLE_VALUE_TYPE_SHOUD_NOT_USE;
}

};
