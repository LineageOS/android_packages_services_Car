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

#include <android-base/logging.h>
#include <android/frameworks/automotive/telemetry/CarData.h>

namespace android {
namespace automotive {
namespace telemetry {

using ::android::binder::Status;
using ::android::frameworks::automotive::telemetry::CarData;

Status CarTelemetryImpl::write(const std::vector<CarData>& dataList) {
    LOG(INFO) << "write called";
    return Status::ok();
}

status_t CarTelemetryImpl::dump(int fd, const Vector<String16>& args) {
    return android::OK;
}
}  // namespace telemetry
}  // namespace automotive
}  // namespace android
