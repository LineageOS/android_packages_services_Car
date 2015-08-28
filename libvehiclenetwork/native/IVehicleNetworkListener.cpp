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



#define LOG_TAG "VehicleNetworkListener"

#include <memory>

#include <utils/Errors.h>
#include <utils/Log.h>

#include <VehicleNetworkProto.pb.h>

#include <IVehicleNetworkListener.h>

#include "VehicleNetworkProtoUtil.h"

namespace android {

enum {
    ON_EVENTS = IBinder::FIRST_CALL_TRANSACTION,
};

class BpVehicleNetworkListener : public BpInterface<IVehicleNetworkListener>
{
public:
    BpVehicleNetworkListener(const sp<IBinder> & impl)
        : BpInterface<IVehicleNetworkListener>(impl) {
    }

    virtual status_t onEvents(sp<VehiclePropValueListHolder>& events) {
        Parcel data, reply;
        data.writeInterfaceToken(IVehicleNetworkListener::getInterfaceDescriptor());
        std::unique_ptr<VehiclePropValues> values(new VehiclePropValues());
        ASSERT_OR_HANDLE_NO_MEMORY(values.get(), return NO_MEMORY);
        status_t r = VehicleNetworkProtoUtil::toVehiclePropValues(events->getList(), *values.get());
        if (r != NO_ERROR) {
            return r;
        }
        data.writeInt32(1); // for java compatibility
        WritableBlobHolder blob(new Parcel::WritableBlob());
        int size = values->ByteSize();
        data.writeInt32(size);
        data.writeBlob(size, false, blob.blob);
        values->SerializeToArray(blob.blob->data(), size);
        r = remote()->transact(ON_EVENTS, data, &reply);
        return r;
    }
};

IMPLEMENT_META_INTERFACE(VehicleNetworkListener, "com.android.car.IVehicleNetworkListener");

// ----------------------------------------------------------------------

status_t BnVehicleNetworkListener::onTransact(uint32_t code, const Parcel& data, Parcel* reply,
        uint32_t flags) {
    status_t r;
    switch (code) {
        case ON_EVENTS: {
            CHECK_INTERFACE(IVehicleNetworkListener, data, reply);
            if (data.readInt32() == 0) { // java side allows passing null with this.
                return BAD_VALUE;
            }
            List<vehicle_prop_value_t*>* list = new List<vehicle_prop_value_t*>();
            ASSERT_OR_HANDLE_NO_MEMORY(list, return NO_MEMORY);
            sp<VehiclePropValueListHolder> holder(new VehiclePropValueListHolder(list));
            ASSERT_OR_HANDLE_NO_MEMORY(holder.get(), return NO_MEMORY);
            ReadableBlobHolder blob(new Parcel::ReadableBlob());
            ASSERT_OR_HANDLE_NO_MEMORY(blob.blob, return NO_MEMORY);
            int32_t size = data.readInt32();
            r = data.readBlob(size, blob.blob);
            if (r != NO_ERROR) {
                ALOGE("onEvents: cannot read blob");
                return r;
            }
            std::unique_ptr<VehiclePropValues> v(new VehiclePropValues());
            ASSERT_OR_HANDLE_NO_MEMORY(v.get(), return NO_MEMORY);
            if (!v->ParseFromArray(blob.blob->data(), size)) {
                ALOGE("onEvents: cannot parse data");
                return BAD_VALUE;
            }
            r = VehicleNetworkProtoUtil::fromVehiclePropValues(*v.get(), *list);
            if (r != NO_ERROR) {
                ALOGE("onEvents: cannot convert data");
                return BAD_VALUE;
            }
            r = onEvents(holder);
            return r;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

}; // namespace android
