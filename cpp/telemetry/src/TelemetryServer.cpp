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
#include <android/binder_interface_utils.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <inttypes.h>  // for PRIu64 and friends

#include <memory>
#include <thread>  // NOLINT(build/c++11)

namespace android {
namespace automotive {
namespace telemetry {

using ::android::automotive::telemetry::RingBuffer;

constexpr const char kCarTelemetryServiceName[] =
        "android.frameworks.automotive.telemetry.ICarTelemetry/default";
constexpr const char kCarTelemetryInternalServiceName[] =
        "android.automotive.telemetry.internal.ICarTelemetryInternal/default";

// TODO(b/183444070): make it configurable using sysprop
// CarData count limit in the RingBuffer. In worst case it will use kMaxBufferSize * 10Kb memory,
// which is ~ 1MB.
const int kMaxBufferSize = 100;

TelemetryServer::TelemetryServer() : mRingBuffer(kMaxBufferSize) {}

void TelemetryServer::registerServices() {
    std::shared_ptr<CarTelemetryImpl> telemetry =
            ndk::SharedRefBase::make<CarTelemetryImpl>(&mRingBuffer);
    std::shared_ptr<CarTelemetryInternalImpl> telemetryInternal =
            ndk::SharedRefBase::make<CarTelemetryInternalImpl>(&mRingBuffer);

    // Wait for the service manager before starting ICarTelemetry service.
    while (android::base::GetProperty("init.svc.servicemanager", "") != "running") {
        // Poll frequent enough so the writer clients can connect to the service during boot.
        std::this_thread::sleep_for(250ms);
    }

    LOG(VERBOSE) << "Registering " << kCarTelemetryServiceName;
    binder_exception_t exception =
            ::AServiceManager_addService(telemetry->asBinder().get(), kCarTelemetryServiceName);
    if (exception != ::EX_NONE) {
        LOG(FATAL) << "Unable to register " << kCarTelemetryServiceName
                   << ", exception=" << exception;
    }

    LOG(VERBOSE) << "Registering " << kCarTelemetryInternalServiceName;
    exception = ::AServiceManager_addService(telemetryInternal->asBinder().get(),
                                             kCarTelemetryInternalServiceName);
    if (exception != ::EX_NONE) {
        LOG(FATAL) << "Unable to register " << kCarTelemetryInternalServiceName
                   << ", exception=" << exception;
    }
}

void TelemetryServer::startAndJoinThreadPool() {
    ::ABinderProcess_startThreadPool();  // Starts the default 15 binder threads.
    ::ABinderProcess_joinThreadPool();
}

}  // namespace telemetry
}  // namespace automotive
}  // namespace android
