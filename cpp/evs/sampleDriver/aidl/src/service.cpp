/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "EvsEnumerator.h"
#include "EvsGlDisplay.h"

#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/StrongPointer.h>

#include <unistd.h>

#include <atomic>
#include <cstdlib>
#include <string_view>

namespace {

using ::aidl::android::hardware::automotive::evs::implementation::EvsEnumerator;
using ::android::frameworks::automotive::display::V1_0::IAutomotiveDisplayProxyService;

constexpr std::string_view kDisplayServiceInstanceName = "default";
constexpr std::string_view kHwInstanceName = "/hw/1";
constexpr int kNumBinderThreads = 1;

}  // namespace

int main() {
    LOG(INFO) << "EVS Hardware Enumerator service is starting";

    android::sp<IAutomotiveDisplayProxyService> displayService =
            IAutomotiveDisplayProxyService::getService(kDisplayServiceInstanceName.data());
    if (!displayService) {
        LOG(ERROR) << "Cannot use AutomotiveDisplayProxyService.  Exiting.";
        return EXIT_FAILURE;
    }

    // Register our service -- if somebody is already registered by our name,
    // they will be killed (their thread pool will throw an exception).
    std::shared_ptr<EvsEnumerator> service =
            ndk::SharedRefBase::make<EvsEnumerator>(displayService);
    if (!service) {
        LOG(ERROR) << "Failed to instantiate the service";
        return EXIT_FAILURE;
    }

    std::atomic<bool> running{true};
    std::thread hotplugHandler(EvsEnumerator::EvsHotplugThread, std::ref(running));

    const std::string instanceName =
            std::string(EvsEnumerator::descriptor) + std::string(kHwInstanceName);
    auto err = AServiceManager_addService(service->asBinder().get(), instanceName.data());
    if (err != EX_NONE) {
        LOG(ERROR) << "Failed to register " << instanceName << ", exception = " << err;
        return EXIT_FAILURE;
    }

    if (!ABinderProcess_setThreadPoolMaxThreadCount(kNumBinderThreads)) {
        LOG(ERROR) << "Failed to set thread pool";
        return EXIT_FAILURE;
    }

    ABinderProcess_startThreadPool();
    LOG(INFO) << "EVS Hardware Enumerator is ready";

    ABinderProcess_joinThreadPool();
    // In normal operation, we don't expect the thread pool to exit
    LOG(INFO) << "EVS Hardware Enumerator is shutting down";

    // Exit a hotplug device thread
    running = false;
    if (hotplugHandler.joinable()) {
        hotplugHandler.join();
    }

    return EXIT_SUCCESS;
}
