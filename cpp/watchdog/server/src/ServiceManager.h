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

#pragma once

#include "IoOveruseMonitorWrapper.h"
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
          mIoOveruseMonitorWrapper(nullptr),
          mPressureMonitor(nullptr) {}

    // Returns the singleton ServiceManager instance.
    static std::shared_ptr<ServiceManager> getInstance() {
        if (sServiceManager == nullptr) {
            sServiceManager = std::make_shared<ServiceManager>();
        }
        return sServiceManager;
    }

    // Terminates all services and resets the singleton instance.
    static void terminate() {
        if (sServiceManager == nullptr) {
            return;
        }
        sServiceManager->terminateServices();
        sServiceManager.reset();
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

    // Returns the IoOveruseMonitorWrapper instance.
    const android::sp<IoOveruseMonitorWrapperInterface>& getIoOveruseMonitorWrapper() {
        return mIoOveruseMonitorWrapper;
    }

private:
    inline static std::shared_ptr<ServiceManager> sServiceManager = nullptr;

    void terminateServices();
    android::base::Result<void> startWatchdogProcessService(
            const android::sp<Looper>& mainLooper,
            const std::shared_ptr<PackageInfoResolverInterface>& packageInfoResolver);
    android::base::Result<void> startPressureMonitor();
    android::base::Result<void> startWatchdogPerfService(
            const sp<WatchdogServiceHelperInterface>& watchdogServiceHelper,
            const std::shared_ptr<PackageInfoResolverInterface>& packageInfoResolver);

    android::sp<WatchdogProcessServiceInterface> mWatchdogProcessService;
    android::sp<WatchdogPerfServiceInterface> mWatchdogPerfService;
    std::shared_ptr<WatchdogBinderMediatorInterface> mWatchdogBinderMediator;
    android::sp<WatchdogServiceHelperInterface> mWatchdogServiceHelper;
    android::sp<IoOveruseMonitorWrapperInterface> mIoOveruseMonitorWrapper;
    android::sp<PressureMonitorInterface> mPressureMonitor;
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
