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

#include "IoOveruseMonitorWrapper.h"

#include "ServiceManager.h"

#include <aidl/android/automotive/watchdog/IResourceOveruseListener.h>
#include <android/util/ProtoOutputStream.h>

namespace android {
namespace automotive {
namespace watchdog {

namespace {

using ::aidl::android::automotive::watchdog::IoOveruseStats;
using ::aidl::android::automotive::watchdog::IResourceOveruseListener;
using ::aidl::android::automotive::watchdog::internal::IoUsageStats;
using ::aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration;
using ::aidl::android::automotive::watchdog::internal::ResourceStats;
using ::aidl::android::automotive::watchdog::internal::UserPackageIoUsageStats;
using ::android::sp;
using ::android::base::Result;
using ::android::util::ProtoOutputStream;

void onBinderDied(void* cookie) {
    const auto& thiz = ServiceManager::getInstance()->getIoOveruseMonitorWrapper();
    if (thiz == nullptr) {
        return;
    }
    thiz->handleBinderDeath(cookie);
}

}  // namespace

IoOveruseMonitorWrapper::IoOveruseMonitorWrapper(
        const android::sp<WatchdogServiceHelperInterface>& watchdogServiceHelper) :
      mIoOveruseMonitor(
              sp<IoOveruseMonitor>::make(watchdogServiceHelper,
                                         // In carwatchdogd on Automotive, the IoServiceManager
                                         // instance is not available. Pass a new DeathRecipient
                                         // explicitly to facilitate invoking the ServiceManager
                                         // instance instead.
                                         AIBinder_DeathRecipient_new(onBinderDied))) {}

IoOveruseMonitorWrapper::~IoOveruseMonitorWrapper() {
    terminate();
}

Result<void> IoOveruseMonitorWrapper::init() {
    return mIoOveruseMonitor->init();
}

void IoOveruseMonitorWrapper::terminate() {
    mIoOveruseMonitor.clear();
}

std::string IoOveruseMonitorWrapper::name() const {
    return mIoOveruseMonitor->name();
}

bool IoOveruseMonitorWrapper::isInitialized() const {
    return mIoOveruseMonitor->isInitialized();
}

void IoOveruseMonitorWrapper::onCarWatchdogServiceRegistered() {
    mIoOveruseMonitor->onCarWatchdogServiceRegistered();
}

Result<void> IoOveruseMonitorWrapper::onPeriodicCollection(
        time_point_millis time, SystemState systemState,
        const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
        [[maybe_unused]] const android::wp<ProcStatCollectorInterface>& procStatCollector,
        ResourceStats* resourceStats) {
    return mIoOveruseMonitor->onPeriodicCollection(time, systemState == SystemState::GARAGE_MODE,
                                                   uidStatsCollector, resourceStats);
}

Result<void> IoOveruseMonitorWrapper::onCustomCollection(
        time_point_millis time, SystemState systemState,
        [[maybe_unused]] const std::unordered_set<std::string>& filterPackages,
        const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
        [[maybe_unused]] const android::wp<ProcStatCollectorInterface>& procStatCollector,
        ResourceStats* resourceStats) {
    return mIoOveruseMonitor->onPeriodicCollection(time, systemState == SystemState::GARAGE_MODE,
                                                   uidStatsCollector, resourceStats);
}

Result<void> IoOveruseMonitorWrapper::onPeriodicMonitor(
        time_t time, const android::wp<ProcDiskStatsCollectorInterface>& procDiskStatsCollector,
        const std::function<void()>& alertHandler) {
    return mIoOveruseMonitor->onPeriodicMonitor(time, procDiskStatsCollector, alertHandler);
}

Result<void> IoOveruseMonitorWrapper::onDump([[maybe_unused]] int fd) const {
    // TODO(b/183436216): Dump the list of killed/disabled packages. Dump the list of packages that
    //  exceed xx% of their threshold.
    return {};
}

Result<void> IoOveruseMonitorWrapper::onDumpProto(
        [[maybe_unused]] const CollectionIntervals& collectionIntervals,
        [[maybe_unused]] ProtoOutputStream& outProto) const {
    // TODO(b/296123577): Dump the list of killed/disabled packages in proto format.
    return {};
}

bool IoOveruseMonitorWrapper::dumpHelpText(int fd) const {
    return mIoOveruseMonitor->dumpHelpText(fd);
}

Result<void> IoOveruseMonitorWrapper::onTodayIoUsageStatsFetched(
        const std::vector<UserPackageIoUsageStats>& userPackageIoUsageStats) {
    return mIoOveruseMonitor->onTodayIoUsageStatsFetched(userPackageIoUsageStats);
}

Result<void> IoOveruseMonitorWrapper::updateResourceOveruseConfigurations(
        const std::vector<ResourceOveruseConfiguration>& configs) {
    return mIoOveruseMonitor->updateResourceOveruseConfigurations(configs);
}

Result<void> IoOveruseMonitorWrapper::getResourceOveruseConfigurations(
        std::vector<ResourceOveruseConfiguration>* configs) const {
    return mIoOveruseMonitor->getResourceOveruseConfigurations(configs);
}

Result<void> IoOveruseMonitorWrapper::addIoOveruseListener(
        const std::shared_ptr<IResourceOveruseListener>& listener) {
    return mIoOveruseMonitor->addIoOveruseListener(listener);
}

Result<void> IoOveruseMonitorWrapper::removeIoOveruseListener(
        const std::shared_ptr<IResourceOveruseListener>& listener) {
    return mIoOveruseMonitor->removeIoOveruseListener(listener);
}

Result<void> IoOveruseMonitorWrapper::getIoOveruseStats(IoOveruseStats* ioOveruseStats) const {
    return mIoOveruseMonitor->getIoOveruseStats(ioOveruseStats);
}

Result<void> IoOveruseMonitorWrapper::resetIoOveruseStats(
        const std::vector<std::string>& packageNames) {
    return mIoOveruseMonitor->resetIoOveruseStats(packageNames);
}

void IoOveruseMonitorWrapper::removeStatsForUser(userid_t userId) {
    mIoOveruseMonitor->removeStatsForUser(userId);
}

void IoOveruseMonitorWrapper::handleBinderDeath(void* cookie) {
    mIoOveruseMonitor->handleBinderDeath(cookie);
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
