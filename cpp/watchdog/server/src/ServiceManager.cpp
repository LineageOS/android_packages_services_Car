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
#include "PackageInfoResolver.h"

namespace android {
namespace automotive {
namespace watchdog {

using ::android::sp;
using ::android::String16;
using ::android::automotive::watchdog::WatchdogPerfService;
using ::android::automotive::watchdog::WatchdogProcessService;
using ::android::base::Error;
using ::android::base::Result;

sp<WatchdogProcessService> ServiceManager::sWatchdogProcessService = nullptr;
sp<WatchdogPerfService> ServiceManager::sWatchdogPerfService = nullptr;
sp<IoOveruseMonitor> ServiceManager::sIoOveruseMonitor = nullptr;
sp<WatchdogBinderMediator> ServiceManager::sWatchdogBinderMediator = nullptr;
sp<WatchdogServiceHelperInterface> ServiceManager::sWatchdogServiceHelper = nullptr;

Result<void> ServiceManager::startServices(const sp<Looper>& looper) {
    if (sWatchdogBinderMediator != nullptr || sWatchdogServiceHelper != nullptr ||
        sWatchdogProcessService != nullptr || sWatchdogPerfService != nullptr ||
        sIoOveruseMonitor != nullptr) {
        return Error(INVALID_OPERATION) << "Cannot start services more than once";
    }
    /*
     * PackageInfoResolver must be initialized first time on the main thread before starting any
     * other thread as the getInstance method isn't thread safe. Thus initialize PackageInfoResolver
     * by calling the getInstance method before starting other service as they may access
     * PackageInfoResolver's instance during initialization.
     */
    sp<IPackageInfoResolverInterface> packageInfoResolver = PackageInfoResolver::getInstance();
    auto result = startProcessAnrMonitor(looper);
    if (!result.ok()) {
        return result;
    }
    result = startPerfService();
    if (!result.ok()) {
        return result;
    }
    sWatchdogServiceHelper = new WatchdogServiceHelper();
    result = sWatchdogServiceHelper->init(sWatchdogProcessService);
    if (!result.ok()) {
        return Error() << "Failed to initialize watchdog service helper: " << result.error();
    }
    result = packageInfoResolver->initWatchdogServiceHelper(sWatchdogServiceHelper);
    if (!result.ok()) {
        return Error() << "Failed to initialize package name resolver: " << result.error();
    }
    return {};
}

void ServiceManager::terminateServices() {
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
    if (sWatchdogServiceHelper != nullptr) {
        sWatchdogServiceHelper->terminate();
        sWatchdogServiceHelper.clear();
    }
    PackageInfoResolver::terminate();
}

Result<void> ServiceManager::startProcessAnrMonitor(const sp<Looper>& looper) {
    sp<WatchdogProcessService> service = new WatchdogProcessService(looper);
    const auto& result = service->start();
    if (!result.ok()) {
        return Error(result.error().code())
                << "Failed to start watchdog process monitoring: " << result.error();
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
    /*
     * TODO(b/167240592): Register I/O overuse monitor after it is completely implemented.
     *  Caveat: I/O overuse monitor reads from /data partition when initialized so initializing here
     *  would cause the read to happen during early-init when the /data partition is not available.
     *  Thus delay the initialization/registration until the /data partition is available.
     */
    result = service->start();
    if (!result.ok()) {
        return Error(result.error().code())
                << "Failed to start watchdog performance service: " << result.error();
    }
    sWatchdogPerfService = service;
    sIoOveruseMonitor = ioOveruseMonitor;
    return {};
}

Result<void> ServiceManager::startBinderMediator() {
    sWatchdogBinderMediator =
            new WatchdogBinderMediator(sWatchdogProcessService, sWatchdogPerfService,
                                       sIoOveruseMonitor, sWatchdogServiceHelper);
    const auto& result = sWatchdogBinderMediator->init();
    if (!result.ok()) {
        return Error(result.error().code())
                << "Failed to initialize watchdog binder mediator: " << result.error();
    }
    return {};
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
