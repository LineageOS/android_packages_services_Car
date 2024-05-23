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

#ifndef CPP_WATCHDOG_SERVER_SRC_SERVICEMANAGER_H_
#define CPP_WATCHDOG_SERVER_SRC_SERVICEMANAGER_H_

#include "IoOveruseMonitor.h"
#include "PressureMonitor.h"
#include "WatchdogBinderMediator.h"
#include "WatchdogPerfService.h"
#include "WatchdogProcessService.h"
#include "WatchdogServiceHelper.h"

#include <android-base/result.h>
#include <utils/Looper.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>

namespace android {
namespace automotive {
namespace watchdog {

// Manages all the services that are run by the car watchdog daemon.
class ServiceManager final : virtual public android::RefBase {
public:
    ServiceManager() :
          mWatchdogProcessService(nullptr),
          mWatchdogPerfService(nullptr),
          mWatchdogBinderMediator(nullptr),
          mWatchdogServiceHelper(nullptr),
          mIoOveruseMonitor(nullptr),
          mPressureMonitor(nullptr) {}

    // Returns the singleton ServiceManager instance.
    static android::sp<ServiceManager> getInstance() {
        if (sServiceManager == nullptr) {
            sServiceManager = android::sp<ServiceManager>::make();
        }
        return sServiceManager;
    }

    // Terminates all services and resets the singleton instance.
    static void terminate() {
        if (sServiceManager == nullptr) {
            return;
        }
        sServiceManager->terminateServices();
        sServiceManager.clear();
    }

    // Starts early-init services.
    android::base::Result<void> startServices(const android::sp<Looper>& mainLooper);

    // Returns the WatchdogProcessService instance.
    const android::sp<WatchdogProcessServiceInterface>& getWatchdogProcessService() {
        return mWatchdogProcessService;
    }

    // Returns the WatchdogServiceHelper instance.
    const android::sp<WatchdogServiceHelperInterface>& getWatchdogServiceHelper() {
        return mWatchdogServiceHelper;
    }

    // Returns the IoOveruseMonitor instance.
    const android::sp<IoOveruseMonitorInterface>& getIoOveruseMonitor() {
        return mIoOveruseMonitor;
    }

private:
    inline static android::sp<ServiceManager> sServiceManager = nullptr;

    void terminateServices();
    android::base::Result<void> startWatchdogProcessService(const android::sp<Looper>& mainLooper);
    android::base::Result<void> startPressureMonitor();
    android::base::Result<void> startWatchdogPerfService(
            const sp<WatchdogServiceHelperInterface>& watchdogServiceHelper);

    android::sp<WatchdogProcessServiceInterface> mWatchdogProcessService;
    android::sp<WatchdogPerfServiceInterface> mWatchdogPerfService;
    std::shared_ptr<WatchdogBinderMediatorInterface> mWatchdogBinderMediator;
    android::sp<WatchdogServiceHelperInterface> mWatchdogServiceHelper;
    android::sp<IoOveruseMonitorInterface> mIoOveruseMonitor;
    android::sp<PressureMonitorInterface> mPressureMonitor;
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android

#endif  // CPP_WATCHDOG_SERVER_SRC_SERVICEMANAGER_H_
