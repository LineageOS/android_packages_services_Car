/*
 * Copyright (c) 2025, The Android Open Source Project
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
#define DEBUG false  // STOPSHIP if true.

#include "IoOveruseMonitor.h"

#include "ServiceManager.h"

namespace android {
namespace automotive {
namespace watchdog {

namespace {

using ::android::sp;

void onBinderDied(void* cookie) {
    const auto& thiz = ServiceManager::getInstance()->getIoOveruseMonitor();
    if (thiz == nullptr) {
        return;
    }
    thiz->handleBinderDeath(cookie);
}

}  // namespace

IoOveruseMonitor::IoOveruseMonitor(
        const android::sp<WatchdogServiceHelperBaseInterface>& watchdogServiceHelperBase,
        const std::shared_ptr<PackageInfoResolverInterface>& packageInfoResolver) :
      IoOveruseMonitorBase(watchdogServiceHelperBase, packageInfoResolver,
                           // In carwatchdogd on Automotive, the IoServiceManager
                           // instance is not available. Pass a new DeathRecipient
                           // explicitly to facilitate invoking the ServiceManager
                           // instance instead.
                           AIBinder_DeathRecipient_new(onBinderDied)) {}

IoOveruseMonitor::~IoOveruseMonitor() {
    terminate();
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
