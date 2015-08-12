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

#ifndef CAR_VEHICLE_HAL_EVENT_LISTENER_H_
#define CAR_VEHICLE_HAL_EVENT_LISTENER_H_

#include <hardware/vehicle.h>

#include <utils/List.h>

namespace android {

/**
 * Listener to monitor Hal event. Should register this to VehicleHal instance.
 */
class IVehicleHalEventListener {
public:
    virtual ~IVehicleHalEventListener() {};

    /**
     * Called in hal thread context during init. Can be a place to run thread specific init.
     */
    virtual void onHalThreadInit() = 0;
    virtual void onHalThreadRelease() = 0;
    virtual void onHalEvents(List<vehicle_prop_value_t*>& events) = 0;
    virtual void onHalError(int errorCode) = 0;
};


};
#endif /* CAR_VEHICLE_HAL_EVENT_LISTENER_H_ */
