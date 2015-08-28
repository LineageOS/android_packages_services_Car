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

#ifndef ANDROID_VEHICLE_NETWORK_H
#define ANDROID_VEHICLE_NETWORK_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/threads.h>
#include <utils/RefBase.h>
#include <utils/Errors.h>
#include <binder/IInterface.h>
#include <binder/IMemory.h>

#include "IVehicleNetwork.h"

namespace android {

// ----------------------------------------------------------------------------

/**
 * Listener for client to implement to get events from Vehicle network service.
 */
class VehicleNetworkListener : public RefBase
{
public:
    VehicleNetworkListener() {};
    virtual ~VehicleNetworkListener() {};
    virtual void onEvents(sp<VehiclePropValueListHolder>& events) = 0;
};

// ----------------------------------------------------------------------------
/**
 * Vehicle network API for low level components like HALs to access / control car information.
 * This is reference counted. So use with sp<>.
 */
class VehicleNetwork : public IBinder::DeathRecipient, public BnVehicleNetworkListener
{
public:
    /**
     * Factory method for VehicleNetwork. Client should use this method to create
     * a new instance.
     */
    static sp<VehicleNetwork> createVehicleNetwork(sp<VehicleNetworkListener> &listener);

    virtual ~VehicleNetwork();

    /** Set int32 value */
    status_t setInt32Property(int32_t property, int32_t value);
    /** get int32 value */
    status_t getInt32Property(int32_t property, int32_t* value, int64_t* timestamp);
    status_t setInt64Property(int32_t property, int64_t value);
    status_t getInt64Property(int32_t property, int64_t* value, int64_t* timestamp);
    status_t setFloatProperty(int32_t property, float value);
    status_t getFloatProperty(int32_t property, float* value, int64_t* timestamp);
    status_t setStringProperty(int32_t property, const String8& value);
    status_t getStringProperty(int32_t property, String8& value, int64_t* timestamp);
    sp<VehiclePropertiesHolder> listProperties(int32_t property = 0);
    /** For generic value setting. At least prop, value_type, and value should be set. */
    status_t setProperty(const vehicle_prop_value_t& value);
    /** For generic value getting. value->prop should be set. */
    status_t getProperty(vehicle_prop_value_t* value);
    status_t subscribe(int32_t property, float sampleRate);
    void unsubscribe(int32_t property);

    //IBinder::DeathRecipient, not for client
    void binderDied(const wp<IBinder>& who);
    // BnVehicleNetworkListener, not for client
    status_t onEvents(sp<VehiclePropValueListHolder>& events);

private:
    VehicleNetwork(sp<IVehicleNetwork>& vehicleNetwork, sp<VehicleNetworkListener> &listener);

private:
    sp<IVehicleNetwork> mService;
    sp<VehicleNetworkListener> mClientListener;
    Mutex mLock;
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif /* ANDROID_VEHICLE_NETWORK_H */

