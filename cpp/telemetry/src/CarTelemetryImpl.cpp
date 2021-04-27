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

#include "CarTelemetryImpl.h"

#include "BufferedCarData.h"

#include <aidl/android/frameworks/automotive/telemetry/CarData.h>
#include <android/binder_ibinder.h>

#include <stdio.h>

#include <memory>

namespace android {
namespace automotive {
namespace telemetry {

using ::aidl::android::frameworks::automotive::telemetry::CarData;

CarTelemetryImpl::CarTelemetryImpl(RingBuffer* buffer) : mRingBuffer(buffer) {}

// TODO(b/174608802): Add 10kb size check for the `dataList`, see the AIDL for the limits
ndk::ScopedAStatus CarTelemetryImpl::write(const std::vector<CarData>& dataList) {
    uid_t publisherUid = ::AIBinder_getCallingUid();
    for (auto&& data : dataList) {
        mRingBuffer->push({.mId = data.id,
                           .mContent = std::move(data.content),
                           .mPublisherUid = publisherUid});
    }
    return ndk::ScopedAStatus::ok();
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
