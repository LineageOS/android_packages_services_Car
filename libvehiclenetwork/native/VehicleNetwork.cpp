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

#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <utils/threads.h>

#include <hardware/vehicle.h>

#include <VehicleNetwork.h>

namespace android {

sp<VehicleNetwork> VehicleNetwork::createVehicleNetwork(sp<VehicleNetworkListener>& listener) {
    sp<IBinder> binder = defaultServiceManager()->getService(
            String16(IVehicleNetwork::SERVICE_NAME));
    sp<VehicleNetwork> vn;
    if (binder != NULL) {
        sp<IVehicleNetwork> ivn(interface_cast<IVehicleNetwork>(binder));
        vn = new VehicleNetwork(ivn, listener);
        if (vn != NULL) {
            binder->linkToDeath(vn);
            // in case thread pool is not started, start it.
            ProcessState::self()->startThreadPool();
        }
    }
    return vn;
}

VehicleNetwork::VehicleNetwork(sp<IVehicleNetwork>& vehicleNetwork,
        sp<VehicleNetworkListener> &listener) :
        mService(vehicleNetwork),
        mClientListener(listener) {
}

VehicleNetwork::~VehicleNetwork() {
    IInterface::asBinder(mService)->unlinkToDeath(this);
}

status_t VehicleNetwork::setInt32Property(int32_t property, int32_t value) {
    vehicle_prop_value_t v;
    v.prop = property;
    v.value_type = VEHICLE_VALUE_TYPE_INT32;
    v.value.int32_value = value;
    return setProperty(v);
}

status_t VehicleNetwork::getInt32Property(int32_t property, int32_t* value, int64_t* timestamp) {
    vehicle_prop_value_t v;
    v.prop = property;
    // do not check error as it is always safe to access members for this data type.
    // saves one if for normal flow.
    status_t r = getProperty(&v);
    *value = v.value.int32_value;
    *timestamp = v.timestamp;
    return r;
}

status_t VehicleNetwork::setInt64Property(int32_t property, int64_t value) {
    vehicle_prop_value_t v;
    v.prop = property;
    v.value_type = VEHICLE_VALUE_TYPE_INT64;
    v.value.int64_value = value;
    return setProperty(v);
}

status_t VehicleNetwork::getInt64Property(int32_t property, int64_t* value, int64_t* timestamp) {
    vehicle_prop_value_t v;
    v.prop = property;
    status_t r = getProperty(&v);
    *value = v.value.int64_value;
    *timestamp = v.timestamp;
    return r;
}

status_t VehicleNetwork::setFloatProperty(int32_t property, float value) {
    vehicle_prop_value_t v;
    v.prop = property;
    v.value_type = VEHICLE_VALUE_TYPE_FLOAT;
    v.value.float_value = value;
    return setProperty(v);
}

status_t VehicleNetwork::getFloatProperty(int32_t property, float* value, int64_t* timestamp) {
    vehicle_prop_value_t v;
    v.prop = property;
    status_t r = getProperty(&v);
    *value = v.value.float_value;
    *timestamp = v.timestamp;
    return r;
}

status_t VehicleNetwork::setStringProperty(int32_t property, const String8& value) {
    vehicle_prop_value_t v;
    v.prop = property;
    v.value_type = VEHICLE_VALUE_TYPE_STRING;
    v.value.str_value.data = (uint8_t*)value.string();
    v.value.str_value.len = value.length();
    return setProperty(v);
}

status_t VehicleNetwork::getStringProperty(int32_t property, String8& value, int64_t* timestamp) {
    vehicle_prop_value_t v;
    v.prop = property;
    status_t r = getProperty(&v);
    if (r == NO_ERROR) {
        value.setTo((char*)v.value.str_value.data, v.value.str_value.len);
    }
    *timestamp = v.timestamp;
    return r;
}

sp<VehiclePropertiesHolder> VehicleNetwork::listProperties(int32_t property) {
    return mService->listProperties(property);
}

status_t VehicleNetwork::setProperty(const vehicle_prop_value_t& value) {
    return mService->setProperty(value);
}

status_t VehicleNetwork::getProperty(vehicle_prop_value_t* value) {
    return mService->getProperty(value);
}

status_t VehicleNetwork::subscribe(int32_t property, float sampleRate) {
    return mService->subscribe(this, property, sampleRate);
}

void VehicleNetwork::unsubscribe(int32_t property) {
    mService->unsubscribe(this, property);
}

void VehicleNetwork::binderDied(const wp<IBinder>& who) {
    //TODO
}

status_t VehicleNetwork::onEvents(sp<VehiclePropValueListHolder>& events) {
    //TODO call this in separate thread to prevent blocking VNS
    mClientListener->onEvents(events);
    return NO_ERROR;
}

}; // namespace android
