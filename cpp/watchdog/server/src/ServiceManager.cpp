/*
 * Copyright (c) 2020 The Android Open Source Project
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

#define LOG_TAG "carwatchdogd"

#include "ServiceManager.h"

#include "IoPerfCollection.h"
#include "utils/PackageNameResolver.h"

namespace android {
namespace automotive {
namespace watchdog {

using android::sp;
using android::String16;
using android::automotive::watchdog::WatchdogPerfService;
using android::automotive::watchdog::WatchdogProcessService;
using android::base::Error;
using android::base::Result;

sp<WatchdogProcessService> ServiceManager::sWatchdogProcessService = nullptr;
sp<WatchdogPerfService> ServiceManager::sWatchdogPerfService = nullptr;
sp<WatchdogBinderMediator> ServiceManager::sWatchdogBinderMediator = nullptr;
sp<IoOveruseMonitor> ServiceManager::sIoOveruseMonitor = nullptr;

Result<void> ServiceManager::startServices(const sp<Looper>& looper) {
    if (sWatchdogProcessService != nullptr || sWatchdogPerfService != nullptr ||
        sWatchdogBinderMediator != nullptr || sIoOveruseMonitor != nullptr) {
        return Error(INVALID_OPERATION) << "Cannot start services more than once";
    }
    PackageNameResolver::getInstance();
    auto result = startProcessAnrMonitor(looper);
    if (!result.ok()) {
        return result;
    }
    result = startPerfService();
    if (!result.ok()) {
        return result;
    }
    return {};
}

void ServiceManager::terminateServices() {
    PackageNameResolver::terminate();
    if (sWatchdogProcessService != nullptr) {
        sWatchdogProcessService->terminate();
        sWatchdogProcessService.clear();
    }
    if (sIoOveruseMonitor != nullptr) {
        sIoOveruseMonitor.clear();
    }
    if (sWatchdogPerfService != nullptr) {
        sWatchdogPerfService->terminate();
        sWatchdogPerfService.clear();
    }
    if (sWatchdogBinderMediator != nullptr) {
        sWatchdogBinderMediator->terminate();
        sWatchdogBinderMediator.clear();
    }
}

Result<void> ServiceManager::startProcessAnrMonitor(const sp<Looper>& looper) {
    sp<WatchdogProcessService> service = new WatchdogProcessService(looper);
    const auto& result = service->start();
    if (!result.ok()) {
        return Error(result.error().code())
                << "Failed to start process monitoring: " << result.error();
    }
    sWatchdogProcessService = service;
    return {};
}

Result<void> ServiceManager::startPerfService() {
    sp<WatchdogPerfService> service = new WatchdogPerfService();
    sp<IoOveruseMonitor> ioOveruseMonitor = new IoOveruseMonitor();
    auto result = service->registerDataProcessor(new IoPerfCollection());
    if (!result.ok()) {
        return Error() << "Failed to register I/O perf collection: " << result.error();
    }
    // TODO(b/167240592): Register I/O overuse monitor after it is completely implemented.
    //  Caveat: I/O overuse monitor reads from /data partition when initialized so initializing here
    //  would cause the read to happen during early-init when the /data partition is not available.
    //  Thus delay the initialization/registration until the /data partition is available.
    result = service->start();
    if (!result.ok()) {
        return Error(result.error().code())
                << "Failed to start performance service: " << result.error();
    }
    sWatchdogPerfService = service;
    sIoOveruseMonitor = ioOveruseMonitor;
    return {};
}

Result<void> ServiceManager::startBinderMediator() {
    sWatchdogBinderMediator = new WatchdogBinderMediator();
    const auto& result = sWatchdogBinderMediator->init(sWatchdogProcessService,
                                                       sWatchdogPerfService, sIoOveruseMonitor);
    if (!result.ok()) {
        return Error(result.error().code())
                << "Failed to start binder mediator: " << result.error();
    }
    return {};
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
