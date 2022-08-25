/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <memory>
#define LOG_TAG "cartelemetryd_sample"

#include <aidl/android/frameworks/automotive/telemetry/BnCarTelemetryCallback.h>
#include <aidl/android/frameworks/automotive/telemetry/ICarTelemetry.h>
#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <android/binder_manager.h>
#include <utils/SystemClock.h>

using ::aidl::android::frameworks::automotive::telemetry::CallbackConfig;
using ::aidl::android::frameworks::automotive::telemetry::CarData;
using ::aidl::android::frameworks::automotive::telemetry::ICarTelemetry;
using ::android::base::StringPrintf;

class CarTelemetryCallbackImpl :
      public aidl::android::frameworks::automotive::telemetry::BnCarTelemetryCallback {
public:
    ndk::ScopedAStatus onChange(const std::vector<int32_t>& carDataIds) {
        for (int32_t id : carDataIds) {
            LOG(INFO) << "CarTelemetryCallbackImpl: CarData ID=" << id << " is active";
        }
        return ndk::ScopedAStatus::ok();
    }
};

int main(int argc, char* argv[]) {
    const auto started_at_millis = android::elapsedRealtime();

    // The name of the service is described in
    // https://source.android.com/devices/architecture/aidl/aidl-hals#instance-names
    const std::string instance = StringPrintf("%s/default", ICarTelemetry::descriptor);
    LOG(INFO) << "Obtaining: " << instance;
    std::shared_ptr<ICarTelemetry> service = ICarTelemetry::fromBinder(
            ndk::SpAIBinder(AServiceManager_getService(instance.c_str())));
    if (!service) {
        LOG(ERROR) << "ICarTelemetry service not found, may be still initializing?";
        return 1;
    }

    // Add a ICarTelemetryCallback and listen for changes in CarData IDs 1, 2, and 3
    std::shared_ptr<CarTelemetryCallbackImpl> callback =
            ndk::SharedRefBase::make<CarTelemetryCallbackImpl>();
    CallbackConfig config;
    config.carDataIds = {1, 2, 3};
    LOG(INFO) << "Adding a CarTelemetryCallback";
    ndk::ScopedAStatus addStatus = service->addCallback(config, callback);
    if (!addStatus.isOk()) {
        LOG(WARNING) << "Failed to add CarTelemetryCallback: " << addStatus.getMessage();
    }

    LOG(INFO) << "Building a CarData message, delta_since_start: "
              << android::elapsedRealtime() - started_at_millis << " millis";

    // Build a CarData message
    // TODO(b/174608802): set a correct data ID and content
    CarData msg;
    msg.id = 1;
    msg.content = {1, 0, 1, 0};

    LOG(INFO) << "Sending the car data, delta_since_start: "
              << android::elapsedRealtime() - started_at_millis << " millis";

    // Send the data
    ndk::ScopedAStatus writeStatus = service->write({msg});

    if (!writeStatus.isOk()) {
        LOG(WARNING) << "Failed to write to the service: " << writeStatus.getMessage();
    }

    // Note: On a device the delta_since_start was between 1ms to 4ms
    //      (service side was not fully implemented yet during the test).
    LOG(INFO) << "Finished sending the car data, delta_since_start: "
              << android::elapsedRealtime() - started_at_millis << " millis";

    // Remove the ICarTelemetryCallback to prevent a dead reference
    LOG(INFO) << "Removing a CarTelemetryCallback";
    ndk::ScopedAStatus removeStatus = service->removeCallback(callback);
    if (!removeStatus.isOk()) {
        LOG(WARNING) << "Failed to remove CarTelemetryCallback: " << removeStatus.getMessage();
    }

    return 0;
}
