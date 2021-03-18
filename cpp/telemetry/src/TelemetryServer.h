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

#ifndef CPP_TELEMETRY_SRC_TELEMETRYSERVER_H_
#define CPP_TELEMETRY_SRC_TELEMETRYSERVER_H_

#include "CarTelemetryImpl.h"
#include "CarTelemetryInternalImpl.h"

#include <utils/Errors.h>

namespace android {
namespace automotive {
namespace telemetry {

class TelemetryServer {
public:
    TelemetryServer();

    // Registers all the implemented AIDL services. Waits until `servicemanager` is available.
    // Aborts the process if fails.
    void registerServices();

    // Blocks the thread.
    void startAndJoinThreadPool();

private:
    RingBuffer mRingBuffer;
};

}  // namespace telemetry
}  // namespace automotive
}  // namespace android

#endif  // CPP_TELEMETRY_SRC_TELEMETRYSERVER_H_
