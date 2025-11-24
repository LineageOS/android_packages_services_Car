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

#pragma once

#include "IoOveruseMonitorBase.h"
#include "ProcDiskStatsCollector.h"
#include "ProcStatCollector.h"
#include "UidStatsCollector.h"
#include "WatchdogPerfService.h"

#include <aidl/android/automotive/watchdog/IResourceOveruseListener.h>
#include <aidl/android/automotive/watchdog/internal/ResourceOveruseConfiguration.h>
#include <aidl/android/automotive/watchdog/internal/UserPackageIoUsageStats.h>
#include <android-base/result.h>
#include <android/util/ProtoOutputStream.h>
#include <cutils/multiuser.h>

#include <time.h>

#include <string>
#include <unordered_set>
#include <vector>

namespace android {
namespace automotive {
namespace watchdog {

/**
 * IoOveruseMonitorInterface interface defines the methods that the I/O overuse monitoring
 * module should implement.
 */
class IoOveruseMonitorInterface :
      virtual public DataProcessorInterface,
      virtual public IoOveruseMonitorBaseInterface {
public:
    // Returns whether or not the monitor is initialized.
    virtual bool isInitialized() const = 0;

    // Dumps the help text.
    virtual bool dumpHelpText(int fd) const = 0;

    // Below API is from internal/ICarWatchdog.aidl. Please refer to the AIDL for description.
    virtual android::base::Result<void> updateResourceOveruseConfigurations(
            const std::vector<
                    aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration>&
                    configs) = 0;
    virtual android::base::Result<void> getResourceOveruseConfigurations(
            std::vector<
                    aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration>*
                    configs) const = 0;
    virtual android::base::Result<void> onTodayIoUsageStatsFetched(
            const std::vector<
                    aidl::android::automotive::watchdog::internal::UserPackageIoUsageStats>&
                    userPackageIoUsageStats) = 0;

    // Below methods support APIs from ICarWatchdog.aidl. Please refer to the AIDL for description.
    virtual android::base::Result<void> addIoOveruseListener(
            const std::shared_ptr<aidl::android::automotive::watchdog::IResourceOveruseListener>&
                    listener) = 0;

    virtual android::base::Result<void> removeIoOveruseListener(
            const std::shared_ptr<aidl::android::automotive::watchdog::IResourceOveruseListener>&
                    listener) = 0;

    virtual android::base::Result<void> getIoOveruseStats(
            aidl::android::automotive::watchdog::IoOveruseStats* ioOveruseStats) const = 0;

    virtual android::base::Result<void> resetIoOveruseStats(
            const std::vector<std::string>& packageNames) = 0;

    // Removes stats for the given user from the internal cache.
    virtual void removeStatsForUser(userid_t userId) = 0;

    virtual void handleBinderDeath(void* cookie) = 0;
};

/**
 * IoOveruseMonitor forwards method calls to IoOveruseMonitorBase.
 */
class IoOveruseMonitor final : public IoOveruseMonitorInterface, public IoOveruseMonitorBase {
public:
    explicit IoOveruseMonitor(
            const android::sp<WatchdogServiceHelperBaseInterface>& watchdogServiceHelperBase,
            const std::shared_ptr<PackageInfoResolverInterface>& packageInfoResolver);

    virtual ~IoOveruseMonitor();

    bool isInitialized() const override { return IoOveruseMonitorBase::isInitialized(); }

    void onCarWatchdogServiceRegistered() override {
        IoOveruseMonitorBase::onCarWatchdogServiceRegistered();
    }

    std::string name() const override { return IoOveruseMonitorBase::name(); }

    android::base::Result<void> onSystemStartup() {
        // No tracking of boot-time and wake-up events in I/O overuse monitoring.
        return {};
    }

    android::base::Result<void> onBoottimeCollection(
            [[maybe_unused]] time_point_millis time,
            [[maybe_unused]] const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
            [[maybe_unused]] const android::wp<ProcStatCollectorInterface>& procStatCollector,
            [[maybe_unused]] aidl::android::automotive::watchdog::internal::ResourceStats*
                    resourceStats) override {
        // No I/O overuse monitoring during boot-time.
        return {};
    }

    android::base::Result<void> onWakeUpCollection(
            [[maybe_unused]] time_point_millis time,
            [[maybe_unused]] const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
            [[maybe_unused]] const android::wp<ProcStatCollectorInterface>& procStatCollector)
            override {
        // No I/O overuse monitoring during wake up.
        return {};
    }

    android::base::Result<void> onUserSwitchCollection(
            [[maybe_unused]] time_point_millis time, [[maybe_unused]] userid_t from,
            [[maybe_unused]] userid_t to,
            [[maybe_unused]] const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
            [[maybe_unused]] const android::wp<ProcStatCollectorInterface>& procStatCollector)
            override {
        // No I/O overuse monitoring during user switch.
        return {};
    }

    android::base::Result<void> onPeriodicCollection(
            time_point_millis time, SystemState systemState,
            const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
            [[maybe_unused]] const android::wp<ProcStatCollectorInterface>& procStatCollector,
            aidl::android::automotive::watchdog::internal::ResourceStats* resourceStats) override {
        return IoOveruseMonitorBase::onPeriodicCollection(time,
                                                          systemState == SystemState::GARAGE_MODE,
                                                          uidStatsCollector, resourceStats);
    }

    android::base::Result<void> onPeriodicCollection(
            time_point_millis time, bool isGarageModeActive,
            const android::wp<UidStatsCollectorBaseInterface>& uidStatsCollectorBase,
            aidl::android::automotive::watchdog::internal::ResourceStats* resourceStats) override {
        return IoOveruseMonitorBase::onPeriodicCollection(time, isGarageModeActive,
                                                          uidStatsCollectorBase, resourceStats);
    }

    android::base::Result<void> onCustomCollection(
            time_point_millis time, SystemState systemState,
            [[maybe_unused]] const std::unordered_set<std::string>& filterPackages,
            const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
            [[maybe_unused]] const android::wp<ProcStatCollectorInterface>& procStatCollector,
            aidl::android::automotive::watchdog::internal::ResourceStats* resourceStats) override {
        return IoOveruseMonitorBase::onPeriodicCollection(time,
                                                          systemState == SystemState::GARAGE_MODE,
                                                          uidStatsCollector, resourceStats);
    }

    android::base::Result<void> onPeriodicMonitor(
            time_t time, const android::wp<ProcDiskStatsCollectorInterface>& procDiskStatsCollector,
            const std::function<void()>& alertHandler) override {
        return IoOveruseMonitorBase::onPeriodicMonitor(time, procDiskStatsCollector, alertHandler);
    }

    android::base::Result<void> onDump([[maybe_unused]] int fd) const override {
        // TODO(b/183436216): Dump the list of killed/disabled packages. Dump the list of packages
        // that
        //  exceed xx% of their threshold.
        return {};
    }

    android::base::Result<void> onDumpProto(
            [[maybe_unused]] const CollectionIntervals& collectionIntervals,
            [[maybe_unused]] android::util::ProtoOutputStream& outProto) const override {
        // TODO(b/296123577): Dump the list of killed/disabled packages in proto format.
        return {};
    }

    bool dumpHelpText(int fd) const override { return IoOveruseMonitorBase::dumpHelpText(fd); }

    android::base::Result<void> onCustomCollectionDump([[maybe_unused]] int fd) override {
        // No special processing for custom collection. Thus no custom collection dump.
        return {};
    }

    // Below methods implement AIDL interfaces.
    android::base::Result<void> updateResourceOveruseConfigurations(
            const std::vector<
                    aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration>&
                    configs) override {
        return IoOveruseMonitorBase::updateResourceOveruseConfigurations(configs);
    }

    android::base::Result<void> getResourceOveruseConfigurations(
            std::vector<
                    aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration>*
                    configs) const override {
        return IoOveruseMonitorBase::getResourceOveruseConfigurations(configs);
    }

    android::base::Result<void> onTodayIoUsageStatsFetched(
            const std::vector<
                    aidl::android::automotive::watchdog::internal::UserPackageIoUsageStats>&
                    userPackageIoUsageStats) override {
        return IoOveruseMonitorBase::onTodayIoUsageStatsFetched(userPackageIoUsageStats);
    }

    android::base::Result<void> addIoOveruseListener(
            const std::shared_ptr<aidl::android::automotive::watchdog::IResourceOveruseListener>&
                    listener) override {
        return IoOveruseMonitorBase::addIoOveruseListener(listener);
    }

    android::base::Result<void> removeIoOveruseListener(
            const std::shared_ptr<aidl::android::automotive::watchdog::IResourceOveruseListener>&
                    listener) override {
        return IoOveruseMonitorBase::removeIoOveruseListener(listener);
    }

    android::base::Result<void> getIoOveruseStats(
            aidl::android::automotive::watchdog::IoOveruseStats* ioOveruseStats) const override {
        return IoOveruseMonitorBase::getIoOveruseStats(ioOveruseStats);
    }

    android::base::Result<void> resetIoOveruseStats(
            const std::vector<std::string>& packageNames) override {
        return IoOveruseMonitorBase::resetIoOveruseStats(packageNames);
    }

    void removeStatsForUser(userid_t userId) override {
        IoOveruseMonitorBase::removeStatsForUser(userId);
    }

    void handleBinderDeath(void* cookie) override {
        IoOveruseMonitorBase::handleBinderDeath(cookie);
    }

protected:
    android::base::Result<void> init() override { return IoOveruseMonitorBase::init(); }

    void terminate() override { IoOveruseMonitorBase::terminate(); }
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
