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

#ifdef CARWATCHDOGD_BINARY
#define LOG_TAG "carwatchdogd"
#else
#define LOG_TAG "iowatchdogd"
#endif

#include "IoServiceManager.h"

#include "PackageInfoResolver.h"

#include <log/log.h>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::sp;
using ::android::base::Error;
using ::android::base::Result;
using ::ndk::SharedRefBase;

Result<void> IoServiceManager::startServices() {
    if (mWatchdogBinderMediatorBase != nullptr || mWatchdogServiceHelperBase != nullptr ||
        mIoOveruseMonitor != nullptr || mWatchdogPerfServiceBase != nullptr) {
        return Error(INVALID_OPERATION) << "Cannot start services more than once";
    }
    /*
     * PackageInfoResolver must be initialized first on the main thread before starting any other
     * thread because the PackageInfoResolver::getInstance method isn't thread safe. Thus initialize
     * PackageInfoResolver by calling the PackageInfoResolver::getInstance method before starting
     * other services as they may access PackageInfoResolver's instance during initialization.
     */
    const std::shared_ptr<PackageInfoResolverInterface>& packageInfoResolver =
            PackageInfoResolver::getInstance();

    mWatchdogServiceHelperBase = sp<WatchdogServiceHelperBase>::make();
    if (auto result =
                packageInfoResolver->initWatchdogServiceHelperBase(mWatchdogServiceHelperBase);
        !result.ok()) {
        return Error() << "Failed to initialize package name resolver: " << result.error();
    }

    mIoOveruseMonitor = sp<IoOveruseMonitor>::make(mWatchdogServiceHelperBase, packageInfoResolver);
    mWatchdogPerfServiceBase =
            sp<WatchdogPerfServiceBase>::make(mWatchdogServiceHelperBase, packageInfoResolver);
    mWatchdogPerfServiceBase->init();
    mWatchdogPerfServiceBase->registerIoOveruseMonitor(mIoOveruseMonitor);
    if (auto result = mWatchdogPerfServiceBase->start(); !result.ok()) {
        return Error(result.error().code())
                << "Failed to start watchdog performance service: " << result.error();
    }

    mWatchdogBinderMediatorBase =
            SharedRefBase::make<WatchdogBinderMediatorBase>(mWatchdogPerfServiceBase,
                                                            mWatchdogServiceHelperBase,
                                                            mIoOveruseMonitor);
    if (auto result = mWatchdogBinderMediatorBase->init(); !result.ok()) {
        return Error(result.error().code())
                << "Failed to initialize watchdog binder mediator: " << result.error();
    }
    return {};
}

void IoServiceManager::terminateService() {
    mIoOveruseMonitor.clear();
    if (mWatchdogBinderMediatorBase != nullptr) {
        mWatchdogBinderMediatorBase->terminate();
        mWatchdogBinderMediatorBase.reset();
    }
    if (mWatchdogPerfServiceBase != nullptr) {
        mWatchdogPerfServiceBase->terminate();
        mWatchdogPerfServiceBase.clear();
    }
    if (mWatchdogServiceHelperBase != nullptr) {
        mWatchdogServiceHelperBase->terminate();
        mWatchdogServiceHelperBase.clear();
    }
    PackageInfoResolver::terminate();
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
