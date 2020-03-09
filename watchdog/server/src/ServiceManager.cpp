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

#include <binder/IServiceManager.h>

namespace android {
namespace automotive {
namespace watchdog {

using android::defaultServiceManager;
using android::sp;
using android::String16;
using android::automotive::watchdog::WatchdogProcessService;
using android::base::Error;
using android::base::Result;

sp<WatchdogProcessService> ServiceManager::sWatchdogProcessService = nullptr;
sp<IoPerfCollection> ServiceManager::sIoPerfCollection = nullptr;

Result<void> ServiceManager::startServices(const sp<Looper>& looper) {
    if (sWatchdogProcessService != nullptr || sIoPerfCollection != nullptr) {
        return Error(INVALID_OPERATION) << "Cannot start services more than once";
    }
    auto result = startProcessAnrMonitor(looper);
    if (!result) {
        return result;
    }
    result = startIoPerfCollection();
    if (!result) {
        return result;
    }
    return {};
}

void ServiceManager::terminateServices() {
    if (sWatchdogProcessService != nullptr) {
        sWatchdogProcessService->terminate();
        sWatchdogProcessService = nullptr;
    }
    if (sIoPerfCollection != nullptr) {
        sIoPerfCollection->terminate();
        sIoPerfCollection = nullptr;
    }
}

Result<void> ServiceManager::startProcessAnrMonitor(const sp<Looper>& looper) {
    sWatchdogProcessService = new WatchdogProcessService(looper);
    status_t status =
            defaultServiceManager()
                    ->addService(String16("android.automotive.watchdog.ICarWatchdog/default"),
                                 sWatchdogProcessService);
    if (status != OK) {
        return Error(status) << "Failed to start carwatchdog process ANR monitor";
    }
    return {};
}

Result<void> ServiceManager::startIoPerfCollection() {
    /* TODO(b/148486340): Start I/O performance data collection after the WatchdogBinderMediator
     *  (b/150291965) is implemented to handle the boot complete so the boot-time collection can be
     *  switched to periodic collection after boot complete.
    sp<IoPerfCollection> service = new IoPerfCollection();
    const auto& result = service.start();
    if (!result) {
        return Error(result.error().code())
                << "Failed to start I/O performance collection: " << result.error();
    }
    sIoPerfCollection = service;
    */
    return {};
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
