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
#include "RingBuffer.h"

#include <android-base/chrono_utils.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>

#include <thread>  // NOLINT(build/c++11)

using ::android::String16;
using ::android::automotive::telemetry::CarTelemetryImpl;
using ::android::automotive::telemetry::RingBuffer;

constexpr const char kCarTelemetryServiceName[] =
        "android.frameworks.automotive.telemetry.ICarTelemetry/default";
// Total CarData content size limit in the RingBuffer. 2MB max memory for buffer is good for now.
const int kMaxBufferSizeKilobytes = 2048;

// TODO(b/174608802): handle SIGQUIT/SIGTERM

int main(void) {
    LOG(INFO) << "Starting cartelemetryd";

    RingBuffer buffer(kMaxBufferSizeKilobytes * 1024);

    android::sp<CarTelemetryImpl> telemetry = new CarTelemetryImpl(&buffer);

    // Wait for the service manager before starting ICarTelemetry service.
    while (android::base::GetProperty("init.svc.servicemanager", "") != "running") {
        // Poll frequent enough so the writer clients can connect to the service during boot.
        std::this_thread::sleep_for(250ms);
    }

    LOG(VERBOSE) << "Registering " << kCarTelemetryServiceName;
    auto status = android::defaultServiceManager()->addService(String16(kCarTelemetryServiceName),
                                                               telemetry);
    if (status != android::OK) {
        LOG(ERROR) << "Unable to register " << kCarTelemetryServiceName << ", status=" << status;
        return 1;
    }

    LOG(VERBOSE) << "Service is created, joining the threadpool";
    android::ProcessState::self()->startThreadPool();  // Starts default 15 binder threads.
    android::IPCThreadState::self()->joinThreadPool();
    return 1;  // never reaches
}
