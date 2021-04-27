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

#include "BufferedCarData.h"

#include <android-base/logging.h>
#include <android/automotive/telemetry/internal/CarDataInternal.h>
#include <android/automotive/telemetry/internal/ICarDataListener.h>

namespace android {
namespace automotive {
namespace telemetry {

using ::android::sp;
using ::android::automotive::telemetry::internal::CarDataInternal;
using ::android::automotive::telemetry::internal::ICarDataListener;
using ::android::binder::Status;

CarTelemetryInternalImpl::CarTelemetryInternalImpl(RingBuffer* buffer) : mRingBuffer(buffer) {}

Status CarTelemetryInternalImpl::setListener(const sp<ICarDataListener>& listener) {
    // TODO(b/182608968): implement
    return Status::ok();
}

Status CarTelemetryInternalImpl::clearListener() {
    // TODO(b/182608968): implement
    return Status::ok();
}

status_t CarTelemetryInternalImpl::dump(int fd, const android::Vector<android::String16>& args) {
    dprintf(fd, "ICarTelemetryInternal:\n");
    mRingBuffer->dump(fd);
    return android::OK;
}
}  // namespace telemetry
}  // namespace automotive
}  // namespace android
