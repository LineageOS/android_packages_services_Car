/*
 * Copyright (c) 2021, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "CarTelemetryInternalImpl.h"

#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android/automotive/telemetry/internal/BnCarDataListener.h>
#include <android/automotive/telemetry/internal/CarDataInternal.h>
#include <android/automotive/telemetry/internal/ICarDataListener.h>
#include <binder/IPCThreadState.h>

namespace android {
namespace automotive {
namespace telemetry {

using ::android::sp;
using ::android::automotive::telemetry::internal::BnCarDataListener;
using ::android::automotive::telemetry::internal::CarDataInternal;
using ::android::automotive::telemetry::internal::ICarDataListener;
using ::android::base::StringPrintf;
using ::android::binder::Status;

CarTelemetryInternalImpl::CarTelemetryInternalImpl(RingBuffer* buffer) : mRingBuffer(buffer) {
    mBinderDeathRecipient = new BinderDeathRecipient(
            [this](const wp<android::IBinder>& binder) { listenerBinderDied(binder); });
}

Status CarTelemetryInternalImpl::setListener(const sp<ICarDataListener>& listener) {
    const std::scoped_lock<std::mutex> lock(mMutex);

    if (mCarDataListener != nullptr) {
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE,
                                         "CarDataListener is already set.");
    }

    sp<IBinder> binder = BnCarDataListener::asBinder(listener);
    status_t status = binder->linkToDeath(mBinderDeathRecipient);
    if (status != android::OK) {
        pid_t callingPid = IPCThreadState::self()->getCallingPid();
        uid_t callingUid = IPCThreadState::self()->getCallingUid();
        std::string errorStr = StringPrintf("The given callback(pid: %d, uid: %d) is dead",
                                            callingPid, callingUid);
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, errorStr.c_str());
    }

    mCarDataListener = listener;
    return Status::ok();
}

Status CarTelemetryInternalImpl::clearListener() {
    const std::scoped_lock<std::mutex> lock(mMutex);
    if (mCarDataListener == nullptr) {
        return Status::ok();
    }
    sp<IBinder> binder = BnCarDataListener::asBinder(mCarDataListener);
    auto status = binder->unlinkToDeath(mBinderDeathRecipient);
    if (status != android::OK) {
        LOG(WARNING) << "unlinkToDeath for CarDataListener failed, continuing anyway";
    }
    mCarDataListener = nullptr;
    return Status::ok();
}

status_t CarTelemetryInternalImpl::dump(int fd, const android::Vector<android::String16>& args) {
    dprintf(fd, "ICarTelemetryInternal:\n");
    mRingBuffer->dump(fd);
    return android::OK;
}

// Removes the listener if its binder dies.
void CarTelemetryInternalImpl::listenerBinderDied(const wp<android::IBinder>& what) {
    const std::scoped_lock<std::mutex> lock(mMutex);
    if (BnCarDataListener::asBinder(mCarDataListener) == what.unsafe_get()) {
        LOG(WARNING) << "A CarDataListener died, removing the listener.";
        mCarDataListener = nullptr;
    } else {
        LOG(ERROR) << "An unknown CarDataListener died, ignoring";
    }
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
