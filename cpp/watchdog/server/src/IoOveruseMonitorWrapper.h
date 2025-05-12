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

#include "IoOveruseMonitor.h"
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

// Forward declaration for testing use only.
namespace internal {

class IoOveruseMonitorWrapperPeer;

}  // namespace internal

/**
 * IoOveruseMonitorWrapperInterface interface defines the methods that the I/O overuse monitoring
 * wrapper module should implement.
 */
class IoOveruseMonitorWrapperInterface : virtual public DataProcessorInterface {
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

    virtual void handleBinderDeath(void* cookie) = 0;

    virtual android::base::Result<void> getIoOveruseStats(
            aidl::android::automotive::watchdog::IoOveruseStats* ioOveruseStats) const = 0;

    virtual android::base::Result<void> resetIoOveruseStats(
            const std::vector<std::string>& packageNames) = 0;

    // Removes stats for the given user from the internal cache.
    virtual void removeStatsForUser(userid_t userId) = 0;
};

/**
 * IoOveruseMonitorWrapper forwards method calls to IoOveruseMonitor.
 */
class IoOveruseMonitorWrapper final : public IoOveruseMonitorWrapperInterface {
public:
    explicit IoOveruseMonitorWrapper(
            const android::sp<WatchdogServiceHelperInterface>& watchdogServiceHelper);

    virtual ~IoOveruseMonitorWrapper();

    bool isInitialized() const override;

    void onCarWatchdogServiceRegistered() override;

    // Below methods implement DataProcessorInterface.
    std::string name() const override;

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
            const android::wp<ProcStatCollectorInterface>& procStatCollector,
            aidl::android::automotive::watchdog::internal::ResourceStats* resourceStats) override;

    android::base::Result<void> onCustomCollection(
            time_point_millis time, SystemState systemState,
            const std::unordered_set<std::string>& filterPackages,
            const android::wp<UidStatsCollectorInterface>& uidStatsCollector,
            const android::wp<ProcStatCollectorInterface>& procStatCollector,
            aidl::android::automotive::watchdog::internal::ResourceStats* resourceStats) override;

    android::base::Result<void> onPeriodicMonitor(
            time_t time, const android::wp<ProcDiskStatsCollectorInterface>& procDiskStatsCollector,
            const std::function<void()>& alertHandler) override;

    android::base::Result<void> onDump(int fd) const override;
    android::base::Result<void> onDumpProto(
            const CollectionIntervals& collectionIntervals,
            android::util::ProtoOutputStream& outProto) const override;

    bool dumpHelpText(int fd) const override;

    android::base::Result<void> onCustomCollectionDump([[maybe_unused]] int fd) override {
        // No special processing for custom collection. Thus no custom collection dump.
        return {};
    }

    // Below methods implement AIDL interfaces.
    android::base::Result<void> updateResourceOveruseConfigurations(
            const std::vector<
                    aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration>&
                    configs) override;

    android::base::Result<void> getResourceOveruseConfigurations(
            std::vector<
                    aidl::android::automotive::watchdog::internal::ResourceOveruseConfiguration>*
                    configs) const override;

    android::base::Result<void> onTodayIoUsageStatsFetched(
            const std::vector<
                    aidl::android::automotive::watchdog::internal::UserPackageIoUsageStats>&
                    userPackageIoUsageStats) override;

    android::base::Result<void> addIoOveruseListener(
            const std::shared_ptr<aidl::android::automotive::watchdog::IResourceOveruseListener>&
                    listener) override;

    android::base::Result<void> removeIoOveruseListener(
            const std::shared_ptr<aidl::android::automotive::watchdog::IResourceOveruseListener>&
                    listener) override;

    void handleBinderDeath(void* cookie) override;

    android::base::Result<void> getIoOveruseStats(
            aidl::android::automotive::watchdog::IoOveruseStats* ioOveruseStats) const override;

    android::base::Result<void> resetIoOveruseStats(
            const std::vector<std::string>& packageName) override;

    void removeStatsForUser(userid_t userId) override;

protected:
    android::base::Result<void> init();

    void terminate();

private:
    // Local IoOveruseMonitor instance to forward method calls to.
    android::sp<IoOveruseMonitorInterface> mIoOveruseMonitor;

    // For unit tests.
    friend class internal::IoOveruseMonitorWrapperPeer;
    FRIEND_TEST(IoOveruseMonitorWrapperTest, TestInit);
    FRIEND_TEST(IoOveruseMonitorWrapperTest, TestTerminate);
};

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
