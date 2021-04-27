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

#include "TelemetryServer.h"

#include "CarTelemetryImpl.h"
#include "RingBuffer.h"

#include <android-base/chrono_utils.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>

#include <inttypes.h>  // for PRIu64 and friends

#include <memory>
#include <thread>  // NOLINT(build/c++11)

namespace android {
namespace automotive {
namespace telemetry {

using ::android::String16;
using ::android::automotive::telemetry::CarTelemetryImpl;
using ::android::automotive::telemetry::RingBuffer;

constexpr const char kCarTelemetryServiceName[] =
        "android.frameworks.automotive.telemetry.ICarTelemetry/default";
constexpr const char kCarTelemetryInternalServiceName[] =
        "android.automotive.telemetry.internal.ICarTelemetryInternal/default";

// Total CarData content size limit in the RingBuffer. 2MB max memory for buffer is good for now.
const int kMaxBufferSizeKilobytes = 2048;

TelemetryServer::TelemetryServer() : mRingBuffer(kMaxBufferSizeKilobytes * 1024) {}

void TelemetryServer::registerServices() {
    android::sp<CarTelemetryImpl> telemetry = new CarTelemetryImpl(&mRingBuffer);
    android::sp<CarTelemetryInternalImpl> telemetryInternal =
            new CarTelemetryInternalImpl(&mRingBuffer);

    // Wait for the service manager before starting ICarTelemetry service.
    while (android::base::GetProperty("init.svc.servicemanager", "") != "running") {
        // Poll frequent enough so the writer clients can connect to the service during boot.
        std::this_thread::sleep_for(250ms);
    }

    LOG(VERBOSE) << "Registering " << kCarTelemetryServiceName;
    auto status = android::defaultServiceManager()->addService(String16(kCarTelemetryServiceName),
                                                               telemetry);
    if (status != android::OK) {
        LOG(FATAL) << "Unable to register " << kCarTelemetryServiceName << ", status=" << status;
    }

    LOG(VERBOSE) << "Registering " << kCarTelemetryInternalServiceName;
    status =
            android::defaultServiceManager()->addService(String16(kCarTelemetryInternalServiceName),
                                                         telemetryInternal);
    if (status != android::OK) {
        LOG(FATAL) << "Unable to register " << kCarTelemetryInternalServiceName
                   << ", status=" << status;
    }
}

void TelemetryServer::startAndJoinThreadPool() {
    android::ProcessState::self()->startThreadPool();  // Starts default 15 binder threads.
    android::IPCThreadState::self()->joinThreadPool();
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
