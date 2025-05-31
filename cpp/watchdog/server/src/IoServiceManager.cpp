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

#define LOG_TAG "carwatchdogd"

#include "IoServiceManager.h"

#include "PackageInfoResolver.h"

#include <log/log.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::sp;
using ::android::base::Error;
using ::android::base::Result;

Result<void> IoServiceManager::startServices() {
    if (mWatchdogServiceHelperBase != nullptr || mIoOveruseMonitor != nullptr) {
        return Error(INVALID_OPERATION) << "Cannot start services more than once";
    }
    /*
     * PackageInfoResolver must be initialized first on the main thread before starting any other
     * thread because the PackageInfoResolver::getInstance method isn't thread safe. Thus initialize
     * PackageInfoResolver by calling the PackageInfoResolver::getInstance method before starting
     * other services as they may access PackageInfoResolver's instance during initialization.
     */
    std::shared_ptr<PackageInfoResolverInterface> packageInfoResolver =
            PackageInfoResolver::getInstance();

    mWatchdogServiceHelperBase = sp<WatchdogServiceHelperBase>::make();
    if (auto result =
                packageInfoResolver->initWatchdogServiceHelperBase(mWatchdogServiceHelperBase);
        !result.ok()) {
        return Error() << "Failed to initialize package name resolver: " << result.error();
    }

    mIoOveruseMonitor = sp<IoOveruseMonitor>::make(mWatchdogServiceHelperBase);
    return {};
}

void IoServiceManager::terminateService() {
    if (mWatchdogServiceHelperBase != nullptr) {
        mWatchdogServiceHelperBase->terminate();
        mWatchdogServiceHelperBase.clear();
    }
    mIoOveruseMonitor.clear();
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
