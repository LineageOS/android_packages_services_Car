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
#include "WatchdogProcessService.h"

#include <binder/IServiceManager.h>

namespace android {
namespace automotive {
namespace watchdog {

using android::defaultServiceManager;
using android::String16;
using android::automotive::watchdog::WatchdogProcessService;
using android::base::Error;
using android::base::Result;

Result<void> ServiceManager::startService(ServiceType type, const sp<Looper>& looper) {
    switch (type) {
        case PROCESS_ANR_MONITOR:
            return startProcessAnrMonitor(looper);
        case IO_PERFORMANCE_MONITOR:
            return startIoPerfMonitor();
        default:
            return Error() << "Invalid service type";
    }
}

Result<void> ServiceManager::startProcessAnrMonitor(const sp<Looper>& looper) {
    sp<WatchdogProcessService> service = new WatchdogProcessService(looper);
    status_t status =
            defaultServiceManager()
                    ->addService(String16("android.automotive.watchdog.ICarWatchdog/default"),
                                 service);
    if (status != OK) {
        return Error(status) << "Failed to start carwatchdog process ANR monitor";
    }
    return {};
}

Result<void> ServiceManager::startIoPerfMonitor() {
    return Error() << "Not implemented";
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
