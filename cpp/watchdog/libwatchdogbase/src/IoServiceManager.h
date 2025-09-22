/*
 * Copyright (c) 2025 The Android Open Source Project
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

#include "IoOveruseMonitorBase.h"
#include "WatchdogBinderMediatorBase.h"
#include "WatchdogPerfServiceBase.h"
#include "WatchdogServiceHelperBase.h"

#include <android-base/result.h>
#include <utils/Looper.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>

namespace android {
namespace automotive {
namespace watchdog {

// Manages the flash memory services that are run by the car watchdog daemon.
class IoServiceManager : virtual public android::RefBase {
public:
    IoServiceManager() :
          mIoOveruseMonitorBase(nullptr),
          mWatchdogBinderMediatorBase(nullptr),
          mWatchdogPerfServiceBase(nullptr),
          mWatchdogServiceHelperBase(nullptr) {}

    // Returns the singleton IoServiceManager instance.
    static std::shared_ptr<IoServiceManager> getInstance() {
        if (sIoServiceManager == nullptr) {
            sIoServiceManager = std::make_shared<IoServiceManager>();
        }
        return sIoServiceManager;
    }

    // Terminates all services and resets the singleton instance.
    static void terminate() {
        if (sIoServiceManager == nullptr) {
            return;
        }
        sIoServiceManager->terminateService();
        sIoServiceManager.reset();
    }

    // Starts early-init services.
    android::base::Result<void> startServices();

    // Returns the IoOveruseMonitorBase instance.
    const android::sp<IoOveruseMonitorBaseInterface>& getIoOveruseMonitorBase() {
        return mIoOveruseMonitorBase;
    }

    // Returns the WatchdogServiceHelperBase instance.
    const android::sp<WatchdogServiceHelperBaseInterface>& getWatchdogServiceHelperBase() {
        return mWatchdogServiceHelperBase;
    }

private:
    inline static std::shared_ptr<IoServiceManager> sIoServiceManager = nullptr;

    void terminateService();

    android::sp<IoOveruseMonitorBaseInterface> mIoOveruseMonitorBase;
    std::shared_ptr<WatchdogBinderMediatorInterface> mWatchdogBinderMediatorBase;
    android::sp<WatchdogPerfServiceBaseInterface> mWatchdogPerfServiceBase;
    android::sp<WatchdogServiceHelperBaseInterface> mWatchdogServiceHelperBase;
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
